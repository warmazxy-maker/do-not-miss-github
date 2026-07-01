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

## 026. 活动发布缺少审核前信息质量预处理，推荐和成长分析依赖粗糙原始文本

### 发生了什么问题？

活动由社会端上传后，原先几乎是直接进入事件列表和 OpenSearch 推荐池。这样有两个问题：

1. 活动信息质量不可控，描述过短、收益不清、目标人群不清的活动也可能进入学生端。
2. 推荐和成长标签主要依赖标题、内容、技能字段等原始文本，缺少“适合谁、能学到什么、会提升哪些能力”的结构化理解。

用户提出的优化方向是：活动发布本来就应该有审核流程，可以利用“正在审核中”这段时间做 AI 质量预处理；预处理结果不仅服务审核，也要反馈到能力值和成长分析里。

### 原因

之前项目只对“学生搜索请求”做 Query Rewrite 和检索增强，但对“活动数据本身”没有做入库前后的结构化治理。

也就是说，用户侧查询已经被整理成结构化意图，但活动侧仍然主要是原始文本。两端信息粒度不对齐，会导致推荐、RAG 召回、成长标签抽取都要反复从脏文本里猜含义。

### 解决方式

新增 Event Quality Agent 链路：

1. 为 `events` 增加 `review_status`，活动创建后默认进入 `PENDING_REVIEW`，不立刻进入学生端推荐池。
2. 新增 `event_quality_reports` 表，保存质量分、审核建议、难度、摘要、适合人群、前置要求、学习产出、结构化标签、能力影响、风险点、缺失字段、疑似重复活动等结果。
3. 新增 `event-quality` RabbitMQ 队列和 `EventQualityAnalysisMessage`，活动创建后通过 Outbox 异步投递质量分析任务。
4. 新增 `EventQualityService`：优先调用 LLM 生成结构化质量报告，模型不可用时用本地规则兜底。
5. 质量 Agent 根据结果自动把活动标记为：
   - `APPROVED`：允许进入学生端和 OpenSearch
   - `NEEDS_REVISION`：需要修改，暂不展示
   - `REJECTED`：拒绝，触发索引删除
6. OpenSearch 索引只收录 `APPROVED && !expired` 的活动，并把质量报告中的 `learningOutcomes`、`extractedTags`、`abilityImpacts`、`targetStudents` 一并写入检索文档。
7. 成长标签抽取在学生完成活动后，会优先参考质量报告里的 `abilityImpacts`，再结合原始活动文本和用户复盘内容抽取标签。
8. 新增管理接口用于查看质量报告、重新分析、人工通过、人工拒绝，为后续管理端审核页面预留能力。

### 结果

事件发布链路从：

`上传活动 -> 直接展示/索引 -> 推荐和成长标签都读原始文本`

升级为：

`上传活动 -> PENDING_REVIEW -> Outbox -> RabbitMQ -> Event Quality Agent -> 质量报告落库 -> 通过后写 OpenSearch -> 完成活动后成长标签引用能力影响`

这让项目从“用户请求侧智能”向“数据生产侧智能治理”前进了一步。面试时可以把它讲成信息质量预处理 Agent：它解决的不是单个推荐 prompt，而是活动数据进入系统前的质量、结构化、审核和后续派生数据一致性问题。

用户的原始想法偏产品流程：利用审核等待期做 AI 预处理，并把结果反哺能力值；实现方案在此基础上补了工程链路：`review_status + event_quality_reports + Outbox/MQ + Agent Run 日志 + OpenSearch enrichment + 成长标签引用 abilityImpacts`。

验证：`mvn -q -DskipTests compile` 已通过。

## 027. 向量召回直接使用富文本 allText，可能导致核心语义被冗余字段稀释

### 发生了什么问题？

在活动质量预处理 Agent 接入后，OpenSearch 文档里加入了适合人群、前置要求、学习产出、能力影响、风险点等结构化字段。这样 BM25 关键词检索更容易命中，但如果把这些字段全部拼进同一个 embedding 文本，就可能出现语义稀释。

例如用户输入“我想学 Java”，一个活动本来有“Java 开发”“项目设计”等强相关信息，但如果同一个向量文本里又混入大量“适合初级学生、团队协作、线上参与、风险点、缺失字段”等信息，embedding 会把整段文本压成一个混合语义向量，核心的 Java 语义可能被冲淡。

### 原因

BM25 和向量召回适合吃的文本不同：

1. BM25 可以吃更丰富的字段，因为它有字段权重，适合精确命中关键词。
2. Embedding 更适合吃短而干净的核心语义文本，文本越杂，越容易把主题向量拉偏。

之前 OpenSearch 文档用 `allText` 同时承担 BM25 扩展检索和 embedding 生成，导致“关键词检索文本”和“语义向量文本”没有分工。

### 解决方式

在 `EventSearchIndexService` 中拆分两类文本：

1. `allText`：继续给 BM25 使用，保留标题、组织、分类、地点、内容、收益、技能、适合人群、前置要求、学习产出、核心标签、能力影响等丰富字段。
2. `semanticText`：新增语义向量专用文本，只保留标题、核心分类、核心技能、活动摘要、学习产出、核心标签、能力影响。
3. 风险点、缺失字段、重复活动 ID 等审核辅助信息不进入 `semanticText`。
4. embedding 生成从原来的 `embedding(allText)` 改为 `embedding(semanticText)`。
5. BM25 multi-match 字段中加入 `semanticText^2`，让这份干净语义文本也能参与关键词检索，但不替代原有丰富字段。

### 结果

现在检索链路变成：

`BM25 -> 使用 allText 和多字段权重，负责精确关键词命中`

`Vector -> 使用 semanticText，负责干净主题语义召回`

这让系统不再粗暴地把所有字段塞进同一个向量，而是让关键词召回和语义召回各自使用适合自己的文本。面试时可以讲成“为避免 embedding 语义稀释，将检索文档拆成 search text 与 semantic text，BM25 负责精确命中，向量负责主题泛化召回，结构化字段和 rerank 负责最终筛选”。

验证：`mvn -q -DskipTests compile` 已通过。

## 028. 成长能力评分由 LLM 直接给分，缺少闭合尺度、职责边界与公平性契约

### 发生了什么问题？

当前 `GrowthTagService` 会让 LLM 直接返回 `scoreDelta=3..10`，然后把每条成长证据的分数简单累加到标签总分。早期这样可以快速展示能力地图，但随着能力分准备面向学生甚至企业展示，这种实现存在明显风险：

1. 同一份经历重复调用模型，可能得到不同分数。
2. 标签累计分、当前能力水平和画像可信度没有被严格区分。
3. 活动难度使用 `0..100`，而累计分可以持续增长，两个不同尺度不能直接进行 IRT 式难度比较。
4. 原 Skill 的 `profile_confidence` 倍率为 `0.40..1.30`，容易形成强者恒强的反馈循环。
5. 公司实习、个人项目、证书和代码仓库的来源可信度与实际成长价值混在一起，容易把机构背景误当成能力。
6. LLM 同时负责理解证据和计算最终分数，缺少确定性、幂等性、版本管理和审计能力。

### 原因

原实现把“读懂经历”和“执行评分公式”合并在同一个 Prompt 中。LLM 擅长从非结构化材料中识别难度、个人贡献、完成质量和能力关联，但不适合成为最终数学裁判：

- 模型输出具有随机性。
- 规则升级后难以复现旧分数。
- 来源可信度、证据质量等相关因素直接相乘会重复扣分。
- 缺少证据哈希、Prompt 版本、评分规则版本和历史快照，无法完整解释一分从哪里来。

### 用户参与的设计修正

用户明确提出并保留了以下产品规则：

1. `profile_confidence` 只能通过考官测试或人工审核改变。
2. 用户活动描述与真实能力明显不符时，考官测试可以降低置信度，从而让系统在后续评分中更谨慎。
3. 原置信度影响幅度过大，需要明显收窄。
4. 公司实习应保留比普通个人自述更高的默认来源可信度。
5. 个人产出不能天然低于公司实习；高质量个人项目可以凭公开仓库、可运行 Demo、测试、提交记录和明确个人贡献获得更高评价。

### 解决方式

完成 `IRT_sys_agent Scoring System v2.0` 第一阶段规则定稿：

1. 将评分状态拆分为：
   - `experience_score`：累计成长证据，可持续增长。
   - `ability_score`：`0..100` 的当前能力估计，不能简单累加。
   - `profile_confidence`：`0..1` 的画像可信度，只能由 Judge 或人工审核更新。
   - `ability_uncertainty`：能力估计的不确定度。
2. 将置信度倍率从原来的 `0.40..1.30` 收窄为 `0.85..1.10`，每次 Judge 测试最多调整 `0.08`。
3. 拆分“成长价值”和“验证强度”：
   - 成长价值看难度匹配、完成质量、能力相关度、个人贡献和新颖度。
   - 验证强度看来源可验证性、证据质量、贡献清晰度和一致性。
