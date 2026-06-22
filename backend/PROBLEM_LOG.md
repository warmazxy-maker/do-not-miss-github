# do not miss 问题日志

这份文档只记录明确指定要沉淀的问题。每条记录保持同一结构：发生了什么、原因、解决方式、结果。

## 001. AI 推荐时模型乱生成数据库不存在的活动

### 发生了什么

学生端使用 AI 推荐事件时，页面右侧返回的活动不一定来自数据库已有事件。模型会根据学生需求自行编造活动标题、组织、地点和时间，导致前端看起来“推荐成功”，但推荐结果无法和真实活动的预约、关注、成就等后续流程关联。

### 原因

早期 AI 推荐更像是“让大模型根据需求生成推荐文案”，候选事件约束不够强。模型没有被严格限制只能返回数据库中的 `eventId`，后端也缺少对模型返回结果的二次校验，所以模型容易把“合理的活动想象”当成真实推荐返回。

### 解决方式

后端新增混合召回层 `HybridEventRetrievalService`，先由后端从数据库中召回真实候选事件，再把候选事件的 `eventId`、标题、组织、时间、地点、收益、技能和召回证据传给大模型。

同时在 `AiService` 中增加两层约束：

1. Prompt 明确要求模型只能使用 `candidateEvents` 中真实存在的 `eventId`，不能编造活动、组织、时间或地点。
2. 后端对模型返回结果做 normalize，只保留候选集合中存在的 `eventId`，过滤低分或无效结果，并把混合召回 evidence 合并进推荐理由。

### 结果

AI 推荐结果被限制为数据库已有事件。即使模型输出不存在的 `eventId`，后端也会过滤掉，前端最终只展示真实可预约的活动。

## 002. AI/Agent 相关需求下高度相关活动排序不稳定

### 发生了什么

学生在 AI 助手中输入“我想了解 AI 的开发，agent 相关的之类的”时，数据库中存在明显相关的活动“agent 应用教学”，但它在检索 trace 中排名偏低，最终可能被 AI 推荐阶段过滤掉。

### 原因

当时运行环境没有启用 OpenSearch / 向量召回，推荐链路主要依赖数据库候选、Java 本地规则打分和 AI 排序。

同时 Query Rewrite 会把原始需求扩写成 Java、Spring Boot、后端开发、项目开发、Python、API 集成等额外词，导致 Java 项目、企业活动和历史偏好活动被抬高，稀释了“agent 应用教学”的直接相关性。

此外，用户画像 memory 权重偏高，会让历史偏好压过当前明确需求。

### 解决方式

开启搜索增强配置：`SEARCH_ENABLED=true`、`SEARCH_VECTOR_ENABLED=true`、`AI_EMBEDDING_MODEL=text-embedding-v4`、`AI_EMBEDDING_DIMENSIONS=1024`，让推荐链路升级为 OpenSearch BM25 + Embedding 向量召回。

修复 OpenSearch 索引创建失败问题：中文 ngram 分词器使用 `min_gram=1,max_gram=3`，需要显式配置 `index.max_ngram_diff=2`。

调整混合检索排序权重，降低用户画像 memory 权重，把 OpenSearch BM25/向量融合召回的排序分纳入最终分数，并补充 `ai`、`agent`、`llm`、`大模型`、`人工智能` 等技术意图词。

### 结果

使用“我想学学 agent 开发”测试后，`agent 应用教学` 不再被挤到候选列表后部。检索 trace 中该活动 `eventId=20` 从原先约第 8 名提升到第 3 名，并能进入最终 AI 推荐结果。

## 003. 检索优化缺少客观评测和 A/B 对比

### 发生了什么

之前判断检索优化是否有效，主要靠手动输入几句话观察前端结果。这样可以发现问题，但很难证明“优化真的变好”，也很难防止后续改动让旧场景退化。

### 原因

项目只有 trace 接口和少量人工测试，没有固定评测集，也没有把“目标活动是否进入前 K、最好排名是多少、当前方案和旧方案谁更好”变成可复现指标。

### 解决方式

新增检索评测能力：

1. 在 `retrieval-eval-set.json` 中维护固定评测集，包含用户自然语言需求、期望命中的活动 ID、期望关键词和 `topK`。
2. 在 `RetrievalEvalService` 中加入 `expectedEventIds`、`matchedEventIds`、`bestExpectedRank`、`reciprocalRank` 等指标。
3. 增加 A/B 对比接口 `/api/ai/retrieval/evaluation/ab`，同时运行当前混合检索方案和旧版本地打分基线方案。
4. 在 `HybridEventRetrievalService` 中保留 `retrieveBaseline`，用于对比不启用 OpenSearch 向量召回和重排时的效果。

### 结果

现在可以一键跑固定评测集，得到 current 与 baseline 的 hitRate、MRR、每条 case 的最好命中排名和胜负关系。当前 6 条评测 case 均能命中，且当前混合检索方案没有出现相对基线的退化。

## 004. Query Rewrite 过度扩写导致检索偏航

### 发生了什么

用户输入“我想学学 agent 开发”这类短需求时，Query Rewrite 曾经容易把需求扩写成 Java、Spring Boot、后端开发、项目实践、企业偏好、地点偏好等一大串额外检索词。这样虽然看起来更“丰富”，但会让检索系统偏离用户真正表达的核心意图。

### 原因

Query Rewrite 早期把大模型输出、规则兜底和用户画像合并得比较宽松。模型可能根据常识补充技术栈，用户画像也可能把历史偏好带进本次查询，导致当前明确需求被长期偏好和模型联想稀释。

对于 AI / Agent / LLM 这类需求，普通“编程项目实践”规则也可能被“开发、项目”等词触发，从而把 Agent 学习需求误拉向 Java 项目、后端项目或企业项目。

### 解决方式

重写 `QueryRewriteService` 的提示词和归一化逻辑：

1. Prompt 明确要求模型只抽取用户明确表达或规则兜底已经识别出的信息，不把用户画像里的长期偏好强行写入本次查询。
2. 增加保守过滤逻辑，对 intentTags、skills、preferredCategories、constraints 做数量限制和来源校验。
3. 对 AI / Agent / LLM / 大模型 / RAG 需求增加专门规则，优先识别为“AI Agent 开发学习”。
4. 只有当用户明确提到 Java、Spring、Python、Go 等技术栈时，才把普通编程项目实践规则合并进去。
5. 地点、收益、类别等字段如果没有在本次需求或规则兜底中明确出现，就不让模型随意补充。

### 结果

使用“我想学学 agent 开发”测试后，Rewrite 结果收敛到 AI、Agent、LLM、大模型、RAG、工具调用等核心词，不再混入无关 Java/Spring 技术栈。检索结果中 `大模型agent开发竞赛`、`agent应用教学`、`阿里天池大模型比赛` 稳定进入前排。

## 005. 用户画像固定权重导致当前需求和历史偏好冲突

### 发生了什么

检索排序中用户画像 memory 原本使用固定权重。这样在部分场景下会出现两个方向的问题：如果用户本次需求非常明确，历史偏好可能把不相关但“符合过去兴趣”的活动抬高；如果用户本次需求很模糊，系统又没有充分利用用户画像来补足偏好。

### 原因

早期混合检索公式是固定加权，类似 `keywordScore`、`intentScore`、`memoryScore`、`metadataScore`、`recallScore` 按固定比例合成最终分数。固定权重无法区分“明确需求”和“模糊需求”：明确需求更应该相信当前 query 和召回证据，模糊需求则更应该借助历史画像。

### 解决方式

在 `HybridEventRetrievalService` 中新增用户画像权重自适应策略：

