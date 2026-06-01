package com.paicli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PaiCLI 的配置管理中心
 *
 * 【文件位置】~/.paicli/config.json
 *
 * 【作用】
 *   - 保存所有模型 provider 的配置（API Key、Base URL、模型名、温度、最大 token）
 *   - 支持多 provider 热切换（/model glm / /model deepseek / /model kimi）
 *   - 配置查找采用多级优先级链：config.json → 环境变量 → .env 文件
 *
 * 【配置格式示例】
 * {
 *   "defaultProvider": "glm",
 *   "providers": {
 *     "glm": {
 *       "model": "glm-5.1",
 *       "temperature": 0.7,
 *       "maxTokens": 8192
 *     },
 *     "deepseek": {
 *       "model": "deepseek-chat"
 *     }
 *   }
 * }
 *
 * 【API Key 的安全策略】
 *   - config.json 里可以写 apiKey，但通常不推荐（容易泄露到 git）
 *   - 推荐做法：把 API Key 写在 .env 文件或环境变量里
 *   - 查找链：config.json 里的 apiKey → GLM_API_KEY 环境变量 → .env 文件里的 GLM_API_KEY
 *
 * 【为什么不用 Spring @ConfigurationProperties】
 *   - 这是一个纯 Java CLI 项目，不依赖 Spring
 *   - 用 Jackson ObjectMapper 直接读写 JSON 就够了
 */
@JsonIgnoreProperties(ignoreUnknown = true)  // 忽略 config.json 里多余的字段，防止旧格式报错
public class PaiCliConfig {

