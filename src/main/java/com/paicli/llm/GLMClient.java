package com.paicli.llm;

/**
 * 智谱 AI (GLM) 模型客户端实现
 *
 * 继承自 AbstractOpenAiCompatibleClient，只需提供 API URL、模型名称、API Key。
 * 所有 HTTP 请求、SSE 流式解析、工具调用处理都由父类完成。
 *
 * GLM 的特殊处理：
 * 1. 根据模型名称自动选择 API 地址（编程接口 vs 多模态接口）
 * 2. GLM-5V 系列的图片格式需要特殊处理（去掉 data:image/png;base64, 前缀）
 * 3. 支持 Prompt Caching（glm-prompt-cache 模式）
 */
public class GLMClient extends AbstractOpenAiCompatibleClient {

    /**
     * GLM 编程专用接口地址
     * 用于 glm-5.1 等纯文本编程模型
     */
    private static final String CODING_API_URL = "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions";

    /**
     * GLM 多模态通用接口地址
     * 用于 glm-5v 等支持图片的模型
     */
    private static final String MULTIMODAL_API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";

    /** 默认使用的模型标识符 */
    private static final String DEFAULT_MODEL = "glm-5.1";

    private final String apiKey;
    private final String model;
    private final String apiUrl;

    /**
     * 构造函数：使用默认模型初始化。
     * @param apiKey 智谱 API 密钥
     */
    public GLMClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    /**
     * 构造函数：指定具体模型名称。
     * @param apiKey 智谱 API 密钥
     * @param model 模型名称（如 glm-5.1, glm-4v-plus）
     */
    public GLMClient(String apiKey, String model) {
        this(apiKey, model, null);
    }

    /**
     * 内部构造函数：支持自定义 API 基础 URL。
     */
    GLMClient(String apiKey, String model, String apiUrl) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = apiUrl != null && !apiUrl.isBlank() ? apiUrl : selectApiUrl(this.model);
    }

    /**
     * 获取当前配置的 API 请求地址。
     * 根据模型类型自动选择编程接口或多模态接口。
     */
    @Override
    protected String getApiUrl() {
        return apiUrl;
    }

    /**
     * 获取当前使用的模型名称。
     */
    @Override
    protected String getModel() {
        return model;
    }

    /**
     * 获取 API 鉴权密钥。
     */
    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "glm";
    }

    /**
     * 声明 GLM 模型的最大上下文窗口。
     * @return 200,000 Tokens
     */
    @Override
    public int maxContextWindow() {
        return 200_000;
    }

    /**
     * GLM 模型支持 Prompt Caching 功能。
     */
    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    /**
     * 返回 GLM 特有的缓存模式标识符。
     */
    @Override
    public String promptCacheMode() {
        return "glm-prompt-cache";
    }

    /**
     * 重写图片 URL 转换逻辑。
     * <p>
     * GLM-5V 系列模型在接收 Base64 图片时，要求直接传递 Base64 字符串，
     * 而不需要添加 "data:image/png;base64," 前缀。
     */
    @Override
    protected String toImageUrl(LlmClient.ContentPart part) {
        if (isGlm5v() && "image_base64".equals(part.type())) {
            return part.imageBase64();
        }
        return super.toImageUrl(part);
    }

    /**
     * 根据模型名称自动选择对应的 API 端点。
     * @param model 模型名称
     * @return 匹配的请求 URL
     */
    private static String selectApiUrl(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase();
        if (normalized.startsWith("glm-5v")) {
            return MULTIMODAL_API_URL;
        }
        return CODING_API_URL;
    }

    /**
     * 判断当前模型是否属于 GLM-5V 多模态系列。
     */
    private boolean isGlm5v() {
        return model != null && model.trim().toLowerCase().startsWith("glm-5v");
    }
}