4. 保留公司实习较高的来源可信度先验，但禁止把公司名称或机构名气直接作为成长分倍率。
5. LLM 改为 Evidence Assessment Agent，只输出结构化因素和证据引用，不再输出最终 `scoreDelta`。
6. 最终得分、上下限、重复衰减、置信度微调和能力更新全部由版本化 Java 评分引擎确定。
7. 增加 `evidence_hash`、`prompt_version`、`model_name`、`rubric_version`、`scoring_rule_version` 和历史快照版本，支持幂等、复现和审计。
8. 明确 Judge 触发条件、申诉入口、公平性约束和禁止行为。

规范文档：

`do-not-miss-docs/IRT_sys_agent_Scoring_System_v2.docx`

### 结果

第一阶段只冻结评分规则，没有修改现有业务代码。后续数据库和 Java 评分引擎可以直接以 v2 输入输出契约为依据实施，避免边开发边改变“能力分到底代表什么”。

文档结构审计通过：

- DOCX 压缩包无损坏。
- 页面尺寸、段落层级和 13 张规则表结构完整。
- 所有表格均具有固定列宽与单元格宽度。
- 核心字段、置信度范围、职责边界和审计字段均已写入。

由于当前环境没有可用的 LibreOffice，且本机 Word 无界面导出超时，本次未完成页面 PNG 视觉渲染；已完成 DOCX 结构、内容和表格几何检查。

## 029. 能力评分 v2 缺少可审计、可版本化和并发安全的数据底座

### 发生了什么问题？

第一阶段已经冻结了能力评分规则，但项目数据库仍只有：

- `growth_tags`
- `growth_tag_evidences`
- 一个整数 `score_delta`

这些表足以展示早期能力地图，却无法承载新的评分契约：

1. 不能区分累计经历、当前能力、能力不确定度和画像可信度。
2. 没有地方保存 LLM 抽取出的难度、完成质量、个人贡献和证据引用。
3. 没有地方保存 Java 评分引擎的中间因素和规则版本。
4. 重新评分只能覆盖旧值，无法解释历史分数为什么发生变化。
5. 同一份证据重复投递时缺少数据库级幂等约束。
6. Judge 测试、问题、回答和可信度变化没有独立审计记录。
7. 两个异步任务同时更新同一能力或可信度时可能发生丢失更新。

### 原因

原成长标签数据模型围绕前端展示设计，`growth_tags.score` 是证据分数的简单汇总。随着评分系统引入 Evidence Agent、确定性 Java 引擎和 Judge Agent，需要把“任务、证据、计算结果、当前状态、审核记录”拆开保存。

如果继续把所有信息写入成长标签表或一个大 JSON：

- 很难建立唯一约束。
- 无法按状态扫描失败任务。
- 无法查询某一能力的历史评分。
- 无法用乐观锁保护当前能力状态。
- 无法对规则版本做 A/B 对比和重新计算。

### 解决方式

新增 Flyway 迁移 `V14__add_ability_scoring_v2.sql`，建立七张表：

1. `ability_scoring_profiles`
   - 保存用户全局 `profile_confidence`。
   - 使用 `confidence_version` 乐观锁保护 Judge 更新。
2. `user_ability_states`
   - 按用户和能力维度保存 `experience_score`、`ability_score`、`ability_uncertainty` 和等级。
   - 使用 `state_version` 乐观锁防止并发评分丢失更新。
3. `ability_assessment_jobs`
   - 保存证据抽取任务、输入快照、执行状态、错误、证据哈希和模型版本。
   - 通过“成就记录 + 证据哈希 + Prompt 版本 + Rubric 版本”建立幂等唯一约束。
4. `ability_evidence_assessments`
   - 保存 LLM 的活动级结构化证据评估和原始 JSON。
   - 不包含最终得分。
5. `ability_evidence_dimensions`
   - 保存同一活动对多个能力维度的相关性、置信度和证据引用。
6. `ability_score_results`
   - 保存 Java 引擎计算前后的所有分数、因素快照、Judge 标记和评分规则版本。
   - 重新计算创建新记录，通过 `supersedes_result_id` 保留版本链。
7. `judge_assessments`
   - 保存触发原因、题目、回答、Rubric 结果、审核人和可信度变化。
   - 数据库约束单次可信度变化范围为 `-0.08..0.08`。

同时新增 `abilityscore` 包中的实体、枚举和 Repository 骨架。现有 `GrowthTagService` 未修改，新评分系统暂时作为旁路底座，不影响当前功能。

详细说明：

`do-not-miss-docs/能力评分系统数据模型_v2.md`

### 结果

- `mvn -q -DskipTests compile` 通过。
- Docker 中 MySQL、Redis、RabbitMQ、OpenSearch 均健康。
- Flyway 成功应用 V13 和 V14，数据库当前版本为 14。
- Hibernate `ddl-auto=validate` 验证全部新实体与数据库字段一致。
- 后端成功启动并连接 RabbitMQ。
- 验证结束后已停止临时后端进程，基础设施容器保持运行。

本阶段没有启用新的评分业务流程，也没有改变前端展示。下一阶段可以在这套数据结构上实现确定性 Java 评分引擎。

## 030. 能力评分缺少确定性数学引擎，LLM 结构化证据无法稳定转换为能力状态

### 发生了什么问题？

第二阶段已经建立能力评分数据表，但系统仍缺少真正执行计算的 Java 引擎。如果继续沿用旧版 `GrowthTagService`：

- LLM 会直接输出 `scoreDelta`。
- 相同经历重复调用可能得到不同分数。
- 高水平用户完成简单任务仍可能获得不合理收益。
- 来源可信度、完成质量和个人贡献无法形成统一、可解释的计算过程。
- 累计经历分和 `0..100` 能力分仍可能混为一谈。
- 无法根据规则稳定触发 Judge 审核。

### 原因

LLM 擅长理解证据，但不是确定性计算器。评分系统需要一段不依赖模型、不受温度和 Prompt 波动影响的纯函数，将结构化因素转换为：

- 暂定经验增量
- 已验证经验增量
- 新能力分
- 新不确定度
- 建议等级
- Judge 触发原因

同时，这段计算必须记录规则版本和所有中间量，以便后续复现和 A/B 校准。

### 解决方式

新增 `abilityscore` 评分引擎：

1. `AbilityScoreCalculator`
   - 纯 Java、无数据库、无 LLM 依赖。
   - 输入相同时输出完全相同。
   - 使用 `BigDecimal` 和固定四位小数保证稳定结果。
2. `AbilityScoringPolicyV2`
   - 集中保存规则版本、权重、阈值和上限。
   - 当前版本为 `ability-score-v2.0`。
3. `AbilityEvidenceSourceType`
   - 在服务端保存来源可信度先验。
   - 公司实习为 `0.85`，可运行个人 Demo 为 `0.80`，但来源只参与验证强度。
4. 成长价值使用加权和：
   - 完成质量 `30%`
   - 个人贡献 `25%`
   - 能力相关度 `30%`
   - 新颖度 `15%`
5. 验证强度使用加权和：
   - 来源可信度 `45%`
   - 证据质量 `30%`
   - 个人贡献判断置信度 `15%`
   - 综合抽取置信度 `10%`
6. 难度匹配单独作为倍率，高水平用户做远低于自身能力的任务时倍率最低为 `0.05`。
7. 画像可信度倍率限制在 `0.85..1.10`，避免旧方案过大的强者恒强效应。
8. 能力分使用带 headroom 的饱和更新，越接近 100 越难继续上升。
9. 强且独立的证据会降低能力不确定度，最低不低于 `0.05`。
10. 新增 Judge 触发规则，包括跨级、高难度弱证据、低可信度强声明、刷分和高影响低抽取置信度。
11. 新增 `AbilityScoringService`：
    - 读取证据评估、能力维度、画像可信度和当前能力状态。
    - 按维度调用计算器。
    - 保存 `ability_score_results`。
    - 更新 `user_ability_states`。
    - 通过“assessment + dimension + scoring rule”复用已有结果，防止重复加分。
    - 跨级需要 Judge 时先更新数值，但不提前授予新等级。
12. 将全部中间因素保存到 `factor_snapshot_json`，将触发原因保存到 `judge_flags_json`。

详细公式：

`do-not-miss-docs/Java确定性能力评分引擎_v2.md`

### 结果

新增 7 组单元测试，覆盖：

- 相同输入结果完全一致。
- 高水平用户完成简单任务几乎不增长。
- 高质量个人项目可以超过普通公司实习。
- 画像可信度只产生窄幅影响。
- 重复低新颖度活动收益下降并触发刷分标记。
- 异常跨级和弱证据强声明触发 Judge。
- 强独立证据降低能力不确定度。

验证结果：

- `mvn -q test`：7 个测试全部通过，0 失败、0 错误。
- Spring Boot 使用隔离端口 `18080` 启动成功。
- Flyway、Hibernate、MySQL 和 RabbitMQ 初始化正常。
- 临时 `18080` 实例已关闭，原 `8080` 后端实例保持运行。

本阶段尚未让完成活动自动进入新评分链路。下一阶段需要实现 Evidence Assessment Agent，将真实活动、复盘和证据转换为 Java 引擎所需的结构化输入。