    // ─── 常量定义 ───
    // 配置文件固定存放在 ~/.paicli/config.json
    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".paicli");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
    // Jackson ObjectMapper 启用缩进输出，让 config.json 人类可读
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // ─── 核心字段 ───
    // 当前默认使用的 provider 名（glm / deepseek / step / kimi）
    private String defaultProvider = "glm";
    // 所有 provider 的配置表，用 LinkedHashMap 保持插入顺序
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    /**
     * 单个模型 provider 的配置
     * 对应 config.json 中 providers 下的每一项
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProviderConfig {
        private String apiKey;
        private String baseUrl;
        private String model;
        private double temperature = 0.7;  // 温度：0 = 确定性最强，1 = 最有创意
        private int maxTokens = 8192;      // 单次响应最大 token 数

        public ProviderConfig() {}

        public ProviderConfig(String apiKey, String baseUrl, String model) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.model = model;
        }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }

    // ─── getter / setter ───
    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
    public Map<String, ProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }

    /**
     * 获取指定 provider 的 API Key
     *
     * 【优先级链】
     *   1. config.json 里该 provider 的 apiKey 字段
     *   2. 环境变量 GLM_API_KEY / DEEPSEEK_API_KEY 等
     *   3. .env 文件里的 GLM_API_KEY / DEEPSEEK_API_KEY 等
     *
     * 这就是为什么你不需要把 API Key 写到 config.json 里也能用的原因
     */
    public String getApiKey(String provider) {
        ProviderConfig providerConfig = providers.get(provider);
        if (providerConfig != null && providerConfig.getApiKey() != null && !providerConfig.getApiKey().isBlank()) {
            return providerConfig.getApiKey();
        }
        return loadApiKeyFromEnv(provider);
    }

    /**
     * 获取指定 provider 的模型名
     *
     * 【优先级链】
     *   1. config.json 里该 provider 的 model 字段
     *   2. 环境变量 GLM_MODEL / DEEPSEEK_MODEL 等
     *   3. .env 文件里的 GLM_MODEL / DEEPSEEK_MODEL 等
     *
     * 如果都没配置，返回 null（由 LlmClientFactory 使用各自的默认值）
     */
    public String getModel(String provider) {
        ProviderConfig providerConfig = providers.get(provider);
        if (providerConfig != null && providerConfig.getModel() != null && !providerConfig.getModel().isBlank()) {
            return providerConfig.getModel();
        }
        return loadModelFromEnv(provider);
    }

    /**
     * 获取指定 provider 的 API Base URL
     *
     * 【优先级链】
     *   1. config.json 里该 provider 的 baseUrl 字段
     *   2. 环境变量 STEP_BASE_URL / KIMI_BASE_URL 等
     *   3. .env 文件里的 STEP_BASE_URL / KIMI_BASE_URL 等
     *
     * GLM / DeepSeek 不需要配 baseUrl（有硬编码默认值），Step / Kimi 支持自定义
     */
    public String getBaseUrl(String provider) {
        ProviderConfig providerConfig = providers.get(provider);
        if (providerConfig != null && providerConfig.getBaseUrl() != null && !providerConfig.getBaseUrl().isBlank()) {
            return providerConfig.getBaseUrl();
        }
        return loadBaseUrlFromEnv(provider);
    }

    /**
     * 从 ~/.paicli/config.json 加载配置
     *
     * 【容错处理】
     *   - 文件不存在 → 返回带默认值的新实例
     *   - JSON 解析失败 → 打印警告，返回带默认值的新实例
     *   - config.json 里有未知字段 → 被 @JsonIgnoreProperties 忽略
     */
    public static PaiCliConfig load() {
        if (Files.exists(CONFIG_FILE)) {
            try {
                return mapper.readValue(CONFIG_FILE.toFile(), PaiCliConfig.class);
            } catch (IOException e) {
                System.err.println("⚠️ 配置文件读取失败，使用默认配置: " + e.getMessage());
            }
        }
        return new PaiCliConfig();
    }

    /**
     * 保存配置到 ~/.paicli/config.json
     *
     * 【使用场景】
     *   - /model 切换模型后，更新 defaultProvider 并保存
     *   - /config 面板修改配置后保存
     *
     * 【容错处理】
     *   - 目录不存在 → 自动创建 ~/.paicli/
     *   - 写入失败 → 打印警告，不影响程序运行
     */
    public void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            mapper.writeValue(CONFIG_FILE.toFile(), this);
        } catch (IOException e) {
            System.err.println("⚠️ 配置保存失败: " + e.getMessage());
        }
    }

    /**
     * 从环境变量 / .env 文件加载指定 provider 的模型名
     *
     * 【环境变量映射】
     *   glm      → GLM_MODEL
     *   deepseek → DEEPSEEK_MODEL
     *   kimi     → KIMI_MODEL / MOONSHOT_MODEL（Kimi 原名 Moonshot）
     *   其他     → {PROVIDER}_MODEL
     *
     * 【查找顺序】系统环境变量 → .env（当前目录）→ .env（home 目录）
     */
    private static String loadModelFromEnv(String provider) {
        String envKey = switch (provider.toLowerCase()) {
            case "glm" -> "GLM_MODEL";
            case "deepseek" -> "DEEPSEEK_MODEL";
            case "kimi" -> "KIMI_MODEL";
            default -> provider.toUpperCase() + "_MODEL";
        };

        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String dotEnvValue = readFromDotEnv(envKey);
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }

        if ("kimi".equalsIgnoreCase(provider)) {
            String moonshotValue = System.getenv("MOONSHOT_MODEL");
            if (moonshotValue != null && !moonshotValue.isBlank()) {
                return moonshotValue.trim();
            }
            String moonshotDotEnvValue = readFromDotEnv("MOONSHOT_MODEL");
            if (moonshotDotEnvValue != null && !moonshotDotEnvValue.isBlank()) {
                return moonshotDotEnvValue.trim();
            }
        }

        return null;
    }

    /**
     * 从环境变量 / .env 文件加载指定 provider 的 API Key
     *
     * 【环境变量映射】
     *   glm      → GLM_API_KEY
     *   deepseek → DEEPSEEK_API_KEY
     *   step     → STEP_API_KEY
     *   kimi     → KIMI_API_KEY / MOONSHOT_API_KEY
     *
     * 【查找顺序】系统环境变量 → .env（当前目录）→ .env（home 目录）
     */
    private static String loadApiKeyFromEnv(String provider) {
        String envKey = switch (provider.toLowerCase()) {
            case "glm" -> "GLM_API_KEY";
            case "deepseek" -> "DEEPSEEK_API_KEY";
            case "step" -> "STEP_API_KEY";
            case "kimi" -> "KIMI_API_KEY";
            default -> provider.toUpperCase() + "_API_KEY";
        };

        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String dotEnvValue = readFromDotEnv(envKey);
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }

        if ("kimi".equalsIgnoreCase(provider)) {
            String moonshotValue = System.getenv("MOONSHOT_API_KEY");
            if (moonshotValue != null && !moonshotValue.isBlank()) {
                return moonshotValue.trim();
            }
            String moonshotDotEnvValue = readFromDotEnv("MOONSHOT_API_KEY");
            if (moonshotDotEnvValue != null && !moonshotDotEnvValue.isBlank()) {
                return moonshotDotEnvValue.trim();
            }
        }

        return null;
    }

    /**
     * 从环境变量 / .env 文件加载指定 provider 的 Base URL
     *
     * 只有部分 provider 需要自定义 Base URL（如 step / kimi），GLM / DeepSeek 使用内置默认值
     *
     * 【查找顺序】系统环境变量 → .env（当前目录）→ .env（home 目录）
     */
    private static String loadBaseUrlFromEnv(String provider) {
        String envKey = switch (provider.toLowerCase()) {
            case "step" -> "STEP_BASE_URL";
            case "kimi" -> "KIMI_BASE_URL";
            default -> provider.toUpperCase() + "_BASE_URL";
        };

        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String dotEnvValue = readFromDotEnv(envKey);
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }

        if ("kimi".equalsIgnoreCase(provider)) {
            String moonshotValue = System.getenv("MOONSHOT_BASE_URL");
            if (moonshotValue != null && !moonshotValue.isBlank()) {
                return moonshotValue.trim();
            }
            String moonshotDotEnvValue = readFromDotEnv("MOONSHOT_BASE_URL");
            if (moonshotDotEnvValue != null && !moonshotDotEnvValue.isBlank()) {
                return moonshotDotEnvValue.trim();
            }
        }

        return null;
    }

    /**
     * 从 .env 文件读取指定 key 的值
     *
     * 【查找顺序】当前目录 .env → 用户 home 目录 .env
     * 【格式要求】KEY=VALUE 格式，支持 # 注释和空行
     *
     * 例如 .env 内容：
     *   GLM_API_KEY=sk-xxxxx
     *   DEEPSEEK_API_KEY=sk-yyyyy  # 这是注释
     */
    private static String readFromDotEnv(String key) {
        File[] envFiles = { new File(".env"), new File(System.getProperty("user.home"), ".env") };
        for (File envFile : envFiles) {
            if (!envFile.exists()) continue;
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith(key + "=")) {
                        return line.substring((key + "=").length()).trim();
                    }
                }
            } catch (IOException ignored) {}
        }
        return null;
    }
}
