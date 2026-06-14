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
 * MCP server 管理器。
 * <p>
 * 统一管理所有 MCP server 的生命周期：配置加载 → 并发启动 → 工具注册 → 资源缓存 → 状态监控 → 关闭清理。
 * 每个配置好的 server 对应一个 {@link McpServer} 实例，包含其 {@link McpClient}、工具列表和运行状态。
 * </p>
 * <p>
 * 核心职责：
 * <ul>
 *   <li>配置加载：通过 {@link McpConfigLoader} 合并用户级和项目级 mcp.json</li>
 *   <li>并发启动：所有 server 并行初始化，支持有界等待（不阻塞 CLI 首屏）</li>
 *   <li>工具注册：将 MCP 工具注册到 {@link ToolRegistry}，命名格式 {@code mcp__{server}__{tool}}</li>
 *   <li>资源缓存：通过 {@link McpResourceCache} 缓存 resource 列表，支持增量刷新</li>
 *   <li>通知路由：监听 server 推送的 tools/resources 变更通知并自动更新</li>
 *   <li>运维操作：restart / enable / disable / logs 等 /mcp 子命令</li>
 * </ul>
 * </p>
 */
public class McpServerManager implements AutoCloseable {
    /** 启动进度打印间隔（5 秒） */
    private static final Duration STARTUP_PROGRESS_INTERVAL = Duration.ofSeconds(5);

    /** 工具注册表，MCP 工具会注册到这里供 Agent 调用 */
    private final ToolRegistry toolRegistry;
    /** 项目根目录，stdio 子进程的工作目录 */
    private final Path projectDir;
    /** 配置加载器，合并用户级 + 项目级 mcp.json */
    private final McpConfigLoader configLoader;
    /** 所有已配置的 server，key 为 server 名称 */
    private final Map<String, McpServer> servers = new ConcurrentHashMap<>();
    /** 资源缓存，避免每次都向 server 拉取 resource 列表 */
    private final McpResourceCache resourceCache = new McpResourceCache();

    /** 便捷构造：使用默认配置加载器 */
    public McpServerManager(ToolRegistry toolRegistry, Path projectDir) {
        this(toolRegistry, projectDir, new McpConfigLoader(projectDir));
    }

    /**
     * 完整构造。
     *
     * @param toolRegistry 工具注册表
     * @param projectDir   项目根目录
     * @param configLoader 配置加载器（可注入以便测试）
     */
    public McpServerManager(ToolRegistry toolRegistry, Path projectDir, McpConfigLoader configLoader) {
        this.toolRegistry = toolRegistry;
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.configLoader = configLoader;
    }

    // ==================== 配置加载与启动 ====================

    /**
     * 加载配置文件中的 server 定义。
     * <p>合并 ~/.paicli/mcp.json 和 .paicli/mcp.json，清除旧状态并创建 McpServer 占位对象。</p>
     */
    public void loadConfiguredServers() throws IOException {
        Map<String, McpServerConfig> configs = configLoader.load();
        servers.clear();
        configs.forEach((name, config) -> servers.put(name, new McpServer(name, config)));
    }

    /** 阻塞启动所有 server（兼容旧调用） */
    public void startAll() {
        startAll(null);
    }

    /** 阻塞启动所有 server，并通过 progressOut 输出启动进度 */
    public void startAll(PrintStream progressOut) {
        startAll(progressOut, null);
    }

