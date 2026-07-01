# Evidence Assessment Agent v2

## 职责

Evidence Assessment Agent 位于活动完成事实与 Java 确定性评分引擎之间：

```text
achievement_records
-> Evidence Assessment Agent
-> ability_evidence_assessments
-> ability_evidence_dimensions
-> Java Ability Scoring Engine
-> ability_score_results
-> user_ability_states
```

它负责理解证据，不负责决定最终分数。

## 触发方式

### 自动触发

以下业务会在原事务中写入 Outbox：

- 学生完成活动。
- 学生完成挑战。
- 学生更新历史记录中的复盘。

Outbox Dispatcher 将消息投递到：

```text
queue: do-not-miss.ability-evidence
routing key: ability-evidence.assess
```

消费者调用 Evidence Assessment Agent。

### 手动触发

```http
POST /api/ability-scoring/records/{recordId}/assess
Authorization: Bearer <token>
```

用于测试、补偿或重新生成证据评估。

## 第一步：构造证据快照

输入包括：

- 成就记录 ID、用户 ID 和来源类型。
- 活动或挑战标题。
- 组织、类别、地点、内容和技能。
- 完成时间。
- 用户填写的 `did` 和 `learned`。
- 服务端推断的来源类型。
- Event Quality Agent 的活动质量报告。

Event Quality 报告可以提供：

- 活动预期难度。
- 学习产出。
- 适合人群。
- 能力影响。
- 活动质量与报告置信度。

它表示活动理论上可以培养什么，不代表学生已经掌握。

快照使用稳定 JSON 序列化，并生成：

```text
SHA-256 evidence_hash
```

任务幂等键：

```text
achievement_record_id
+ evidence_hash
+ prompt_version
+ rubric_version
```

## 第二步：LLM 结构化提取

模型只能输出：

- 活动难度和判断置信度。
- 完成质量和判断置信度。
- 个人贡献和判断置信度。
- 总体抽取置信度。
- 最多五个能力维度及相关度。
- 证据发现。
- 新颖度特征。
- 风险标记。
- Judge 建议。

Prompt 明确禁止输出：

- `scoreDelta`
- 最终能力分
- 等级晋升
- `profileConfidence` 变化

当前版本：

```text
prompt_version = ability-evidence-prompt-v2.0
rubric_version = irt-evidence-v2
```

## 第三步：本地校验与兜底

后端会：

- 把比例值限制到 `0..1`。
- 把活动难度限制到 `0..100`。
- 归一化能力维度名称。
- 去重并限制维度数量。
- 限制字符串和证据列表长度。
- 模型无效或不可用时执行本地规则。

本地规则会优先读取 Event Quality Agent 的 `abilityImpacts`，然后使用活动、技能和复盘关键词补充能力维度。

## 服务端独立计算的信号

### 来源类型

来源可信度不能由用户或 LLM 自己填写：

- 个人挑战：`SELF_REPORT`
- 企业活动：`COMPANY_INTERNSHIP_OR_WORK`
- 学校或研究活动：`SCHOOL_COURSE_OR_ASSIGNMENT`
- 其他组织活动：`ORGANIZATION_ACTIVITY_RECORD`

### 证据质量

证据质量由两部分组成：

```text
evidenceQuality
= localEvidenceQuality × 0.60
+ modelEvidenceQuality × 0.40
```

本地证据考虑：

- 是否是组织活动完成记录。
- 是否填写 `did`。
- 是否填写 `learned`。
- 是否有 Event Quality 报告。

模型证据质量取结构化证据发现中的最强可靠证据值。

### 新颖度

后端对当前经历和用户历史经历进行词项 Jaccard 相似度比较：

```text
novelty = 1 - maxHistorySimilarity
```

最低为 `0.10`。相似活动越多，新颖度越低。

模型只提取活动族、技术和难度带，不直接决定新颖度分数。

## 第四步：落库

写入：

- `ability_assessment_jobs`
- `ability_evidence_assessments`
- `ability_evidence_dimensions`

原始结构化模型响应和服务端信号共同保存在 `raw_response_json`，用于审计。

## 第五步：确定性评分

Agent 完成后调用：

```text
AbilityScoringService
-> AbilityScoreCalculator
```

评分结果保存到：

- `ability_score_results`
- `user_ability_states`

同一成就记录、同一能力、同一评分规则只允许生效一次。补写复盘可以生成新的证据评估，但在版本化差额重算上线前不会重复增加能力分。

## 可观测日志

Agent Run 类型：

```text
ABILITY_EVIDENCE_ASSESSMENT
```

执行步骤：

1. `EVIDENCE_SNAPSHOT`
2. `EVIDENCE_EXTRACTION`
3. `EVIDENCE_VALIDATION`
4. `DETERMINISTIC_SCORING`

模型失败时仍可走本地规则完成评估。

## 查询接口

查看当前能力状态：

```http
GET /api/ability-scoring/states
```

查看最近评分结果：

```http
GET /api/ability-scoring/results
```

## 实际验证

使用数据库中 `demo-student` 的真实活动记录进行测试：

- 模式：`qwen:qwen-plus`
- 活动难度：`35`
- 完成质量：`0.72`
- 个人贡献：`0.65`
- 证据质量：`0.7880`
- 新颖度：`1.0000`

抽取出三个能力维度：

- 现场活动执行能力
- 跨文化沟通能力
- 团队协作与现场应变

Java 引擎分别产生约：

- `5.7844`
- `5.6334`
- `5.5256`

再次连续调用两次后：

- 任务 ID 不变。
- 评估 ID 不变。
- 模型调用次数不增加。
- 三条评分结果全部复用。
- 能力分没有重复累计。

