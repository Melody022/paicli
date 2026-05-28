package com.paicli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmTraceLogger;
import com.paicli.lsp.LspDiagnosticReport;
import com.paicli.memory.ConversationHistoryCompactor;
import com.paicli.memory.MemoryManager;
import com.paicli.plan.*;
import com.paicli.prompt.PromptAssembler;
import com.paicli.prompt.PromptContext;
import com.paicli.prompt.PromptMode;
import com.paicli.runtime.CancellationContext;
import com.paicli.skill.SkillContextBuffer;
import com.paicli.skill.SkillIndexFormatter;
import com.paicli.skill.SkillRegistry;
import com.paicli.util.AnsiStyle;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import com.paicli.tool.ToolRegistry.ToolInvocation;
import com.paicli.util.TerminalMarkdownRenderer;
import com.paicli.image.ImageReferenceParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute Agent - 先规划后执行的Agent实现
 *
 * 核心流程：
 * 1. 接收用户目标
 * 2. 调用Planner生成DAG执行计划（包含多个有依赖关系的任务）
 * 3. 人工审阅计划（可选：执行/补充要求/取消）
 * 4. 按依赖顺序执行计划中的任务（无依赖的任务可并行执行）
 * 5. 支持任务失败时自动重规划
 *
 * 与ReAct模式的区别：
 * - ReAct：边思考边执行，每一步都是"思考-行动-观察"循环
 * - Plan-and-Execute：先制定完整计划，再按计划执行，适合复杂多步骤任务
 *
 * 触发方式：用户在CLI中输入 /plan 命令
 */