1. 根据 query 的清晰度分成 `HIGH`、`MEDIUM`、`LOW` 三档。
2. 明确需求，例如包含 `agent`、`java`、`日语`、`研究`、`报酬` 等强意图词时，降低 memory 权重，提高 keyword、intent 和 recall 权重。
3. 模糊需求，例如“推荐点活动”“看看有什么机会”这类表达，提升 memory 权重，让历史偏好帮助补全排序。
4. baseline 检索保持旧固定权重不动，方便继续用 A/B 接口比较当前方案和旧方案。
5. 检索 evidence 中增加“用户画像权重自适应”说明，便于 trace 时观察当前使用了哪种权重策略。

### 结果

代码已编译通过。当前混合检索不再使用单一固定 memory 权重，而是根据需求清晰度动态调整用户画像影响力：明确需求减少历史偏好干扰，模糊需求增强个性化补全能力。

## 006. Agent 调用缺少工作流状态机和可观测日志

### 发生了什么

AI 事件推荐和 AI 计划推荐能返回结果，但当推荐结果不符合预期时，只能从前端结果倒推问题。比如无法直接判断问题发生在 Query Rewrite、MCP 工具上下文、混合召回、模型重排、规则 fallback，还是最终响应构造阶段。

### 原因

早期 Agent 链路只是普通 service 调用，没有把一次 Agent 执行拆成可观测步骤。后端缺少统一的 run/step 状态记录，也没有记录每一步的输入摘要、输出摘要、耗时和失败原因。

### 解决方式

新增 Agent 工作流日志模块：

1. 新增 `agent_runs` 表，记录一次完整 Agent 调用的用户、类型、状态、目标、输入摘要、输出摘要、错误信息、开始时间和结束时间。
2. 新增 `agent_run_steps` 表，记录一次调用中的每个阶段，包括步骤名、顺序号、状态、输入摘要、输出摘要、错误信息、开始时间和结束时间。
3. 新增 `AgentRunService`，使用独立事务写日志，避免 AI 推荐本身是只读事务时无法落库。
4. 新增查询接口：
   - `GET /api/ai/agent-runs`
   - `GET /api/ai/agent-runs/{runId}`
5. 将 AI 事件推荐链路接入状态记录，覆盖 `PROFILE_MEMORY`、`MCP_CONTEXT`、`QUERY_REWRITE`、`RETRIEVAL`、`HISTORY_LOAD`、`LLM_RECOMMENDATION`、`RULE_FALLBACK`、`RESPONSE_BUILD` 等步骤。
6. 将 AI 计划推荐链路接入状态记录，覆盖用户画像、MCP、候选活动召回、日程加载、模型计划生成、规则 fallback 和响应构造等步骤。

### 结果

代码已编译通过，数据库已执行 `V7__add_agent_run_logs.sql` 迁移。测试一次“我想学学 agent 开发”的 AI 事件推荐后，系统成功写入一条 `EVENT_RECOMMENDATION` 类型的 `SUCCEEDED` run，并记录 7 个 step。现在可以通过 Agent Run 详情查看每一步耗时、输入摘要、输出摘要和最终推荐的 `eventId`。

## 007. AI 推荐计划无法直接落地到日程

### 发生了什么

AI 计划推荐可以生成多份行动计划，但用户只能阅读计划流程，不能把某一份计划直接写入 Schedule。这样计划停留在“建议文案”层面，用户还需要手动复制每个步骤到日程模块，体验割裂，也不利于后续基于日程做提醒和执行追踪。

### 原因

计划推荐返回的是 `title`、`style`、`summary` 和若干 `steps`，其中步骤只有 `dateLabel`、`scheduleHint`、`itemType`、`eventId` 和理由。Schedule 模块原本只支持手动创建明确开始/结束时间的时间块，没有专门处理 AI 计划这种“半结构化计划”的导入逻辑。

### 解决方式

新增 AI 计划导入日程能力：

1. Schedule 新增 `AI_PLAN` 日程类型，用来区分手动日程、预约日程和 AI 计划导入日程。
2. 新增 `POST /api/schedule/import-ai-plan` 接口，接收当前选中的 AI 计划和步骤列表。
3. 后端导入时对 `EVENT` 步骤优先使用数据库真实活动的开始时间、结束时间和地点，避免让模型的模糊时间描述覆盖真实活动时间。
4. 对 `STUDY`、`REFLECTION`、`CHALLENGE` 等非活动步骤，后端根据步骤顺序和“第 N 周”等提示推断未来时间块。
5. 前端计划弹窗新增“写入日程”按钮，点击后导入当前选中的计划，并自动刷新日程月视图。

### 结果

代码已编译和语法检查通过。现在用户可以在 AI 计划弹窗中选择某一份计划，一键写入 Schedule；写入成功后系统会切换到日程模块展示生成的时间块。AI 计划从“建议”变成了可执行的日程安排。

## 008. Coach 对话缺少长期记忆复习机制

### 发生了什么

日志与教练模块可以和用户聊天，也可以把当天对话生成日志，但生成后的日志更多只是“存档”。后续 Coach 对话不会主动取出过去的关键经历进行复习，用户写过的反思、踩坑、收获没有被转化成可反复利用的长期记忆。

### 原因

早期 Coach 链路只把用户最新一句话直接发给大模型，最多在生成日志时读取当天对话和用户画像。系统没有把历史日志沉淀成独立的长期记忆对象，也没有记录复习次数、上次复习时间、下次复习时间这类调度信息。因此 Coach 无法判断“什么时候应该复习哪段经历”，也无法把复习动作纳入可持久化、可解释的 Agent 流程。

### 解决方式

新增 Coach 长期记忆复习模块：

1. 新增 `coach_memory_reviews` 表，将 Coach 日志沉淀为复习卡片，字段包括 `source_log_id`、`memory_type`、`title`、`memory_text`、`tags`、`strength`、`review_count`、`last_reviewed_at`、`next_review_at`。
2. 生成或更新 Coach 日志时，自动把日志 upsert 成一张长期记忆卡片，避免用户额外手动整理记忆。
3. Coach 聊天前会回填已有日志对应的复习卡片，并查询当前到期的记忆卡片。
4. Coach 普通回复不再只传用户最新一句，而是传入“最新消息、当天对话、用户画像、到期长期记忆”组成的上下文，让模型在回答当前问题的同时自然穿插一个短复习问题。
5. 每次复习后更新 `review_count`、`last_reviewed_at` 和 `next_review_at`，使用 1 天、3 天、7 天、14 天、30 天的间隔做简化版间隔重复。
6. 新增 `GET /api/coach/memory-reviews`，用于查看当前用户已经沉淀出的长期记忆卡片和复习调度状态。

### 结果

代码已编译通过。现在 Coach 日志不再只是普通文本存档，而会变成可持久化、可调度的长期记忆卡片。后续用户和教练聊天时，系统会按到期时间取出一条旧经历，引导用户进行轻量复习，从而让“日志与教练”从一次性聊天升级为带长期记忆的学习陪伴 Agent。

## 009. 事件推荐缺少多轮检索上下文

### 发生了什么

事件推荐原本是单轮检索。用户如果先说“我想学日语”，再补一句“最好是线上的”，系统容易把第二句话当成独立需求处理，只理解成“线上活动”，而不是“线上 + 日语学习”。这会导致用户必须一次性把所有条件说完整，体验不符合真实搜索习惯。

### 原因

早期 Query Rewrite 只负责把当前这一句自然语言改写成结构化查询，没有维护短期检索会话状态。系统也没有判断当前输入和上一轮搜索之间的关系，无法区分“补充约束”“继续上一轮搜索”和“切换到新主题”。因此上一轮的 goal、skills、location、constraints 等信息不会自动参与下一轮检索。

### 解决方式

将多轮检索做成 Query Rewrite Agent 的上下文动作选择能力，而不是额外拆一个独立 Gate Agent：