## 031. 完成记录与 Java 评分引擎之间缺少 Evidence Assessment Agent

### 发生了什么问题？

项目已经具备 Event Quality Agent 和确定性 Java 评分引擎，但两者之间没有真正的数据转换层：

1. `achievement_records` 只保存活动、挑战和用户复盘的原始事实。
2. Java 评分引擎需要难度、完成质量、个人贡献、相关能力、证据质量和新颖度等结构化因素。
3. Event Quality Agent 只能说明活动理论上可以培养什么，不能证明学生实际做了什么。
4. 如果直接把活动质量报告当成学生能力证据，会把“活动价值”误认为“学生掌握程度”。
5. 模型失败、JSON 越界、重复 MQ 消费和补写复盘都可能造成错误或重复加分。

### 原因

旧版成长标签抽取把“识别能力标签”和“给标签加分”合并在一起。新评分架构拆分职责后，需要一个专门的 Agent：

- 读取活动事实、活动质量报告和用户复盘。
- 抽取结构化证据。
- 不参与最终数学计算。
- 将校验后的证据交给 Java 评分引擎。

同时，来源可信度和历史新颖度不能交给用户或 LLM 自行声明，必须由服务端独立计算。

### 解决方式

实现 Evidence Assessment Agent v2：

1. 构造稳定证据快照：
   - 成就记录
   - 活动原始信息
   - `did` / `learned`
   - Event Quality 报告
   - 服务端来源类型
2. 对快照计算 SHA-256 `evidence_hash`。
3. 使用“成就记录 + 哈希 + Prompt 版本 + Rubric 版本”创建幂等任务。
4. 调用 Qwen 输出严格结构化 JSON，只允许包含：
   - 活动难度
   - 完成质量
   - 个人贡献
   - 能力维度与相关度
   - 证据发现
   - 新颖度特征
   - 风险和 Judge 建议
5. Prompt 明确禁止模型输出最终分数、等级和画像可信度变化。
6. 后端执行数值 clamp、维度归一化、去重、长度限制和最多五维约束。
7. 模型不可用或输出异常时，使用 Event Quality `abilityImpacts` 和本地关键词规则兜底。
8. 服务端独立推断来源类型：
   - 个人挑战为自述。
   - 企业活动为公司记录。
   - 学校/研究活动为学校记录。
   - 其他活动为组织活动记录。
9. 服务端独立计算证据质量：
   - 本地完成记录与复盘占 `60%`
   - 模型证据发现占 `40%`
10. 服务端使用历史经历词项 Jaccard 相似度计算新颖度，最低为 `0.10`。
11. 结构化结果写入：
    - `ability_assessment_jobs`
    - `ability_evidence_assessments`
    - `ability_evidence_dimensions`
12. 自动调用 `AbilityScoringService`，写入评分结果和当前能力状态。
13. 新增 Agent Run 类型和四个可观测步骤：
    - `EVIDENCE_SNAPSHOT`
    - `EVIDENCE_EXTRACTION`
    - `EVIDENCE_VALIDATION`
    - `DETERMINISTIC_SCORING`
14. 新增 Outbox/RabbitMQ 链路：
    - 队列 `do-not-miss.ability-evidence`
    - routing key `ability-evidence.assess`
15. 完成活动、完成挑战和更新复盘时自动写入证据评估消息。
16. 新增手动补偿与查询接口：
    - `POST /api/ability-scoring/records/{recordId}/assess`
    - `GET /api/ability-scoring/states`
    - `GET /api/ability-scoring/results`
17. 增加成就记录级幂等保护：
    - 同一成就记录、能力维度和评分规则只生效一次。
    - 新证据可以留档，但当前不会重复加分。

详细说明：

`do-not-miss-docs/Evidence_Assessment_Agent_v2.md`

### 结果

使用真实数据库记录完成端到端测试：

- Qwen 模式成功调用。
- 生成 1 条任务、1 条活动级评估、3 条能力维度、3 条评分结果和 3 条能力状态。
- 活动难度为 `35`，完成质量为 `0.72`，个人贡献为 `0.65`。
- 服务端证据质量为 `0.7880`，新颖度为 `1.0000`。
- 三个维度的已验证经验增量约为 `5.78 / 5.63 / 5.53`。

连续再次调用两次：

- 仍复用同一个任务和评估。
- `attempt_count` 保持为 1，证明模型没有重复调用。
- 三条结果均返回 `reusedExistingResult=true`。
- 数据库评分结果数量保持 3 条，能力分未重复累计。

RabbitMQ 管理接口确认：

- `do-not-miss.ability-evidence` 为持久化队列。
- 当前消息数为 0。
- 当前消费者数为 1。

验证：

- `mvn -q test` 通过，原有 7 个评分单元测试全部成功。
- 新版后端在隔离端口 `18080` 启动成功。
- 当前原 `8080` 后端实例仍保持运行。

## 032. 能力评分缺少公平约束、重复证据防护和用户申诉链路

### 发生了什么问题？

Evidence Assessment Agent 和 Java 确定性评分引擎已经能够把活动经历转成能力分，但仍存在会影响真实使用公平性的边界问题：

1. 用户可以把相同经历复制成多条成就记录，绕过单记录幂等键重复得分。
2. 团队活动中的个人贡献主要依赖自述，容易把团队成果全部归到个人名下。
3. 同一活动可以提取多个能力维度，如果每个维度都独立获得完整收益，会出现“多贴标签、多拿分”。
4. 用户补写或修正复盘后，旧证据需要重算；直接再评分会重复加分，直接覆盖又会破坏审计历史。
5. 系统给出的评分会影响能力地图和智能简历，但用户没有正式申诉入口。

### 原因

原有幂等约束关注的是“同一成就记录、同一证据哈希、同一规则版本”，能够防止 MQ 重复消费，却无法识别“不同记录承载相同实质内容”。

同时，LLM 提取出的个人贡献和能力相关度虽然经过了数值范围校验，但缺少跨字段的业务约束。评分结果也只有追加能力，没有区分安全替换、时间线回放和人工申诉。

### 解决方式

1. 增加实质内容指纹：
   - 对来源、标题、组织、分类、内容、技能、`did` 和 `learned` 进行规范化。
   - 使用 SHA-256 生成 `content_fingerprint`。
   - 同一用户、不同记录出现相同指纹时标记 `DUPLICATE_REVIEW`，关联 `duplicate_of_job_id`，停止 LLM 和评分执行。
2. 增加团队贡献约束：
   - 团队经历没有明确个人职责时，贡献上限为 `0.55`，置信度上限为 `0.45`。
   - 已描述个人职责但没有独立验证时，上限为 `0.85 / 0.75`。
   - 写入 `FAIRNESS_CONTRIBUTION_CAPPED` 风险标记。
3. 增加多能力维度预算：
   - 单次经历的能力相关度总预算为 `2.0`。
   - 超出预算时按比例缩放全部维度。
   - 原始相关度、调整后相关度和缩放倍率写入评分因子快照。
4. 增加证据修正安全重算：
   - 如果旧结果仍是能力时间线中的最新结果，从旧结果之前的状态重新计算，并使用 `supersedes_result_id` 建立版本链。
   - 旧结果标记为 `SUPERSEDED`，不会重复累计。
   - 如果后面已有其他评分，创建 `EVIDENCE_CHANGED_REPLAY` 待办，不直接破坏当前状态。
5. 增加用户申诉：
   - `POST /api/ability-scoring/results/{resultId}/appeals`
   - `GET /api/ability-scoring/appeals`
   - 同一结果存在待处理申诉时拒绝重复提交。
6. 增加 Flyway `V15__add_ability_scoring_fairness.sql`：
   - 扩展 `ability_assessment_jobs`。
   - 新建 `ability_score_appeals`。
   - 增加指纹、状态和用户时间索引。

详细说明：

`do-not-miss-docs/能力评分公平性与防刷分_v2.md`

### 结果

- `mvn -q test`：15 个测试通过，0 失败。
- 重复内容跨记录提交会进入人工复核，不会生成第二份能力收益。
- 团队成果无法仅凭自述获得满额个人贡献。
- 一次经历的多能力收益被固定预算约束。
- 证据修改可以安全替换最新结果；涉及后续历史时转入回放待办。
- 用户可以提交并查询评分申诉，重复申诉返回 HTTP `400`。
- Flyway 成功应用 V15，数据库版本为 `15`。
- 后端健康接口正常，当前 `8080` 实例保持运行。
- 真实接口验证产生的临时申诉记录已清理。

## 033. 混合召回评测中 BM25 通道未稳定参与

### 发生了什么问题？

在 RAG 召回评测和调试结果中，部分候选活动的 `bm25Score` 为 `0`，并且在特定长查询下可能出现整条 BM25 结果为空、只有向量召回继续工作的现象。

需要区分两种情况：

1. 单个候选的 `bm25Score=0`：该活动可能只被向量通道召回，本身不一定是故障。
2. 所有候选的 BM25 分同时缺失：说明关键词召回请求失败或返回空结果，属于需要修复的问题。

### 原因

