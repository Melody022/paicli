package com.paicli.llm;

import com.paicli.config.PaiCliConfig;

/**
 * LlmClient 工厂类，用于创建不同类型的 LlmClient 实例
 */

public class LlmClientFactory {

    private LlmClientFactory() {}

    /**
     * 根据 provider 创建对应的 LlmClient
     *
     * @param provider 服务提供者名称
     * @param config   配置
     * @return 创建的 LlmClient 实例，如果无法创建则返回 null
     */
    //根据provider创建对应的LlmClient
    public static LlmClient create(String provider, PaiCliConfig config) {
        //空值保护，防止空指针异常
        if (provider == null) return null;
        // 标准化后的provider（小写 + 别名转换，如 moonshot -> kimi），用于匹配Client和主配置查找
        String normalized = normalizeProvider(provider);
        // 仅小写的原始provider（保留别名），用于配置的降级查找（用户可能用别名配置了ApiKey）
        String configuredProvider = provider.trim().toLowerCase();
        // 从配置中获取apiKey
        String apiKey = config.getApiKey(normalized);
        // 如果配置中没有找到apiKey，则尝试用原始provider查找
        if ((apiKey == null || apiKey.isBlank()) && !configuredProvider.equals(normalized)) {
            apiKey = config.getApiKey(configuredProvider);
        }
        // 如果仍然没有找到apiKey，则返回null
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        // 从配置中获取model和baseUrl
        //firstConfigured（a，b）：如果a不为空且不为空白，则返回a，否则返回b
        String model = firstConfigured(config.getModel(normalized),
                configuredProvider.equals(normalized) ? null : config.getModel(configuredProvider));
        String baseUrl = firstConfigured(config.getBaseUrl(normalized),
                configuredProvider.equals(normalized) ? null : config.getBaseUrl(configuredProvider));
        //java17新语法：switch表达式，可以根据normalized的值，创建具体客户端
        return switch (normalized) {
            case "glm" -> new GLMClient(apiKey, model);
            case "deepseek" -> new DeepSeekClient(apiKey, model);
            case "step" -> new StepClient(apiKey, model, baseUrl);
            case "kimi" -> new KimiClient(apiKey, model, baseUrl);
            default -> null;
        };
    }

    // 从配置中创建 LlmClient
    public static LlmClient createFromConfig(PaiCliConfig config) {
        // 【语法解答】这里不是“对象的值是方法”，而是标准的“调用方法并接收返回值”写法：
        // 1. 右侧 create(...) 会优先执行。它调用了下方的静态工厂方法，根据 provider 实例化具体的客户端对象。
        // 2. 左侧 LlmClient client 声明了一个变量，用于接收 create() 方法执行完毕后【返回的对象实例】。
        // 3. 这在 Java 中非常常见，等价于把“创建对象的过程”封装到了方法里（工厂模式），调用方直接拿结果即可。
        LlmClient client = create(config.getDefaultProvider(), config);
        // 如果根据默认配置成功创建了客户端，则直接返回该实例
        if (client != null) {
            return client;
        }
        // 如果默认配置未成功，则尝试依次使用 glm、deepseek、step、kimi 作为 provider 创建客户端
        for (String provider : new String[]{"glm", "deepseek", "step", "kimi"}) {
            client = create(provider, config);
            if (client != null) {
                return client;
            }
        }

        return null;
    }

    //将provider转换为小写，并进行一些常见的别名转换
    private static String normalizeProvider(String provider) {
        String normalized = provider.trim().toLowerCase();
        return switch (normalized) {
            case "stepfun", "step-fun" -> "step";
            case "moonshot", "moonshotai", "moonshot-ai" -> "kimi";
            default -> normalized;
        };
    }

    //返回优先级较高的配置，如果primary为空或blank，则返回fallback，否则返回primary
    private static String firstConfigured(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }
}