1. 在 Query Rewrite 输出中新增 `contextDecision`，包含 `action`、`relation`、`confidence`、`reason`。
2. `action` 支持三种动作：
   - `MERGE`：当前输入是在上一轮目标上补充条件，例如“最好是线上的”。
   - `KEEP`：当前输入仍围绕上一轮搜索但没有新增稳定条件，例如“还有吗”。
   - `CLEAR`：当前输入切换了核心目标，例如从“学日语”切到“学编程语言”。
3. 新增 `SearchSessionContextService`，用 Redis 保存用户短期检索上下文，key 为 `search:session:{userId}`，默认 TTL 为 60 分钟。
4. Query Rewrite 会读取上一轮上下文，并让模型在抽取结构化查询时同时判断上下文动作；后端会对模型输出进行归一化和兜底校验。
5. 如果是 `MERGE`，后端合并上一轮 goal、skills、intentTags、constraints，并用当前输入覆盖地点、收益等新约束；如果是 `KEEP`，沿用上一轮检索上下文；如果是 `CLEAR`，覆盖旧上下文并开启新一轮搜索。
6. 检索请求会使用合并后的 `preferredLocation` 和 `benefitPreference`，让“最好线上”“想要报酬”这类后续补充能真正影响召回和排序。

### 结果

代码已编译通过。现在事件推荐支持短期多轮检索：用户可以先描述目标，再逐步补充地点、收益、难度等条件；如果用户切换到新目标，Query Rewrite Agent 会输出 `CLEAR`，后端清理旧上下文并重新开始一轮查询。这个优化让 Query Rewrite 从单纯的结构化抽取升级为“需求理解 + 上下文动作选择 + 检索记忆管理”的轻量 Agent。

## 010. 语义召回证据没有充分进入最终推荐排序

### 发生了什么

用户输入“我想学日语”时，OpenSearch 已经能够召回多条日语相关活动，例如“日语N5学习”“日语口语训练”“日语N5初学者训练”等，但最终 AI 推荐结果偏少，部分人眼看上去高度相关的活动得分不够高或没有进入最终推荐列表。

### 原因

早期混合检索中，OpenSearch 的 BM25 和向量检索主要负责“把候选活动捞出来”，后端只保留了融合后的排名位置，并把它转换为 `recallScore`。这会导致两个问题：

1. OpenSearch 的原始 `_score` 没有进入最终打分，向量语义相似度只负责召回，不直接影响排序。
2. LLM 推荐和 Critic 提示词偏保守，低于 65 分的候选会被过滤，模型容易只返回最直接命中的少量活动。
3. 已过期活动只是被 metadata 扣分，没有清晰地在最终分上表达“不可推荐”。

### 解决方式

对混合检索和最终推荐链路做了三处优化：

1. `EventSearchIndexService` 新增 `SearchHit(eventId, score)`，BM25 检索和向量检索都返回 OpenSearch `_score`。
2. `HybridEventRetrievalService` 将 OpenSearch 分数拆成 `bm25Score` 和 `semanticScore`，归一化后参与最终分；明确需求场景下提高 `semanticScore` 权重，使语义相似活动更容易排到前面。
3. `RetrievedEvent` 和 trace 接口新增 `bm25Score`、`semanticScore`、`recallScore`，便于排查 badcase。
4. 过期活动不从候选池删除，但在有 MCP 当前时间上下文时，将 `finalScore` 直接置为 0，并在 evidence 中记录原因。
5. LLM 推荐阈值从 65 放宽到 55，Prompt 改为保留有关键词、技能、类别或 `semanticScore` 证据的中等相关活动。
6. 如果 LLM 返回过少，后端会用高分混合检索候选做补充推荐，避免“检索召回了但模型漏掉”的情况。
7. Critic Agent 改为只过滤明显无关、过期置零或完全缺乏证据的推荐；漏审的候选不再默认删除。

### 结果

代码已编译通过。现在 OpenSearch 不再只是召回工具，而是同时提供关键词相关性和向量语义相似度证据。最终推荐排序会更重视 `semanticScore`，过期活动会被清晰置零，LLM 输出也从“过度保守”调整为“保留有证据的中等相关候选”。

## 011. AI 计划推荐缺少多 Agent 分工和可解释规划链路

### 发生了什么

AI 计划推荐原本可以根据用户目标生成多份行动计划，并且支持一键写入 Schedule，但内部链路仍然更像“单个大模型一次性生成计划”。当用户目标较复杂，例如“想 21 天提升 Java 项目设计能力”“想结合活动、挑战、复盘形成成长路径”时，单 Agent 容易把目标理解、候选活动筛选、时间安排、计划质量判断混在一次调用里完成，结果可解释性不足，也不利于面试中讲清楚 Agent 架构。

### 原因

早期方案把“计划推荐”整体交给一个 Action Plan Agent。这个设计能跑通功能，但有几个问题：

1. 目标理解、证据收集、计划生成、时间校验、质量审核没有明确分工。
2. 召回模块已经具备 OpenSearch BM25、向量召回、混合打分和语义证据，但旧计划链路只是简单取少量候选，缺少“检索接地的规划”表达。
3. 多份计划只是一次模型输出中的多个结果，不是真正由不同规划策略生成，难以体现 multi-agent 的差异化分工。
4. Schedule 可执行性检查和 Critic 审核不够独立，模型生成的计划缺少稳定的工程兜底。

### 用户参与的设计修正

最初的设计思路是增加一个较独立的 Evidence Collector，由它统一收集活动、日程、画像、挑战和 MCP 上下文。我提出这个方案后，用户指出：项目本身已经有成熟的召回模块，Evidence Collector 不应该重新造一套检索逻辑，而应该直接复用原有混合召回，召回一批活动后再提供给 Planner Agents。

这个修正改变了最终架构：

1. 原方案偏向“新增一个材料收集层”，可能让系统复杂度上升。
2. 用户修正后的方案变成“复用现有 Hybrid Retrieval 作为 Evidence Collector 的核心工具”。
3. 最终实现采用“检索接地的多 Agent 规划”：先用现有召回模块获得真实候选活动，再让多个 Planner Agent 基于同一份证据生成不同风格计划。

这使设计更贴合项目已有能力，也更容易在面试中解释：不是为了堆 Agent 而重写功能，而是在已有 RAG/Hybrid Search 基础上，把规划任务拆成清晰的 Agent 工作流。

### 解决方式

将 AI 计划推荐升级为可观测的 multi-agent planning 工作流：

1. 新增 `GOAL_ANALYSIS` 步骤：Goal Agent 将用户目标解析为 `goal`、`level`、`horizonDays`、`intensity`、`preferredLocation`、`constraints`、`successCriteria` 和 `searchQuery`。
2. Evidence Collector 不重新实现检索，而是复用 `HybridEventRetrievalService`，使用 Goal Agent 输出的 `searchQuery` 召回最多 24 个候选活动。
3. 新增三个 Planner Agent：
   - `StablePlannerAgent`：生成稳妥型计划，强调低风险、均匀节奏、活动与复盘结合。
   - `SprintPlannerAgent`：生成集中突破型计划，强调短期高强度推进和阶段性交付。
   - `ExplorePlannerAgent`：生成探索型计划，强调先试活动/小挑战，再根据反馈调整方向。
4. 新增 `SCHEDULE_CHECK`：用 Java 规则校验 `eventId` 是否来自召回证据、活动是否过期、是否与已有日程冲突、是否缺少复盘步骤、当前日程是否过满。
5. 新增 `CRITIC_REVIEW`：Critic Agent 审核计划是否围绕目标、是否使用真实召回证据、是否可执行、是否有先后顺序和复盘闭环。
6. `RecommendedPlan` 增加 `qualityScore`、`agentTrace`、`nodes`、`edges`、`scheduleDrafts`，在保持前端原有 `steps` 兼容的同时，为流程图展示和后续 Schedule 导入提供更结构化的数据。
7. 前端计划卡片和弹窗增加质量分和 Agent 链路展示，便于测试时看到计划来自哪个 Planner、是否经过 Checker 和 Critic。

