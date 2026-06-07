package com.paicli.mcp;

import com.paicli.mcp.config.McpConfigLoader;
import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.notifications.NotificationRouter;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.mcp.resources.McpResourceCache;
import com.paicli.mcp.resources.McpResourceContent;
import com.paicli.mcp.resources.McpResourceDescriptor;
import com.paicli.mcp.resources.McpResourceTool;
import com.paicli.policy.AuditLog;
import com.paicli.mcp.transport.McpTransport;
import com.paicli.mcp.transport.StdioTransport;
import com.paicli.mcp.transport.StreamableHttpTransport;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ═══════════════════════════════════════════════════════════════
 * MCP Server 管理器
 * ═══════════════════════════════════════════════════════════════
 *
 * 【职责】
 * 管理所有 MCP Server 的生命周期：加载配置 → 启动（并行）→ 动态注册工具到 ToolRegistry
 * → 支持运行时重启/禁用/启用 → 关闭时清理资源。
 *
 * 【MCP 是什么？】
 * MCP（Model Context Protocol）是一种让 PaiCLI 动态获取外部工具的协议。
 * 外部工具可以是一个 npx 启动的 Node.js 进程（如 chrome-devtools-mcp），
 * 也可以是 HTTP 服务。它们通过 stdio 或 HTTP 与 PaiCLI 通信，
 * 动态提供 tools/resources/prompts。
 *
 * 【核心流程】
 * loadConfiguredServers() → 从 mcp.json 读取 server 列表
 * startAll() → 并行启动所有 server，最多等 8 秒
 *    ↓ 每个 server 独立执行：
 *    createTransport() → 创建通信层（stdio / HTTP）
 *    McpClient.initialize() → 握手 + 能力协商
 *    listTools() → 获取工具列表 → 注册到 ToolRegistry
 *    listResources() → 获取资源列表 → 缓存到 resourceCache
 *    registerNotificationHandlers() → 监听 tools/resources 变更通知
 *    ↓
 * READY：Agent 可以使用这些工具了
 *
 * 【安全机制】
 * - validateNoDuplicateTools()：拒绝重名工具，防止冲突
 * - 每调用一次 readResourceForMention() 都会记录审计日志
 * - 启动超时不阻塞 CLI，后台继续初始化
 */
public class McpServerManager implements AutoCloseable {
    // 启动进度打印的间隔时间：每 5 秒刷一次
    private static final Duration STARTUP_PROGRESS_INTERVAL = Duration.ofSeconds(5);

    // ToolRegistry：所有 MCP 工具最终都要注册到这里，Agent 通过它调用
    private final ToolRegistry toolRegistry;
    // 项目根目录：用于 stdio 模式设置工作目录、展开 ${PROJECT_DIR} 变量
    private final Path projectDir;
    // 配置加载器：从 mcp.json 读取/展开/校验 server 配置
    private final McpConfigLoader configLoader;
    // server 名称 → McpServer 实例（ConcurrentHashMap 支持并发安全）
    private final Map<String, McpServer> servers = new ConcurrentHashMap<>();
    // Resource 缓存：避免每次 @mention 都重新拉取 resource 列表
    private final McpResourceCache resourceCache = new McpResourceCache();

    // ═══════════════════════════════════════════════════════════
    // 构造方法
    // 两个重载：一个自动创建 McpConfigLoader，一个允许注入（方便单测）
    // ═══════════════════════════════════════════════════════════

    public McpServerManager(ToolRegistry toolRegistry, Path projectDir) {
        this(toolRegistry, projectDir, new McpConfigLoader(projectDir));
    }

    public McpServerManager(ToolRegistry toolRegistry, Path projectDir, McpConfigLoader configLoader) {
        this.toolRegistry = toolRegistry;
        // 标准化项目路径：. → D:\xxx\paicli，去除了 .. 和 . 的歧义
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.configLoader = configLoader;
    }

    // ═══════════════════════════════════════════════════════════
    // 配置加载与启动
    // ═══════════════════════════════════════════════════════════

