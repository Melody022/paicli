package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 客户端统一接口 —— 大模型调用的顶层抽象
 *
 * 这是 PaiCLI 与大模型通信的核心接口。所有模型提供商（GLM、DeepSeek、Kimi 等）
 * 都通过实现此接口来接入 PaiCLI 的 Agent 系统。
 *
 * 调用链路：
 * Agent.run() → llmClient.chat(messages, tools) → HTTP 请求 → 流式解析 → ChatResponse
 *
 * 继承关系：
 * LlmClient（接口）
 *   └── AbstractOpenAiCompatibleClient（抽象基类，封装 HTTP + SSE 流式处理）
 *         ├── GLMClient（智谱 GLM 模型）
 *         ├── DeepSeekClient（DeepSeek 模型）
 *         ├── StepClient（阶跃星辰模型）
 *         └── KimiClient（月之暗面 Kimi 模型）
 */
public interface LlmClient {

    /**
     * 同步聊天接口（非流式）—— 等待完整响应后一次性返回
     *
     * @param messages 对话历史消息列表（包含 system、user、assistant、tool 四种角色）
     * @param tools 当前可用的工具定义列表（JSON Schema 格式）
     * @return 模型的完整响应（包含 content、reasoningContent、toolCalls）
     */
    ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException;

    /**
     * 流式聊天接口 —— 边接收边显示，实现打字机效果
     *
     * 这是 TUI 实时显示的核心。调用过程中，每收到一小段数据就通过 listener 回调通知 UI 层，
     * 让用户可以实时看到 AI 的思考和回复过程。
     *
     * @param messages 对话历史消息列表
     * @param tools 当前可用的工具定义列表
     * @param listener 流式事件监听器，用于接收 reasoning 和 content 的增量文本
     * @return 累加后的完整响应对象
     */
    ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException;

    /**
     * 获取模型名称（如 glm-5.1、deepseek-chat）
     * 用于日志记录、状态栏显示、费用计算
     */
    String getModelName();

    /**
     * 获取提供商名称（如 glm、deepseek、step、kimi）
     * 用于日志和状态栏显示
     */
    String getProviderName();

    /**
     * 获取模型支持的最大上下文窗口（Token 数）
     *
     * Agent 会根据此数值决定：
     * 1. 何时触发对话历史压缩（接近窗口上限时）
     * 2. 何时触发短期记忆压缩
     * 3. MCP resource 索引是否启用（window < 32k 时关闭）
     *
     * 默认 128,000 tokens，各模型可覆盖（如 GLM 是 200,000）
     */
    default int maxContextWindow() {
        return 128_000;
    }

    /**
     * 是否支持 Prompt Caching（前缀缓存）功能
     *
     * 启用后，相同前缀的 system prompt 不会重复计费，
     * 可以显著降低 API 调用成本
     */
    default boolean supportsPromptCaching() {
        return false;
    }

    /**
     * 返回缓存模式的具体标识符
     * 不同模型提供商的缓存标识符不同（如 GLM 用 "glm-prompt-cache"）
     */
    default String promptCacheMode() {
        return "none";
    }

    /**
     * 多模态内容片段 —— 支持文本 + 图片的混合消息
     *
     * 一条 user 消息可以包含多个 ContentPart，比如：
     * - 纯文本："请分析这张图片"
     * - Base64 图片：从剪贴板粘贴的截图
     * - 图片 URL：网络上的图片链接
     *
     * 这样 LLM 就能"看到"用户提供的图片，实现多模态交互
     */
    record ContentPart(String type, String text, String imageBase64, String imageUrl, String mimeType) {
        /** 创建一个纯文本片段 */
        public static ContentPart text(String text) {
            return new ContentPart("text", text, null, null, null);
        }

        /** 创建一个 Base64 编码的图片片段（例如从剪贴板粘贴的截图） */
        public static ContentPart imageBase64(String imageBase64, String mimeType) {
            return new ContentPart("image_base64", null, imageBase64, null,
                    mimeType == null || mimeType.isBlank() ? "image/png" : mimeType);
        }

        /** 创建一个网络图片链接片段 */
        public static ContentPart imageUrl(String imageUrl) {
            return new ContentPart("image_url", null, null, imageUrl, null);
        }

        /** 判断当前片段是不是文字 */
        public boolean isText() {
            return "text".equals(type);
        }

        /** 判断当前片段是不是图片 */
        public boolean isImage() {
            return "image_base64".equals(type) || "image_url".equals(type);
        }
    }