### 结果

代码已编译通过，前端 JS 语法检查通过。现在计划推荐不再是单次 LLM 生成，而是：

`Goal Agent -> Hybrid Retrieval Evidence Collector -> Stable/Sprint/Explore Planner Agents -> Schedule Checker -> Critic Agent -> Formatter`

这条链路保留了原有召回模块的工程价值，又把规划生成拆成了目标理解、多策略规划、确定性校验和质量审核几个可解释环节。面试时可以将其描述为 Retrieval-grounded Multi-Agent Planning：召回层保证计划基于真实活动，Planner Agents 产生不同策略，Checker 和 Critic 负责可执行性与质量边界。

## 012. 用户画像仍停留在规则关键词聚合，缺少 LLM 语义画像快照

### 发生了什么

用户画像原本来自已完成活动、已完成挑战、进行中挑战数量和教练日志，但最终画像是由 Java 规则实时聚合出来的。画像字段虽然包含 `summary`、`strengths`、`preferredCategories`、`preferredLocations`、`benefitPreferences`、`evidenceKeywords`、`recentSignals` 等结构，但内容主要来自统计、关键词匹配和模板拼接。

这导致画像更像“关键词画像”，而不是“LLM 理解用户成长经历后生成的语义画像”。同时，每次推荐时都重新读取用户历史记录和教练日志，后续数据量变大后会带来性能压力。

### 原因

早期设计优先保证推荐链路稳定，所以用户画像采用同步规则聚合：请求推荐时直接读取成就记录、挑战记录和教练日志，然后用 Java 统计高频类别、地点、收益偏好和能力关键词。

这种方案实现简单、可解释、不会依赖大模型，但缺少两类能力：

1. 不能对用户经历进行更自然的语义概括，例如学习风格、近期目标、能力迁移、成长阶段。
2. 没有持久化画像快照，推荐接口需要反复从原始记录中聚合画像。

### 解决方式

新增用户画像快照机制，将“原始事实”和“画像表达”拆开：

1. 新增 `user_profile_snapshots` 表，用于持久化结构化画像快照，字段包括摘要、能力优势、偏好类别、偏好地点、收益偏好、证据关键词、近期信号、完成数量、挑战数量、日志数量、dirty 状态和生成来源。
2. 新增 `UserProfileSnapshotEntity`、`UserProfileSnapshotRepository` 和 `UserProfileSnapshotDirtyService`。
3. 完成活动、完成挑战、更新历史复盘、生成教练日志后，不再立即重新总结画像，而是标记当前用户画像 `dirty=true`。
4. `UserMemoryService.profile(userId)` 优先读取非 dirty 的画像快照；如果快照不存在或仍为 dirty，则回退到原来的 Java 规则画像，保证推荐链路不断。
5. 新增 `UserProfileSnapshotRefreshJob`，定时扫描 dirty 快照，调用 LLM 生成新的结构化画像。LLM 只负责填充既有画像结构，不改变后端结构。
6. 如果 LLM 没启用或调用失败，则使用规则画像写入快照，生成来源标记为 `rule-fallback`。

### 结果

代码已编译通过。现在用户画像从“每次请求动态规则聚合”升级为“原始事实持久化 + dirty 标记 + 后台刷新画像快照 + 规则 fallback”的结构。

推荐、自我分析、Coach 等模块仍然调用同一个 `UserMemoryService.profile(userId)`，不需要大改业务链路。后续用户完成活动、挑战或生成教练日志时，系统会标记画像待刷新；后台任务会基于真实记录生成 LLM 语义画像快照。这样既保留了原始数据可追溯性，也让个人成就模块和推荐系统拥有更清晰的长期语义画像。
## 013. 成长曲线固定标签折线图缺少个性化能力标签底座

### 发生了什么

个人成就模块里的成长曲线仍然使用固定维度，例如沟通表达、执行协作、调研分析、内容创作、跨文化理解等。用户完成活动或挑战后，后端通过规则把记录匹配到这些固定维度，再让折线图上涨。

这种方式能证明“完成经历会影响成长统计”，但用户看到的是多条固定维度折线，不够直观，也难以表达用户自己独特的能力积累。

### 原因

早期成长曲线为了快速跑通闭环，采用固定标签和规则加分。这个方案实现简单，但有几个限制：

1. 标签维度由系统预设，不一定符合用户真实成长路径。
2. 一个具体经历背后的能力证据没有独立存储，难以点进标签查看“为什么我有这个能力”。
3. 折线图只展示分数变化，不能呈现里程碑、关键经历和智能简历所需的证据链。
4. 后续如果直接做前端能力地图，没有后端标签和证据数据支撑，容易变成纯 UI 效果。

### 解决方式

先按稳妥方案完成第一阶段和第二阶段，不接 LLM，不大改前端：

1. 新增 `growth_tags` 表，表示用户能力标签，例如 `Java 后端开发`、`日语沟通`、`调研分析`、`活动运营`。
2. 新增 `growth_tag_evidences` 表，表示某个标签下面的真实证据，对应一条成就记录，并保存标题、摘要、做了什么、学到了什么、分数增量和里程碑标记。
3. 新增 `GrowthTagEntity`、`GrowthTagEvidenceEntity`、`GrowthTagRepository`、`GrowthTagEvidenceRepository`。
4. 新增 `GrowthTagService`，先使用 Java 规则 fallback 从成就记录中抽取 1-3 个能力标签，保证没有 LLM 时也能工作。
5. 完成活动、完成挑战、更新历史复盘时，自动根据成就记录生成或更新标签证据，并重新计算标签总分、证据数量和重要性分。
6. 新增能力标签查询接口：
   - `GET /api/achievements/growth-tags`
   - `GET /api/achievements/growth-tags/{tagId}`
   - `POST /api/achievements/growth-tags/rebuild`
   - `PUT /api/achievements/growth-tags/evidences/{evidenceId}/milestone`
7. 新增历史回填能力，可以把已有成就记录批量转换成 growth tags 和 evidences。

### 结果

代码已编译通过。现在个人成就模块已经具备“能力标签 + 真实证据”的后端底座：未来前端能力地图可以直接读取标签分数和证据数量，标签详情页可以展示该标签下的时间线证据。

当前版本仍然是规则 fallback 标签生成，尚未接入 LLM 智能抽取和标签归并。后续可以在这个底座上继续升级：让 LLM 根据活动、挑战、复盘内容生成更个性化的标签，并让智能简历优先引用高分标签和重要里程碑。

## 014. 成长标签仍依赖固定规则，缺少 LLM 智能抽取和可观测 Agent 链路

### 发生了什么?

在第 013 条优化中，个人成就模块已经新增了 `growth_tags` 和 `growth_tag_evidences`，可以把已完成活动、挑战和复盘记录转成能力标签与证据。但这一版标签生成仍然主要依赖 Java 关键词规则，例如命中 `java` 就生成 `Java 后端开发`，命中 `日语` 就生成 `日语沟通`。

这样虽然稳定，但标签不够智能：同一类能力的不同表达难以自然归并，活动的真实含义也不能被充分理解。用户提出的方向是让 LLM 根据活动内容、挑战目标、`did` 和 `learned` 生成能力标签，后端负责校验和落库。

### 原因

固定规则适合作为 fallback，但不适合作为最终的智能成长标签方案：