public class PlanExecuteAgent {
    private static final Logger log = LoggerFactory.getLogger(PlanExecuteAgent.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    /**
     * 计划执行结果记录
     * @param result 执行结果文本
     * @param persistAssistantMessage 是否将结果持久化到对话历史（取消操作不持久化）
     */
    private record PlanRunOutcome(String result, boolean persistAssistantMessage) {
        static PlanRunOutcome executed(String result) {
            return new PlanRunOutcome(result, true);
        }

        static PlanRunOutcome canceled(String result) {
            return new PlanRunOutcome(result, false);
        }

        static PlanRunOutcome failed(String result) {
            return new PlanRunOutcome(result, true);
        }
    }

    /**
     * 单个任务的执行结果
     * @param result 任务结果文本
     * @param streamedOutput 是否已经通过流式输出显示过结果
     */
    private record TaskRunResult(String result, boolean streamedOutput) {
        static TaskRunResult of(String result, boolean streamedOutput) {
            return new TaskRunResult(result, streamedOutput);
        }
    }

    /**
     * 任务执行结果的包装类（包含成功和失败情况）
     * @param task 原始任务对象
     * @param result 成功时的结果文本
     * @param streamedOutput 是否已流式输出
     * @param error 失败时的异常（成功时为null）
     */
    private record TaskExecutionResult(Task task, String result, boolean streamedOutput, Exception error) {
        static TaskExecutionResult success(Task task, TaskRunResult taskRunResult) {
            return new TaskExecutionResult(task, taskRunResult.result(), taskRunResult.streamedOutput(), null);
        }

        static TaskExecutionResult failure(Task task, Exception error) {
            return new TaskExecutionResult(task, null, false, error);
        }

        boolean failed() {
            return error != null;
        }
    }

    /**
     * 计划审阅处理器接口
     * 用于实现人工审阅（HITL）逻辑，用户可以审阅生成的计划后决定是否执行
     */
    public interface PlanReviewHandler {
        PlanReviewDecision review(String goal, ExecutionPlan plan);
    }

    /**
     * 计划审阅操作枚举
     */
    public enum PlanReviewAction {
        EXECUTE,    // 执行计划
        SUPPLEMENT, // 补充要求后重新规划
        CANCEL      // 取消执行
    }

    /**
     * 计划审阅决策记录
     * @param action 审阅操作（执行/补充/取消）
     * @param feedback 补充要求内容（仅SUPPLEMENT时有值）
     */
    public record PlanReviewDecision(PlanReviewAction action, String feedback) {
        public static PlanReviewDecision execute() {
            return new PlanReviewDecision(PlanReviewAction.EXECUTE, null);
        }

        public static PlanReviewDecision supplement(String feedback) {
            return new PlanReviewDecision(PlanReviewAction.SUPPLEMENT, feedback);
        }

        public static PlanReviewDecision cancel() {
            return new PlanReviewDecision(PlanReviewAction.CANCEL, null);
        }
    }

    /** LLM客户端，用于调用大模型 */
    private final LlmClient llmClient;
    /** 工具注册表，管理所有可用工具 */
    private final ToolRegistry toolRegistry;
    /** 规划器，负责生成和重新生成执行计划 */
    private final Planner planner;
    /** 计划审阅处理器，实现人工审阅逻辑 */
    private final PlanReviewHandler reviewHandler;
    /** 记忆管理器，管理对话历史和长期记忆 */
    private final MemoryManager memoryManager;
    /** 对话历史压缩器，防止上下文超限 */
    private final ConversationHistoryCompactor historyCompactor;
    /** 输出流 */
    private final PrintStream out;
    /** 外部上下文提供器（如MCP资源） */
    private Supplier<String> externalContextSupplier = () -> "";
    /** Skill注册表 */
    private SkillRegistry skillRegistry;
    /** Skill上下文缓冲区 */
    private SkillContextBuffer skillContextBuffer;
    /** 提示词组装器 */
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();

    /**
     * 最简构造函数（仅需LLM客户端）
     * 使用默认审阅处理器（自动执行，不需人工审阅）
     *
     * @param llmClient LLM客户端
     */
    public PlanExecuteAgent(LlmClient llmClient) {
        this(llmClient, (goal, plan) -> PlanReviewDecision.execute());
    }

    /**
     * 带审阅处理器的构造函数
     *
     * @param llmClient LLM客户端
     * @param reviewHandler 计划审阅处理器
     */
    public PlanExecuteAgent(LlmClient llmClient, PlanReviewHandler reviewHandler) {
        this(llmClient, new ToolRegistry(), null, null, reviewHandler);
    }

    /**
     * 带工具注册表和记忆管理器的构造函数
     *
     * @param llmClient LLM客户端
     * @param toolRegistry 工具注册表
     * @param memoryManager 记忆管理器
     * @param reviewHandler 计划审阅处理器
     */
    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler);
    }

    /**
     * 带自定义输出流的构造函数
     *
     * @param llmClient LLM客户端
     * @param toolRegistry 工具注册表
     * @param memoryManager 记忆管理器
     * @param reviewHandler 计划审阅处理器
     * @param out 输出流
     */
    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler,
                            PrintStream out) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler, out);
    }

    /**
     * 带规划器的构造函数（包级别访问）
     *
     * @param llmClient LLM客户端
     * @param toolRegistry 工具注册表
     * @param planner 规划器
     * @param memoryManager 记忆管理器
     * @param reviewHandler 计划审阅处理器
     */
    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(llmClient, toolRegistry, planner, memoryManager, reviewHandler, null);
    }

    /**
     * 完整构造函数（包级别访问）
     *
     * 初始化所有组件：
     * - LLM客户端
     * - 工具注册表
     * - 输出流
     * - 规划器
     * - 审阅处理器
     * - 记忆管理器
     * - 对话历史压缩器
     *
     * @param llmClient LLM客户端
     * @param toolRegistry 工具注册表
     * @param planner 规划器
     * @param memoryManager 记忆管理器
     * @param reviewHandler 计划审阅处理器
     * @param out 输出流
     */
    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler, PrintStream out) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry != null ? toolRegistry : new ToolRegistry();
        this.out = out == null ? deferredSystemOut() : out;
        this.planner = planner != null ? planner : new Planner(llmClient, this.out);
        this.reviewHandler = reviewHandler == null ? (goal, plan) -> PlanReviewDecision.execute() : reviewHandler;
        this.memoryManager = memoryManager != null ? memoryManager : new MemoryManager(llmClient);
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        this.toolRegistry.setContextProfile(this.memoryManager.getContextProfile());
        this.toolRegistry.setMemorySaver(this.memoryManager::storeFact);
    }

    /**
     * 创建延迟输出的PrintStream
     *
     * 当没有显式提供输出流时，使用此方法创建一个包装System.out的PrintStream。
     * 这样可以延迟System.out的绑定，避免在类加载时就固定输出目标。
     *
     * @return 包装后的PrintStream
     */
    private static PrintStream deferredSystemOut() {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                System.out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                System.out.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                System.out.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }

    /**
     * 设置外部上下文提供器
     *
     * 外部上下文通常包含MCP资源索引等信息，会在构建任务提示词时注入。
     *
     * @param externalContextSupplier 外部上下文提供器（可为null，会使用空提供器替代）
     */
    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    /**
     * 设置Skill注册表
     *
     * Skill用于扩展Agent的能力，可以提供预定义的任务处理策略。
     *
     * @param skillRegistry Skill注册表
     */
    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /**
     * 设置Skill上下文缓冲区
     *
     * 用于缓存Skill加载的内容，会在下一轮用户消息前注入到对话中。
     *
     * @param skillContextBuffer Skill上下文缓冲区
     */
    public void setSkillContextBuffer(SkillContextBuffer skillContextBuffer) {
        this.skillContextBuffer = skillContextBuffer;
    }

    /**
     * 检查并压缩对话历史
     *
     * 当对话历史的token数接近LLM窗口上限时，会将早期对话压缩为摘要，
     * 防止超出上下文窗口限制。
     *
     * @param messages 当前消息列表（会被原地修改）
     * @param out 输出流（用于显示压缩提示）
     */
    private void maybeCompactHistory(List<LlmClient.Message> messages, PrintStream out) {
        if (historyCompactor == null) return;
        int trigger = memoryManager.getContextProfile().compressionTriggerTokens();
        try {
            boolean compacted = historyCompactor.compactIfNeeded(messages, trigger);
            if (compacted && out != null) {
                out.println("📦 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
            }
        } catch (Exception e) {
            log.warn("conversationHistory compaction failed", e);
        }
    }

    /**
     * 构建Skill索引字符串
     *
     * 将已启用的Skill格式化为索引字符串，用于注入到系统提示词中，
     * 让LLM知道当前可用的Skill能力。
     *
     * @return Skill索引字符串（可能为空）
     */
    private String buildSkillIndex() {
        if (skillRegistry == null) return "";
        try {
            return SkillIndexFormatter.format(skillRegistry.enabledSkills());
        } catch (Exception e) {
            log.warn("Failed to build skill index", e);
            return "";
        }
    }

    /**
     * 在内容前前置Skill上下文
     *
     * 将SkillContextBuffer中缓存的内容（如通过load_skill加载的内容）
     * 前置到用户输入内容前面。
     *
     * @param content 原始内容
     * @return 前置Skill上下文后的内容
     */
    private String prependSkillBodies(String content) {
        if (skillContextBuffer == null || skillContextBuffer.isEmpty()) {
            return content;
        }
        String drained = skillContextBuffer.drain();
        if (drained.isEmpty()) return content;
        return drained + "\n" + content;
    }

    /**
     * 运行任务（自动判断是否需要规划）
     *
     * 执行流程：
     * 1. 记录用户消息到对话历史
     * 2. 检查是否已取消
     * 3. 调用runWithPlan执行计划
     * 4. 将结果持久化到对话历史（取消操作除外）
     *
     * @param userInput 用户输入的目标描述
     * @return 执行结果文本
     */
    public String run(String userInput) {
        log.info("Plan run started: inputLength={}", userInput == null ? 0 : userInput.length());
        memoryManager.addUserMessage(userInput);
        StreamState streamState = new StreamState();
        try {
            if (CancellationContext.isCancelled()) {
                return "⏹️ 已取消当前计划执行。";
            }
            PlanRunOutcome outcome = runWithPlan(userInput, streamState);
            if (outcome.persistAssistantMessage() && outcome.result() != null && !outcome.result().isBlank()) {
                memoryManager.addAssistantMessage("[计划结果] " + outcome.result());
            }
            if (streamState.hasStreamedOutput() && (outcome.result() == null || outcome.result().isBlank())) {
                return "";
            }
            return outcome.result();
        } catch (Exception e) {
            log.error("Plan run failed", e);
            String errorMessage = "❌ 执行失败: " + e.getMessage();
            memoryManager.addAssistantMessage(errorMessage);
            return errorMessage;
        }
    }

    /**
     * 使用Plan-and-Execute模式执行
     *
     * @param goal 用户目标
     * @param streamState 流式输出状态跟踪器
     * @return 计划执行结果
     * @throws IOException IO异常
     */
    private PlanRunOutcome runWithPlan(String goal, StreamState streamState) throws IOException {
        // 调用Planner创建DAG执行计划
        ExecutionPlan plan = planner.createPlan(goal);
        // 进入人工审阅和执行循环
        return reviewAndExecutePlan(plan, streamState);
    }

    /**
     * 人工审阅计划并执行
     *
     * 实现人工审阅（HITL）的核心逻辑：
     * - 用户可以审阅生成的计划
     * - 支持三种操作：执行、补充要求、取消
     * - 补充要求后会重新规划
     *
     * @param plan 执行计划
     * @param streamState 流式输出状态跟踪器
     * @return 计划执行结果
     * @throws IOException IO异常
     */
    private PlanRunOutcome reviewAndExecutePlan(ExecutionPlan plan, StreamState streamState) throws IOException {
        while (true) {
            // 调用审阅处理器获取用户决策
            PlanReviewDecision decision = reviewHandler.review(plan.getGoal(), plan);

            // 决策为空或选择执行：开始执行计划
            if (decision == null || decision.action() == PlanReviewAction.EXECUTE) {
                return PlanRunOutcome.executed(executePlan(plan, streamState));
            }

            // 选择取消：返回取消结果
            if (decision.action() == PlanReviewAction.CANCEL) {
                return PlanRunOutcome.canceled("⏹️ 已取消本次计划执行。");
            }

            // 选择补充要求：将反馈附加到目标后重新规划
            String feedback = decision.feedback() == null ? "" : decision.feedback().trim();
            if (feedback.isEmpty()) {
                return PlanRunOutcome.executed(executePlan(plan, streamState));
            }

            out.println("📝 已收到补充要求，正在重新规划...\n");
            plan = planner.createPlan(plan.getGoal() + "\n补充要求：" + feedback);
        }
    }

    /**
     * 执行计划
     *
     * 核心执行循环：
     * 1. 获取当前可执行的任务（按依赖顺序）
     * 2. 批量执行任务（支持并行）
     * 3. 处理任务结果：
     *    - 成功：标记完成，记录结果
     *    - 失败：如果进度<50%，尝试重新规划；否则继续执行其他任务
     * 4. 重复直到所有任务完成或无法继续
     *
     * @param plan 执行计划
     * @param streamState 流式输出状态跟踪器
     * @return 最终结果文本
     * @throws IOException IO异常
     */
    private String executePlan(ExecutionPlan plan, StreamState streamState) throws IOException {
        log.info("Executing plan: goal='{}', taskCount={}", plan.getGoal(), plan.getAllTasks().size());
        out.println("🚀 开始执行计划...\n");

        plan.markStarted();
        StringBuilder finalResult = new StringBuilder();
        /** 记录哪些任务已经通过流式输出显示过结果，避免重复输出 */
        Map<String, Boolean> streamedTaskOutputs = new HashMap<>();

        // 主执行循环：持续获取并执行可执行任务
        while (true) {
            // 检查是否已取消
            if (CancellationContext.isCancelled()) {
                return "⏹️ 已取消当前计划执行。";
            }

            // 获取当前可执行的任务列表（按拓扑排序）
            List<Task> executableTasks = getExecutableTasksInOrder(plan);
            if (executableTasks.isEmpty()) {
                break; // 没有更多可执行任务，退出循环
            }

            // 批量执行任务（单个任务串行，多个无依赖任务并行）
            List<TaskExecutionResult> batchResults = executeTaskBatch(plan, executableTasks, streamState);

            // 处理执行结果
            for (TaskExecutionResult batchResult : batchResults) {
                Task task = batchResult.task();

                // 任务成功
                if (!batchResult.failed()) {
                    task.markCompleted(batchResult.result());
                    streamedTaskOutputs.put(task.getId(), batchResult.streamedOutput());
                    log.info("Task completed: {} status={} resultChars={}",
                            task.getId(), task.getStatus(), batchResult.result() == null ? 0 : batchResult.result().length());

                    // 输出完成信息（已流式输出的只显示简短提示）
                    if (batchResult.streamedOutput() || batchResult.result() == null || batchResult.result().isBlank()) {
                        out.println("✅ 完成 [" + task.getId() + "]\n");
                    } else {
                        out.println("✅ 完成 [" + task.getId() + "]: "
                                + batchResult.result().substring(0, Math.min(100, batchResult.result().length())) + "\n");
                    }
                    continue;
                }

                // 任务失败
                Exception error = batchResult.error();
                task.markFailed(error.getMessage());
                log.warn("Task failed: {} error={}", task.getId(), error.getMessage());
                out.println("❌ 失败 [" + task.getId() + "]: " + error.getMessage() + "\n");

                // 如果计划进度<50%，尝试自动重新规划
                if (plan.getProgress() < 0.5) {
                    out.println("🔄 尝试重新规划...\n");
                    ExecutionPlan replanned = planner.replan(plan, error.getMessage());
                    // 重新规划后需要再次进入人工审阅循环
                    return reviewAndExecutePlan(replanned, streamState).result();
                }

                // 记录失败结果
                if (!finalResult.isEmpty()) {
                    finalResult.append("\n");
                }
                finalResult.append("任务 ").append(task.getId()).append(" 失败: ").append(error.getMessage());
            }
        }

        // 检查是否所有任务都完成
        if (!plan.isAllCompleted() && !plan.hasFailed()) {
            plan.markFailed();
            return "⚠️ 计划未能继续推进，存在未满足依赖的任务。";
        }

        // 构建最终结果
        String planSummary = finalResult.isEmpty()
                ? buildFinalResult(plan, streamedTaskOutputs)
                : finalResult.toString();

        // 根据计划状态返回结果
        if (plan.hasFailed()) {
            plan.markFailed();
            if (planSummary.isBlank()) {
                return "⚠️ 计划部分完成，有任务失败。";
            }
            return "⚠️ 计划部分完成，有任务失败。\n" + planSummary;
        }

        plan.markCompleted();
        if (planSummary.isBlank()) {
            return "✅ 计划执行完成！";
        }
        return "✅ 计划执行完成！\n" + planSummary;
    }

    /**
     * 获取可执行任务列表（按拓扑排序顺序）
     *
     * 可执行任务定义：所有依赖任务都已完成的任务
     * 返回顺序：按计划的执行顺序（拓扑排序）
     *
     * @param plan 执行计划
     * @return 可执行任务列表
     */
    private List<Task> getExecutableTasksInOrder(ExecutionPlan plan) {
        // 获取当前可执行的任务ID集合
        Set<String> executableIds = plan.getExecutableTasks().stream()
                .map(Task::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 按计划的执行顺序过滤并返回
        return plan.getExecutionOrder().stream()
                .filter(executableIds::contains)
                .map(plan::getTask)
                .toList();
    }

    /**
     * 批量执行任务
     *
     * 执行策略：
     * - 单个任务：直接串行执行
     * - 多个任务：并行执行（最多4个线程），输出缓冲后按顺序flush避免交错
     *
     * @param plan 执行计划
     * @param executableTasks 可执行任务列表
     * @param streamState 流式输出状态跟踪器
     * @return 任务执行结果列表（按原始顺序）
     */
    private List<TaskExecutionResult> executeTaskBatch(ExecutionPlan plan, List<Task> executableTasks,
                                                       StreamState streamState) {
        // 单个任务：串行执行
        if (executableTasks.size() == 1) {
            Task task = executableTasks.get(0);
            log.info("Executing single task: {} type={}", task.getId(), task.getType());
            out.println("▶️ 执行任务 [" + task.getId() + "]: " + task.getDescription());
            task.markStarted();

            try {
                return List.of(TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task, streamState, out)));
            } catch (Exception e) {
                return List.of(TaskExecutionResult.failure(task, e));
            }
        }

        // 多个任务：并行执行
        String parallelTaskIds = executableTasks.stream()
                .map(Task::getId)
                .collect(Collectors.joining(", "));
        log.info("Executing parallel batch: {}", parallelTaskIds);
        out.println("⚡ 本轮并行执行 " + executableTasks.size() + " 个任务: " + parallelTaskIds);

        // 创建线程池（最多4个线程）
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(executableTasks.size(), 4), r -> {
            Thread t = new Thread(r, "paicli-plan-executor");
            t.setDaemon(true);
            return t;
        });
        try {
            // 为每个任务创建输出缓冲区
            Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();
            List<Future<TaskExecutionResult>> futures = new ArrayList<>();

            // 提交所有任务到线程池
            for (Task task : executableTasks) {
                out.println("▶️ 并行任务 [" + task.getId() + "]: " + task.getDescription());
                task.markStarted();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                buffers.put(task.getId(), baos);
                PrintStream taskOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
                futures.add(executor.submit(() -> {
                    try {
                        return TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task, streamState, taskOut));
                    } catch (Exception e) {
                        return TaskExecutionResult.failure(task, e);
                    }
                }));
            }

            // 收集所有任务结果
            List<TaskExecutionResult> results = new ArrayList<>();
            for (Future<TaskExecutionResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), e));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    Exception error = cause instanceof Exception exception
                            ? exception
                            : new RuntimeException(cause);
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), error));
                }
            }

            // 按任务顺序 flush 各缓冲区到 stdout，避免并行输出交错
            for (Task task : executableTasks) {
                ByteArrayOutputStream buf = buffers.get(task.getId());
                if (buf != null && buf.size() > 0) {
                    out.print(buf.toString(StandardCharsets.UTF_8));
                    out.flush();
                }
            }

            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /** 单个任务的最大迭代次数（防止无限循环） */
    private static final int MAX_TASK_ITERATIONS = 5;

    /**
     * 执行单个任务
     *
     * 执行流程：
     * 1. 构建任务上下文（包含目标、计划、依赖任务结果）
     * 2. 组装提示词（系统提示 + 任务上下文 + 长期记忆 + Skill）
     * 3. 调用LLM，支持多轮工具调用（最多5轮迭代）
     * 4. 每轮迭代：调用LLM → 执行工具 → 将结果回灌到消息历史
     * 5. 返回任务结果
     *
     * @param goal 总目标
     * @param plan 执行计划
     * @param task 当前任务
     * @param streamState 流式输出状态跟踪器
     * @param out 输出流
     * @return 任务执行结果
     * @throws IOException IO异常
     */
    private TaskRunResult executeTask(String goal, ExecutionPlan plan, Task task,
                                      StreamState streamState, PrintStream out) throws IOException {
        // 组装系统提示词（包含任务类型、描述、外部上下文、Skill索引）
        String prompt = promptAssembler.assemble(PromptMode.PLAN, PromptContext.builder()
                .variable("taskType", task.getType())
                .variable("taskDescription", task.getDescription())
                .externalContext(buildExternalContext())
                .skillIndex(buildSkillIndex())
                .build());

        // 注入长期记忆上下文
        String memoryContext = memoryManager.buildContextForQuery(
                task.getDescription(),
                memoryManager.getContextProfile().memoryContextTokens());

        // 构建任务上下文（包含总目标、当前任务描述、依赖任务结果）
        String taskInput = buildTaskContext(goal, plan, task);
        if (!memoryContext.isEmpty()) {
            taskInput = taskInput + "\n\n" + memoryContext;
        }

        // 前置Skill上下文
        taskInput = prependSkillBodies(taskInput);

        // 构建初始消息列表（系统提示 + 用户任务输入）
        List<LlmClient.Message> messages = new ArrayList<>(Arrays.asList(
                LlmClient.Message.system(prompt),
                ImageReferenceParser.userMessage(
                        taskInput,
                        Path.of(toolRegistry.getProjectPath()))
        ));

        /** 累积所有工具调用结果 */
        StringBuilder allResults = new StringBuilder();
        int iteration = 0;
        /** 流式渲染器，用于实时显示思考过程和内容输出 */
        TaskStreamRenderer streamRenderer = new TaskStreamRenderer(task.getId(), streamState, out);

        // Token统计
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int totalCachedInputTokens = 0;

        // 多轮工具调用循环（最多5轮）
        while (iteration < MAX_TASK_ITERATIONS) {
            // 检查是否已取消
            if (CancellationContext.isCancelled()) {
                streamRenderer.finish();
                return TaskRunResult.of("⏹️ 已取消任务 [" + task.getId() + "]。", streamRenderer.hasStreamedOutput());
            }
            iteration++;

            // 调 LLM 前注入LSP诊断信息，并评估messages是否接近window上限
            injectPendingLspDiagnostics(messages, out);
            maybeCompactHistory(messages, out);

            // 调用LLM（支持流式输出）
            LlmClient.ChatResponse response = llmClient.chat(
                    messages,
                    toolRegistry.getToolDefinitions(),
                    streamRenderer
            );
            // 记录推理过程日志
            LlmTraceLogger.logReasoning(log,
                    "plan-task task=" + task.getId() + " iteration=" + iteration,
                    llmClient,
                    response.reasoningContent());

            // 再次检查是否已取消
            if (CancellationContext.isCancelled()) {
                streamRenderer.finish();
                return TaskRunResult.of("⏹️ 已取消任务 [" + task.getId() + "]。", streamRenderer.hasStreamedOutput());
            }

            // 累计Token使用量
            totalInputTokens += response.inputTokens();
            totalOutputTokens += response.outputTokens();
            totalCachedInputTokens += response.cachedInputTokens();

            log.info("Task {} iteration {} response: toolCalls={}, reasoningChars={}, contentChars={}",
                    task.getId(),
                    iteration,
                    response.toolCalls() == null ? 0 : response.toolCalls().size(),
                    response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                    response.content() == null ? 0 : response.content().length());

            // 没有工具调用：任务完成
            if (!response.hasToolCalls()) {
                // 记录Token使用量
                memoryManager.recordTokenUsage(totalInputTokens, totalOutputTokens, totalCachedInputTokens);

                // 如果只有工具结果没有文本内容，返回工具结果
                if (!allResults.isEmpty() && (response.content() == null || response.content().isBlank())) {
                    String toolOnlyResult = allResults.toString().trim();
                    if (!toolOnlyResult.isBlank()) {
                        memoryManager.addAssistantMessage("[计划任务 " + task.getId() + "] " + toolOnlyResult);
                    }
                    streamRenderer.finish();
                    return TaskRunResult.of(toolOnlyResult, streamRenderer.hasStreamedOutput());
                }

                // 返回LLM的文本响应
                if (response.content() != null && !response.content().isBlank()) {
                    memoryManager.addAssistantMessage("[计划任务 " + task.getId() + "] " + response.content());
                }
                streamRenderer.finish();
                return TaskRunResult.of(response.content(), streamRenderer.hasStreamedOutput());
            }

            // 有工具调用：执行工具并将结果回灌到消息历史
            printToolCalls(out, response.toolCalls());

            // 将assistant响应（含工具调用）添加到消息历史
            messages.add(LlmClient.Message.assistant(
                    response.reasoningContent(),
                    response.content(),
                    response.toolCalls()
            ));

            // 在工具执行前 flush 并重置流式渲染器：避免 Markdown renderer pending 文本
            // 被 HITL 提示"跨过"导致 🧠 / 🤖 标题与内容错位
            streamRenderer.resetBetweenIterations();

            // 执行工具调用（支持并行执行多个工具）
            List<ToolExecutionResult> toolResults = executeToolCalls(task.getId(), response.toolCalls());

            // 将工具结果添加到消息历史
            for (ToolExecutionResult toolResult : toolResults) {
                memoryManager.addToolResult(toolResult.name(), toolResult.result());
                allResults.append(toolResult.result()).append("\n");
                messages.add(LlmClient.Message.tool(toolResult.id(), toolResult.result()));
            }

            // 处理工具返回的图片内容
            appendImageToolMessages(messages, toolResults);
        }

        // 达到最大迭代次数，返回累积结果
        String fallbackResult = allResults.toString().trim();
        if (!fallbackResult.isBlank()) {
            memoryManager.addAssistantMessage("[计划任务 " + task.getId() + "] " + fallbackResult);
        }
        streamRenderer.finish();
        return TaskRunResult.of(fallbackResult, streamRenderer.hasStreamedOutput());
    }

    /**
     * 构建外部上下文（如MCP资源索引）
     *
     * @return 外部上下文字符串
     */
    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context for plan task", e);
            return "";
        }
    }

    /**
     * 注入待处理的LSP诊断信息到对话历史
     *
     * 当工具执行后产生LSP诊断（如编译错误、警告），会延迟注入到下一轮LLM调用前
     *
     * @param messages 消息历史
     * @param out 输出流
     */
    private void injectPendingLspDiagnostics(List<LlmClient.Message> messages, PrintStream out) {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        messages.add(LlmClient.Message.user(report.promptText()));
        out.println(report.displayText());
        log.info("Injected LSP diagnostics into plan task conversation");
    }

    /**
     * 预览内容（截断到指定长度）
     *
     * @param content 原始内容
     * @param maxLength 最大长度
     * @return 截断后的内容
     */
    private String preview(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    /**
     * 执行工具调用
     *
     * @param taskId 任务ID（用于日志）
     * @param toolCalls 工具调用列表
     * @return 工具执行结果列表
     */
    private List<ToolExecutionResult> executeToolCalls(String taskId, List<LlmClient.ToolCall> toolCalls) {
        List<ToolInvocation> invocations = new ArrayList<>();
        for (LlmClient.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("Task {} scheduling tool {}", taskId, toolName);
            log.debug("Task {} tool args [{}]: {}", taskId, toolName, toolArgs);
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
        }

        if (invocations.size() > 1) {
            log.info("Task {} executing {} tool calls in parallel", taskId, invocations.size());
        }
        List<ToolExecutionResult> results = toolRegistry.executeTools(invocations);
        for (ToolExecutionResult result : results) {
            log.debug("Task {} tool result preview [{}]: {}", taskId, result.name(), preview(result.result(), 300));
        }
        return results;
    }

    /**
     * 将工具返回的图片内容追加到消息历史
     *
     * 某些工具（如截图）会返回图片，需要作为多模态消息添加到对话中
     *
     * @param messages 消息历史
     * @param toolResults 工具执行结果
     */
    private void appendImageToolMessages(List<LlmClient.Message> messages, List<ToolExecutionResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return;
        }
        for (ToolExecutionResult result : toolResults) {
            if (!result.hasImageParts()) {
                continue;
            }
            List<LlmClient.ContentPart> parts = new ArrayList<>();
            parts.add(LlmClient.ContentPart.text("工具 " + result.name() + " 返回了图片内容，请结合上面的工具文本结果分析。"));
            parts.addAll(result.imageParts());
            messages.add(LlmClient.Message.user(parts));
        }
    }

    /**
     * 打印工具调用信息
     *
     * 将工具调用按名称分组显示，便于用户了解执行了哪些工具
     *
     * @param out 输出流
     * @param toolCalls 工具调用列表
     */
    private static void printToolCalls(PrintStream out, List<LlmClient.ToolCall> toolCalls) {
        // 按工具名称分组
        Map<String, List<LlmClient.ToolCall>> grouped = new LinkedHashMap<>();
        for (LlmClient.ToolCall tc : toolCalls) {
            grouped.computeIfAbsent(tc.function().name(), k -> new ArrayList<>()).add(tc);
        }

        // 打印每个工具组
        for (var group : grouped.entrySet()) {
            String toolName = group.getKey();
            List<LlmClient.ToolCall> calls = group.getValue();
            out.println(AnsiStyle.subtle("  " + toolLabel(toolName, calls.size())));
            for (LlmClient.ToolCall tc : calls) {
                String detail = extractKeyParam(toolName, tc.function().arguments());
                if (!detail.isEmpty()) {
                    out.println(AnsiStyle.subtle("    └ " + detail));
                }
            }
        }
    }

    /**
     * 生成工具标签（带emoji图标）
     *
     * @param toolName 工具名称
     * @param count 调用次数
     * @return 格式化的工具标签
     */
    private static String toolLabel(String toolName, int count) {
        return switch (toolName) {
            case "read_file" -> "📖 读取 " + count + " 个文件";
            case "write_file" -> "✏️ 写入 " + count + " 个文件";
            case "list_dir" -> "📂 列出 " + count + " 个目录";
            case "execute_command" -> "⚡ 执行 " + count + " 条命令";
            case "create_project" -> "🏗️ 创建 " + count + " 个项目";
            case "search_code" -> "🔍 搜索代码 " + count + " 次";
            case "web_search" -> "🌐 联网搜索 " + count + " 次";
            case "web_fetch" -> "📰 抓取 " + count + " 个网页";
            case "save_memory" -> "💾 保存长期记忆 " + count + " 条";
            default -> toolName != null && toolName.startsWith("mcp__")
                    ? formatMcpLabel(toolName, count)
                    : "🔧 " + toolName + " × " + count;
        };
    }

    /**
     * 格式化MCP工具标签
     *
     * @param toolName MCP工具名称（格式：mcp__server__tool）
     * @param count 调用次数
     * @return 格式化的MCP工具标签
     */
    private static String formatMcpLabel(String toolName, int count) {
        String[] parts = toolName.split("__", 3);
        String display = parts.length == 3 ? parts[1] + "." + parts[2] : toolName;
        return count == 1
                ? "🔌 调用 MCP 工具 " + display
                : "🔌 调用 MCP 工具 " + display + " × " + count;
    }

    /**
     * 提取工具参数的关键信息（用于简短显示）
     *
     * @param toolName 工具名称
     * @param argsJson 参数JSON字符串
     * @return 关键参数预览
     */
    private static String extractKeyParam(String toolName, String argsJson) {
        try {
            JsonNode node = JSON_MAPPER.readTree(argsJson);
            // 根据工具类型提取关键参数
            String key = switch (toolName) {
                case "read_file", "write_file", "list_dir" -> "path";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "search_code", "web_search" -> "query";
                case "web_fetch" -> "url";
                case "save_memory" -> "fact";
                default -> null;
            };
            if (key == null) {
                return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
            }
            String value = node.path(key).asText("");
            if (value.length() > 80) {
                value = value.substring(0, 77) + "...";
            }
            return value;
        } catch (Exception e) {
            return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
        }
    }

    /**
     * 流式输出状态跟踪器
     * 用于跟踪整个计划执行过程中是否有流式输出发生
     */
    private static final class StreamState {
        /** 是否已经发生过流式输出 */
        private volatile boolean streamedOutput;

        private void markStreamed() {
            this.streamedOutput = true;
        }

        private boolean hasStreamedOutput() {
            return streamedOutput;
        }
    }

    /**
     * 任务流式渲染器
     *
     * 负责将LLM的流式输出实时渲染到终端，支持：
     * - 推理过程（reasoning）的流式显示
     * - 内容输出（content）的流式显示
     * - Markdown格式渲染
     * - 推理和内容的分离显示（带不同标题）
     *
     * 线程安全：所有方法都使用synchronized修饰
     */
    private static final class TaskStreamRenderer implements LlmClient.StreamListener {
        private final String taskId;
        private final StreamState streamState;
        private final PrintStream out;

        /** 待处理的推理内容（等待非空白内容出现后才开始渲染） */
        private final StringBuilder pendingReasoning = new StringBuilder();
        /** 延迟的推理内容（在content开始后收到的reasoning） */
        private final StringBuilder lateReasoning = new StringBuilder();

        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean streamedOutput;

        private TaskStreamRenderer(String taskId, StreamState streamState, PrintStream out) {
            this.taskId = taskId;
            this.streamState = streamState;
            this.out = out;
        }

        @Override
        public synchronized void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }

            // 如果content已经开始，将reasoning存入lateReasoning
            if (contentStarted) {
                lateReasoning.append(delta);
                return;
            }

            // 首次收到reasoning时，等待非空白内容出现
            if (!reasoningStarted) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;
                }

                // 打印推理标题并开始渲染
                out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
                reasoningRenderer = new TerminalMarkdownRenderer(out);
                reasoningRenderer.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
                streamState.markStreamed();
            } else {
                // 继续渲染推理内容
                reasoningRenderer.append(delta);
            }
            out.flush();
        }

        @Override
        public synchronized void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }

            // 首次收到content时
            if (!contentStarted) {
                // 如果reasoning已经开始，先完成reasoning渲染
                if (reasoningStarted && reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    out.println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    // 如果有pending的reasoning，先渲染它们
                    out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
                    TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                    r.append(pendingReasoning.toString());
                    r.finish();
                    out.println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }

                // 打印内容标题（用"输出"避免误导，因为可能是tool-call前的叙述）
                out.println(AnsiStyle.section("🤖 任务输出 [" + taskId + "]"));
                contentRenderer = new TerminalMarkdownRenderer(out);
                contentStarted = true;
                streamedOutput = true;
                streamState.markStreamed();
            }

            // 渲染content内容
            contentRenderer.append(delta);
            out.flush();
        }

        /**
         * 完成渲染（任务结束时调用）
         */
        private synchronized void finish() {
            if (streamedOutput) {
                if (reasoningRenderer != null) {
                    reasoningRenderer.finish();
                }
                if (contentRenderer != null) {
                    contentRenderer.finish();
                }
                flushLateReasoning();
                out.println("\n");
            }
        }

        /**
         * 两次 iteration 之间（通常是一次 tool-call 分支完成后）调用：
         * 收尾当前渲染器并重置状态，让下一轮迭代能重新打印 🧠 / 🤖 标题，
         * 避免标题和内容被 HITL / 工具执行中断而错位。
         */
        private synchronized void resetBetweenIterations() {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            flushLateReasoning();
            pendingReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            if (streamedOutput) {
                out.println();
            }
        }

        private synchronized boolean hasStreamedOutput() {
            return streamedOutput;
        }

        /**
         * 刷新延迟的推理内容（在content开始后收到的reasoning）
         */
        private void flushLateReasoning() {
            String late = lateReasoning.toString().trim();
            if (late.isEmpty()) {
                lateReasoning.setLength(0);
                return;
            }
            out.println();
            out.println(AnsiStyle.heading("🧠 补充思考 [" + taskId + "]"));
            TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(out);
            renderer.append(late);
            renderer.finish();
            lateReasoning.setLength(0);
        }
    }

    /**
     * 构建任务上下文
     *
     * 为当前任务构建包含完整上下文的输入文本：
     * - 总目标
     * - 当前任务描述
     * - 依赖任务的结果（如果有）
     *
     * @param goal 总目标
     * @param plan 执行计划
     * @param task 当前任务
     * @return 任务上下文字符串
     */
    private String buildTaskContext(String goal, ExecutionPlan plan, Task task) {
        StringBuilder context = new StringBuilder();
        context.append("总目标：").append(goal).append("\n");
        context.append("当前任务：").append(task.getDescription()).append("\n");

        // 添加依赖任务信息
        if (task.getDependencies().isEmpty()) {
            context.append("依赖任务：无\n");
        } else {
            context.append("依赖任务结果：\n");
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTask(depId);
                if (dep == null) {
                    continue;
                }
                context.append("- ").append(dep.getId())
                        .append(" / ").append(dep.getDescription())
                        .append(" / 状态=").append(dep.getStatus())
                        .append("\n");
                if (dep.getResult() != null && !dep.getResult().isBlank()) {
                    context.append(dep.getResult()).append("\n");
                }
            }
        }

        context.append("请执行此任务。如果是ANALYSIS或VERIFICATION类型，请基于以上上下文直接给出结果。");
        return context.toString();
    }

    /**
     * 构建最终结果
     *
     * 策略：
     * 1. 优先返回叶子任务（没有被其他任务依赖的任务）的结果
     * 2. 如果叶子任务都没有结果，返回最后一个有结果的任务的结果
     * 3. 已通过流式输出显示过的任务结果会被跳过（避免重复）
     *
     * @param plan 执行计划
     * @param streamedTaskOutputs 已流式输出的任务ID集合
     * @return 最终结果字符串
     */
    private String buildFinalResult(ExecutionPlan plan, Map<String, Boolean> streamedTaskOutputs) {
        StringBuilder result = new StringBuilder();

        // 获取叶子任务（没有被其他任务依赖的任务）
        List<Task> leafTasks = plan.getAllTasks().stream()
                .filter(task -> task.getDependents().isEmpty())
                .toList();

        // 收集叶子任务的结果
        for (Task task : leafTasks) {
            // 跳过已流式输出的任务
            if (Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId()))) {
                continue;
            }
            if (task.getResult() == null || task.getResult().isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append("\n");
            }
            result.append("[").append(task.getId()).append("] ").append(task.getResult());
        }

        // 如果有叶子任务结果，返回它们
        if (!result.isEmpty()) {
            return result.toString();
        }

        // 否则返回最后一个有结果的任务的结果
        return plan.getAllTasks().stream()
                .filter(task -> !Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId())))
                .filter(task -> task.getResult() != null && !task.getResult().isBlank())
                .reduce((first, second) -> second)
                .map(Task::getResult)
                .orElse("");
    }

}
