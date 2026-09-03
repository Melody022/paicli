# sharkCli

一个基于 Java 的 AI Agent 命令行产品，对标 Claude Code，展示先进的 AI 编程助手架构设计与实现。

## 技术架构

**执行引擎**
- ReAct Agent：思考-行动-观察循环，支持 9 种内置工具 + MCP 动态工具
- Plan-and-Execute：复杂任务拆解、DAG 依赖执行、用户可编辑计划
- Multi-Agent：Planner/Worker/Reviewer 三角色协作，自动冲突解决与重试

**上下文与记忆**
- 短期记忆：对话历史与工具结果管理，Token 预算动态计算
- 长期记忆：关键事实跨会话复用，相关性检索与注入
- 代码理解：RAG 语义检索 + 代码关系图谱，支持本地 Ollama Embedding

**工具生态**
- 9 个核心工具：文件操作、Shell 执行、代码创建、语义搜索、联网搜索
- MCP 协议支持：动态工具注册、resources 虚拟化、HITL 安全审批
- Chrome DevTools 集成：浏览器控制、登录态复用、SPA 页面抓取
- Skill 系统：按场景加载专家手册，扩展 Agent 能力

**安全与审计**
- Human-in-the-Loop：危险操作人工审批（三级危险等级）
- 路径围栏：文件工具强制项目根内，防止路径穿越
- 命令快速拒绝：黑名单拦截破坏性命令（sudo/rm -rf 等）
- 结构化审计：危险工具调用按天记录，可查询回溯

**上下文工程**
- 三层 Prompt 分层：base → personality → mode → approval → skills → context
- 长上下文模式：支持 1M token 窗口，自动 prompt cache 管理
- 动态上下文注入：MCP resources、RAG 检索、诊断信息

**TUI 产品**
- 三种渲染形态：inline 流式（默认）/ Lanterna 全屏 / Plain 文本
- 底部状态栏：实时显示模型、MCP、Skill、Token/Cost 统计
- 行内折叠工具块：类似 Claude Code 的交互体验

## 技术亮点

1. **多模型适配**：GLM-5.1、DeepSeek V4、StepFun、Kimi K2.6，通过 LlmClient 接口抽象实现 Provider 无关设计

2. **异步并行**：单轮多个工具调用并行执行，结果保持顺序，超时有取消机制

3. **长上下文处理**：80% 窗口预算、short/balanced/long 模式、MCP resources 索引注入、Token/Cost 实时展示

4. **Git 快照与回滚**：JGit 纯 Java 实现 Side-Git，每个 turn 自动创建 pre/post 快照，支持回滚

5. **LSP 诊断注入**：JavaParser 轻量语法诊断，错误实时反馈给 Agent 修正

6. **MCP 协议栈**：stdio + HTTP 双传输、动态工具注册、resources 虚拟化、审计脱敏

## 技术栈

- Java 17 + Maven
- JLine 4（终端交互）
- SQLite（向量存储）
- JGit（快照管理）
- OkHttp + Jackson
- JavaParser（AST 分析）
- Jsoup（HTML 提取）

## 代码规模

- 21 期迭代，交付完整 Agent CLI 生态
- 9 个核心工具 + 60+ MCP 动态工具
- 3 个执行路径（ReAct/Plan/Multi-Agent）
- 20 个内置 Skill
- 5 个主要模块（agent/llm/tool/memory/mcp）

## 个人贡献

（请补充你在项目中的具体工作，例如）
- 设计并实现了 Multi-Agent 编排器，支持角色分工与冲突解决
- 优化了长上下文处理，实现 Token 预算动态计算与 prompt cache
- 实现了 MCP 协议栈，支持动态工具注册与 resources 虚拟化
- 开发了 Chrome DevTools 集成，实现浏览器登录态复用
- 构建了 Skill 系统，实现了可扩展的专家手册加载机制