1. 规则只能覆盖预设关键词，无法理解“我做了什么”和“我学到了什么”的语义。
2. 标签归并依赖固定 normalizedName，面对新能力方向时不够自然。
3. 里程碑识别只能靠用户手动标记，无法自动识别证书、比赛、项目上线、长期挑战完成等重要节点。
4. 标签生成过程缺少 Agent 日志，后续 badcase 不容易定位到底是 LLM 没返回、返回格式坏了，还是规则兜底接管了。

### 解决方式

把成长标签生成升级为“LLM 抽取 Agent + Java 校验 + 规则兜底”的结构：

1. `GrowthTagService` 注入 `OpenAiCompatibleLlmClient`、`AgentRunService` 和 `ObjectMapper`。
2. 用户完成活动、挑战或更新复盘后，系统先启动 `GROWTH_TAG_EXTRACTION` 类型的 Agent run。
3. 如果大模型可用，`GROWTH_TAG_EXTRACTION` step 会把成就记录和已有标签列表传给 LLM，请它返回 1-3 个结构化标签。
4. LLM 输出只负责建议 `name`、`normalizedName`、`description`、`scoreDelta`、`evidenceSummary`、`milestone` 和 `milestoneReason`。
5. Java 后端负责校验标签数量、归一化 normalizedName、限制文本长度、限制分数范围、去重，并最终写入 `growth_tags` 和 `growth_tag_evidences`。
6. 如果 LLM 没配置、超时、返回空或格式不合法，系统自动进入 `RULE_FALLBACK` step，继续使用原本的关键词规则生成标签，保证主流程不断。
7. 如果 LLM 判断某条经历是证书、比赛获奖、项目上线或长期挑战完成，可以自动把证据标记为 milestone；用户仍然可以通过接口手动修改。

### 结果

代码已编译通过。现在个人成就模块的标签生成不再只是固定规则，而是优先使用 LLM 理解真实经历，再由 Java 做边界控制和持久化。Agent 日志会记录每次标签抽取是模型成功还是规则兜底，便于后续面试中讲“可观测 Agent 工作流”和 badcase 定位。

当前仍未完成的部分是前端能力地图、标签详情里程碑视图，以及智能简历对高分标签/重要里程碑的优先引用，这些可以作为后续阶段继续推进。

## 015. 个人成就前端仍以类别统计和折线图为主，无法直观看到能力标签

### 发生了什么?

后端已经具备 `growth_tags` 和 `growth_tag_evidences`，也已经接入 LLM 标签抽取 Agent，但前端个人成就页仍然主要展示类别统计条形图和旧版成长折线图。用户真正想看到的是“我现在沉淀出了哪些能力”，而不是几条固定维度的折线。

这会导致一个问题：后端已经产生了智能能力标签，但用户在页面上感知不到这套新能力。个人成就模块仍然像统计面板，而不像“成长地图”。

### 原因

前端最早是围绕固定类别和固定成长维度做的：

1. 左侧参与分析只展示活动类别数量。
2. 自我分析里的成长曲线仍然基于前端固定 `growthDimensions` 规则生成。
3. 新增的后端能力标签接口 `/api/achievements/growth-tags` 尚未被前端消费。
4. 历史数据可能还没生成 growth tags，如果页面自动 rebuild，可能触发批量 LLM 调用，存在 token 成本和等待时间问题。

### 解决方式

先完成能力地图的第一版前端落地，不做标签详情页和智能简历改造：

1. 在个人成就左侧面板新增“能力地图 / 智能标签”区域。
2. 前端启动后登录用户会拉取 `/api/achievements/growth-tags`，保存为 `growthTags`。
3. 能力标签按照 `importanceScore`、`score`、`evidenceCount` 排序，优先展示重要且证据多的标签。
4. 标签大小由 `score` 决定，重要标签通过边框强调，避免继续使用难读的多条折线。
5. 点击标签后，在地图下方展示该标签的描述、累计分数、证据数和重要性分。
6. 新增“刷新能力地图”按钮，手动调用 `/api/achievements/growth-tags/rebuild`，用于旧数据回填或测试 LLM 标签抽取。
7. 保存历史复盘后，前端会重新拉取 growth tags，让地图及时反映新的 `did` / `learned` 内容。
8. 移动端做了响应式处理，头像和标签改为纵向布局，避免标签挤压或重叠。

### 结果

前端 JS 静态语法检查已通过。现在个人成就页能直接展示后端智能能力标签：用户一进入模块就可以看到自己的能力地图，点击标签能看到基础解释和指标。

当前仍未完成的是下一阶段：标签详情页的里程碑时间线，以及智能简历优先引用高分标签和重要里程碑。

## 016. 能力地图只有标签概览，缺少可追溯的成长证据时间线

### 发生了什么?

第 015 条优化后，个人成就页已经可以展示智能能力标签地图，用户能看到某个能力标签的累计分数、证据数量和重要性分。但点击标签后只能看到基础描述，不能继续看到“这个能力到底是由哪些活动、挑战、复盘一步步积累起来的”。

这会让能力地图更像一个漂亮的统计结果，而不是可追溯的成长档案。用户原本提出的目标是：点进一个标签后，能看到类似公路里程碑的轨迹，知道哪天做了什么、学到了什么、哪些节点比较重要。

### 原因

前端第一版能力地图只接入了 `/api/achievements/growth-tags` 列表接口，这个接口只返回标签概览，不返回证据明细。后端其实已经提供了：

- `GET /api/achievements/growth-tags/{tagId}`
- `PUT /api/achievements/growth-tags/evidences/{evidenceId}/milestone`

但前端还没有使用这两个接口，所以无法展示证据时间线，也无法让用户手动标记重要里程碑。

### 解决方式

把能力地图从“只看标签概览”升级成“标签详情 + 成长里程碑时间线”：

1. 点击能力标签时，前端会懒加载 `/api/achievements/growth-tags/{tagId}`，读取该标签下的证据列表。
2. 标签详情区展示该标签的描述、累计分数、证据数量和重要性分。
3. 证据按发生时间排列，渲染成纵向“成长里程碑”时间线。
4. 每个节点展示日期、标题、证据摘要、分数增量、用户写的 `did` / `learned`，以及里程碑原因。
5. 重要节点使用更醒目的颜色和星标展示。
6. 每个证据节点支持“标记里程碑 / 取消里程碑”，调用后端 `PUT /api/achievements/growth-tags/evidences/{evidenceId}/milestone`。
7. 标记里程碑后，前端会重新拉取标签列表和当前标签详情，让能力地图的 `importanceScore` 和时间线状态同步更新。
8. 标签详情采用懒加载缓存，避免一次性拉取所有标签的证据明细。

### 结果

前端 JS 静态语法检查已通过。现在能力地图不只是展示标签大小，还能点进具体标签查看成长证据时间线，并且可以手动把关键经历标记为里程碑。

当前仍未完成的是下一阶段：智能简历优先引用高分能力标签、重要里程碑和用户复盘内容。

## 017. 智能简历没有优先引用能力标签和里程碑证据

### 发生了什么?

个人成就模块已经完成了能力标签、标签详情和里程碑时间线，但智能简历生成仍然主要依赖历史记录、成就统计和用户画像。也就是说，页面上已经能看到“Java 后端开发 多少分、几条证据、哪些里程碑”，但 AI 生成简历时还没有明确优先使用这些证据。

这会削弱智能简历的可信度：简历文字可能看起来流畅，但和能力地图里的高分标签、重要里程碑没有形成强绑定。

### 原因

早期 `/api/ai/self-analysis` 的上下文主要包含：

1. `achievementSummary`：完成数量、类别数量、成长曲线数据。
2. `studentMemory`：用户画像摘要、优势、偏好和近期信号。
3. `history`：最近若干条完成记录。