1. Query Rewrite 生成的完整长文本被直接交给 OpenSearch `multi_match`，其中包含目标、水平、约束等重复或通用描述，不适合作为 BM25 查询词。
2. BM25 请求发生 `RestClientException` 时，旧代码只记录警告并返回空列表，导致混合召回退化为向量召回，但接口仍可正常返回，问题不易被发现。
3. 旧索引中的新增质量字段和语义字段没有统一使用中文分析器。
4. 原来的重建操作只更新文档，不会删除并重建索引，因此 Mapping 和 Analyzer 修改无法真正应用到已经存在的索引。
5. 建索引和搜索阶段都使用 n-gram 分析器，查询侧产生过多碎片词，降低了 BM25 的稳定性和可解释性。

### 解决方式

1. 新增 `Bm25QueryCompactor`：
   - 从完整 Rewrite 文本中保留中文、英文技术词和数字。
   - 去除“推荐、活动、适合”等通用词。
   - 普通查询最多保留 24 个词、240 个字符。
   - 失败重试查询最多保留 8 个词、96 个字符。
2. BM25 首次调用失败后，使用更短的 fallback 查询重试一次；只有两次都失败才让向量和本地召回继续兜底。
3. 缩小 BM25 字段范围并设置权重：
   - `title^5`
   - `skill^4`
   - `learningOutcomes^3`
   - `extractedTags^3`
   - `qualitySummary^2`
   - `content^2`
   - `semanticText^2`
4. 使用 `operator=or` 和 `minimum_should_match=20%`，避免长查询要求过严导致无结果。
5. 中文文本字段统一采用：
   - 建索引：`cjk_ngram`
   - 查询分析：`cjk`
6. 全量重建接口改为删除旧索引、按新 Mapping 创建索引、重新写入全部已审核且未过期活动并执行 refresh。
7. 新增 `Bm25QueryCompactorTest`，验证技术词保留、通用标签删除和 fallback 长度上限。

### 结果

- `mvn -q test` 通过。
- 全量重建 OpenSearch 索引成功，共写入 37 个已审核且未过期活动。
- 新索引的标题、技能、学习产出和语义文本字段均使用 `cjk_ngram` / `cjk` 分析器。
- 真实查询验证：
  - “我想学习JVM性能调优和GC诊断”：`JVM 性能调优工作坊` 的 `bm25Score=100`、`semanticScore=100`。
  - “我想学日语”：日语相关候选均获得非零 BM25 分，第一名 `bm25Score=100`。
  - “我想学agent开发”：`Agent 应用开发教学` 的 `bm25Score=100`、`semanticScore=100`，排在第一名。
- BM25 与向量语义召回现在都能进入候选融合和最终排序；即使 BM25 临时失败，也会留下明确日志并执行一次短查询重试。

## 034. 能力评分只能标记需要 Judge，但缺少真正的考核闭环

### 发生了什么问题？

Java 能力评分引擎已经能够识别跨级、高收益、弱证据和疑似刷分等高风险结果，并写入 `REVIEW_REQUIRED` 和 Judge Flags，但旧系统只停留在“需要考官验证”的提示：

1. 不会自动创建考核任务。
2. 没有根据能力维度和真实经历生成问题。
3. 学生无法提交答案。
4. 没有逐题 Rubric 判卷。
5. PASS/FAIL 不会改变评分状态、等级和画像可信度。
6. `judge_assessments` 虽然已经建表，但没有业务服务和接口使用。

### 原因

早期阶段只先建立了 Judge 数据模型和触发标记，用于保证评分引擎不会在证据不足时直接确认跨级。真正的跨请求状态机、远程模型调用、可信度并发更新和等级确认尚未实现。

同时，Judge 不能直接嵌入能力评分事务中调用远程模型，否则完成活动时可能被 LLM 超时拖慢。因此需要把“创建任务”和“开始考试、判卷”拆开。

### 解决方式

1. 新增 `JudgeAssessmentService`，实现：
   - 自动创建 Judge 任务。
   - 开始考核并生成题目。
   - 校验完整答案。
   - 调用 Judge LLM 逐题判分。
   - Java 决定 PASS/FAIL/MANUAL_REVIEW。
   - 应用可信度和等级变化。
2. 新增状态机：
   - `PENDING`
   - `IN_PROGRESS`
   - `COMPLETED`
   - `FAILED`
3. 新增三个固定考核方向：
   - 本人贡献与事实细节。
   - 目标能力的核心原理。
   - 排错、迁移和边界分析。
4. LLM 只给逐题分数、反馈和证据，最终阈值由 Java 决定：
   - `>=70`：PASS
   - `<50`：FAIL
   - `50..69`：MANUAL_REVIEW
5. 模型不可用时强制进入人工复核，不根据答案长度或关键词自动通过。
6. 新增 `JudgeDecisionPolicy`：
   - PASS 小幅提高可信度。
   - FAIL 小幅降低可信度。
   - MANUAL_REVIEW 不调整。
   - 单次调整绝对值不超过 `0.08`。
7. PASS 时：
   - 评分结果改为 `VERIFIED`。
   - 确认 `proposedRank`。
8. FAIL 时：
   - 评分结果改为 `PROVISIONAL`。
   - 保留原等级。
   - 不粗暴删除已经记录的活动事实和能力增量。
9. 在 `AbilityScoringService` 中自动创建 Judge 任务，已有 `REVIEW_REQUIRED` 结果再次评分时也会补建。
10. 新增接口：
    - `GET /api/ability-judges`
    - `GET /api/ability-judges/{judgeId}`
    - `POST /api/ability-judges/{judgeId}/start`
    - `POST /api/ability-judges/{judgeId}/submit`
11. 新增 Flyway V16：
    - `score_result_id` 唯一索引，保证一个评分结果只有一个 Judge 任务。
    - `judge_version` 乐观锁，防止重复提交答案重复改变可信度。
12. 增加 Agent Run 可观测步骤：
    - `JUDGE_QUESTION_GENERATION`
    - `JUDGE_EVALUATION`
    - `JUDGE_APPLY`

详细说明：

`do-not-miss-docs/能力Judge系统_v1.md`

### 结果

- 新增 7 个 Judge 单元测试，覆盖：
  - PASS 阈值和正向可信度变化。
  - FAIL 阈值和负向可信度变化。
  - 临界分进入人工复核。
  - 模型不可用时禁止自动通过。
  - Judge 任务创建。
  - PASS 后确认等级。
  - 人工复核时不修改能力状态。
- 项目完整测试共 25 个测试方法通过。
- Flyway 成功从 V15 升级到 V16。
- Spring Boot 在隔离端口 `18080` 启动成功。
- Judge 查询接口鉴权访问正常。
- 数据库唯一索引 `uk_judge_score_result` 已生效。
- 临时 `18080` 验证实例已关闭，原 `8080` 实例未被停止。

## 035. 个人成就页仍展示旧成长标签，用户无法操作新的能力评分与 Judge

### 发生了什么问题？

后端已经完成确定性能力评分和 Judge 考核闭环，但学生端个人成就页仍以旧的成长标签气泡和固定统计为主：

1. 看不到当前能力分、经验分、不确定度和等级。
2. 看不到每一次评分变更及其状态。
3. `REVIEW_REQUIRED` 只存在于数据库，用户无法开始 Judge。
4. Judge 题目、作答、判卷结果没有前端入口。
5. 对评分有异议时无法提交申诉。
6. 旧里程碑证据和新评分状态分散，用户无法理解“为什么得到这个分数”。

### 原因

能力评分、Judge 和申诉接口是在旧个人成就页面之后逐步增加的。旧页面的数据源只有 `/api/achievements/growth-tags`，没有加载：

- `/api/ability-scoring/states`
- `/api/ability-scoring/results`
- `/api/ability-judges`

因此后端能力已经存在，但没有形成面向学生的可解释交互。

### 解决方式

1. 将能力地图主数据源切换为 `ability-scoring/states`：
   - 气泡大小和强度由能力分决定。
   - 显示能力等级、经验分、可信程度和不确定度。
2. 新增评分记录区：
   - 展示经验增量。
   - 展示能力分前后变化。
   - 展示 `PROVISIONAL`、`VERIFIED`、`REVIEW_REQUIRED` 等状态。
   - 保留评分规则版本和时间。
3. 新增 Judge 状态入口：
   - 待验证能力在气泡和详情中同时提示。
   - 点击后调用 `/start` 生成题目。
   - 在弹窗内完成作答并调用 `/submit`。
   - 展示最终得分、逐题反馈、决策和画像可信度变化。
4. 新增评分申诉入口，将理由和补充证据提交到评分申诉接口。
5. 保留旧成长标签详情作为“成长证据与里程碑”层，并按规范化能力名称与新能力状态关联。
6. 增加刷新按钮，统一重新读取能力状态、评分结果、Judge 任务和成长证据。
7. 补齐桌面端和移动端响应式样式。

### 结果