    /**
     * 从 mcp.json 加载所有配置好的 server（仅创建 McpServer 对象，不启动）。
     * 调用时机：
     *   Main.java 启动时 → loadConfiguredServers() → startAll()
     *   /mcp restart 时 → restart() 内部走 start() 单个启动
     *
     * @throws IOException 配置文件读取失败
     */
    public void loadConfiguredServers() throws IOException {
        Map<String, McpServerConfig> configs = configLoader.load();
        servers.clear();
        configs.forEach((name, config) -> servers.put(name, new McpServer(name, config)));
    }

    // 三个重载：无参数、有 PrintStream（打印进度到输出流）、有 PrintStream+超时
    public void startAll() {
        startAll(null);
    }

    public void startAll(PrintStream progressOut) {
        startAll(progressOut, null);
    }

    /**
     * 并行启动所有已配置的 MCP server。
     *
     * 设计要点：
     * 1. 用独立的 daemon thread pool 启动，避免 npx/uvx 冷启动时占满
     *    ForkJoinPool.commonPool 线程池，影响其他并发工具的执行。
     * 2. 当 maxWait 为 null 时，阻塞等待所有 server 就绪（旧行为）。
     *    当有超时限制时，超过时间未就绪的 server 在后台继续启动，
     *    CLI 先显示首屏，不会被慢速 server 阻塞。
     * 3. 启动期间每 5 秒打印一次进度（哪些 server 还在启动中）。
     *
     * @param progressOut 进度信息输出流（null 表示不打印进度）
     * @param maxWait     最多等待时间（null = 无限等待）
     */
    public void startAll(PrintStream progressOut, Duration maxWait) {
        // 筛选出未禁用的 server 作为启动目标
        List<McpServer> targets = servers.values().stream()
                .filter(server -> !server.config().isDisabled())
                .toList();
        if (targets.isEmpty()) {
            return;
        }
        // 创建专属线程池：最多 8 个线程，所有线程都是 daemon（不阻止 JVM 退出）
        AtomicInteger threadId = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(targets.size(), 8),
                r -> {
                    Thread t = new Thread(r, "paicli-mcp-startup-" + threadId.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        // 启动进度打印线程（每 5 秒输出一次哪些 server 还在启动）
        Thread progressPrinter = startProgressPrinter(targets, progressOut, STARTUP_PROGRESS_INTERVAL);
        try {
            // 每个 server 异步启动
            List<CompletableFuture<Void>> futures = targets.stream()
                    .map(server -> CompletableFuture.runAsync(() -> start(server), executor))
                    .toList();
            CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            if (maxWait == null || maxWait.isZero() || maxWait.isNegative()) {
                // 无限等待：直到全部启动完成（或失败）
                all.join();
            } else {
                try {
                    // 有限等待：超时后未就绪的 server 在后台继续启动
                    all.get(Math.max(1, maxWait.toMillis()), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    printStartupTimeout(targets, progressOut, maxWait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    printStartupTimeout(targets, progressOut, maxWait);
                } catch (Exception e) {
                    // 其他异常（如 CancellationException）→ 继续等待完成
                    all.join();
                }
            }
        } finally {
            // 无论正常结束还是异常，都要关闭进度打印和线程池
            if (progressPrinter != null) {
                progressPrinter.interrupt();
            }
            executor.shutdown();
        }
    }

    /**
     * 打印 MCP 启动超时提示：列出仍在启动中的 server，告诉用户可查看 /mcp。
     */
    private void printStartupTimeout(List<McpServer> targets, PrintStream out, Duration maxWait) {
        if (out == null) {
            return;
        }
        List<McpServer> stillStarting = targets.stream()
                .filter(server -> server.status() == McpServerStatus.STARTING)
                .sorted(Comparator.comparing(McpServer::name))
                .toList();
        if (stillStarting.isEmpty()) {
            return;
        }
        String names = stillStarting.stream()
                .map(McpServer::name)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        long displaySeconds = Math.max(1, (long) Math.ceil(maxWait.toMillis() / 1000.0));
        out.printf("⚠️ MCP 启动超过 %ds，先进入 CLI；后台继续启动: %s%n",
                displaySeconds, names);
        out.println("   可用 /mcp 查看最新状态，或 /mcp logs <name> 查看日志。");
        out.flush();
    }

    /**
     * 启动一个 daemon 线程，每隔 interval 秒打印一次仍在启动中的 server 列表。
     * 这样用户能看到 "⏳ chrome-devtools 启动中...（已等待 5s）"
     *
     * @return 线程引用，后续通过 interrupt() 停止
     */
    private Thread startProgressPrinter(List<McpServer> targets, PrintStream out, Duration interval) {
        if (out == null || targets.isEmpty()) {
            return null;
        }
        Map<String, Instant> startedAt = new ConcurrentHashMap<>();
        targets.forEach(server -> startedAt.put(server.name(), Instant.now()));
        Thread thread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    TimeUnit.MILLISECONDS.sleep(interval.toMillis());
                    List<McpServer> starting = targets.stream()
                            .filter(server -> server.status() == McpServerStatus.STARTING)
                            .sorted(Comparator.comparing(McpServer::name))
                            .toList();
                    if (starting.isEmpty()) {
                        continue;
                    }
                    for (McpServer server : starting) {
                        long waited = Duration.between(startedAt.get(server.name()), Instant.now()).toSeconds();
                        out.printf("   ⏳ %-16s %-6s 启动中...（已等待 %ds）%n",
                                server.name(), server.transportName(), waited);
                    }
                    out.flush();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "paicli-mcp-startup-progress");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    // ═══════════════════════════════════════════════════════════
    // Server 运行时管理（重启/禁用/启用）
    // 这些方法由 /mcp 命令触发，加了 synchronized 保证并发安全
    // ═══════════════════════════════════════════════════════════

    /**
     * 重启指定 server：先反注册旧工具 → 关闭旧连接 → 重新启动。
     * 用于 /mcp restart 命令。
     */
    public synchronized String restart(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        unregisterTools(server);
        server.close();
        server.config().setDisabled(false);  // 重启时自动启用
        start(server);
        return server.status() == McpServerStatus.READY
                ? "✅ MCP server 已重启: " + name
                : "❌ MCP server 重启失败: " + name + " - " + server.errorMessage();
    }

    /**
     * 替换参数并重启：用于 /browser connect/disconnect 切换 chrome-devtools 的参数。
     * 例如从 { "--isolated": "true" } 改为 { "--autoConnect": "" }
     */
    public synchronized String restartWithArgs(String name, List<String> args) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        server.config().setArgs(args);
        return restart(name);
    }

    /**
     * 根据名称获取 McpServer 实例（用于 Main 里查询状态）。
     * 不同步是因为 ConcurrentHashMap.get() 本身是线程安全的。
     */
    public McpServer server(String name) {
        return servers.get(name);
    }

    /**
     * 禁用指定 server：反注册工具 → 关闭连接 → 标记 DISABLED。
     * 用于 /mcp disable 命令。
     */
    public synchronized String disable(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        unregisterTools(server);
        server.close();
        server.config().setDisabled(true);
        server.status(McpServerStatus.DISABLED);
        server.errorMessage(null);
        return "⏸️ MCP server 已禁用: " + name;
    }

    /**
     * 启用并启动指定 server。
     * 用于 /mcp enable 命令。
     */
    public synchronized String enable(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        server.config().setDisabled(false);
        start(server);
        return server.status() == McpServerStatus.READY
                ? "▶️ MCP server 已启用: " + name
                : "❌ MCP server 启用失败: " + name + " - " + server.errorMessage();
    }

    /**
     * 获取 server 的 stderr 日志（用于 /mcp logs 命令）。
     */
    public String logs(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        List<String> lines = server.logs();
        if (lines.isEmpty()) {
            return "📭 MCP server 暂无 stderr 日志: " + name;
        }
        return String.join(System.lineSeparator(), lines);
    }

    // ═══════════════════════════════════════════════════════════
    // 状态查询与展示
    // ═══════════════════════════════════════════════════════════

    /**
     * 返回所有 server 的排序列表（按名称排序）。
     */
    public Collection<McpServer> servers() {
        return servers.values().stream()
                .sorted(java.util.Comparator.comparing(McpServer::name))
                .toList();
    }

    /**
     * 格式化所有 server 的状态（用于 /mcp 命令）。
     * 输出示例：
     *   🔌 MCP Servers
     *   chrome-devtools  ● ready      stdio  3 tools   uptime 5m   pid 12345
     */
    public String formatStatus() {
        StringBuilder sb = new StringBuilder("🔌 MCP Servers\n");
        if (servers.isEmpty()) {
            sb.append("  未配置 MCP server。配置文件: ~/.paicli/mcp.json 或 .paicli/mcp.json");
            return sb.toString();
        }
        for (McpServer server : servers()) {
            String status = switch (server.status()) {
                case READY -> "● ready";
                case STARTING -> "… starting";
                case DISABLED -> "○ disabled";
                case ERROR -> "✗ error";
            };
            String tools = server.status() == McpServerStatus.READY
                    ? server.tools().size() + (server.tools().size() == 1 ? " tool" : " tools")
                    : "—";
            String uptime = server.status() == McpServerStatus.READY ? "uptime " + formatDuration(server.uptime()) : "";
            String pid = server.processId() == null ? "" : "pid " + server.processId();
            String error = server.status() == McpServerStatus.ERROR && server.errorMessage() != null
                    ? server.errorMessage()
                    : "";
            sb.append(String.format("  %-14s %-11s %-6s %-9s %-10s %s %s%n",
                    server.name(), status, server.transportName(), tools, uptime, pid, error));
        }
        return sb.toString().trim();
    }

    /**
     * 用于启动屏幕的摘要信息（比 formatStatus 更简洁，侧重启动结果）。
     */
    public String startupSummary() {
        if (servers.isEmpty()) {
            return "🔌 MCP server：未配置（可创建 ~/.paicli/mcp.json 或 .paicli/mcp.json）";
        }
        long ready = servers.values().stream().filter(s -> s.status() == McpServerStatus.READY).count();
        int tools = servers.values().stream().mapToInt(s -> s.tools().size()).sum();
        StringBuilder sb = new StringBuilder("🔌 启动 MCP server（" + servers.size() + " 个）...\n");
        for (McpServer server : servers()) {
            if (server.status() == McpServerStatus.READY) {
                sb.append(String.format("   ✓ %-14s %-6s %3d 工具%n",
                        server.name(), server.transportName(), server.tools().size()));
            } else if (server.status() == McpServerStatus.DISABLED) {
                sb.append(String.format("   ○ %-14s %-6s disabled%n", server.name(), server.transportName()));
            } else if (server.status() == McpServerStatus.STARTING) {
                sb.append(String.format("   … %-14s %-6s starting%n", server.name(), server.transportName()));
            } else {
                sb.append(String.format("   ✗ %-14s %-6s 启动失败: %s%n",
                        server.name(), server.transportName(), server.errorMessage()));
            }
        }
        sb.append("   ").append(ready).append("/").append(servers.size())
                .append(" 就绪，共 ").append(tools).append(" 个 MCP 工具");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════
    // Resource 相关
    // ═══════════════════════════════════════════════════════════

    /**
     * 返回所有缓存的 Resource 描述（用于 Tab 补全，让用户可以打出 @server:uri）。
     */
    public List<McpResourceDescriptor> resourceCandidates() {
        return resourceCache.all();
    }

    /**
     * 构建 MCP Resource 索引文本，注入到 Agent 的 system prompt 中。
     * 这样 LLM 在长上下文模式下可以知道有哪些资源可用，
     * 需要正文时再调用对应工具或使用 @-mention。
     *
     * @return Markdown 格式的资源索引（最多 200 条）
     */
    public String resourceIndexForPrompt() {
        List<McpResourceDescriptor> resources = resourceCache.all().stream()
                .sorted(Comparator.comparing(McpResourceDescriptor::serverName)
                        .thenComparing(McpResourceDescriptor::uri))
                .limit(200)
                .toList();
        if (resources.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## MCP Resources 索引（仅 URI / 描述，不含正文）\n\n");
        sb.append("长上下文模式下可参考以下资源索引判断是否需要读取 resource；需要正文时再调用对应 MCP resource 工具或使用用户显式 @-mention。\n\n");
        for (McpResourceDescriptor resource : resources) {
            sb.append("- @").append(resource.serverName()).append(':').append(resource.uri());
            String displayName = resource.displayName();
            if (!displayName.equals(resource.uri())) {
                sb.append(" — ").append(displayName);
            }
            if (resource.description() != null && !resource.description().isBlank()) {
                sb.append("：").append(resource.description());
            }
            if (resource.mimeType() != null && !resource.mimeType().isBlank()) {
                sb.append(" [").append(resource.mimeType()).append(']');
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * /mcp resources 命令：展示指定 server 的所有可用 Resource。
     */
    public String resources(String serverName) {
        McpServer server = servers.get(serverName);
        if (server == null) {
            return "未找到 MCP server: " + serverName;
        }
        if (server.client() == null || server.status() != McpServerStatus.READY) {
            return "MCP server 未就绪: " + serverName + " (" + server.status() + ")";
        }
        try {
            List<McpResourceDescriptor> resources = refreshResources(server);
            return McpClient.formatResources(resources);
        } catch (Exception e) {
            return "读取 MCP resources 失败: " + e.getMessage();
        }
    }

    /**
     * /mcp prompts 命令：展示指定 server 的所有可用 Prompt 模板。
     */
    public String prompts(String serverName) {
        McpServer server = servers.get(serverName);
        if (server == null) {
            return "未找到 MCP server: " + serverName;
        }
        if (server.client() == null || server.status() != McpServerStatus.READY) {
            return "MCP server 未就绪: " + serverName + " (" + server.status() + ")";
        }
        try {
            List<String> prompts = server.client().listPrompts();
            if (prompts.isEmpty()) {
                return "📭 该 MCP server 暂无 prompts: " + serverName;
            }
            StringBuilder sb = new StringBuilder("🧩 MCP prompts - ").append(serverName).append('\n');
            for (String prompt : prompts) {
                sb.append("- ").append(prompt).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "读取 MCP prompts 失败: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // @mention 展开：用户输入 @server:uri 时，拉到对应资源的内容
    // 这是用户侧展开 MCP Resource 的核心入口
    // ═══════════════════════════════════════════════════════════

    /**
     * 根据 @server:uri 引用读取 MCP Resource 内容。
     * 调用链路：
     *   AtMentionExpander.expand(input)
     *     → 解析 @server:uri 语法
     *     → McpServerManager.readResourceForMention(serverName, uri)
     *       → 校验 server 就绪状态
     *       → 检查缓存是否需要刷新
     *       → 调用 McpClient.readResource(uri) 获取 MCP 资源内容
     *       → 记录审计日志
     *       → 返回 ResourceReadResult（content + mimeType）
     *
     * 异常处理逻辑：
     * - 如果捕获到的异常已经是 IOException → 直接抛出
     * - 如果其他异常（如 IllegalArgumentException）→ 包装成 IOException 抛出
     * 这样调用方（AtMentionExpander）统一处理 IOException 即可。
     *
     * @param serverName MCP server 名称
     * @param uri        资源 URI
     * @return 资源内容 + MIME 类型
     * @throws IOException server 未就绪、URI 无效或读取失败
     */
    public ResourceReadResult readResourceForMention(String serverName, String uri) throws IOException {
        McpServer server = servers.get(serverName);
        if (server == null) {
            throw new IOException("未找到 MCP server: " + serverName);
        }
        if (server.client() == null || server.status() != McpServerStatus.READY) {
            throw new IOException("MCP server 未就绪: " + serverName + " (" + server.status() + ")");
        }
        long start = System.nanoTime();
        // 构造审计用的工具名和参数（如 mcp__chrome-devtools__read_resource + {"uri":"..."}）
        String toolName = McpToolDescriptor.namespaced(serverName, McpResourceTool.READ_RESOURCE);
        String args = "{\"uri\":\"" + uri.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        try {
            // 如果 server 的资源缓存已过时（如收到 resources/list_changed 通知），先刷新
            if (resourceCache.isServerStale(serverName)) {
                refreshResources(server);
            }
            List<McpResourceContent> contents = server.client().readResource(uri);
            resourceCache.markResourceFresh(serverName, uri);
            // 记录审计日志：@mention 展开视为允许操作
            toolRegistry.getAuditLog().record(AuditLog.AuditEntry.allowByMention(
                    toolName, args, elapsedMillis(start)));
            return ResourceReadResult.from(contents);
        } catch (Exception e) {
            // 失败也要记录审计日志
            toolRegistry.getAuditLog().record(AuditLog.AuditEntry.error(
                    toolName, args, e.getMessage(), elapsedMillis(start)));
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 核心私有方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 启动单个 MCP server（整个 MCP 生命周期都在这里）。
     *
     * 执行顺序：
     * 1. unregisterTools() — 清除上一次注册的工具（重启时用到）
     * 2. server.close() — 关闭旧连接
     * 3. 检查是否 disabled，是则标记 DISABLED 直接返回
     * 4. 标记为 STARTING
     * 5. configLoader.prepare() — 展开 ${VAR} 环境变量
     * 6. createTransport() — 创建传输层（stdio 或 HTTP）
     * 7. new McpClient() — 创建 MCP 协议客户端
     * 8. client.initialize() — 握手（包括 protocol version 协商）
     * 9. registerNotificationHandlers() — 订阅 tools/resources 变更通知
     * 10. buildToolList() — 拉取工具列表并注册到 ToolRegistry
     * 11. 标记为 READY
     *
     * 任何一步失败 → 标记为 ERROR 并记录错误信息。
     * 异常不抛到外面，由 startAll() 或其他调用方各自处理。
     */
    private void start(McpServer server) {
        // 前置清理：如果有旧工具和旧连接，先清理掉（重要：重启路径必须）
        unregisterTools(server);
        server.close();
        if (server.config().isDisabled()) {
            server.status(McpServerStatus.DISABLED);
            return;
        }
        server.status(McpServerStatus.STARTING);
        server.errorMessage(null);
        try {
            // 展开配置中的 ${VAR} 环境变量引用，校验 transport 参数
            configLoader.prepare(server.config());
            // 创建传输层（stdio → 启动子进程 stdin/stdout；HTTP → HTTP 连接）
            McpTransport transport = createTransport(server.config());
            // 创建 MCP 协议客户端并握手
            McpClient client = new McpClient(server.name(), transport);
            client.initialize();
            // 注册通知处理器：server 可以在运行时动态更新 tools/resources
            registerNotificationHandlers(server, client);
            // 拉取工具列表，注册到 ToolRegistry
            List<McpToolDescriptor> tools = buildToolList(server, client);
            replaceTools(server, client, tools);
            // 设置 server 状态为 READY
            server.client(client);
            server.tools(tools);
            server.markStarted();
            server.status(McpServerStatus.READY);
        } catch (Exception e) {
            // 启动失败：清理已创建的资源，标记 ERROR
            server.close();
            server.errorMessage(e.getMessage());
            server.status(McpServerStatus.ERROR);
        }
    }

    /**
     * 从 MCP server 获取工具列表 + 资源列表，组装成统一的 McpToolDescriptor 列表。
     * 如果 server 支持 resources，额外添加两个虚拟工具：
     *   mcp__{serverName}__list_resources
     *   mcp__{serverName}__read_resource
     */
    private List<McpToolDescriptor> buildToolList(McpServer server, McpClient client) throws IOException {
        List<McpToolDescriptor> tools = new ArrayList<>(client.listTools());
        if (client.supportsResources()) {
            List<McpResourceDescriptor> resources = client.listResources();
            resourceCache.put(server.name(), resources);
            // 添加虚拟工具：list_resources 和 read_resource
            tools.addAll(McpResourceTool.descriptors(server.name()));
        }
        // 检查有没有重名的工具——重名会导致 ToolRegistry 混乱
        validateNoDuplicateTools(server.name(), tools);
        return tools;
    }

    /**
     * 把工具列表注册到 ToolRegistry，绑定对应的执行函数（invoker）。
     * 普通工具走 invokeMcpToolOutput()，虚拟工具（list_resources / read_resource）
     * 走 McpResourceTool.invoker() 的本地实现——不经过 MCP 协议，直接操作 resourceCache。
     */
    private void replaceTools(McpServer server, McpClient client, List<McpToolDescriptor> tools) {
        toolRegistry.replaceMcpToolOutputsForServer(server.name(), tools,
                descriptor -> isResourceVirtualTool(descriptor)
                        ? args -> ToolOutput.text(McpResourceTool.invoker(client, descriptor).apply(args))
                        : args -> invokeMcpToolOutput(client, descriptor, args));
    }

    /**
     * 判断工具描述符是否指向虚拟 resource 工具。
     * 虚拟工具不走 MCP 协议的 tools/call，而是由本地的 McpResourceTool 处理。
     */
    private boolean isResourceVirtualTool(McpToolDescriptor descriptor) {
        return McpResourceTool.LIST_RESOURCES.equals(descriptor.name())
                || McpResourceTool.READ_RESOURCE.equals(descriptor.name());
    }

    /**
     * 注册 MCP 通知处理器，支持三种运行时变更：
     * 1. tools/list_changed → 重新拉取工具列表并更新 ToolRegistry
     * 2. resources/list_changed → 标记 server 资源缓存过期
     * 3. resources/updated → 标记单个资源 URI 缓存过期
     *
     * 这样当 MCP server 运行时动态增减工具时，Agent 不需要重启就能感知到变化。
     */
    private void registerNotificationHandlers(McpServer server, McpClient client) {
        NotificationRouter router = new NotificationRouter();
        // 工具列表变更：重新拉取 + 重新注册
        router.on("notifications/tools/list_changed", ignored -> {
            try {
                List<McpToolDescriptor> tools = buildToolList(server, client);
                replaceTools(server, client, tools);
                server.tools(tools);
            } catch (Exception e) {
                server.errorMessage("tools/list_changed 处理失败: " + e.getMessage());
            }
        });
        // 资源列表变更：标记整个 server 的资源缓存过期
        router.on("notifications/resources/list_changed", ignored -> resourceCache.invalidateServer(server.name()));
        // 单个资源更新：标记该 URI 的缓存过期
        router.on("notifications/resources/updated", params -> {
            String uri = params.path("uri").asText("");
            if (!uri.isBlank()) {
                resourceCache.invalidateResource(server.name(), uri);
            }
        });
        client.onNotification(router);
    }

    /**
     * 刷新指定 server 的资源缓存：重新调用 listResources() 并更新 resourceCache。
     */
    private List<McpResourceDescriptor> refreshResources(McpServer server) throws IOException {
        List<McpResourceDescriptor> resources = server.client().listResources();
        resources = resources.stream()
                .sorted(Comparator.comparing(McpResourceDescriptor::uri))
                .toList();
        resourceCache.put(server.name(), resources);
        return resources;
    }

    /**
     * MCP 工具执行入口：把 LLM 给的 JSON 参数透传给 server 的 tools/call，并把异常转成 LLM 可读字符串。
     * 提取成独立方法是为了让 server 维度的错误信息（serverName/toolName）在堆栈和日志里清晰可见。
     */
    private static ToolOutput invokeMcpToolOutput(McpClient client, McpToolDescriptor descriptor, String argumentsJson) {
        try {
            return client.callToolOutput(descriptor.name(), argumentsJson);
        } catch (Exception e) {
            return ToolOutput.text("MCP 工具调用失败 (" + descriptor.serverName() + "/" + descriptor.name() + "): "
                    + e.getMessage());
        }
    }

    /**
     * 根据配置创建对应传输层：
     * - HTTP 模式 → StreamableHttpTransport（连接远程 HTTP 的 MCP server）
     * - stdio 模式 → StdioTransport（启动子进程，通过 stdin/stdout 通信）
     */
    private McpTransport createTransport(McpServerConfig config) throws IOException {
        if (config.isHttp()) {
            return new StreamableHttpTransport(config.getUrl(), config.getHeaders());
        }
        return new StdioTransport(config.getCommand(), config.getArgs(), config.getEnv(), projectDir);
    }

    /**
     * 检查工具列表是否有重名。
     * MCP 协议允许不同的 server 返回同名工具（通过 namespacedName 区分），
     * 但同一个 server 内不允许重名——否则 ToolRegistry 没办法按名称查找。
     *
     * @throws IllegalArgumentException 发现重名工具时抛出异常
     */
    private void validateNoDuplicateTools(String serverName, List<McpToolDescriptor> tools) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (McpToolDescriptor tool : tools) {
            counts.merge(tool.name(), 1, Integer::sum);
        }
        List<String> duplicates = new ArrayList<>();
        counts.forEach((name, count) -> {
            if (count > 1) duplicates.add(name);
        });
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException("MCP server " + serverName + " 返回重复工具名: " + duplicates);
        }
    }

    /**
     * 反注册指定 server 的所有工具：遍历 server 的工具列表，
     * 从 ToolRegistry 中逐个移除。
     * 在重启/禁用/关闭时调用。
     */
    private void unregisterTools(McpServer server) {
        for (McpToolDescriptor tool : server.tools()) {
            toolRegistry.unregisterMcpTool(tool.namespacedName());
        }
        server.tools(List.of());
    }

    /**
     * 纳秒 → 毫秒（用于计算操作耗时）
     */
    private static long elapsedMillis(long startedAtNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    // ═══════════════════════════════════════════════════════════
    // 内部 Record：MCP Resource 读取结果
    // ═══════════════════════════════════════════════════════════

    /**
     * MCP Resource 读取结果：纯文本 + MIME 类型。
     * 用于 AtMentionExpander 展开 @server:uri 后注入到 prompt。
     *
     * @param content  资源正文（binary resource 会被省略为 `[binary resource blob omitted]`）
     * @param mimeType 资源的 MIME 类型（如 text/plain、text/markdown）
     */
    public record ResourceReadResult(String content, String mimeType) {
        /**
         * 把 MCP 协议返回的 McpResourceContent 列表转换成纯文本。
         * 多个内容块用换行拼接；二进制内容直接标记跳过，不进入 LLM prompt。
         */
        static ResourceReadResult from(List<McpResourceContent> contents) {
            if (contents == null || contents.isEmpty()) {
                return new ResourceReadResult("", "text/plain");
            }
            StringBuilder text = new StringBuilder();
            String firstMimeType = null;
            for (McpResourceContent content : contents) {
                if (firstMimeType == null || firstMimeType.isBlank()) {
                    firstMimeType = content.mimeType();
                }
                if (content.isText()) {
                    text.append(content.text());
                } else {
                    // 二进制资源：不读入 LLM，只标记长度
                    text.append("[binary resource blob omitted, base64 length=")
                            .append(content.blob() == null ? 0 : content.blob().length())
                            .append(']');
                }
                text.append(System.lineSeparator());
            }
            return new ResourceReadResult(text.toString().trim(), firstMimeType);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 格式化 Duration 为可读字符串：5s / 3m / 2h
     */
    private static String formatDuration(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        return (minutes / 60) + "h";
    }

    // ═══════════════════════════════════════════════════════════
    // 关闭清理
    // ═══════════════════════════════════════════════════════════

    /**
     * 关闭所有 MCP server：反注册工具 → 关闭连接。
     * 在 JVM shutdown hook 中调用（Main.java 注册的）。
     * 确保程序退出时不会有残留的 MCP 子进程。
     */
    @Override
    public void close() {
        for (McpServer server : servers.values()) {
            unregisterTools(server);
            server.close();
        }
    }
}