这些数据足够生成一般总结，但不够支撑“证据驱动的智能简历”。后续新增的 `growth_tags` 和 `growth_tag_evidences` 没有进入 self-analysis 链路，所以 LLM 不知道哪些能力标签分数更高，也不知道哪些经历被标记为重要里程碑。

### 解决方式

把智能简历升级为“历史记录 + 用户画像 + 能力标签 + 里程碑证据”的综合生成：

1. `AiService` 注入 `GrowthTagRepository` 和 `GrowthTagEvidenceRepository`。
2. 新增 `ResumeEvidencePack`，在生成智能简历前组装三类证据：
   - `topTags`：按 `importanceScore`、`score`、`evidenceCount` 排序的高价值能力标签。
   - `milestoneEvidence`：用户或 LLM 标记的重要里程碑证据。
   - `recentEvidence`：最近的成长证据，用于没有里程碑时兜底。
3. LLM prompt 明确要求优先使用 `resumeEvidence.topTags`、`resumeEvidence.milestoneEvidence` 和用户复盘内容。
4. prompt 限制模型不能编造奖项、证书、组织、项目数量；没有 milestone 时只能引用 topTags 和 recentEvidence。
5. 本地规则 fallback 也同步升级：没有 LLM 时，简历 summary 和 bullets 会优先引用高分标签、证据数量、重要里程碑或最近证据。
6. 前端接口结构不变，仍然返回 `summary`、`resumeBullets`、`strengths`、`suggestions`，所以不需要改前端展示。

### 结果

代码已编译通过。现在智能简历不再只是“总结历史记录”，而是会优先引用能力地图里的高分标签、证据数量和里程碑节点。这样个人成就模块形成了完整闭环：

`完成活动/挑战 -> LLM 抽取成长标签 -> 证据进入能力地图 -> 用户标记里程碑 -> 智能简历优先引用高价值证据`

这条链路比单纯的 AI 文本总结更适合面试表达：它体现了结构化证据沉淀、LLM 生成边界控制、fallback 兜底和面向简历生成的证据优先级设计。

## 018. 能力地图视觉仍偏静态，标签强弱不够直观

### 发生了什么?

能力地图已经可以展示智能标签、分数、证据数量和里程碑，但第一版视觉仍然更像普通标签云：头像在左侧，标签在右侧排列。用户原本设想的是“头像旁边围绕一圈能力泡泡”，分数越高的能力越大，强弱在视觉上更明显。

这不是功能缺失，而是体验表达不够贴合产品设想。对于个人成长画像这种模块，静态标签云可以展示数据，但不如泡泡式能力地图直观。

### 原因

前端最初优先保证数据链路稳定，所以采用简单 flex 标签布局。它的优点是实现稳、兼容性强，但有几个不足：

1. 标签大小差异不明显，用户不能一眼看出主能力和弱能力。
2. 头像与能力标签的关系不够强，看起来像普通列表。
3. 页面缺少进入动画和轻微动态反馈，整体仍偏静态。
4. 如果直接照搬 App 风格到 Web，需要额外处理桌面和手机的响应式布局。

### 解决方式

在不引入前端框架、不新增文件的前提下，把能力地图升级为响应式泡泡布局：

1. 桌面端使用中心头像 + 环绕能力泡泡。
2. JS 根据标签分数计算 `bubble-size`、位置和强度等级。
3. 分数越高，泡泡越大；低分标签颜色更轻，高分标签颜色更深。
4. 里程碑相关标签保留金色边框提示。
5. 泡泡出现时加入进入动画，并有轻微浮动效果，让页面更“活”。
6. 手机端自动降级成两列泡泡网格，避免小屏环绕布局拥挤。
7. 支持 `prefers-reduced-motion`，用户系统关闭动画时不强行动效。

### 结果

前端 JS 静态语法检查已通过。现在能力地图更接近“个人能力星图”：用户头像在中心，能力标签以泡泡方式围绕展示，强能力更大、更醒目，弱能力更轻，手机端也能保持可读和稳定。
## 019. 缺少异步消息队列底座，耗时任务仍和请求链路耦合

### 发生了什么问题

项目已经有 OpenSearch 索引同步、成长标签抽取、用户画像刷新、Coach 长期记忆等多个适合异步化的任务，但当前主要还是同步 service 调用或定时扫描。后续如果把 LLM 标签抽取、索引重建、画像刷新都放在用户请求线程里，接口响应会变慢，也不利于失败重试和任务削峰。

### 原因

后端之前只有 MySQL、Redis、OpenSearch 这些存储/检索组件，没有消息队列。业务模块之间只能直接调用，导致“用户触发操作”和“后续衍生任务”绑定得比较紧。比如完成活动后，理论上应该可以异步触发成长标签抽取、用户画像刷新和搜索索引更新，但没有 MQ 底座时，这些任务只能同步执行、定时补偿，或者暂时不做。

### 解决方式

先完成 RabbitMQ 基础设施接入，不急着迁移业务逻辑：

1. 在 `pom.xml` 中加入 `spring-boot-starter-amqp`。
2. 在 `docker-compose.yml` 中加入 `rabbitmq:3.13-management` 服务，暴露 `5672` 消息端口和 `15672` 管理后台。
3. 在 `application.yml` 中加入 RabbitMQ 连接配置和 `app.mq` 业务交换机/队列配置。
4. 新增 `mq` 包，声明统一 Topic Exchange、三条 durable queue 和 routing key：
   - `do-not-miss.event-index`：后续用于事件创建/更新后的 OpenSearch 索引同步。
   - `do-not-miss.growth-tag`：后续用于完成活动/挑战后的成长标签抽取。
   - `do-not-miss.user-profile`：后续用于用户画像刷新。
5. 新增 `DomainEventPublisher` 和消息结构，先提供统一发布入口，后续业务模块只需要发布领域事件，不需要关心 RabbitMQ 细节。
6. 更新 `.env.example` 和部署指南，补充 RabbitMQ 地址、账号和管理后台。

### 结果

`mvn -q -DskipTests compile` 已通过。当前版本已经具备消息队列基础配置，但业务链路还没有迁移为异步消费；下一步可以优先把 OpenSearch 事件索引同步迁移到 MQ，因为它最典型：事件创建/更新成功后发布消息，消费者异步写入 OpenSearch，失败时可以重试或后续补偿。
## 020. OpenSearch 索引同步和事件请求链路强耦合

### 发生了什么问题

事件创建和删除时，后端原本会在 `EventService` 里直接调用 `EventSearchIndexService.index/delete`。这意味着用户发布事件时，不仅要等 MySQL 写入，还要等 OpenSearch 索引写入，甚至在向量检索开启时还可能等待 embedding 生成。

这个设计在数据少的时候能跑，但后续会有几个问题：

1. OpenSearch 慢或者短暂不可用时，事件发布请求会被拖慢。
2. 向量索引需要调用 embedding 模型，模型接口耗时会进入用户请求链路。
3. 索引写入发生在数据库事务提交之前，如果数据库提交失败，OpenSearch 可能已经写入了脏索引。
4. 索引失败只是打日志，没有队列重试，后续只能依赖手动 `/api/ai/retrieval/reindex` 做全量补偿。

### 原因

早期为了快速打通检索能力，事件模块和检索索引模块采用了直接 service 调用。`EventService.create` 保存事件后立即同步调用 `eventSearchIndexService.index(saved)`，`EventService.delete` 删除事件后立即调用 `eventSearchIndexService.delete(eventId)`。

这种方式简单，但本质上把主业务写库和搜索引擎同步绑在了同一个请求线程里。搜索引擎属于派生数据，适合异步最终一致；如果和主链路强耦合，会牺牲接口响应、容错和后续扩展空间。

### 解决方式

把 OpenSearch 索引同步改成 RabbitMQ 异步链路：