    /**
     * 对话消息单元 —— Agent 与 LLM 交互的核心数据结构
     *
     * 对应 OpenAI 协议中的 message 对象。整个 ReAct 循环就是围绕 Message 列表展开的。
     *
     * 四种角色的消息在对话历史中的流转：
     * ┌─────────────────────────────────────────────────────────────────────┐
     * │ [system]  "你是一个编程助手..."  ← 只在开头，定义 AI 行为准则        │
     * │ [user]    "帮我写一个 Hello World"  ← 用户输入                      │
     * │ [assistant] content="好的" tool_calls=[{read_file, ...}]  ← AI 回复 │
     * │ [tool]    tool_call_id="xxx" content="文件内容: ..."  ← 工具结果    │
     * │ [assistant] content="这是修改后的代码..."  ← AI 最终回答             │
     * └─────────────────────────────────────────────────────────────────────┘
     *
     * @param role 角色类型：
     *             - "system": 系统预设指令，用于定义 AI 的行为准则
     *             - "user": 用户输入的消息
     *             - "assistant": AI 生成的回复（可能包含 toolCalls）
     *             - "tool": 工具执行后的返回结果
     * @param content 消息的文本内容
     * @param reasoningContent AI 的思考过程（深度思考模型如 GLM-5.1 会返回此字段）
     * @param toolCalls AI 发起的工具调用请求列表（当 AI 决定使用工具时）
     * @param toolCallId 当角色为 "tool" 时，用于关联对应的工具调用 ID
     */
    record Message(String role, String content, String reasoningContent, List<ToolCall> toolCalls,
                   String toolCallId, List<ContentPart> contentParts) {
        public Message(String role, String content, String reasoningContent, List<ToolCall> toolCalls,
                       String toolCallId) {
            this(role, content, reasoningContent, toolCalls, toolCallId, null);
        }

        public Message(String role, String content) {
            this(role, content, null, null, null);
        }

        /** 创建系统消息 —— 定义 AI 的身份和行为准则 */
        public static Message system(String content) {
            return new Message("system", content);
        }

        /** 创建用户消息（纯文本） */
        public static Message user(String content) {
            return new Message("user", content);
        }

        /** 创建用户消息（支持多模态，包含文本和图片） */
        public static Message user(List<ContentPart> contentParts) {
            return new Message("user", plainText(contentParts), null, null, null,
                    contentParts == null ? null : List.copyOf(contentParts));
        }

        /** 创建助手消息（纯文本回复，没有工具调用） */
        public static Message assistant(String content) {
            return new Message("assistant", content);
        }

        /** 创建助手消息（包含思考过程） */
        public static Message assistant(String reasoningContent, String content) {
            return new Message("assistant", content, reasoningContent, null, null);
        }

        /** 创建助手消息（包含工具调用请求） */
        public static Message assistant(String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, null, toolCalls, null);
        }

