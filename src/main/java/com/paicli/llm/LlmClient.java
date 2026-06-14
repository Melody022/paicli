package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 客户端统一接口。
 * <p>
 * 所有大模型提供商（GLM / DeepSeek / Step / Kimi / FreeLLMApi 等）都实现该接口，
 * 上层 Agent / PlanExecuteAgent / SubAgent 仅依赖此接口与模型交互，实现多模型可替换。
 * </p>
 * <p>
 * 接口内同时定义了与 LLM 通信所需的全部数据模型（record），包括：
 * {@link Message}（消息）、{@link ToolCall}（工具调用）、{@link Tool}（工具定义）、
 * {@link ContentPart}（多模态内容片段）、{@link StreamListener}（流式回调）、
 * {@link ChatResponse}（响应封装）。
 * </p>
 */
public interface LlmClient {

    /**
     * 非流式对话：发送消息列表和可用工具列表，返回完整响应。
     *
     * @param messages 对话历史消息列表（含 system / user / assistant / tool 消息）
     * @param tools    当前可用的工具定义列表，为空则不启用 function calling
     * @return 模型完整响应，包含文本内容和可能的工具调用
     * @throws IOException 网络异常或请求失败时抛出
     */
    ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException;

    /**
     * 流式对话：发送消息列表和可用工具列表，通过 listener 逐 token 回调。
     * <p>
     * 适用于需要实时展示生成过程的场景（ReAct thinking 区、inline renderer 等）。
     * </p>
     *
     * @param messages 对话历史消息列表
     * @param tools    当前可用的工具定义列表
     * @param listener 流式事件监听器，接收 reasoning / content 增量片段
     * @return 模型完整响应（流式结束后汇总）
     * @throws IOException 网络异常或请求失败时抛出
     */
    ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException;

    /**
     * 获取当前使用的模型名称（如 "glm-4-flash"、"deepseek-chat" 等）。
     */
    String getModelName();

    /**
     * 获取提供商标识（如 "glm"、"deepseek"、"step"、"kimi" 等），
     * 用于 LlmClientFactory 按 provider 选择客户端实例。
     */
    String getProviderName();

    /**
     * 当前模型的最大上下文窗口（token 数）。
     * <p>默认 128K，各实现可按模型实际能力覆盖。</p>
     */
    default int maxContextWindow() {
        return 128_000;
    }

    /**
     * 是否支持 prompt caching（提示缓存）。
     * <p>开启后，重复前缀的 system prompt 可复用缓存以减少计费和延迟。</p>
     */
    default boolean supportsPromptCaching() {
        return false;
    }

    /**
     * prompt caching 模式标识。
     * <p>
     * 返回 "none" 表示不支持；具体模式字符串由各实现定义
     * （如 "auto"、"manual" 等），供上层决策是否发送缓存控制标记。
     * </p>
     */
    default String promptCacheMode() {
        return "none";
    }

    // ==================== 数据模型定义 ====================

    /**
     * 多模态内容片段。
     * <p>
     * 支持三种类型：纯文本（text）、Base64 编码图片（image_base64）、图片 URL（image_url）。
     * 用于 Phase 21 图片输入能力，让 user 消息可携带图文混合内容。
     * </p>
     */
    record ContentPart(String type, String text, String imageBase64, String imageUrl, String mimeType) {

        /** 创建纯文本片段 */
        public static ContentPart text(String text) {
            return new ContentPart("text", text, null, null, null);
        }

        /**
         * 创建 Base64 编码的图片片段。
         * 若未指定 mimeType，默认使用 image/png。
         */
        public static ContentPart imageBase64(String imageBase64, String mimeType) {
            return new ContentPart("image_base64", null, imageBase64, null,
                    mimeType == null || mimeType.isBlank() ? "image/png" : mimeType);
        }

        /** 创建图片 URL 引用片段 */
        public static ContentPart imageUrl(String imageUrl) {
            return new ContentPart("image_url", null, null, imageUrl, null);
        }

        /** 是否为纯文本片段 */
        public boolean isText() {
            return "text".equals(type);
        }

        /** 是否为图片片段（Base64 或 URL 均算） */
        public boolean isImage() {
            return "image_base64".equals(type) || "image_url".equals(type);
        }
    }

    /**
     * 对话消息。
     * <p>
     * 对应 OpenAI 兼容 API 的 message 对象，支持四种角色：
     * system（系统提示）、user（用户输入）、assistant（模型回复）、tool（工具执行结果）。
     * </p>
     * <p>
     * 关键字段说明：
     * <ul>
     *   <li>{@code reasoningContent} — 思考链/推理内容（DeepSeek V4 / Kimi thinking 模式），
     *       需随下一轮请求历史带回以保持上下文连贯</li>
     *   <li>{@code toolCalls} — assistant 消息中的工具调用请求列表</li>
     *   <li>{@code toolCallId} — tool 角色消息对应的工具调用 ID</li>
     *   <li>{@code contentParts} — 多模态内容片段列表（图文混合输入时使用）</li>
     * </ul>
     * </p>
     */
    record Message(String role, String content, String reasoningContent, List<ToolCall> toolCalls,
                   String toolCallId, List<ContentPart> contentParts) {

        /** 完整构造（含多模态内容片段） */
        public Message(String role, String content, String reasoningContent, List<ToolCall> toolCalls,
                       String toolCallId) {
            this(role, content, reasoningContent, toolCalls, toolCallId, null);
        }

        /** 最简构造：仅角色 + 文本内容 */
        public Message(String role, String content) {
            this(role, content, null, null, null);
        }

        // ---------- 工厂方法：按角色快捷创建消息 ----------

        /** 创建 system 消息（系统提示词） */
        public static Message system(String content) {
            return new Message("system", content);
        }

        /** 创建纯文本 user 消息 */
        public static Message user(String content) {
            return new Message("user", content);
        }

        /**
         * 创建多模态 user 消息（图文混合）。
         * contentParts 中的文本部分会被提取为 content 字段，
         * 图片部分保留在 contentParts 中供各 Client 按 API 格式序列化。
         */
        public static Message user(List<ContentPart> contentParts) {
            return new Message("user", plainText(contentParts), null, null, null,
                    contentParts == null ? null : List.copyOf(contentParts));
        }

        /** 创建纯文本 assistant 消息 */
        public static Message assistant(String content) {
            return new Message("assistant", content);
        }

        /** 创建带思考链的 assistant 消息（thinking 模式） */
        public static Message assistant(String reasoningContent, String content) {
            return new Message("assistant", content, reasoningContent, null, null);
        }

        /** 创建带工具调用的 assistant 消息 */
        public static Message assistant(String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, null, toolCalls, null);
        }