- `app.js` 通过 Node 语法检查。
- 使用模拟后端数据完成桌面端、Judge 弹窗和 `390px` 手机端浏览器验证。
- 手机端页面宽度与视口均为 `390px`，没有横向溢出。
- Judge 手机弹窗宽度为 `366px`，题目区域可正常滚动。
- 浏览器运行期间没有 JavaScript 页面错误。

## 036. 原生单文件前端难以继续承载复杂业务和动态交互

### 发生了什么问题？

旧前端长期使用：

```text
index.html + app.js + styles.css
```

随着事件推荐、计划、Schedule、Coach、能力评分和 Judge 等模块持续增加，出现了以下工程问题：

1. `app.js` 已增长到十万字符以上，状态、请求和 DOM 操作混杂。
2. 学生端与社会端依赖手动切换，没有清晰的路由和权限边界。
3. 每个模块都直接读取 localStorage 和调用 `fetch`，错误处理方式容易分散。
4. 登录失效、页面刷新和角色跳转需要在不同函数中重复处理。
5. 很难增加稳定的页面过渡、模块懒加载和组件级测试。
6. 修改一个公共区域时容易影响其他已经完成的功能。

### 原因

项目最初用于快速验证产品想法，原生 HTML、CSS 和 JavaScript 能够用最低成本完成第一版。但项目已经从前端原型发展为包含多个业务域和 AI 工作流的完整应用，原有单文件结构不再适合继续扩展。

### 解决方式

1. 在原前端目录建立 Vue 3 + TypeScript + Vite 工程。
2. 引入 Vue Router，建立：
   - `/login`
   - `/register`
   - `/student/*`
   - `/social/*`
3. 使用路由守卫处理：
   - 未登录访问受保护页面。
   - 已登录用户访问登录页。
   - 学生账号访问社会端。
   - 社会端账号访问学生端。
4. 使用 Pinia 统一管理：
   - Token。
   - 当前用户。
   - 七天会话恢复。
   - 登录、注册和退出状态。
5. 新增统一 API Client：
   - 自动添加 Bearer Token。
   - 自动序列化 JSON。
   - 统一解析后端错误。
   - 收到 `401` 时清理失效会话并跳转登录。
6. 开发环境由 Vite 将 `/api` 代理到 Spring Boot `8080`。
7. 将原页面保存为 `legacy-index.html`，后续逐模块迁移时继续作为行为参照。
8. 建立学生端与社会端独立工作区壳层和响应式侧边导航。
9. 更新部署指南，前端改为 `npm install`、`npm run dev` 和 `npm run build`。

### 结果

- `npm run typecheck` 通过。
- `npm run build` 通过，生产包成功输出到 `dist`。
- 依赖审计结果为 0 个漏洞。
- 使用真实后端账号完成登录。
- 未登录访问受保护路由会跳转登录，并保留目标地址。
- 登录后可以回到原目标页面。
- 浏览器刷新后会通过 `/api/auth/me` 恢复用户状态。
- 学生账号访问社会端路由会自动返回学生工作区。
- 桌面端宽度 `1440px` 与手机端宽度 `390px` 均无横向溢出。
- 手机导航可正常展开，浏览器测试期间没有 JavaScript 页面错误。

## 037. 通用前端壳层无法体现学生端的高频任务与模块关系

### 发生了什么问题？

Vue 工程完成基础搭建后，学生端仍然使用一套偏通用的工作区壳层。事件、关注、预约、日程、挑战、教练和个人成就虽然都有独立路由，但导航层级没有表达学生从“发现机会”到“执行行动”再到“成长复盘”的业务路径。

同时存在以下体验问题：

1. 桌面端模块入口缺少业务分组，模块增多后扫描成本较高。
2. 手机端依赖展开侧边栏，单手切换高频模块不够顺畅。
3. 新建挑战、添加日程、查找事件等高频动作缺少统一入口。
4. 页面标题、所属业务区和当前登录用户没有形成稳定的工作区上下文。
5. 后续逐模块迁移时缺少统一的内容容器、页面过渡和响应式边界。

### 原因

第一阶段的重点是完成 Vue、路由、登录状态和 API 封装，因此工作区只承担“路由能够打开”的基础职责。它没有根据学生端真实使用频率重新设计信息架构，也没有区分桌面端的持续导航和移动端的拇指操作场景。

### 解决方式

1. 新增学生端专属 `StudentWorkspace`，不再与社会端共用通用工作区布局。
2. 将学生端导航重组为三个业务组：
   - 发现：事件、关注。
   - 行动：我的预约、日程、挑战。
   - 成长：教练、个人成就。
3. 桌面端使用固定侧边栏和吸顶顶部工具栏，持续显示当前业务区、页面标题和用户状态。
4. 增加模块跳转面板，可按模块名称快速检索并跳转。
5. 增加统一“新建”菜单，集中提供查找事件、创建挑战和添加日程入口。
6. 移动端改为固定底部导航，高频保留事件、日程、挑战和个人成就，其余模块收纳到“更多”底部面板。
7. 增加统一内容容器、路由切换过渡、菜单动画和移动端安全底部留白。
8. 将导航配置集中到 `src/navigation/student.ts`，桌面端、移动端和命令面板复用同一份模块定义，避免入口配置漂移。

### 结果

- `npm run typecheck` 通过。
- `npm run build` 通过。
- 桌面端 `1440px` 视口下文档宽度为 `1440px`，没有横向溢出。
- 手机端 `390px` 视口下文档宽度为 `390px`，没有横向溢出。
- 自动化验证确认三个导航分组、五个移动端底部入口、三个快捷新建动作和三个“更多”模块入口均可正常使用。
- 模块跳转面板可以从事件页跳转到个人成就页。
- 浏览器验证期间没有 JavaScript 页面错误。

## 040. Schedule 依赖手工输入时间，无法像日历工具一样直接安排和调整任务

### 发生了什么问题？

旧 Schedule 已经升级为周视图，但新建或修改日程仍然主要依赖表单输入开始时间和结束时间。用户在安排挑战、学习任务和 AI 计划时，需要先判断日期和时间，再手工填写两个 `datetime-local` 字段。

同时存在以下问题：

1. 无法在空白时间轴直接拖出一段时间。
2. 已有日程不能直接拖到另一日期或时段。
3. 调整任务时长需要重新打开表单修改结束时间。
4. 日程颜色和类型虽然可以区分，但操作方式仍不像成熟日历工具。
5. 手机端七天视图如果强制压缩，会导致时间块难以阅读和点击。

### 原因

后端 Schedule API 已支持创建、更新和删除，但旧前端只把这些能力封装为表单提交，没有建立“像素位置 ↔ 日期时间”的映射，也没有处理拖拽选择、整块移动和底部拉伸三种交互状态。

### 解决方式

1. 新增 Vue 周视图 `StudentScheduleView`，固定显示每天 `07:00-22:00`。
2. 将每天时间轴划分为 30 分钟槽位，并建立：
   - 指针纵向位置到分钟数的换算。
   - 30 分钟吸附。
   - 日程开始时间和时长到卡片 `top/height` 的换算。
3. 在空白时间轴按下并拖动：
   - 实时显示选区。
   - 松开后自动填充开始和结束时间。
   - 用户只需补充标题、地点和备注。
4. 日程卡片使用原生拖放：
   - 保留原任务时长。
   - 根据目标日期和纵向位置计算新开始时间。
   - 调用 `PUT /api/schedule/{itemId}` 持久化。
5. 卡片底部新增拉伸手柄：
   - 拖动时实时改变卡片高度。
   - 松开后计算新的结束时间并持久化。
6. 支持关联挑战：
   - 选择挑战后自动填入标题、目标和描述。
   - 一次日程只代表挑战的一段执行时间，不会自动完成挑战。
7. 保留点击卡片打开精确编辑、删除和备注修改。
8. 使用稳定哈希为不同日程分配多色样式。
9. 手机端保留完整七列周视图，使用横向滚动避免内容被强制压缩。
10. 顶部快捷新建链接 `/student/schedule?action=create` 会自动打开编辑器。

### 结果

- 浏览器验证成功从空白时间轴拖出并创建第三个时间块。
- 整块拖动成功把测试日程移动到新的日期和 `16:00`。
- 底部拉伸成功把结束时间调整到 `18:00`。
- 拖拽和拉伸均调用后端更新接口，不只是前端视觉变化。
- 桌面端 `1440px` 视口无横向溢出。
- 周视图固定包含 7 个日期列。
- 浏览器验证期间没有 JavaScript 页面错误。

## 041. Coach、挑战、关注和社会端仍停留在旧前端，Vue 迁移链路不完整

### 发生了什么问题？

事件、个人成就和 Schedule 迁移后，Coach、挑战、关注和社会端仍然依赖旧 `app.js`。这会造成：

1. 学生端在不同模块之间切换时出现新旧两套交互风格。
2. Coach 的对话、日志和长期记忆无法复用 Vue 状态与组件。
3. 挑战创建、分页、完成复盘和写入成就仍依赖旧 DOM 操作。
4. 关注组织页面仍使用本地拼装资料，无法统一读取后端组织信息。
5. 社会端只有通用占位页，发布、审核状态和活动质量报告没有 Vue 入口。
6. 公开事件搜索只返回已审核活动，社会端无法查询自己刚提交的待审核活动。