        /** 创建助手消息（包含思考过程 + 工具调用请求） */
        public static Message assistant(String reasoningContent, String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, reasoningContent, toolCalls, null);
        }

        /**
         * 创建工具结果消息 —— 工具执行后回填给 LLM
         *
         * @param toolCallId 对应的工具调用 ID（用于 LLM 关联是哪个工具的结果）
         * @param content 工具执行的结果文本
         */
        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, null, null, toolCallId);
        }

        public boolean hasContentParts() {
            return contentParts != null && !contentParts.isEmpty();
        }

        public boolean hasImageContent() {
            return hasContentParts() && contentParts.stream().anyMatch(ContentPart::isImage);
        }

        public int imagePartCount() {
            if (!hasContentParts()) {
                return 0;
            }
            int count = 0;
            for (ContentPart part : contentParts) {
                if (part != null && part.isImage()) {
                    count++;
                }
            }
            return count;
        }

        /**
         * 移除图片内容，用于历史消息压缩
         * 避免旧图片占用过多上下文窗口
         */
        public Message withoutImageContent() {
            if (!hasImageContent()) {
                return this;
            }
            List<ContentPart> stripped = new ArrayList<>();
            int omitted = 0;
            for (ContentPart part : contentParts) {
                if (part == null) {
                    continue;
                }
                if (part.isImage()) {
                    omitted++;
                } else {
                    stripped.add(part);
                }
            }
            stripped.add(ContentPart.text("[历史图片附件已省略 " + omitted
                    + " 张；如需重新查看，请使用上文 Image source 或相关工具结果。]"));
            return new Message(role, plainText(stripped), reasoningContent, toolCalls, toolCallId, List.copyOf(stripped));
        }

        /** 移除思考过程内容 */
        public Message withoutReasoningContent() {
            if (reasoningContent == null || reasoningContent.isBlank()) {
                return this;
            }
            return new Message(role, content, null, toolCalls, toolCallId, contentParts);
        }

        private static String plainText(List<ContentPart> parts) {
            if (parts == null || parts.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int imageCount = 0;
            for (ContentPart part : parts) {
                if (part == null) {
                    continue;
                }
                if (part.isText() && part.text() != null && !part.text().isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append("\n\n");
                    }
                    sb.append(part.text());
                } else if (part.isImage()) {
                    imageCount++;
                }
            }
            if (imageCount > 0) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append("[已附加 ").append(imageCount).append(" 张图片]");
            }
            return sb.toString();
        }
    }

    /**
     * 工具调用指令 —— LLM 告诉 Agent "请帮我执行这个工具"
     *
     * 当 LLM 决定使用工具时，会在响应中返回 toolCalls 列表。
     * 每个 ToolCall 包含：
     * - id: 唯一标识符（执行完工具后，用这个 ID 把结果回填给 LLM）
     * - function: 要调用的函数名和参数
     *
     * 示例：
     * {
     *   "id": "call_abc123",
     *   "function": {
     *     "name": "read_file",
     *     "arguments": "{\"path\": \"src/Main.java\"}"
     *   }
     * }
     */
    record ToolCall(String id, Function function) {
        /**
         * 函数详情
         * @param name 函数名称（如 read_file、write_file、execute_command）
         * @param arguments 参数字符串（JSON 格式，如 {"path": "src/Main.java"}）
         */
        public record Function(String name, String arguments) {}
    }

    /**
     * 工具定义 —— 告诉 LLM "我有哪些工具可以用"
     *
     * Agent 在每次调 LLM 时，都会把所有可用工具的定义发过去。
     * LLM 根据这些定义知道可以调用哪些工具、每个工具需要什么参数。
     *
     * 示例（对应 OpenAI 的 tools 数组）：
     * {
     *   "name": "read_file",
     *   "description": "读取文件内容（仅限项目根目录之内）",
     *   "parameters": {
     *     "type": "object",
     *     "properties": {
     *       "path": {"type": "string", "description": "文件路径"}
     *     },
     *     "required": ["path"]
     *   }
     * }
     */
    record Tool(String name, String description, JsonNode parameters) {}

    /**
     * 流式输出监听器 —— 实现打字机效果的关键接口
     *
     * LLM 返回数据时是分批到达的（SSE 流），每批包含一小段文本。
     * 通过 StreamListener，UI 层可以实时显示这些增量文本，
     * 让用户看到 AI 正在"思考"和"打字"的过程。
     */
    interface StreamListener {
        /** 空实现，用于不需要处理流式输出的场景（如同步调用） */
        StreamListener NO_OP = new StreamListener() {};

        /**
         * 接收 AI 思考过程的增量文本
         * 部分深度思考模型（如 GLM-5.1）会先输出思考过程，再输出正式回答
         */
        default void onReasoningDelta(String delta) {}

        /** 接收 AI 正式回复内容的增量文本 */
        default void onContentDelta(String delta) {}
    }

    /**
     * 聊天响应结果 —— LLM 返回的完整响应
     *
     * 这是 LLM 一次调用的完整返回，包含：
     * - content: AI 的正式回答文本
     * - reasoningContent: AI 的思考过程（深度思考模型支持）
     * - toolCalls: 工具调用请求列表（如果 AI 决定使用工具）
     * - inputTokens/outputTokens: token 消耗统计
     */
    record ChatResponse(String role, String content, String reasoningContent, List<ToolCall> toolCalls,
                        int inputTokens, int outputTokens, int cachedInputTokens) {
        public ChatResponse(String role, String content, List<ToolCall> toolCalls,
                            int inputTokens, int outputTokens) {
            this(role, content, null, toolCalls, inputTokens, outputTokens, 0);
        }

        public ChatResponse(String role, String content, String reasoningContent, List<ToolCall> toolCalls,
                            int inputTokens, int outputTokens) {
            this(role, content, reasoningContent, toolCalls, inputTokens, outputTokens, 0);
        }

        /**
         * 判断 LLM 是否请求了工具调用
         * 如果返回 true，Agent 需要执行工具并继续循环
         * 如果返回 false，说明 AI 已经给出最终回答，ReAct 循环结束
         */
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }
}
