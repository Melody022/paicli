package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import okio.BufferedSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容协议的抽象基类 —— 封装 HTTP 请求和 SSE 流式解析的核心实现
 *
 * 这是所有 LLM 客户端（GLM、DeepSeek、Kimi 等）的公共父类。
 * 它把与大模型 API 通信的通用逻辑都封装在这里，子类只需要提供：
 * - getApiUrl(): API 地址
 * - getModel(): 模型名称
 * - getApiKey(): API 密钥
 *
 * 核心流程（chat 方法）：
 * 1. 把 Message 列表 + Tool 列表序列化为 JSON 请求体
 * 2. 发起 HTTP POST 请求到大模型 API
 * 3. 逐行读取 SSE 流（Server-Sent Events）
 * 4. 从每个 SSE 数据块中提取 content、reasoning、tool_calls 的增量
 * 5. 通过 StreamListener 回调通知 UI 层实时显示
 * 6. 累加所有增量，最终返回完整的 ChatResponse
 */
public abstract class AbstractOpenAiCompatibleClient implements LlmClient {

    /** JSON 序列化工具，用于构建请求体和解析响应 */
    protected static final ObjectMapper mapper = new ObjectMapper();

    /**
     * 共享的 HTTP 客户端，所有 LLM 客户端实例共用
     *
     * 超时配置说明：
     * - connectTimeout: 建立 TCP 连接的超时时间（60s）
     * - readTimeout: 等待服务器响应的超时时间（300s，因为深度思考模型可能需要很长时间）
     * - writeTimeout: 发送请求体的超时时间（60s）
     * - callTimeout: 整个调用的总超时时间（600s）
     *
     * 这些超时可以通过 JVM 参数覆盖：
     * -Dpaicli.llm.connect.timeout.seconds=60
     * -Dpaicli.llm.read.timeout.seconds=300
     */
    protected static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(readTimeoutSeconds("paicli.llm.connect.timeout.seconds", 60), TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds("paicli.llm.read.timeout.seconds", 300), TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSeconds("paicli.llm.write.timeout.seconds", 60), TimeUnit.SECONDS)
            .callTimeout(readTimeoutSeconds("paicli.llm.call.timeout.seconds", 600), TimeUnit.SECONDS)
            .build();

