package com.paicli.config;

import java.nio.file.Path;

/**
 * PaiCLI 数据目录统一入口。
 *
 * <p>查找链（按优先级）：
 * <ol>
 *   <li>环境变量 {@code PAICLI_HOME}
 *   <li>系统属性 {@code paicli.home}
 *   <li>回退 {@code ~/.paicli}
 * </ol>
 *
 * <p>所有模块的数据目录都通过 {@link #resolve(String...)} 从这个统一基路径展开，
 * 不再各模块各自拼接 {@code System.getProperty("user.home") + "/.paicli/..."}。
 */
public class PaiCliHome {

    private static final Path BASE;

    static {
        String env = System.getenv("PAICLI_HOME");
        if (env != null && !env.isBlank()) {
            BASE = Path.of(env).normalize().toAbsolutePath();
        } else {
            String prop = System.getProperty("paicli.home");
            if (prop != null && !prop.isBlank()) {
                BASE = Path.of(prop).normalize().toAbsolutePath();
            } else {
                BASE = Path.of(System.getProperty("user.home"), ".paicli");
            }
        }
    }

    /** 获取 PaiCLI 数据根目录（已规范化、绝对化）。 */
    public static Path get() {
        return BASE;
    }

    /**
     * 以 PaiCLI 数据根目录为基，拼接子路径并返回规范化绝对路径。
     *
     * <pre>{@code
     * PaiCliHome.resolve("logs")              → D:/paicli-data/logs
     * PaiCliHome.resolve("rag", "codebase.db") → D:/paicli-data/rag/codebase.db
     * }</pre>
     */
    public static Path resolve(String first, String... more) {
        return BASE.resolve(Path.of(first, more)).normalize();
    }

    /**
     * 以 PaiCLI 数据根目录为基，拼接单段路径的简写。
     *
     * <pre>{@code
     * PaiCliHome.resolve("config.json") → D:/paicli-data/config.json
     * }</pre>
     */
    public static Path resolve(String sub) {
        return BASE.resolve(sub).normalize();
    }

    // ── 私有构造，工具类不允许实例化 ──
    private PaiCliHome() {}
}