### 原因

第一阶段迁移优先覆盖学生端核心链路。剩余模块虽然已有后端 API，但缺少 Vue 页面、类型契约和统一 API 封装。社会端还缺少一个按当前用户查询全部发布记录的接口。

### 解决方式

1. Coach：
   - 迁移今日对话和发送接口。
   - 支持直接生成今日日志。
   - 展示成长日志详情。
   - 展示到期长期记忆卡片、复习次数和记忆强度。
2. 挑战：
   - 迁移进行中、已完成、已取消三个状态。
   - 支持分页。
   - 支持创建、取消和完成。
   - 完成时填写“做了什么”和“学到了什么”，并进入个人成就。
3. 关注：
   - 合并 `/api/follows` 和 `/api/organizations`。
   - 展示组织类型、简介和取消关注操作。
4. 社会端：
   - 迁移发布事件表单。
   - 发布后明确提示进入质量分析和审核。
   - 迁移我的事件列表、审核状态、删除和重新分析。
   - 迁移活动质量报告，展示质量分、难度、目标学生、学习产出、风险和缺失信息。
   - 迁移组织主页，根据已发布活动汇总组织信息。
5. 后端新增 `GET /api/events/mine`：
   - 按当前用户查询自己发布的全部活动。
   - 包含待审核、需修改、已拒绝和已通过状态。
   - 不受公开学生端“只返回已审核活动”规则影响。
6. 将 Schedule、Coach、挑战、关注和社会端接口统一放入 `src/api/workspace.ts`。
7. 为所有新页面补齐加载、空状态、错误状态和移动端布局。

### 结果

- Vue TypeScript 类型检查通过。
- Vite 生产构建通过。
- 后端 Maven 完整测试通过。
- Schedule 拖拽创建、移动和拉伸验证通过。
- Coach 测试对话成功展示 4 条消息和 1 条长期记忆。
- 挑战创建后列表从 1 条增加到 2 条。
- 关注页成功展示后端组织资料。
- 社会端手机页面成功展示待审核活动。
- 社会端 `390px` 视口文档宽度为 `390px`，无横向溢出。
- 所有最终浏览器验收均没有 JavaScript 页面错误。

## 042. 我的预约页面一直显示骨架屏，真实预约功能没有迁移到 Vue

### 发生了什么问题？

进入 `/student/reservations` 后，页面一直显示灰色 Skeleton，无法查看预约活动、取消预约或完成签到。

### 原因

预约后端接口和业务逻辑已经存在，但 Vue 路由仍然指向迁移阶段使用的 `StudentModulePlaceholder`。该组件只是静态骨架占位，不会请求 `/api/reservations`，因此看起来像接口一直处于加载状态。

### 解决方式

1. 新增 `StudentReservationsView`，接入真实预约列表。
2. 展示有效预约、未来活动、线上活动和下一项安排。
3. 支持按活动、组织、地点和技能搜索。
4. 展示活动时间、地点、时长、组织和预约时间。
5. 取消预约调用 `DELETE /api/reservations/{reservationId}`，后端同时移除对应 Schedule 日程。
6. 签到凭证展示真实 `qrToken`，支持复制。
7. 开发环境支持调用 `/api/reservations/scan-complete` 模拟扫码：
   - 预约状态变为已完成。
   - 活动写入个人成就。
   - 当前预约列表移除该活动。
8. 将预约路由从骨架占位页切换到真实 Vue 页面。

### 结果

- Vue TypeScript 类型检查通过。
- Vite 生产构建通过。
- Maven 后端测试通过。
- 浏览器验收成功显示 2 条预约和签到弹窗。
- 骨架占位组件数量为 0。
- 手机端 `390px` 视口下文档宽度为 `390px`，无横向溢出。
- 浏览器验收期间没有 JavaScript 页面错误。

## 043. 能力标签过度细分，相近能力占满能力地图且难以形成整体认知

### 发生了什么问题？

确定性评分引擎会保留 Evidence Assessment Agent 抽取的每一个能力维度。随着活动增多，能力地图出现了大量粒度接近的标签，例如：

- Java 编程基础
- Java 后端开发能力
- Java 编程实践
- Java 集合框架使用

这些维度分别保留有价值，但全部平铺在首页会造成标签爆炸，用户很难快速理解自己的主要能力方向。

### 原因

原能力模型只有扁平的 `user_ability_states`：

1. 每个标准化维度独立评分。
2. Judge、申诉、证据和评分历史均绑定具体维度。
3. 前端直接把所有维度作为同级标签展示。

因此不能直接删除或覆盖相似维度，否则会破坏评分审计、Judge 状态和证据可解释性。

### 解决方式

1. 新增 HAC（Hierarchical Agglomerative Clustering）能力聚类服务。
2. 每个原始能力状态作为一个初始簇，使用平均链接法计算两个簇之间所有成员的平均相似度。
3. 相似度由三部分组成：
   - 领域锚点：Java、Agent、LLM、Python、日语等。
   - 词项 Jaccard 相似度。
   - 字符与领域特征向量的余弦相似度。
4. 每轮合并相似度最高且超过阈值的两个簇，默认阈值为 `0.62`。
5. 增加领域父子映射：
   - Spring、JVM、Maven 归入 Java。
   - LangGraph、Multi-Agent 归入 Agent。
   - AI Engineering、Agent 与 LLM 保持不同主能力，避免过度合并。
6. 为聚类结果生成稳定 `clusterKey`，相同成员集合会得到相同标识。
7. 父能力分使用可信度、证据量加权平均，不直接叠加子能力分。
8. 聚合经验采用折减模型：
   - 最高子能力经验完整保留。
   - 其余子能力经验只按 35% 计入。
   - 减少同一活动产生多个维度造成的重复膨胀。
9. 新增 `GET /api/ability-scoring/clusters`，聚类是只读派生视图，不修改原始能力状态。
10. 前端能力地图只展示主能力；点开后展示全部子能力及其相似度，用户可切换查看各自的评分、Judge、申诉和成长证据。

### 结果

- 三个 Java 子能力可稳定聚合为一个 `Java` 主能力。
- Java 与 JavaScript 不会因字符串包含关系被误合并。
- Spring Boot 会归入 Java，LangGraph 会归入 Agent。
- AI Engineering、Agent、LLM 保持独立。
- 聚类算法单元测试通过。
- Maven 全量测试通过。
- Vue TypeScript 类型检查和生产构建通过。
- 浏览器验收中 4 个子能力压缩为 2 个主能力。
- Java 主能力下正确展示 3 个可切换子能力。
- 手机端 `390px` 视口无横向溢出，浏览器没有 JavaScript 错误。

## 044. 新活动已经产生能力评分，但页面错误提示“旧里程碑证据尚未匹配”

### 发生了什么问题？

新账号 `test2` 完成新活动后，能力分和评分记录均已生成，但“成长证据与里程碑”区域仍提示：

> 新版评分已生成，但旧里程碑证据尚未匹配到这个能力维度。

这不仅没有展示真实活动证据，“旧里程碑”这一描述对新账号和新活动也明显错误。

### 原因

成长证据和能力评分由两条独立异步链路生成：

1. Growth Tag Agent 为成就记录 18 抽取了 `AI Agent 开发`。
2. Evidence Assessment Agent 为同一成就记录抽取了：
   - `Applied LLM Integration`
   - `AI Agent Design & Implementation`
   - `RAG Implementation`
   - 其他能力维度。

前端原先使用标签名称和能力维度名称进行字符串匹配。由于两个 Agent 使用了不同语言和不同粒度的名称，即使它们来自同一活动，名称也无法匹配。

真正稳定的关联关系其实已经存在：

```text
ability_score_results.achievement_record_id
                    =
growth_tag_evidences.record_id
```

### 解决方式

1. 新增能力证据时间线接口：

```text
GET /api/ability-scoring/states/{stateId}/evidences
```

2. 根据能力状态查询其有效评分记录。
3. 从评分记录提取 `achievement_record_id` 并去重。
4. 使用这些记录 ID 查询 `growth_tag_evidences.record_id`。
5. 前端能力详情改为通过成就记录血缘加载证据，不再依赖中英文能力名称相似度。
6. 保留成长标签名称作为展示分类，但不再让名称承担数据库关联职责。
7. 增加明确的异步状态：
   - `READY`：成长证据已生成。
   - `TAG_EXTRACTION_PENDING`：能力评分已完成，成长标签仍在异步整理。
   - `NO_SCORE_RECORD`：当前能力尚无评分来源记录。
8. 删除“旧里程碑证据尚未匹配”的误导提示。

### 结果

- `Applied LLM Integration` 可以正确展示 `AI Agent 开发` 标签下的“大模型agent开发竞赛”证据。
- 验证场景中证据维度和成长标签名称完全不同，仍能稳定关联。
- 旧警告出现次数为 0。
- 里程碑标记继续复用原成长证据 ID，不影响既有操作。
- 能力证据关联单元测试通过。
- Maven 全量测试通过。
- Vue TypeScript 类型检查和生产构建通过。
- 浏览器验收没有 JavaScript 错误。

