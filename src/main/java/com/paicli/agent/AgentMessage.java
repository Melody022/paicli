package com.paicli.agent;

/**
 * Agent 间通信消息 - Multi-Agent 协作的基本通信单元
 *
 * 【设计思路】
 * 使用结构化的消息对象，包含：fromAgent（谁发的）、fromRole（角色）、content（内容）、type（类型）
 *
 * 【消息类型说明】
 * - TASK:      主控分配给子代理的任务（Orchestrator → Worker/Planner）
 * - RESULT:    子代理返回的执行结果（Worker/Planner → Orchestrator）
 * - APPROVAL:  检查者认可结果（Reviewer → Orchestrator）
 * - REJECTION: 检查者拒绝结果，需要重新执行（Reviewer → Orchestrator）
 * - ERROR:     子代理在执行过程中遭遇系统级错误
 *
 * 【业务流程示例】
 * 1. Orchestrator → Worker: AgentMessage.task("orchestrator", "实现登录接口")
 * 2. Worker → Orchestrator: AgentMessage.result("worker-1", AgentRole.WORKER, "已实现...")
 * 3. Reviewer → Orchestrator: AgentMessage.approval("reviewer", "审查通过")
 */
public record AgentMessage(
        String fromAgent,
        AgentRole fromRole,
        String content,
        Type type
) {
    /**
     * 消息类型枚举 - 定义消息的意图
     *
     * 【业务含义】
     * - TASK: 任务分配（从上到下）
     * - RESULT: 执行结果（从下到上）
     * - APPROVAL/REJECTION: 审查结果（横向）
     * - ERROR: 错误通知（任意方向）
     */
    public enum Type {
        TASK,
        RESULT,
        FEEDBACK,
        APPROVAL,
        REJECTION,
        ERROR
    }

    /**
     * 创建任务消息（主控 -> 子代理）
     */
    public static AgentMessage task(String fromAgent, String content) {
        return new AgentMessage(fromAgent, null, content, Type.TASK);
    }

    /**
     * 创建结果消息（子代理 -> 主控）
     */
    public static AgentMessage result(String fromAgent, AgentRole role, String content) {
        return new AgentMessage(fromAgent, role, content, Type.RESULT);
    }

    /**
     * 创建反馈消息（检查者 -> 主控）
     */
    public static AgentMessage feedback(String fromAgent, String content) {
        return new AgentMessage(fromAgent, AgentRole.REVIEWER, content, Type.FEEDBACK);
    }

    /**
     * 创建审批通过消息
     */
    public static AgentMessage approval(String fromAgent, String content) {
        return new AgentMessage(fromAgent, AgentRole.REVIEWER, content, Type.APPROVAL);
    }

    /**
     * 创建拒绝消息（检查者认为结果不合格）
     */
    public static AgentMessage rejection(String fromAgent, String content) {
        return new AgentMessage(fromAgent, AgentRole.REVIEWER, content, Type.REJECTION);
    }

    /**
     * 创建错误消息（子代理在执行过程中遇到系统级错误）
     */
    public static AgentMessage error(String fromAgent, AgentRole role, String content) {
        return new AgentMessage(fromAgent, role, content, Type.ERROR);
    }
}