    private static long readTimeoutSeconds(String key, long defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 获取 API 的基础 URL（子类实现）
     * 例如：https://open.bigmodel.cn/api/coding/paas/v4/chat/completions
     */
    protected abstract String getApiUrl();

    /**
     * 获取当前使用的模型名称（子类实现）
     * 例如：glm-5.1、deepseek-chat
     */
    protected abstract String getModel();

    /**
     * 获取 API 鉴权密钥（子类实现）
     */
    protected abstract String getApiKey();

    /**
     * 是否需要在请求历史中发送 reasoning_content 字段。
     * <p>
     * 如果 assistant 消息中有 reasoning_content，说明模型开启了思考模式，
     * 后续请求中必须将其回传，否则部分 API 会拒绝请求（如 DeepSeek R1、GLM 深度思考）。
     * <p>
     * 实际发送前还会检查 {@code msg.reasoningContent() != null && !msg.reasoningContent().isBlank()}，
     * 所以打开此开关不会影响不返回 reasoning 的模型。
     */
    protected boolean shouldSendReasoningContentInRequestHistory() {
        return true;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    /**
     * 执行流式聊天请求 —— 这是与大模型 API 通信的核心方法
     *
     * 完整流程：
     * ┌────────────────────────────────────────────────────────────────────┐
     * │ 1. buildRequestBody()  把 Message/Tool 列表序列化为 JSON          │
     * │ 2. HTTP POST 请求     发送到大模型 API                            │
     * │ 3. 逐行读取 SSE 流    解析 data: 开头的行                         │
     * │ 4. 提取增量           从每个数据块提取 content/reasoning/tool_calls│
     * │ 5. 回调通知           通过 StreamListener 实时通知 UI 层           │
     * │ 6. 累加返回           所有增量累加后返回完整的 ChatResponse        │
     * └────────────────────────────────────────────────────────────────────┘
     *
     * @param messages 对话历史消息列表
     * @param tools 当前可用的工具定义列表
     * @param listener 流式事件监听器，用于实时显示 AI 的思考和回复
     * @return 累加后的完整响应对象
     */
    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        StreamListener streamListener = listener == null ? StreamListener.NO_OP : listener;

        // 第一步：构建 JSON 请求体
        RequestBody body = RequestBody.create(
                buildRequestBody(messages, tools).toString(),
                MediaType.parse("application/json")
        );

        // 第二步：构建 HTTP 请求（带 Bearer Token 认证）
        Request request = new Request.Builder()
                .url(getApiUrl())
                .header("Authorization", "Bearer " + getApiKey())
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        // 第三步：发起请求并解析 SSE 响应流
        try (Response response = SHARED_HTTP_CLIENT.newCall(request).execute()) {
            ResponseBody responseBodyObj = response.body();
            if (!response.isSuccessful()) {
                String errorBody = responseBodyObj != null ? responseBodyObj.string() : "无响应体";
                throw new IOException("API请求失败: " + response.code() + " - " + errorBody);
            }
            if (responseBodyObj == null) {
                throw new IOException("API返回空响应体");
            }

            // 初始化累加器，用于收集流式返回的碎片化数据
            BufferedSource source = responseBodyObj.source();
            String role = "assistant";
            StringBuilder content = new StringBuilder();      // 累加正式回复
            StringBuilder reasoning = new StringBuilder();    // 累加思考过程
            List<ToolCallAccumulator> toolAccumulators = new ArrayList<>();  // 累加工具调用
            int inputTokens = 0;
            int outputTokens = 0;
            int cachedInputTokens = 0;

            // 第四步：循环读取 SSE 流，直到连接关闭或收到 [DONE] 标记
            while (!source.exhausted()) {
                String line = source.readUtf8Line();
                if (line == null) break;

                String trimmed = line.trim();
                // SSE 协议要求数据行以 "data:" 开头，跳过其他行
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue;

                String payload = trimmed.substring("data:".length()).trim();
                if (payload.isEmpty()) continue;
                if ("[DONE]".equals(payload)) break; // 响应结束标记

                // 解析当前数据块的 JSON 内容
                JsonNode root = mapper.readTree(payload);

                // 提取 Token 使用量统计（通常位于最后一个数据块）
                JsonNode usage = root.path("usage");
                if (!usage.isMissingNode()) {
                    inputTokens = usage.path("prompt_tokens").asInt(inputTokens);
                    outputTokens = usage.path("completion_tokens").asInt(outputTokens);
                    cachedInputTokens = parseCachedInputTokens(usage, cachedInputTokens);
                }

                // 提取内容增量 (Delta)
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) continue;

                JsonNode choice = choices.get(0);
                JsonNode delta = choice.path("delta");
                if (delta.isMissingNode() || delta.isNull()) delta = choice.path("message");
                if (delta.isMissingNode() || delta.isNull()) continue;

                // 更新角色信息
                String deltaRole = delta.path("role").asText("");
                if (!deltaRole.isEmpty()) role = deltaRole;

                // 第五步：提取并回调思考过程增量（深度思考模型支持）
                String reasoningDelta = extractReasoningDelta(delta);
                if (!reasoningDelta.isEmpty()) {
                    reasoning.append(reasoningDelta);
                    streamListener.onReasoningDelta(reasoningDelta);  // 通知 UI 显示思考过程
                }

                // 第六步：提取并回调正式回复增量
                String contentDelta = delta.path("content").asText("");
                if (!contentDelta.isEmpty()) {
                    content.append(contentDelta);
                    streamListener.onContentDelta(contentDelta);  // 通知 UI 显示回复内容
                }

                // 合并工具调用增量（LLM 可能分多次发送工具调用的参数）
                mergeToolCallDeltas(toolAccumulators, delta.path("tool_calls"));
            }

            // 第七步：返回累加后的完整响应
            return new ChatResponse(
                    role,
                    content.toString(),
                    reasoning.toString(),
                    buildToolCalls(toolAccumulators),
                    inputTokens,
                    outputTokens,
                    cachedInputTokens
            );
        }
    }