## 045. HAC 能力地图依赖静态领域 Anchor，新增能力方向扩展性不足

### 发生了什么问题？

HAC 能力地图第一版可以把 `Java 编程基础`、`Java 后端开发能力`、`Java 编程实践` 聚合成 `Java`，也能把 `LangGraph` 归入 `Agent`。但这些主能力 Anchor 主要写死在 Java 代码中。

当后续出现新的能力方向时，例如 `Unity 开发`、`游戏引擎开发`、`游戏开发工程师`，如果系统没有提前维护 `游戏开发` 这个 Anchor，就只能把它们暂时作为零散能力展示，无法自动形成新的主能力方向。

### 原因

原先的能力聚类采用“静态领域锚点 + 词项相似度 + 字符向量相似度”。这种方式对常见领域稳定、可解释，但扩展性依赖人工维护 Anchor 表。

用户提出了更合理的产品设想：相似标签一开始可以独立展示；当系统观察到多个相似标签稳定聚集后，再让系统生成一个新的主 Anchor，并把这些子标签挂到该 Anchor 下。这个设计比纯手工维护更适合长期增长的能力地图。

### 解决方式

新增动态 Anchor 注册机制：

1. 新增 `ability_dynamic_anchors` 表，保存动态发现的主能力 Anchor、别名、成员能力维度、来源、状态、置信度和支持数量。
2. `AbilityFeatureVectorizer` 从“只使用静态 Anchor”升级为“静态 Anchor + 数据库动态 Anchor”。
3. `AbilityHacClusteringService` 在聚类时先加载已通过的动态 Anchor，让它们参与后续向量化和聚类。
4. 当 HAC 发现一个没有现有 Anchor、成员数达到阈值、内部相似度足够稳定的新簇时，调用 `AbilityDynamicAnchorRegistryService` 创建动态 Anchor。
5. 动态 Anchor 命名优先使用 LLM，要求返回短、具体、不过度泛化的主能力名；如果 LLM 不可用，则退回本地规则，使用簇内代表性标签命名。
6. 为避免乱生成宽泛分类，过滤 `综合能力`、`技术能力`、`学习能力`、`软件开发` 等过宽名称。
7. 动态 Anchor 默认要求至少 3 个成员；内部平均相似度达到 `0.66` 才自动标记为 `APPROVED`，否则只保留为 `CANDIDATE`。
8. 聚类响应增加 `anchorKey`、`anchorSource`、`anchorStatus`，便于前端和后续管理端解释这个主能力来自静态规则还是系统自动发现。

### 结果

- 原有 Java、Agent、LLM、日语等静态 Anchor 逻辑保持不变。
- 系统现在具备自增长能力 taxonomy：当新领域的多个子能力稳定出现时，可以登记新的动态 Anchor。
- Maven 全量测试通过。
- 这次优化让能力地图从“人工维护标签体系”升级为“本地 HAC 发现候选簇 + LLM 辅助命名 + 数据库持久化 Anchor”的半自动体系。

## 046. Agent Trace 只有摘要日志，不足以支撑 Bad Case 反思

### 发生了什么问题？

项目已经有 `agent_runs` 和 `agent_run_steps`，可以记录一次 Agent 调用经过了哪些步骤、每一步是否成功、耗时多久，以及输入输出摘要。

但在讨论 Bad Case Intake Agent 时发现：现有 Trace 只能定位大方向，例如“问题发生在 Query Rewrite、Retrieval、LLM Recommendation 还是 Response Build”，却不够支撑后续反思模型和长期经验沉淀。

例如事件推荐中，如果用户投诉“没有推荐我想要的活动”，旧 Trace 只能看到 `candidateCount` 和 `topEventIds`，不能直接看到每个候选活动的 BM25 分、语义分、最终分、推荐理由、模型选择结果和最终过滤结果。

### 原因

原 Trace 是链路级可观测日志，字段被压缩到 `input_summary` 和 `output_summary`。这种设计适合排查接口是否跑通、哪个步骤失败、整体耗时在哪里，但不适合做语义级复盘。

Bad Case 分析需要的是“结构化语义快照”，例如：

- Query Rewrite 把用户问题理解成了什么。
- 检索召回了哪些候选，每个候选的关键词分、语义分和最终分是多少。
- LLM 推荐阶段选了哪些活动，为什么选。
- 计划推荐中每个 Planner 分别生成了什么计划。
- Schedule Checker 和 Critic 修改或保留了什么内容。

这些信息如果只靠摘要字段，Bad Case Agent 很难准确判断根因。

### 解决方式

新增 Agent 语义快照机制：

1. 新增 `agent_trace_artifacts` 表，用来保存一次 Agent Run 中关键步骤的结构化快照。
2. 新增 `AgentTraceArtifactEntity`、`AgentTraceArtifactRepository` 和 `AgentTraceArtifactService`。
3. 每条 artifact 记录：
   - `run_id`
   - `step_name`
   - `artifact_type`
   - `content_summary`
   - `content_json`
   - `content_hash`
   - `redacted`
4. Artifact 写入使用独立事务，失败时只丢失快照，不影响推荐、计划等主业务接口。
5. Agent Run 详情接口新增 `artifacts` 返回字段，前端或后续 Bad Case Agent 可以直接按 `runId` 读取完整 Trace + 语义快照。
6. 事件推荐链路新增快照：
   - `QUERY_REWRITE_RESULT`
   - `RETRIEVAL_CANDIDATES`
   - `MODEL_RECOMMENDATION_OUTPUT`
   - `RULE_FALLBACK_RECOMMENDATION_OUTPUT`
   - `FINAL_RESPONSE`
7. 计划推荐链路新增快照：
   - `PLAN_GOAL_UNDERSTANDING`
   - `PLAN_RETRIEVAL_CANDIDATES`
   - `SCHEDULE_CONTEXT`
   - `PLANNER_STABLE_OUTPUT`
   - `PLANNER_SPRINT_OUTPUT`
   - `PLANNER_EXPLORE_OUTPUT`
   - `SCHEDULE_CHECK_OUTPUT`
   - `PLAN_CRITIC_OUTPUT`
   - `PLAN_MODEL_OUTPUT`
   - `PLAN_RULE_FALLBACK_OUTPUT`
   - `PLAN_FINAL_RESPONSE`

### 结果

- Agent Trace 从“摘要日志”升级为“摘要日志 + 结构化语义快照”。
- 后续用户投诉进入 Bad Case Intake Agent 后，可以根据 `runId` 定位到具体步骤，并读取候选、分数、模型选择、计划输出和校验结果。
- 存储语义快照本身不额外消耗大模型 token；只有后续反思 Agent 读取快照并提交给 LLM 分析时才会消耗 token。
- Maven 全量测试通过。

## 039. 个人成就页混淆成长标签、能力评分与 Judge 验证阶段

### 发生了什么问题？

个人成就模块同时存在两套不同含义的数据：

1. 成长标签 Agent 从已完成活动、挑战和复盘中抽取标签与证据。
2. Evidence Assessment Agent 和 Java 评分引擎生成能力分、经验值、不确定度、评分状态与 Judge 任务。

旧页面虽然已经接入部分新版接口，但用户仍然容易把成长标签的累计分数理解成正式能力分，并且存在以下问题：

1. 异步评分尚未完成时，能力状态为空，页面容易显得像功能失效。
2. 能力标签、评分记录、里程碑和 Judge 入口集中在同一长页面，信息层级不清楚。
3. `REVIEW_REQUIRED`、可信度、不确定度等工程参数缺少面向用户的解释。
4. Judge 从开始、生成题目、作答到判卷的状态不够独立，容易被页面其他信息干扰。
5. 完成记录中的个人复盘与能力证据更新关系不够明显。
6. 手机端能力地图和 Judge 弹窗的信息密度较高。

### 原因

成长标签功能早于确定性能力评分系统。后续新增 Evidence Assessment、评分引擎、公平约束和 Judge 后，前端是在旧标签页面上继续追加区域，没有重新按照数据生命周期设计：

```text
完成记录
→ 成长标签和里程碑证据
→ 结构化证据评估
→ Java 确定性评分
→ 必要时进入 Judge
```

因此标签经验、能力分和验证状态虽然都是真实数据，但在视觉上没有表达它们所处的不同阶段。

### 解决方式

1. 新增 Vue 个人成就页 `StudentAchievementsView`，将页面重组为：
   - 成长总览。
   - 能力档案。
   - 成长记录。
2. 明确区分两种展示模式：
   - 没有能力状态时，展示“已抽取成长标签”，标记为等待异步评分，不把标签分数称为能力分。
   - 能力状态生成后，自动切换为“已评分能力”，显示能力分、经验值、可信程度、不确定度和等级。
3. 使用规则排列的能力标签矩阵替代旧头像气泡：
   - 能力越强，底部强度条越明显。
   - 待 Judge 能力显示独立状态。
   - 桌面端和手机端使用相同的数据结构。
4. 以能力状态作为主视图，并按规范化能力名称匹配旧成长标签：
   - 新评分负责当前能力状态。
   - 旧成长标签负责成长证据和里程碑时间线。
