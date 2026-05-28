package com.paicli.agent;

/**
 * Agent 角色定义 - Multi-Agent 系统中的角色分工
 *
 * 【设计思路】
 * 采用基于能力的分工方式，每个角色专注自己的领域：
 * - PLANNER（规划者）：只负责思考和规划，不调用工具
 * - WORKER（执行者）：只负责执行任务，调用各种工具
 * - REVIEWER（检查者）：只负责检查质量，不调用工具
 *
 * 【为什么这样设计？】
 * 1. 专注才能高效：每个角色只做自己擅长的事
 * 2. 质量保证：Worker 执行，Reviewer 审查，形成闭环
 * 3. 可扩展性：可以轻松添加新的角色（如 TESTER, DOCUMENTER）
 */
public enum AgentRole {

    /**
     * 规划者（Planner）- "策略师"
     *
     * 【职责】分析用户需求，将复杂任务拆解为可执行的子步骤，定义步骤间的依赖关系
     * 【特点】只输出 JSON 计划，不调用工具
     * 【对应 Prompt】prompts/modes/team-planner.md
     */
    PLANNER("规划者", "负责分析用户任务，制定执行计划，将复杂任务拆解为可执行的子任务"),

    /**
     * 执行者（Worker）- "干活的人"
     *
     * 【职责】接收具体任务步骤，调用工具完成任务，返回执行结果
     * 【特点】是唯一能调用工具的角色（shouldUseTools() == true）
     * 【对应 Prompt】prompts/modes/team-worker.md
     * 【工具箱】read_file, write_file, execute_command, search_code, web_search 等
     */
    WORKER("执行者", "负责执行具体任务步骤，调用工具完成文件操作、命令执行等操作"),

    /**
     * 检查者（Reviewer）- "质检员"
     *
     * 【职责】检查执行结果的质量和正确性，输出审查结果（通过/不通过 + 问题 + 建议）
     * 【特点】只负责审查，不调用工具，支持最多 2 次重试机制
     * 【对应 Prompt】prompts/modes/team-reviewer.md
     */
    REVIEWER("检查者", "负责检查执行结果的质量和正确性，提供改进建议");

    private final String displayName;
    private final String description;

    AgentRole(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