        /** 创建同时带思考链和工具调用的 assistant 消息 */
        public static Message assistant(String reasoningContent, String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, reasoningContent, toolCalls, null);
        }

        /** 创建 tool 角色消息（工具执行结果），toolCallId 关联对应的工具调用 */
        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, null, null, toolCallId);
        }

        // ---------- 内容查询与裁剪方法 ----------

        /** 是否包含多模态内容片段 */
        public boolean hasContentParts() {
            return contentParts != null && !contentParts.isEmpty();
        }

        /** 是否包含图片内容（Base64 或 URL） */
        public boolean hasImageContent() {
            return hasContentParts() && contentParts.stream().anyMatch(ContentPart::isImage);
        }

        /** 统计图片片段数量 */
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
         * 移除图片内容，用于历史消息压缩。
         * <p>
         * 长上下文中历史轮次的图片会占用大量 token，
         * 调用此方法将图片片段替换为占位文本 "[历史图片附件已省略 N 张]"，
         * 保留文本内容不变，从而降低上下文 token 消耗。
         * </p>
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
            // 追加占位文本，提示模型图片已被省略
            stripped.add(ContentPart.text("[历史图片附件已省略 " + omitted
                    + " 张；如需重新查看，请使用上文 Image source 或相关工具结果。]"));
            return new Message(role, plainText(stripped), reasoningContent, toolCalls, toolCallId, List.copyOf(stripped));
        }

        /**
         * 移除思考链内容，用于不需要将 reasoning 带回下一轮的场景。
         * <p>
         * 非 thinking 模式的 provider 或上下文压缩时，可剥离 reasoningContent
         * 以减少 token 消耗。
         * </p>
         */
        public Message withoutReasoningContent() {
            if (reasoningContent == null || reasoningContent.isBlank()) {
                return this;
            }
            return new Message(role, content, null, toolCalls, toolCallId, contentParts);
        }

        /**
         * 从多模态内容片段中提取纯文本。
         * <p>
         * 遍历所有片段，拼接文本部分（双换行分隔），
         * 图片部分汇总为 "[已附加 N 张图片]" 占位符。
         * </p>
         */
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
     * 工具调用请求。
     * <p>
     * assistant 消息中返回的工具调用信息，包含唯一 ID 和函数详情。
     * 上层 Agent 根据 id 匹配 tool 角色的响应消息。
     * </p>
     */
    record ToolCall(String id, Function function) {
        /** 被调用的函数信息：名称 + JSON 格式的参数 */
        public record Function(String name, String arguments) {}
    }

    /**
     * 工具定义。
     * <p>
     * 对应 OpenAI function calling 的 tool 对象，
     * 序列化后发送给模型，让模型了解可用工具及其参数结构。
     * </p>
     */
    record Tool(String name, String description, JsonNode parameters) {}

    /**
     * 流式事件监听器。
     * <p>
     * 在流式 chat 调用中，模型每生成一个 token 片段都会回调对应方法，
     * 上层（如 InlineRenderer）据此实时展示 thinking 预览和正文内容。
     * </p>
     */
    interface StreamListener {

        /** 空操作实例，用于不需要流式回调的场景 */
        StreamListener NO_OP = new StreamListener() {};

        /** 接收思考链增量片段（reasoning_content delta） */
        default void onReasoningDelta(String delta) {}

        /** 接收正文增量片段（content delta） */
        default void onContentDelta(String delta) {}
    }

    /**
     * LLM 响应封装。
     * <p>
     * 包含模型返回的文本内容、思考链、工具调用列表，
     * 以及 token 用量统计（输入 / 输出 / 缓存命中）。
     * </p>
     */
    record ChatResponse(String role, String content, String reasoningContent, List<ToolCall> toolCalls,
                        int inputTokens, int outputTokens, int cachedInputTokens) {

        /** 不含思考链和缓存 token 的简化构造 */
        public ChatResponse(String role, String content, List<ToolCall> toolCalls,
                            int inputTokens, int outputTokens) {
            this(role, content, null, toolCalls, inputTokens, outputTokens, 0);
        }

        /** 含思考链但不含缓存 token 的构造 */
        public ChatResponse(String role, String content, String reasoningContent, List<ToolCall> toolCalls,
                            int inputTokens, int outputTokens) {
            this(role, content, reasoningContent, toolCalls, inputTokens, outputTokens, 0);
        }

        /** 是否包含工具调用请求 */
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }
}