5. 评分记录展示：
   - 经验增量。
   - 能力分前后变化。
   - `PROVISIONAL`、`VERIFIED`、`REVIEW_REQUIRED`、`SUPERSEDED` 状态。
   - 规则版本和时间。
6. 保留评分申诉入口，将申诉理由提交到能力评分申诉接口。
7. 成长记录支持分页和复盘编辑；保存后明确提示画像、标签与能力证据将异步更新。
8. 新增独立 `JudgeDialog`：
   - `PENDING`：解释验证目的，由用户主动开始。
   - `IN_PROGRESS`：展示真实贡献、核心原理和排错边界题目。
   - `COMPLETED`：展示总分、逐题反馈、决策和可信度变化。
   - 后端风险码转换为用户可理解的中文触发原因。
9. Judge 提交成功后重新同步能力状态、评分记录和 Judge 任务。

### 结果

- `npm run typecheck` 通过。
- `npm run build` 通过。
- 使用真实 `warma` 账号验证：
  - 5 条完成记录正确显示。
  - 7 个成长标签正确显示。
  - 新版能力状态为空时明确显示“等待异步评分”，页面不会空白。
  - 成长记录页显示 5 条记录和复盘输入区。
- 使用浏览器拦截数据验证新版能力状态：
  - 展示 3 项已评分能力。
  - Java 后端开发能力分为 37.6。
  - 正确显示经验值、可信程度和不确定度三个指标。
  - `REVIEW_REQUIRED` 正确显示 Judge 入口。
- Judge 完整流程验证通过：
  - 开始验证。
  - 生成 3 道题。
  - 提交答案。
  - 展示 84 分结果、逐题反馈和可信度变化。
- 桌面端 `1440px` 与手机端 `390px` 均无横向溢出。
- 手机 Judge 弹窗宽度为 `390px`。
- 浏览器验证期间没有 JavaScript 页面错误。

## 038. 事件检索、AI 推荐与计划生成缺少统一且可持续扩展的学生端交互

### 发生了什么问题？

旧事件页面已经具备普通搜索、关注、预约、AI 事件推荐和 AI 计划推荐，但交互仍停留在原生单页阶段：

1. 普通事件和 AI 推荐结果都持续向页面下方堆积，结果越多越难比较。
2. AI 多轮需求只有一个文本框，用户看不到自己补充过哪些条件。
3. 推荐分数、置信度和召回证据混在长文本中，不利于快速判断推荐依据。
4. 多份计划先以长列表展示，再进入独立弹窗，信息层级重复。
5. 计划步骤缺少稳定的任务流布局，桌面端和手机端阅读体验差异较大。
6. 普通检索的分页、分类、收益和地点筛选没有形成统一状态。
7. 关注和预约状态虽然来自后端，但页面刷新、AI 推荐和普通事件列表之间容易出现显示不一致。

### 原因

旧页面是在功能持续增加的过程中逐步扩展的。普通检索、AI 推荐和计划推荐共用同一块结果区域，没有针对三种不同任务重新设计信息架构：

- 普通检索需要高密度扫描和稳定分页。
- AI 推荐需要保留多轮需求、展示排序依据。
- 计划推荐需要比较多份方案，并查看步骤顺序和时间轴。

同时，旧代码直接操作 DOM，难以复用事件卡片、统一关注/预约状态，也不适合继续增加加载、错误和移动端视图状态。

### 解决方式

1. 新增 Vue 事件工作区 `StudentEventsView`，将页面拆成事件检索区和 AI 工作台。
2. 普通事件检索接入真实 `/api/events`：
   - 关键词搜索。
   - 分类筛选。
   - 收益筛选。
   - 地点筛选。
   - 客户端分页。
   - 加载、空结果和错误状态。
3. 新增可复用 `EventCard`：
   - 统一显示活动开始与结束时间。
   - 统一显示组织、地点、分类、技能和报酬。
   - 关注状态与 `/api/follows` 同步。
   - 预约状态与 `/api/reservations` 同步。
4. AI 事件推荐接入 `/api/ai/event-recommendations`：
   - 保留本地多轮消息记录。
   - 后续补充条件继续由后端 Query Rewrite 上下文处理。
   - 展示真实数据库活动、推荐分、理由和匹配证据。
   - AI 结果可以直接关注和预约。
5. AI 计划推荐接入 `/api/ai/action-plans`：
   - 支持事件推荐/计划生成模式切换。
   - 自动打开多方案比较弹窗。
   - 展示计划风格、质量分、步骤、警告和 Agent 链路。
6. 新增 `PlanDialog`：
   - 桌面端使用多方案侧栏和横向任务流程。
   - 手机端使用横向方案选择与纵向步骤时间线。
   - 通过 `/api/schedule/import-ai-plan` 将选中计划写入日程。
7. 将事件、推荐、计划和日程导入接口集中到 `src/api/events.ts`，并增加 TypeScript 数据契约。
8. 手机端增加“浏览事件 / AI 助手”切换，避免双栏内容在窄屏中无限堆叠。

### 结果

- `npm run typecheck` 通过。
- `npm run build` 通过。
- 使用真实 `warma` 账号完成浏览器端到端验证。
- 初始事件列表每页显示 6 条，共 6 页。
- 搜索 `agent` 返回 6 条相关活动。
- 输入“我想学agent开发，最好是线上”后，AI 返回 5 条真实数据库活动，最高推荐分为 91。
- 多 Agent Planner 返回 3 份可选计划，当前首份计划包含 4 个步骤。
- 桌面端 `1440px` 视口下无横向溢出。
- 手机端 `390px` 视口下无横向溢出，计划弹窗宽度为 `390px`。
- 浏览器验证期间没有 JavaScript 页面错误。
## 047. Bad Case 反馈只记录投诉内容，缺少自动归因和长期沉淀入口

### 发生了什么问题？

项目已经有 `agent_runs`、`agent_run_steps` 和 `agent_trace_artifacts`，也新增了用户反馈入口，但第一版 Bad Case 反馈只是把用户投诉、期望结果、实际结果保存到 `agent_bad_cases` 表里。

这样虽然能收集用户内测问题，但还不能回答更关键的问题：

1. 这个 bad case 更可能是 Query Rewrite、Retrieval、LLM Recommendation、Schedule Check 还是 Scoring 环节造成的？
2. 哪些 trace artifact 是复盘这次问题最关键的证据？
3. 这条反馈是否值得沉淀成某个 Agent 的长期 bad case 记忆？
4. 这条反馈是否应该进入后续推荐、规划、评分的评测集，防止回归？

### 原因

用户反馈本身是自然语言，和 Agent 内部执行链路之间缺少一层“Intake Agent”。

原设计中，人可以打开 trace 去人工判断问题，但系统不会自动完成：

- 投诉文本理解。
- 关联 Agent Run 读取。
- Step 耗时、失败状态和语义快照分析。
- 根因步骤定位。
- 记忆候选和评测集候选判断。

这会导致 bad case 越积越多，但没有变成可利用的 Agent 优化资产。

### 解决方式

1. 新增 `AgentRunType.BAD_CASE_INTAKE`，让 Bad Case Intake 本身也进入 Agent Run 日志体系。
2. 新增 `AgentStepName.BAD_CASE_TRACE_LOAD` 和 `AgentStepName.BAD_CASE_TRIAGE`，记录 Intake Agent 的两步流程：
   - 读取用户反馈关联的原始 Agent Run、Step 和 Artifact。
   - 分析问题类型、根因步骤和沉淀价值。
3. 新增 `AgentBadCaseIntakeAgent`：
   - 先用本地规则根据 `issueType`、失败 step、最慢 step、artifact 类型做兜底归因。
   - 如果配置允许并且 LLM 可用，再把用户投诉、source run、steps、artifacts 和本地规则提示交给 LLM 做更细的归因。
   - LLM 输出被限制在已有枚举中，不能自由创造 Agent 名称或 Step 名称。
4. Intake Agent 回填 `agent_bad_cases`：
   - `root_cause_step`
   - `root_cause_summary`
   - `relevant_artifact_ids`
   - `analysis_json`
   - `agent_memory_candidate`
   - `eval_case_candidate`
   - `status = TRIAGED`
   - `triaged_at`
5. 前端反馈弹窗在提交成功后显示 Intake Agent 的自动归因摘要，方便测试时确认反馈不是只被存表。
6. 通过 `app.ai.bad-case-intake-llm-enabled` 控制是否启用 LLM 归因；模型不可用或返回格式错误时自动退回本地规则，不影响反馈保存。

### 结果

- 用户反馈从“投诉记录”升级为“可归因 bad case”。
- 每条 bad case 都能绑定原始 trace、关键 artifact 和根因 step。
- 后续可以继续扩展：
  - Bad Case Memory Loader：Agent 执行前读取对应类型的历史 bad case。
  - Bad Case Eval Builder：把高价值投诉自动转成回归评测样本。
  - Bad Case Dashboard：按 root step、issue type、severity 统计系统薄弱点。
- Maven 全量测试通过。