    /**
     * 从响应增量中提取思考过程（Reasoning Content）。
     * <p>
     * 兼容不同厂商的字段命名差异，依次尝试 reasoning_content, reasoning 及 reasoning_details。
     */
    private String extractReasoningDelta(JsonNode delta) {
        String reasoningContent = delta.path("reasoning_content").asText("");
        if (!reasoningContent.isEmpty()) {
            return reasoningContent;
        }
        String reasoning = delta.path("reasoning").asText("");
        if (!reasoning.isEmpty()) {
            return reasoning;
        }
        JsonNode details = delta.path("reasoning_details");
        if (details.isArray() && !details.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode detail : details) {
                String text = detail.path("text").asText("");
                if (text.isEmpty()) {
                    text = detail.path("content").asText("");
                }
                if (!text.isEmpty()) {
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        return "";
    }

    private int parseCachedInputTokens(JsonNode usage, int fallback) {
        int cached = usage.path("cached_tokens").asInt(fallback);
        cached = usage.path("prompt_cache_hit_tokens").asInt(cached);
        cached = usage.path("input_cache_hit_tokens").asInt(cached);
        JsonNode promptDetails = usage.path("prompt_tokens_details");
        if (!promptDetails.isMissingNode()) {
            cached = promptDetails.path("cached_tokens").asInt(cached);
        }
        JsonNode inputDetails = usage.path("input_tokens_details");
        if (!inputDetails.isMissingNode()) {
            cached = inputDetails.path("cached_tokens").asInt(cached);
        }
        return cached;
    }

    /**
     * 构建发送给 LLM 服务器的 JSON 请求体。
     * <p>
     * 将内部的 Message 和 Tool 对象序列化为符合 OpenAI 规范的 JSON 结构。
     */
    private ObjectNode buildRequestBody(List<Message> messages, List<Tool> tools) {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", getModel());
        requestBody.put("stream", true);

        ArrayNode messagesArray = requestBody.putArray("messages");
        for (Message msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.role());
            appendMessageContent(msgNode, msg);
            if (shouldSendReasoningContentInRequestHistory()
                    && msg.reasoningContent() != null
                    && !msg.reasoningContent().isBlank()) {
                msgNode.put("reasoning_content", msg.reasoningContent());
            }

            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsArray = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = toolCallsArray.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode functionNode = tcNode.putObject("function");
                    functionNode.put("name", tc.function().name());
                    functionNode.put("arguments", tc.function().arguments());
                }
            }

            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.set("parameters", tool.parameters());
            }
        }
        customizeRequestBody(requestBody);
        return requestBody;
    }

    protected void customizeRequestBody(ObjectNode requestBody) {
    }

    private void appendMessageContent(ObjectNode msgNode, Message msg) {
        if (!msg.hasContentParts()) {
            msgNode.put("content", msg.content());
            return;
        }

        ArrayNode contentArray = msgNode.putArray("content");
        for (LlmClient.ContentPart part : msg.contentParts()) {
            if (part == null) {
                continue;
            }
            if (part.isText()) {
                if (part.text() != null && !part.text().isBlank()) {
                    ObjectNode textNode = contentArray.addObject();
                    textNode.put("type", "text");
                    textNode.put("text", part.text());
                }
                continue;
            }
            if (part.isImage()) {
                String imageUrl = toImageUrl(part);
                if (imageUrl == null || imageUrl.isBlank()) {
                    continue;
                }
                ObjectNode imageNode = contentArray.addObject();
                imageNode.put("type", "image_url");
                ObjectNode imageUrlNode = imageNode.putObject("image_url");
                imageUrlNode.put("url", imageUrl);
            }
        }

        if (contentArray.isEmpty()) {
            msgNode.put("content", msg.content());
        }
    }

    protected String toImageUrl(LlmClient.ContentPart part) {
        if ("image_url".equals(part.type())) {
            return part.imageUrl();
        }
        if ("image_base64".equals(part.type())) {
            String mimeType = part.mimeType() == null || part.mimeType().isBlank() ? "image/png" : part.mimeType();
            return "data:" + mimeType + ";base64," + part.imageBase64();
        }
        return null;
    }

    /**
     * 合并流式传输中的工具调用分片。
     * <p>
     * 由于工具调用的 ID、函数名和参数可能分布在多个 SSE 数据块中，
     * 该方法负责根据 index 将这些碎片累加到对应的 Accumulator 中。
     */
    private void mergeToolCallDeltas(List<ToolCallAccumulator> accumulators, JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return;
        }

        for (JsonNode tc : toolCallsNode) {
            int index = tc.path("index").asInt(accumulators.size());
            while (accumulators.size() <= index) {
                accumulators.add(new ToolCallAccumulator());
            }

            ToolCallAccumulator acc = accumulators.get(index);
            String id = tc.path("id").asText("");
            if (!id.isEmpty()) {
                acc.id = id;
            }

            JsonNode function = tc.path("function");
            String name = function.path("name").asText("");
            if (!name.isEmpty()) {
                acc.name.append(name);
            }
            String arguments = function.path("arguments").asText("");
            if (!arguments.isEmpty()) {
                acc.arguments.append(arguments);
            }
        }
    }

    private List<ToolCall> buildToolCalls(List<ToolCallAccumulator> accumulators) {
        if (accumulators.isEmpty()) {
            return null;
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        for (ToolCallAccumulator acc : accumulators) {
            if (acc.id == null || acc.id.isBlank()) {
                continue;
            }
            toolCalls.add(new ToolCall(
                    acc.id,
                    new ToolCall.Function(acc.name.toString(), acc.arguments.toString())
            ));
        }
        return toolCalls.isEmpty() ? null : toolCalls;
    }

    /**
     * 内部辅助类，用于在流式解析过程中临时存储工具调用信息。
     */
    private static final class ToolCallAccumulator {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
