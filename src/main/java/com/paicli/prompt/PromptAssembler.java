package com.paicli.prompt;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 提示词组装器 —— 负责把多个 prompt 模板拼装成完整的 system prompt
 *
 * PaiCLI 的 system prompt 是分层组织的，由多个 .md 模板文件组合而成：
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ base.md                    — 基础身份与能力声明                     │
 * │ + personalities/calm.md    — 人设（冷静型）                         │
 * │ + mode-specific prompt     — 当前模式（Agent/Plan/Team）            │
 * │ + approvals/*.md           — 审批策略（suggest/auto/never）          │
 * │ + Project Context          — 长期记忆 + 外部上下文                   │
 * │ + Skills                   — Skill 索引（最多 20 个 / 4KB）          │
 * │ + context-management.md    — 上下文管理指令                         │
 * │ + handoff.md               — 交接说明                               │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * 这种分层设计的好处：
 * 1. 模块化：每个 .md 文件职责单一，易于维护
 * 2. 可配置：不同模式（Agent/Plan/Team）使用不同的 prompt
 * 3. 动态注入：记忆、Skill、外部上下文等动态内容在运行时注入
 */
public class PromptAssembler {
    /** prompt 模板仓库，负责加载 .md 模板文件 */
    private final PromptRepository repository;

    public PromptAssembler(PromptRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    /** 创建默认的 PromptAssembler（使用 classpath 下的模板） */
    public static PromptAssembler createDefault() {
        return new PromptAssembler(PromptRepository.createDefault());
    }

    /**
     * 组装完整的 system prompt
     *
     * @param mode 当前模式（AGENT、PLAN_EXECUTE、MULTI_AGENT）
     * @param context 上下文信息（记忆、Skill、外部上下文等）
     * @return 组装好的完整 system prompt 字符串
     */
    public String assemble(PromptMode mode, PromptContext context) {
        Objects.requireNonNull(mode, "mode");
        PromptContext ctx = context == null ? PromptContext.empty() : context;

        // 加载基础身份 prompt
        String base = repository.loadRequired("base.md");
        validateLanguageSection(base, "base.md");

        StringBuilder prompt = new StringBuilder();
        // 1. 基础身份
        append(prompt, base);
        // 2. 人设（冷静型）
        append(prompt, repository.loadRequired("personalities/calm.md"));
        // 3. 模式特定的 prompt（Agent/Plan/Team）
        append(prompt, applyVariables(repository.loadRequired(mode.resourcePath()), ctx));
        // 4. 审批策略（suggest/auto/never）
        append(prompt, repository.loadRequired("approvals/" + approvalMode(ctx) + ".md"));
        // 5. 动态上下文：长期记忆 + 外部上下文
        append(prompt, dynamicSection("Project Context", ctx.memoryContext(), ctx.externalContext()));
        // 6. Skill 索引
        append(prompt, dynamicSection("Skills", ctx.skillIndex()));
        // 7. 上下文管理指令
        append(prompt, repository.loadRequired("context/context-management.md"));
        // 8. 交接说明
        append(prompt, repository.loadRequired("handoff.md"));

        String assembled = prompt.toString().trim();
        validateLanguageSection(assembled, "assembled prompt");
        return assembled;
    }

    private String approvalMode(PromptContext context) {
        String mode = context.approvalMode();
        if (mode == null || mode.isBlank()) {
            return "suggest";
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "auto", "never" -> normalized;
            default -> "suggest";
        };
    }

    private static String applyVariables(String template, PromptContext context) {
        String result = template;
        for (Map.Entry<String, String> entry : context.variables().entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        result = result.replace("{{taskType}}", context.variable("taskType"));
        result = result.replace("{{taskDescription}}", context.variable("taskDescription"));
        return result;
    }

    private static String dynamicSection(String title, String... values) {
        StringBuilder body = new StringBuilder();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                if (!body.isEmpty()) {
                    body.append("\n\n");
                }
                body.append(value.trim());
            }
        }
        if (body.isEmpty()) {
            return "";
        }
        return "## " + title + "\n\n" + body;
    }

    private static void append(StringBuilder sb, String section) {
        if (section == null || section.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append(section.trim());
    }

    private static void validateLanguageSection(String prompt, String source) {
        if (prompt == null || !prompt.contains("## Language")) {
            throw new IllegalStateException("Prompt " + source + " must contain a '## Language' section");
        }
    }
}