1. `EventService` 不再直接调用 `EventSearchIndexService`。
2. 事件创建成功后，注册事务提交后回调，发送 `EVENT_INDEX_UPSERT` 消息。
3. 事件删除成功后，注册事务提交后回调，发送 `EVENT_INDEX_DELETE` 消息。
4. 新增 `EventSearchIndexMessageListener`，监听 `do-not-miss.event-index` 队列。
5. 消费者收到 `UPSERT` 后只使用消息里的 `eventId`，重新从 MySQL 查询已提交事件，再写入 OpenSearch。
6. 消费者收到 `DELETE` 后异步删除 OpenSearch 中对应文档。
7. `EventSearchIndexService` 保留原来的容错版 `index/delete`，同时新增 `indexByIdOrThrow/indexOrThrow/deleteOrThrow`，让 MQ 消费失败时能抛出异常并触发监听器重试。
8. RabbitMQ listener 配置了最多 3 次重试，并关闭失败后的无限 requeue，避免 OpenSearch 长时间不可用时消息反复打爆消费者。

### 结果

`mvn -q -DskipTests compile` 和 `docker compose config --quiet` 已通过。现在事件发布/删除的主链路只负责写 MySQL 和发送 MQ 消息，OpenSearch 索引写入由后台消费者异步完成。

当前版本实现的是“异步最终一致”：短时间内可能出现 MySQL 已更新但 OpenSearch 还没同步完的情况，通常是可以接受的。更强的可靠性可以在下一阶段继续加 Outbox 表：先把待发送消息写入数据库，再由后台任务投递 MQ，从而解决“数据库已提交但 MQ 发布失败”的极端情况。
## 021. 成长标签抽取同步执行，LLM 耗时进入用户操作链路

### 发生了什么问题

个人成就模块已经支持智能成长标签：用户完成活动、完成挑战、更新历史复盘后，系统会根据活动内容、挑战目标、`did`、`learned` 等信息抽取能力标签，并写入 `growth_tags` 和 `growth_tag_evidences`。

但在之前的实现里，`AchievementService` 会直接调用 `GrowthTagService.upsertFromRecord(record)`。这意味着用户完成活动或保存复盘时，接口不仅要写成就记录，还要同步执行 LLM 标签抽取、格式校验、fallback、标签聚合刷新和 Agent 日志写入。

数据少时问题不明显，但一旦 LLM 响应变慢，用户点击“完成”或“保存复盘”的接口就会被拖慢。

### 原因

成长标签属于派生数据，不是完成活动/挑战的主事务。主事务真正需要保证的是：

1. 活动或挑战状态正确更新。
2. `achievement_records` 正确落库。
3. 用户画像被标记为需要刷新。

而成长标签抽取更像后台分析任务：它依赖 LLM 或本地规则，耗时不稳定，可以允许短暂延迟，并且可以通过重试或手动 rebuild 补偿。早期为了快速打通能力地图，直接同步调用最简单，但会让主链路和 AI 派生任务耦合。

### 解决方式

把成长标签抽取改成 RabbitMQ 异步链路：

1. `AchievementService` 不再直接依赖 `GrowthTagService`。
2. 完成活动生成成就记录后，事务提交成功再发布 `GrowthTagExtractionMessage`。
3. 完成挑战或更新挑战复盘后，同样发布成长标签抽取消息。
4. 用户在历史记录里更新 `did/learned` 后，也发布成长标签抽取消息，让能力地图后续自动刷新。
5. 新增 `GrowthTagExtractionMessageListener`，监听 `do-not-miss.growth-tag` 队列。
6. 消费者收到消息后，只使用 `recordId + userId` 回查 `achievement_records`，再调用 `GrowthTagService.upsertFromRecordId` 执行抽取。
7. 原有 `GrowthTagService.upsertFromRecord` 保留，内部仍然使用 “LLM 抽取 Agent + Java 校验 + 规则 fallback + Agent 日志”。
8. `/api/achievements/growth-tags/rebuild` 仍然保留同步执行，作为手动补偿和历史数据回填入口。

### 结果

`mvn -q -DskipTests compile` 已通过。现在用户完成活动、完成挑战或保存复盘时，主请求只负责写成就记录并投递 MQ 消息；真正的 LLM 成长标签抽取在后台消费者中完成。

这带来的变化是：能力地图可能会比用户操作晚几秒刷新，属于异步最终一致。如果 RabbitMQ 或 LLM 暂时不可用，主业务不被阻断；后续仍可通过手动 rebuild 或未来的 Outbox/失败重试表做补偿。
## 022. Coach 长期记忆复习目前是后端编排式检索，不是标准 Tool Calling

### 发生了什么问题？

用户追问 Coach 抽取和复习知识的逻辑是否算一种工具：LLM 是否需要通过接口去拿知识卡片。

经确认，当前实现可以稳定完成“日志 -> 长期记忆卡片 -> 到期复习 -> 教练提问”的闭环，但它并不是严格意义上的 Agent Tool Calling。现在的流程是后端在每次 Coach 对话前主动查询 `coach_memory_reviews` 表，把到期的知识卡片作为 `dueLongTermMemories` 注入 prompt，再由 LLM 根据这些上下文生成回复。

也就是说，LLM 目前没有自己选择并调用 `getDueMemoryReviews` 这类工具，知识检索是由后端确定性执行的。

### 原因

MVP 阶段优先保证功能稳定和数据边界可控，所以采用了后端编排式 Memory Retrieval：

1. 用户生成日志后，后端把日志沉淀为 `coach_memory_reviews` 复习卡片。
2. 每次 Coach 对话前，后端调用 `dueMemoryReviews(userId)` 查询到期卡片。
3. 后端把卡片压进 prompt 的 `dueLongTermMemories`。
4. LLM 只负责根据上下文提问、解释、追问或复习。

这种方式可控性强，能避免 LLM 随意查询数据库，也更容易保证用户隔离和权限边界。但缺点是 Agent 味道不够强：LLM 没有显式工具选择过程，面试表达时不能把它说成“标准 tool use”，更准确的说法应该是“后端编排式长期记忆检索”或“Memory Retrieval 注入”。

### 解决方式 / 后续优化方向

当前不需要立即修改功能，但后续可以升级成真正的工具化记忆检索：

1. 抽象 `CoachMemoryTool`，提供 `getDueMemoryReviews(userId)`、`searchMemoryCards(userId, query)`、`markReviewUsed(memoryId)` 等工具接口。
2. Coach Agent 先判断用户意图：普通技术问答、日志复盘、主动复习、回答复习题。
3. 只有在需要复习或需要查历史经验时，才调用记忆工具。
4. 工具返回结构化知识卡片，包括 `title`、`memoryText`、`tags`、`reviewCount`、`nextReviewAt`、`sourceLogId`。
5. LLM 根据工具结果生成回复，后端再记录工具调用日志，方便排查 bad case。
6. 如果继续增强，可以把一篇日志拆成多张更细的知识卡片，例如“一个知识点 / 一个经验 / 一个坑”一张卡，提升复习精准度。

面试表达上可以这样讲：

> 当前版本没有让 LLM 直接访问数据库，而是由后端在对话前完成长期记忆召回，再把到期复习卡片作为上下文注入给 Coach。这样做是为了保证权限、稳定性和可控性。后续可以把这层封装成 Coach Memory Tool，让 Agent 根据意图决定是否调用，从后端编排式 Memory Retrieval 演进到真正的 Tool Calling。

## 023. 用户画像刷新仍依赖定时扫描，画像生成链路不够及时

### 发生了什么问题？

用户画像已经支持 LLM 生成快照，并且完成活动、完成挑战、更新历史复盘、生成 Coach 日志后都会把画像快照标记为 dirty。但是在本次优化前，dirty 快照主要依赖 `UserProfileSnapshotRefreshJob` 定时扫描刷新。

