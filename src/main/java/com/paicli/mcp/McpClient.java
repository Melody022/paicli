package com.paicli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.jsonrpc.JsonRpcClient;
import com.paicli.mcp.jsonrpc.JsonRpcException;
import com.paicli.mcp.protocol.McpCallToolRequest;
import com.paicli.mcp.protocol.McpCallToolResult;
import com.paicli.mcp.protocol.McpInitializeRequest;
import com.paicli.mcp.protocol.McpSchemaSanitizer;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.mcp.resources.McpResourceContent;
import com.paicli.mcp.resources.McpResourceDescriptor;
import com.paicli.mcp.transport.McpTransport;
import com.paicli.tool.ToolOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * MCP（Model Context Protocol）客户端。
 * <p>
 * 封装与单个 MCP server 的完整通信流程：
 * 初始化握手 → 工具/资源/提示词发现 → 工具调用 → 资源读取 → 通知监听。
 * 上层通过 {@link McpServerManager} 管理多个 McpClient 实例，
 * 每个实例对应一个 server（stdio 子进程或 HTTP 端点）。
 * </p>
 * <p>
 * 通信协议基于 JSON-RPC 2.0，由 {@link JsonRpcClient} 处理请求/响应/通知；
 * 传输层由 {@link McpTransport} 抽象，支持 stdio 和 Streamable HTTP 两种方式。
 * </p>
 */
