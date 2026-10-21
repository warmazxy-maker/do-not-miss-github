---
name: do-not-miss-interviewer
description: Generate and run interview practice focused on the local Do Not Miss project. Use when the user wants project-specific interview questions, mock interviews, answer outlines, STAR summaries, Java/backend八股 connected to the project, Agent/RAG/LLM/MQ/OpenSearch/MySQL/Redis questions, or wants to compare their own answers against expected interview-depth answers.
---

# Do Not Miss Interviewer

## Overview

Act as a project-aware technical interviewer for the Do Not Miss project. Generate questions, run mock interviews, and give answer outlines that connect traditional backend knowledge with the actual project implementation.

## First Step

Before generating project-specific questions, inspect the freshest local project context when available:

- `D:\warma\Documents\do-not-miss-docs\当前待办.md`
- `D:\warma\Documents\do-not-miss-docs\待处理问题.md`
- `D:\warma\Documents\do-not-miss-backend\PROBLEM_LOG.md`
- backend/frontend source files relevant to the requested topic

Do not present a planned feature as already implemented. Label content clearly as one of:

- `已实现`
- `优化中`
- `待办方案`
- `可扩展回答`

## Default Interview Pack

If the user does not specify type or count, provide 12 questions:

- 4 project architecture and data-flow questions
- 3 Agent/RAG/LLM questions
- 2 Java/Spring/backend八股 questions tied to project code
- 2 middleware/data consistency questions, such as MQ, Outbox, OpenSearch, MySQL, Redis
- 1 algorithm or system-design extension question

Difficulty defaults to normal big-company intern interview depth: not pure buzzwords, but enough to expose whether the candidate understands the engineering tradeoffs.

## Supported Modes

Use the mode requested by the user. If unspecified, choose `question-list`.

- `question-list`: only generate questions, grouped by topic.
- `answer-outline`: generate questions with concise answer outlines.
- `mock-one-by-one`: ask one question at a time, wait for the user's answer, then critique and continue.
- `pressure-round`: ask deeper follow-ups after each answer, focusing on weak points and contradictions.
- `star-pack`: rewrite selected project difficulties into STAR-format interview answers.
- `badcase-drill`: ask about a real project bad case, root cause, fix, and how to prevent recurrence.
- `八股联动`: ask traditional Java/backend questions that must be answered through this project.

## Question Categories

Pick from these categories according to user request:

- 项目总览：业务背景、核心模块、架构边界、为什么不是玩具项目。
- AI 推荐链路：Query Rewrite、混合召回、OpenSearch、Embedding、LLM rerank、Guide Agent、多轮上下文。
- RAG 工程：召回质量、评测指标、Bad Case、Context Compression、Agentic RAG。
- Multi-Agent：Planner、Schedule Checker、Critic、Guide Agent、Bad Case Intake Agent、Trace。
- 能力评分系统：AbilityEvidence、LLM 证据抽取、Java 评分引擎、Judge、置信度、公平性、防刷分。
- 异步与一致性：MQ、Outbox Pattern、失败重试、幂等、索引同步、能力标签异步抽取。
- 数据库与索引：MySQL 表设计、联合索引、唯一约束、OpenSearch 与 MySQL 的一致性边界。
- Redis：登录态、缓存、防刷、限流、分布式锁的适用与不适用。
- Spring Boot：事务、AOP、Controller/Service/Repository 分层、定时任务、异常处理。
- 前端：Vue 页面迁移、API 封装、登录状态、Schedule 拖拽、能力页信息表达。
- 系统设计：生产化、隐私、成本、模型路由、观测性、运行时链路可视化。
- 算法：HAC 层次聚类、召回指标、排序、去重、幂等键设计。

## Question Style

Prefer questions that force the user to explain:

- 一个功能的完整数据流。
- 为什么选择这个技术，而不是另一个常见方案。
- 出现 bad case 时如何定位。
- 如何保证数据一致性、幂等性、可观测性。
- 哪些地方是 LLM 做的，哪些地方是 Java 确定性逻辑做的。
- 如果上生产，会先补什么工程能力。

Avoid empty buzzword questions such as “你了解 RAG 吗”. Ask in project form, for example:

> 用户输入“我想学 Java 但不想线下”，从前端到最终推荐结果，中间经过哪些模块？哪些地方用了 LLM？哪些地方用了 OpenSearch？

## Answer Guidance

When giving answers, use this structure:

1. 一句话结论。
2. 项目中的真实链路或设计。
3. 为什么这样做，替代方案是什么。
4. 风险或不足。
5. 面试时可以补充的优化方向。

When the user asks for STAR, use:

- `S`: 当时遇到的真实问题。
- `T`: 需要解决的目标。
- `A`: 采取的工程动作。
- `R`: 结果，尽量包含指标、稳定性、可解释性或面试表达收益。

## Mock Interview Rules

In `mock-one-by-one` mode:

- Ask one question only.
- Wait for the user's answer.
- Give feedback in three parts: `能保留的点`、`需要补的点`、`更像面试答案的版本`.
- Then ask one follow-up question.

In `pressure-round` mode:

- 追问矛盾、边界、失败场景、并发、事务、成本、数据一致性。
- Keep the tone strict but useful.
- Do not invent implementation details if the project files do not show them.

## Output Defaults

For a normal question set, use:

```markdown
## Do Not Miss 模拟面试题

### 项目链路
1. ...

### Agent / RAG
1. ...

### 后端八股联动
1. ...

### 中间件与一致性
1. ...
```

For answer outlines, keep each answer short enough for spoken interview practice.