    /**
     * Start all configured servers.
     *
     * <p>When {@code maxWait} is {@code null}, this method preserves the historical
     * blocking behavior and waits until every server reaches READY/ERROR. When a
     * bounded wait is supplied, unfinished servers continue starting on daemon
     * threads so the CLI can render the first prompt instead of being held hostage
     * by a slow stdio/http server.
     */
    /**
     * 启动所有已配置的 server。
     * <p>
     * 当 {@code maxWait} 为 null 时，保持历史阻塞行为，等待所有 server 达到 READY/ERROR。
     * 当提供有界等待时间时，未完成的 server 会在后台 daemon 线程继续启动，
     * CLI 不必被慢启动的 stdio/http server 拖住首屏。
     * </p>
     * <p>启动线程池上限 8 个，避免 npx/uvx 冷启动期间占满 ForkJoinPool.commonPool。</p>
     *
     * @param progressOut 进度输出流，为 null 时不打印进度
     * @param maxWait     最大等待时间，null 表示无限等待
     */
    public void startAll(PrintStream progressOut, Duration maxWait) {
        List<McpServer> targets = servers.values().stream()
                .filter(server -> !server.config().isDisabled())
                .toList();
        if (targets.isEmpty()) {
            return;
        }
        // 用专属 daemon executor，避免 npx/uvx 冷启动期间占满 ForkJoinPool.commonPool 影响其他并发任务。
        AtomicInteger threadId = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(targets.size(), 8),
                r -> {
                    Thread t = new Thread(r, "paicli-mcp-startup-" + threadId.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        Thread progressPrinter = startProgressPrinter(targets, progressOut, STARTUP_PROGRESS_INTERVAL);
        try {
            List<CompletableFuture<Void>> futures = targets.stream()
                    .map(server -> CompletableFuture.runAsync(() -> start(server), executor))
                    .toList();
            CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            if (maxWait == null || maxWait.isZero() || maxWait.isNegative()) {
                all.join();
            } else {
                try {
                    all.get(Math.max(1, maxWait.toMillis()), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    printStartupTimeout(targets, progressOut, maxWait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    printStartupTimeout(targets, progressOut, maxWait);
                } catch (Exception e) {
                    all.join();
                }
            }
        } finally {
            if (progressPrinter != null) {
                progressPrinter.interrupt();
            }
            executor.shutdown();
        }
    }

    /** 超时后打印仍在启动中的 server 列表，提示用户可用 /mcp 查看状态 */
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

    /** 启动进度打印线程：每隔固定间隔输出仍在 STARTING 状态的 server 及已等待时间 */
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

    // ==================== 运维操作（/mcp 子命令）====================

    /**
     * 重启指定 server。
     * <p>流程：卸载旧工具 → 关闭旧连接 → 取消 disabled → 重新执行完整启动流程。</p>
     *
     * @param name server 名称
     * @return 操作结果消息
     */
    public synchronized String restart(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        unregisterTools(server);
        server.close();
        server.config().setDisabled(false);
        start(server);
        return server.status() == McpServerStatus.READY
                ? "✅ MCP server 已重启: " + name
                : "❌ MCP server 重启失败: " + name + " - " + server.errorMessage();
    }

    /**
     * 使用新参数重启指定 server。
     * <p>先覆盖 config 中的 args（如切换 --headless 等），再走标准重启流程。</p>
     */
    public synchronized String restartWithArgs(String name, List<String> args) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        server.config().setArgs(args);
        return restart(name);
    }

    /** 获取指定 server 实例（可能为 null） */
    public McpServer server(String name) {
        return servers.get(name);
    }

    /**
     * 禁用指定 server。
     * <p>卸载工具、关闭连接、标记 disabled 状态，但保留配置以便后续 enable。</p>
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
     * 启用已禁用的 server。
     * <p>取消 disabled 标记后立即执行完整启动流程。</p>
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

    /** 获取指定 server 的 stderr 日志（用于调试和错误排查） */
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

    /** 获取所有 server 实例（按名称排序） */
    public Collection<McpServer> servers() {
        return servers.values().stream()
                .sorted(java.util.Comparator.comparing(McpServer::name))
                .toList();
    }

    // ==================== 状态展示 ====================

    /**
     * 格式化所有 server 状态（用于 /mcp 命令输出）。
     * <p>每行展示：server 名、状态、传输类型、工具数、运行时长、PID、错误信息。</p>
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
     * 生成启动摘要（用于 CLI 首屏 / Banner）。
     * <p>汇总所有 server 的启动状态和工具数量，格式紧凑。</p>
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

    // ==================== 资源（Resources）====================

    /** 获取所有缓存的资源描述符（供补全器和 mention 展开使用） */
    public List<McpResourceDescriptor> resourceCandidates() {
        return resourceCache.all();
    }

    /**
     * 生成资源索引文本（注入 system prompt）。
     * <p>
     * 仅包含 URI 和描述，不含正文。
     * 长上下文模式下模型可参考此索引判断是否需要读取 resource，
     * 需要正文时再调用对应 MCP resource 工具或使用用户显式 @-mention。
     * 最多输出 200 条。
     * </p>
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

    /** 查看指定 server 的资源列表（用于 /mcp resources 命令） */
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

    /** 查看指定 server 的提示词模板列表（用于 /mcp prompts 命令） */
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

    /**
     * 通过 @-mention 读取资源内容。
     * <p>
     * 步骤：
     * 1. 校验 server 存在且就绪
     * 2. 若该 server 的资源缓存过期，先刷新
     * 3. 调用 client.readResource 获取内容
     * 4. 更新缓存并记录审计日志
     * </p>
     *
     * @param serverName server 名称
     * @param uri        资源 URI
     * @return 读取结果（含内容和 mimeType）
     * @throws IOException server 不存在、未就绪或读取失败时抛出
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
        String toolName = McpToolDescriptor.namespaced(serverName, McpResourceTool.READ_RESOURCE);
        String args = "{\"uri\":\"" + uri.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        try {
            if (resourceCache.isServerStale(serverName)) {
                refreshResources(server);
            }
            List<McpResourceContent> contents = server.client().readResource(uri);
            resourceCache.markResourceFresh(serverName, uri);
            toolRegistry.getAuditLog().record(AuditLog.AuditEntry.allowByMention(
                    toolName, args, elapsedMillis(start)));
            return ResourceReadResult.from(contents);
        } catch (Exception e) {
            toolRegistry.getAuditLog().record(AuditLog.AuditEntry.error(
                    toolName, args, e.getMessage(), elapsedMillis(start)));
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    // ==================== 单 server 启动流程 ====================

    /**
     * 启动单个 server 的完整流程。
     * <p>
     * 步骤：
     * 1. 清理旧工具和连接
     * 2. 若已 disabled，标记状态后返回
     * 3. 展开配置中的 ${VAR} 并校验 transport
     * 4. 创建传输层（stdio / HTTP）
     * 5. 创建 McpClient 并执行初始化握手
     * 6. 注册通知处理器（tools/resources 变更监听）
     * 7. 拉取工具列表并注册到 ToolRegistry
     * 8. 标记 READY 状态
     * </p>
     * <p>任何步骤失败都会关闭连接并标记 ERROR 状态，不会阻塞其他 server。</p>
     */
    private void start(McpServer server) {
        unregisterTools(server);
        server.close();
        if (server.config().isDisabled()) {
            server.status(McpServerStatus.DISABLED);
            return;
        }
        server.status(McpServerStatus.STARTING);
        server.errorMessage(null);
        try {
            // 在单 server 启动路径里展开 ${VAR} 与校验 transport，
            // 单个失败仅标 ERROR，不会阻塞其他 server。
            configLoader.prepare(server.config());
            McpTransport transport = createTransport(server.config());
            McpClient client = new McpClient(server.name(), transport);
            client.initialize();
            registerNotificationHandlers(server, client);
            List<McpToolDescriptor> tools = buildToolList(server, client);
            replaceTools(server, client, tools);
            server.client(client);
            server.tools(tools);
            server.markStarted();
            server.status(McpServerStatus.READY);
        } catch (Exception e) {
            server.close();
            server.errorMessage(e.getMessage());
            server.status(McpServerStatus.ERROR);
        }
    }

    /**
     * 构建 server 的完整工具列表。
     * <p>
     * 包括 server 原生工具 + resources 虚拟工具（若 server 支持 resources）。
     * 同时刷新资源缓存，并校验无重复工具名。
     * </p>
     */
    private List<McpToolDescriptor> buildToolList(McpServer server, McpClient client) throws IOException {
        List<McpToolDescriptor> tools = new ArrayList<>(client.listTools());
        if (client.supportsResources()) {
            List<McpResourceDescriptor> resources = client.listResources();
            resourceCache.put(server.name(), resources);
            tools.addAll(McpResourceTool.descriptors(server.name()));
        }
        validateNoDuplicateTools(server.name(), tools);
        return tools;
    }

    /**
     * 将工具注册到 ToolRegistry。
     * <p>
     * 对每个工具生成调用 lambda：
     * 资源虚拟工具走 {@link McpResourceTool} 本地处理，
     * 普通工具透传到 server 的 tools/call。
     * </p>
     */
    private void replaceTools(McpServer server, McpClient client, List<McpToolDescriptor> tools) {
        toolRegistry.replaceMcpToolOutputsForServer(server.name(), tools,
                descriptor -> isResourceVirtualTool(descriptor)
                        ? args -> ToolOutput.text(McpResourceTool.invoker(client, descriptor).apply(args))
                        : args -> invokeMcpToolOutput(client, descriptor, args));
    }

    /** 判断是否为资源虚拟工具（list_resources / read_resource） */
    private boolean isResourceVirtualTool(McpToolDescriptor descriptor) {
        return McpResourceTool.LIST_RESOURCES.equals(descriptor.name())
                || McpResourceTool.READ_RESOURCE.equals(descriptor.name());
    }

    /**
     * 注册 server 通知处理器。
     * <p>
     * 监听三类通知：
     * <ul>
     *   <li>{@code tools/list_changed} — 工具列表变更，重新拉取并注册</li>
     *   <li>{@code resources/list_changed} — 资源列表变更，使 server 级缓存失效</li>
     *   <li>{@code resources/updated} — 单个资源更新，使该 URI 缓存失效</li>
     * </ul>
     * </p>
     */
    private void registerNotificationHandlers(McpServer server, McpClient client) {
        NotificationRouter router = new NotificationRouter();
        router.on("notifications/tools/list_changed", ignored -> {
            try {
                List<McpToolDescriptor> tools = buildToolList(server, client);
                replaceTools(server, client, tools);
                server.tools(tools);
            } catch (Exception e) {
                server.errorMessage("tools/list_changed 处理失败: " + e.getMessage());
            }
        });
        router.on("notifications/resources/list_changed", ignored -> resourceCache.invalidateServer(server.name()));
        router.on("notifications/resources/updated", params -> {
            String uri = params.path("uri").asText("");
            if (!uri.isBlank()) {
                resourceCache.invalidateResource(server.name(), uri);
            }
        });
        client.onNotification(router);
    }

    /** 从 server 重新拉取并缓存资源列表（按 URI 排序） */
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
    // ==================== 内部工具方法 ====================

    /**
     * MCP 工具执行入口。
     * <p>
     * 把 LLM 给的 JSON 参数透传给 server 的 tools/call，
     * 并把异常转成 LLM 可读的错误字符串（而非抛出），
     * 避免单个工具失败导致整个 ReAct 循环中断。
     * </p>
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
     * 根据配置创建传输层实例。
     * <p>HTTP 配置创建 {@link StreamableHttpTransport}，否则创建 {@link StdioTransport} 子进程。</p>
     */
    private McpTransport createTransport(McpServerConfig config) throws IOException {
        if (config.isHttp()) {
            return new StreamableHttpTransport(config.getUrl(), config.getHeaders());
        }
        return new StdioTransport(config.getCommand(), config.getArgs(), config.getEnv(), projectDir);
    }

    /** 校验工具列表中无重复名称，重复时抛异常阻止注册 */
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

    /** 从 ToolRegistry 卸载指定 server 的所有工具 */
    private void unregisterTools(McpServer server) {
        for (McpToolDescriptor tool : server.tools()) {
            toolRegistry.unregisterMcpTool(tool.namespacedName());
        }
        server.tools(List.of());
    }

    /** 纳秒时间戳转毫秒差值（用于审计日志的耗时记录） */
    private static long elapsedMillis(long startedAtNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    /**
     * 资源读取结果。
     * <p>
     * 封装从 MCP server 读取的资源内容和 MIME 类型，
     * 供 mention 展开器将资源内容注入 Agent 上下文。
     * </p>
     */
    public record ResourceReadResult(String content, String mimeType) {

        /**
         * 从 MCP 资源内容列表构造读取结果。
         * <p>
         * 文本内容直接拼接，二进制内容显示 base64 blob 长度占位。
         * MIME 类型取第一个非空值。
         * </p>
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
                    text.append("[binary resource blob omitted, base64 length=")
                            .append(content.blob() == null ? 0 : content.blob().length())
                            .append(']');
                }
                text.append(System.lineSeparator());
            }
            return new ResourceReadResult(text.toString().trim(), firstMimeType);
        }
    }

    /** 时长格式化：秒 → "Xs"，分 → "Xm"，小时 → "Xh" */
    private static String formatDuration(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        return (minutes / 60) + "h";
    }

    /**
     * 关闭所有 server。
     * <p>遍历所有 server 卸载工具并关闭连接，由 McpServerManager 的 AutoCloseable 语义触发。</p>
     */
    @Override
    public void close() {
        for (McpServer server : servers.values()) {
            unregisterTools(server);
            server.close();
        }
    }
}