public class McpClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 默认初始化超时：60 秒 */
    private static final int DEFAULT_INITIALIZE_TIMEOUT_SECONDS = 60;
    /** 系统属性：自定义初始化超时（秒） */
    private static final String INITIALIZE_TIMEOUT_PROPERTY = "paicli.mcp.initialize.timeout.seconds";
    /** 环境变量：自定义初始化超时（秒） */
    private static final String INITIALIZE_TIMEOUT_ENV = "PAICLI_MCP_INITIALIZE_TIMEOUT_SECONDS";

    /** server 名称，用于日志和工具命名空间（如 "filesystem"、"puppeteer"） */
    private final String serverName;
    /** JSON-RPC 客户端，负责请求/响应/通知的协议层处理 */
    private final JsonRpcClient rpc;
    /** 传输层抽象（stdio / HTTP），由构造时注入 */
    private final McpTransport transport;
    /** 服务端声明的能力集合（tools / resources / prompts 等），初始化后赋值 */
    private volatile JsonNode serverCapabilities = JsonNodeFactory.instance.objectNode();

    /**
     * 创建 MCP 客户端。
     *
     * @param serverName server 标识名称
     * @param transport  已建立的传输通道（stdio 子进程或 HTTP 连接）
     */
    public McpClient(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
        this.rpc = new JsonRpcClient(transport);
    }

    /**
     * 执行 MCP 初始化握手。
     * <p>
     * 步骤：
     * 1. 发送 {@code initialize} 请求，携带客户端能力声明，等待 server 返回其能力
     * 2. 发送 {@code notifications/initialized} 通知，告知 server 握手完成
     * </p>
     * <p>超时时间可通过系统属性或环境变量配置，默认 60 秒。</p>
     *
     * @throws IOException 网络异常或初始化失败时抛出
     */
    public void initialize() throws IOException {
        JsonNode result = rpc.request("initialize", McpInitializeRequest.toJson(), initializeTimeoutSeconds());
        serverCapabilities = result == null ? JsonNodeFactory.instance.objectNode() : result.path("capabilities");
        rpc.sendNotification("notifications/initialized", JsonNodeFactory.instance.objectNode());
    }

    /**
     * 解析初始化超时秒数。
     * <p>
     * 优先级：系统属性 > 环境变量 > 默认值 60 秒。
     * 若配置值无效（非数字或非正数），回退到默认值。
     * </p>
     */
    static int initializeTimeoutSeconds() {
        String configured = System.getProperty(INITIALIZE_TIMEOUT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(INITIALIZE_TIMEOUT_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        }
        try {
            int seconds = Integer.parseInt(configured.trim());
            return seconds > 0 ? seconds : DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        } catch (NumberFormatException ignored) {
            return DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        }
    }

    /** 服务端是否声明支持 resources 能力 */
    public boolean supportsResources() {
        return serverCapabilities.has("resources");
    }

    /** 服务端是否声明支持 prompts 能力 */
    public boolean supportsPrompts() {
        return serverCapabilities.has("prompts");
    }

    // ==================== 工具（Tools）====================

    /**
     * 列出服务端所有可用工具。
     * <p>
     * 调用 {@code tools/list} 获取工具列表，每个工具会被解析为 {@link McpToolDescriptor}，
     * 包含命名空间化工具名（{@code mcp__{server}__{tool}}），供 {@link com.paicli.tool.ToolRegistry} 注册。
     * </p>
     * <p>输入 schema 会经过 {@link McpSchemaSanitizer} 清洗，修正不规范的 JSON Schema。</p>
     *
     * @return 工具描述符列表，server 无工具时返回空列表
     * @throws IOException 请求失败时抛出
     */
    public List<McpToolDescriptor> listTools() throws IOException {
        JsonNode result = rpc.request("tools/list", JsonNodeFactory.instance.objectNode(), 30);
        JsonNode tools = result.path("tools");
        if (!tools.isArray()) {
            return List.of();
        }
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            String description = tool.path("description").asText("");
            JsonNode schema = McpSchemaSanitizer.sanitize(tool.path("inputSchema"));
            descriptors.add(new McpToolDescriptor(
                    serverName,
                    name,
                    McpToolDescriptor.namespaced(serverName, name),
                    description,
                    schema
            ));
        }
        return descriptors;
    }

    /**
     * 调用工具并返回文本结果（便捷方法）。
     *
     * @see #callToolOutput(String, String)
     */
    public String callTool(String toolName, String argumentsJson) throws IOException {
        return callToolOutput(toolName, argumentsJson).text();
    }

    /**
     * 调用工具并返回完整 {@link ToolOutput}（含文本和图片）。
     * <p>
     * 步骤：
     * 1. 解析 argumentsJson 为 JSON 节点（空值时为空对象）
     * 2. 构造 {@code tools/call} 请求并发送（超时 60 秒）
     * 3. 反序列化响应为 {@link McpCallToolResult}，转为 ToolOutput
     * 4. 若 server 返回 isError=true，在文本前追加错误前缀
     * </p>
     *
     * @param toolName      工具名称（不含命名空间前缀，server 端使用原始名）
     * @param argumentsJson JSON 格式的参数字符串，可为 null 或空
     * @return 工具执行结果
     * @throws IOException 请求失败或 server 返回错误时抛出
     */
    public ToolOutput callToolOutput(String toolName, String argumentsJson) throws IOException {
        JsonNode args;
        if (argumentsJson == null || argumentsJson.isBlank()) {
            args = JsonNodeFactory.instance.objectNode();
        } else {
            args = MAPPER.readTree(argumentsJson);
        }
        ObjectNode params = McpCallToolRequest.toJson(toolName, args);
        JsonNode result = rpc.request("tools/call", params, 60);
        McpCallToolResult callResult = MAPPER.treeToValue(result, McpCallToolResult.class);
        ToolOutput output = callResult.toToolOutput();
        if (callResult.isError()) {
            return new ToolOutput("MCP 工具返回错误: " + output.text(), output.imageParts());
        }
        return output;
    }

    // ==================== 资源（Resources）====================

    /**
     * 列出服务端所有可用资源。
     * <p>
     * 调用 {@code resources/list}，解析返回的资源描述符列表。
     * 若 server 不支持此方法（JSON-RPC -32601 Method not found），静默返回空列表。
     * </p>
     *
     * @return 资源描述符列表
     * @throws IOException 非 -32601 错误时抛出
     */
    public List<McpResourceDescriptor> listResources() throws IOException {
        try {
            JsonNode result = rpc.request("resources/list", JsonNodeFactory.instance.objectNode(), 30);
            JsonNode resources = result.path("resources");
            if (!resources.isArray()) {
                return List.of();
            }
            List<McpResourceDescriptor> descriptors = new ArrayList<>();
            for (JsonNode resource : resources) {
                McpResourceDescriptor descriptor = McpResourceDescriptor.fromJson(serverName, resource);
                if (descriptor != null) {
                    descriptors.add(descriptor);
                }
            }
            return descriptors;
        } catch (JsonRpcException e) {
            if (e.code() == -32601) {
                return List.of();
            }
            throw e;
        }
    }

    /**
     * 读取指定 URI 的资源内容。
     * <p>
     * 调用 {@code resources/read}，返回资源内容列表（一个 URI 可能对应多个内容块）。
     * 支持文本和二进制（Base64 blob）两种内容类型。
     * </p>
     *
     * @param uri 资源 URI（如 "file:///path/to/file"）
     * @return 资源内容列表
     * @throws IOException 请求失败时抛出
     */
    public List<McpResourceContent> readResource(String uri) throws IOException {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("uri", uri);
        JsonNode result = rpc.request("resources/read", params, 60);
        JsonNode contents = result.path("contents");
        if (!contents.isArray()) {
            return List.of();
        }
        List<McpResourceContent> resourceContents = new ArrayList<>();
        for (JsonNode content : contents) {
            McpResourceContent resourceContent = McpResourceContent.fromJson(content);
            if (resourceContent != null) {
                resourceContents.add(resourceContent);
            }
        }
        return resourceContents;
    }

    /**
     * 订阅资源变更通知。
     * <p>
     * 调用 {@code resources/subscribe}，订阅后 server 会在资源变更时推送通知。
     * 通知通过 {@link #onNotification(Consumer)} 注册的监听器接收。
     * </p>
     *
     * @param uri 要订阅的资源 URI
     * @throws IOException 请求失败时抛出
     */
    public void subscribeResource(String uri) throws IOException {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("uri", uri);
        rpc.request("resources/subscribe", params, 30);
    }

    // ==================== 提示词（Prompts）====================

    /**
     * 列出服务端所有可用提示词模板。
     * <p>
     * 调用 {@code prompts/list}，返回格式化的提示词描述列表。
     * 若 server 不支持此方法（-32601），静默返回空列表。
     * </p>
     *
     * @return 提示词描述行列表（格式："title (name) - description"）
     * @throws IOException 非 -32601 错误时抛出
     */
    public List<String> listPrompts() throws IOException {
        try {
            JsonNode result = rpc.request("prompts/list", JsonNodeFactory.instance.objectNode(), 30);
            JsonNode prompts = result.path("prompts");
            if (!prompts.isArray()) {
                return List.of();
            }
            List<String> lines = new ArrayList<>();
            for (JsonNode prompt : prompts) {
                String name = prompt.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                String title = prompt.path("title").asText("");
                String description = prompt.path("description").asText("");
                String display = title.isBlank() ? name : title + " (" + name + ")";
                lines.add(description.isBlank() ? display : display + " - " + description);
            }
            return lines;
        } catch (JsonRpcException e) {
            if (e.code() == -32601) {
                return List.of();
            }
            throw e;
        }
    }

    // ==================== 通知（Notifications）====================

    /**
     * 注册服务端通知监听器。
     * <p>
     * 接收 server 主动推送的通知（如资源变更、日志等），
     * 监听器在 JsonRpcClient 的读取线程中被调用。
     * </p>
     *
     * @param listener 通知回调，参数为通知的 JSON 内容
     */
    public void onNotification(Consumer<JsonNode> listener) {
        rpc.onNotification(listener);
    }

    // ==================== 格式化工具方法 ====================

    /**
     * 格式化资源列表为用户可读文本。
     * <p>
     * 用于 {@code /mcp resources} 命令展示，每行显示：
     * {@code - uri | displayName | mimeType}，有描述时追加在下一行。
     * </p>
     *
     * @param resources 资源描述符列表
     * @return 格式化文本
     */
    public static String formatResources(List<McpResourceDescriptor> resources) {
        if (resources == null || resources.isEmpty()) {
            return "📭 该 MCP server 暂无 resources";
        }
        StringBuilder sb = new StringBuilder("📚 MCP resources（").append(resources.size()).append("）\n");
        for (McpResourceDescriptor resource : resources) {
            sb.append("- ").append(resource.uri());
            String name = resource.displayName();
            if (name != null && !name.isBlank() && !name.equals(resource.uri())) {
                sb.append(" | ").append(name);
            }
            if (resource.mimeType() != null && !resource.mimeType().isBlank()) {
                sb.append(" | ").append(resource.mimeType());
            }
            if (resource.description() != null && !resource.description().isBlank()) {
                sb.append("\n  ").append(resource.description());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 格式化资源内容为 XML 标记文本。
     * <p>
     * 用于将资源内容注入 Agent 上下文时的序列化格式：
     * 文本资源直接嵌入 {@code <resource>} 标签内，
     * 二进制资源显示 base64 blob 长度占位。
     * </p>
     *
     * @param contents 资源内容列表
     * @return XML 格式的文本
     */
    public static String formatResourceContents(List<McpResourceContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return "📭 MCP resource 内容为空";
        }
        StringBuilder sb = new StringBuilder();
        for (McpResourceContent content : contents) {
            String mimeType = content.mimeType() == null || content.mimeType().isBlank()
                    ? "application/octet-stream"
                    : content.mimeType();
            sb.append("<resource uri=\"").append(escapeXml(content.uri()))
                    .append("\" mimeType=\"").append(escapeXml(mimeType)).append("\">\n");
            if (content.isText()) {
                sb.append(content.text());
            } else {
                sb.append("[binary resource blob omitted, base64 length=")
                        .append(content.blob() == null ? 0 : content.blob().length())
                        .append(']');
            }
            sb.append("\n</resource>\n");
        }
        return sb.toString().trim();
    }

    /** XML 特殊字符转义，防止资源 URI / mimeType 破坏 XML 结构 */
    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    // ==================== 传输层信息透传 ====================

    /** 获取 server 进程的 stderr 输出行（用于调试和错误排查） */
    public List<String> stderrLines() {
        return transport.stderrLines();
    }

    /** 获取 server 子进程的 PID（stdio 模式），HTTP 模式返回 null */
    public Long processId() {
        return transport.processId();
    }

    /** 获取传输层名称（如 "stdio"、"http"） */
    public String transportName() {
        return transport.transportName();
    }

    @Override
    public void close() {
        // 直接走 transport-level 关闭信号：stdio 通过 stdin EOF + 进程销毁；HTTP 通过 DELETE session。
        // 之前会先发 shutdown notification，但当 server 卡死 / 队列堵塞时这条通知会让 close 阻塞 60 秒。
        // 移除后退出更快、行为更可预期；shutdown 语义改由 transport 层承担。
        rpc.close();
    }
}
