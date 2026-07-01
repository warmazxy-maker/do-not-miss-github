# Do Not Miss

Do Not Miss 是一个面向大学生成长与社会实践机会发现的平台。项目围绕“活动发现 -> AI 推荐 / 规划 -> 预约与日程 -> 完成记录 -> 能力评分 -> 个人成长画像”构建闭环，尝试把 RAG、Agent、多轮记忆、异步消息和能力评估系统落到一个真实业务场景里。

## 核心功能

- 学生端活动检索、预约、关注组织、挑战管理、日程管理。
- 社会端活动发布，活动进入审核与质量预处理流程。
- AI 推荐：Query Rewrite + OpenSearch BM25 / Embedding 混合召回 + LLM 推荐解释。
- AI 规划：多 Agent 计划生成，包含 Planner、Schedule Checker、Critic。
- Agent Trace：记录每次 AI 调用的 Run / Step / Artifact，便于定位 Bad Case。
- Bad Case Intake Agent：接收用户反馈，结合 Trace 自动归因。
- Coach：学习日志与长期记忆复习。
- 用户画像：完成记录、挑战、教练日志触发异步画像刷新。
- 能力评分：LLM 证据抽取 + Java 固定评分引擎 + Judge 验证。
- 能力地图：HAC 层次聚类与动态 Anchor，将相近能力标签归并展示。
- 异步工程：RabbitMQ、Outbox Pattern、失败重试表，保证索引、画像、成长标签等派生数据最终一致。

## 技术栈

- 后端：Java 21、Spring Boot、Spring Data JPA、Flyway、Maven
- 数据库：MySQL
- 缓存：Redis
- 消息队列：RabbitMQ
- 检索：OpenSearch、BM25、Embedding 向量召回、混合召回评测
- AI：Qwen / OpenAI-compatible API、Embedding API、Agent Workflow、RAG
- 前端：Vue 3、Vite、TypeScript
- 部署：Docker Compose 启动中间件，Maven 启动后端，Vite 启动前端

## 项目结构

```text
do-not-miss
├── backend/   Spring Boot 后端、数据库迁移、AI/RAG/Agent/评分逻辑
├── frontend/  Vue 3 + Vite 前端
└── docs/      待处理问题、面试问题、项目文档
```

## 快速启动

### 1. 启动中间件

```powershell
cd backend
docker compose up -d
```

默认会启动：

- MySQL: `3306`
- Redis: `6379`
- RabbitMQ: `5672`，管理页面 `15672`
- OpenSearch: `9200`

### 2. 配置后端环境变量

```powershell
cd backend
copy .env.example .env
```

在 `.env` 中填入自己的模型 Key，例如：

```text
DASHSCOPE_API_KEY=your-api-key
```

注意：`.env` 不应该提交到 GitHub。

### 3. 启动后端

```powershell
cd backend
mvn spring-boot:run
```

后端默认地址：

```text
http://localhost:8080
```

### 4. 启动前端

```powershell
cd frontend
npm install
npm run dev
```

前端默认地址：

```text
http://localhost:5173
```

## GitHub 上传前检查

- 不要上传 `backend/.env`。
- 不要上传 `backend/target/`。
- 不要上传 `frontend/node_modules/`。
- 不要上传 `frontend/dist/`。
- 不要上传本地日志、录屏、截图或私有配置。
- 保留 `.env.example`，它用于说明需要配置哪些环境变量。

## 项目亮点

- 不让 LLM 直接编造活动：推荐结果必须来自 RAG 候选和真实数据库 eventId。
- OpenSearch 是派生检索索引，MySQL 仍是业务数据源。
- Outbox Pattern 连接 MySQL 事务与 RabbitMQ 投递，消费者使用幂等 Upsert / Refresh。
- Agent Trace 与 Bad Case Intake 让 AI 功能可观测、可复盘、可沉淀。
- 能力评分拆成 Evidence Evaluator 和 Java AbilityScoreEngine，避免 LLM 直接决定最终分数。
- HAC 能力聚类与动态 Anchor 让用户能力图谱从零散标签逐渐归并成稳定能力方向。