这意味着用户刚完成一个活动或刚写完日志后，画像不会立刻异步生成，只能等下一轮定时任务。对功能来说能跑，但对推荐、计划生成、Coach 个性化这些依赖画像的 Agent 链路来说，更新不够及时，也不太适合面试里讲“消息驱动的异步画像抽取”。

### 原因

RabbitMQ 的 `user-profile` 队列、routing key 和 `UserProfileRefreshMessage` 其实已经在基础设施里预留，但业务侧还没有完整接上：

1. `UserProfileSnapshotDirtyService.markDirty` 只负责把 `user_profile_snapshots.dirty` 置为 true。
2. 没有在事务提交后投递用户画像刷新消息。
3. 后端缺少监听 `do-not-miss.user-profile` 队列的消费者。
4. 定时任务可以兜底，但不适合承担主要实时刷新职责。

### 解决方式

把用户画像刷新改为 RabbitMQ 异步触发，同时保留定时任务作为兜底：

1. `UserProfileSnapshotDirtyService` 注入 `DomainEventPublisher`。
2. `markDirty(userId, reason)` 写入 dirty 快照后，在事务提交成功后发布 `UserProfileRefreshMessage`。
3. 新增 `UserProfileRefreshMessageListener`，监听 `${app.mq.queues.user-profile}`。
4. 消费端收到消息后调用 `UserMemoryService.refreshSnapshot(userId)`，重新读取用户完成记录、挑战、Coach 日志，并生成新的画像快照。
5. 不同触发点写入不同 reason，便于后续排查：
   - `completed-event`
   - `completed-challenge`
   - `updated-completed-challenge`
   - `updated-achievement-reflection`
   - `coach-log-saved`
6. 原有 `UserProfileSnapshotRefreshJob` 保留，如果 MQ 或 LLM 临时不可用，dirty 快照仍可被定时任务补偿刷新。

### 结果

`mvn -q -DskipTests compile` 已通过。现在用户画像链路变成：

`用户完成活动/挑战/写复盘/生成日志 -> 标记画像 dirty -> 事务提交后投递 MQ -> user-profile 消费者异步刷新画像快照`

这样主业务接口只负责写核心数据和投递消息，LLM 画像生成不再依赖定时扫描，也不会阻塞用户操作。面试表达上可以强调：这是一个“消息驱动 + 最终一致 + 定时任务兜底”的用户画像抽取链路。

## 024. MQ 发布缺少 Outbox，业务提交成功后仍可能丢失异步消息

### 发生了什么问题？

项目已经把 OpenSearch 索引同步、成长标签抽取、用户画像刷新迁移到了 RabbitMQ 异步链路。但在本次优化前，业务代码是在事务提交后直接调用 RabbitMQ 发布消息。

这个设计比同步执行 LLM/索引任务更好，但仍然存在一个可靠性缝隙：如果 MySQL 业务事务已经提交成功，而应用进程在 `afterCommit` 回调执行前崩溃，或者 RabbitMQ 当时不可用，消息可能只会写日志，不会被可靠保存。结果就是：

1. 活动已经发布，但 OpenSearch 索引没有同步。
2. 成就记录已经生成，但成长标签没有抽取。
3. Coach 日志已经保存，但用户画像没有刷新。

这些都属于“派生数据最终一致”问题，短期可以靠手动 rebuild 或定时任务兜底，但工程上缺少可靠消息投递闭环。

### 原因

原来的 `DomainEventPublisher` 直接调用 `RabbitTemplate.convertAndSend`。虽然发布动作通常放在事务提交后执行，避免了“业务回滚但消息已发”的问题，但它没有解决反方向问题：

`业务事务提交成功 -> 应用崩溃 / MQ 发布失败 -> 消息没有持久化 -> 后台任务永远不知道要补偿`

RabbitMQ 自身只能保证已经进入 broker 的消息可靠，不能保证应用端一定能把“该发的消息”成功发出去。因此需要在业务数据库里先记录待发送消息。

### 解决方式

引入 Outbox Pattern：

1. 新增 Flyway 迁移 `V11__add_domain_event_outbox.sql`，创建 `domain_event_outbox` 表。
2. `DomainEventPublisher` 不再直接发布 RabbitMQ，而是在当前业务事务内把消息写入 outbox 表。
3. outbox 记录包含：
   - `exchange_name`
   - `routing_key`
   - `payload_type`
   - `payload_json`
   - `status`
   - `attempts`
   - `next_attempt_at`
   - `last_error`
   - `sent_at`
4. 新增 `DomainEventOutboxDispatcher` 定时扫描 `PENDING/FAILED` 且到达重试时间的消息。
5. Dispatcher 反序列化 payload 后投递 RabbitMQ，成功则标记 `SENT`。
6. 投递失败则增加 `attempts`，记录 `last_error`，并按指数退避设置下一次重试时间。
7. 最大重试次数默认 8 次，可通过 `.env` 配置。
8. 原本三个异步方向都统一走 Outbox：
   - event index
   - growth tag extraction
   - user profile refresh

### 结果

`mvn -q -DskipTests compile` 已通过。现在消息链路从：

`业务事务提交后直接发 RabbitMQ`

升级为：

`业务数据 + outbox 消息同事务落库 -> Dispatcher 异步投递 RabbitMQ -> 失败留表重试 -> 成功标记 SENT`

这条链路解决了“数据库提交成功但 MQ 发布失败导致消息丢失”的核心问题。它的语义是至少一次投递，所以消费者需要保持幂等；当前消费者大多通过 `eventId`、`recordId`、`userId` 回查并 upsert，适合这种模型。

面试表达上可以重点讲：Outbox 牺牲了一点实时性和实现复杂度，换来了业务事务与消息投递之间的可靠衔接，是异步系统里典型的最终一致性设计。

## 025. 已过期活动仍然混在事件列表和 AI 召回候选中

### 发生了什么问题？

活动原本只有 `start_time` 和 `end_time`，没有持久化的过期状态。这样前端列表、普通搜索、AI 推荐、OpenSearch 召回都需要各自判断“这个活动是不是已经结束”。如果只在前端按时间隐藏，数据库里仍然看不出活动状态，AI 召回也可能从旧索引里拿到已经过期的活动。

### 原因

过期判断属于业务状态，但之前只作为临时计算逻辑存在。尤其在接入 OpenSearch 和异步索引后，如果活动过期但索引没有及时删除，推荐链路仍可能把它当成候选活动。

### 解决方式

1. 新增 Flyway 迁移 `V12__add_event_expired_flag.sql`，为 `events` 增加 `expired` 字段，并把 `end_time < NOW(6)` 的历史活动回填为过期。
2. 新增 `EventExpirationService`，启动后定时扫描过期活动，把 `expired=false && end_time < now` 的活动标记为过期。
3. 标记过期时写入事件索引删除消息，让 OpenSearch 旧文档通过 MQ/Outbox 异步删除。
4. 普通事件搜索默认增加 `expired=false` 条件。
5. AI 混合召回在拿到 OpenSearch 候选后再次过滤 `expired=false`，避免旧索引污染推荐结果。
6. 前端 `getStudentEvents`、`getSocialEvents` 和本地 AI fallback 增加兜底过滤，避免后端不可用时本地缓存里的过期活动继续展示。

### 结果

现在事件链路变成：

`活动结束 -> 定时任务/查询前扫描 -> 数据库标记 expired=true -> 发送索引删除事件 -> 前端列表和 AI 推荐只使用未过期活动`

这比单纯前端隐藏更可靠，因为数据库、搜索索引、推荐候选和 UI 展示的状态会逐步收敛到一致。
