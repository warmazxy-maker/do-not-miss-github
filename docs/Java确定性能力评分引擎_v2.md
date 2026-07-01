# Java 确定性能力评分引擎 v2

## 目标

Java 评分引擎负责把 Evidence Assessment Agent 提取出的结构化因素转换成可复现的分数。

它不调用 LLM。相同的：

- 评分输入
- 历史状态
- 评分规则版本

必须产生完全相同的输出。

当前规则版本：

```text
ability-score-v2.0
```

## 输入因素

### 当前用户状态

- `currentExperienceScore`
- `currentAbilityScore`
- `currentAbilityUncertainty`
- `currentRank`
- `profileConfidence`

### 活动和证据因素

- `activityDifficulty`
- `completionQuality`
- `personalContribution`
- `personalContributionConfidence`
- `dimensionRelevance`
- `evidenceQuality`
- `assessmentConfidence`
- `novelty`
- `sourceType`

所有比例字段都会被限制在 `0..1`，能力和难度会被限制在 `0..100`。

## 第一步：成长价值

成长价值回答：

> 假设这项经历是真的，它本身能给这个能力提供多少成长信号？

```text
growthValue
= completionQuality     × 0.30
+ personalContribution × 0.25
+ dimensionRelevance   × 0.30
+ novelty              × 0.15
```

采用加权和而不是全部相乘，避免某一个偏低字段把结果直接压到接近零。

## 第二步：验证强度

验证强度回答：

> 系统有多大把握相信这项成长声明？

```text
verificationStrength
= sourceCredibilityPrior         × 0.45
+ evidenceQuality                × 0.30
+ personalContributionConfidence × 0.15
+ assessmentConfidence           × 0.10
```

来源可信度由服务器枚举提供，用户和 LLM 不能自行填写。

默认来源先验：

| 来源 | 可信度先验 |
|---|---:|
| 个人自述 | 0.45 |
| 私人记录 | 0.50 |
| 公开代码仓库 | 0.70 |
| 可运行个人 Demo | 0.80 |
| 学校课程或作业 | 0.75 |
| 公司实习或工作记录 | 0.85 |
| 已知竞赛平台 | 0.90 |
| 官方证书 | 0.90 |
| 可验证第三方记录 | 0.95 |

公司实习拥有更高的默认可验证性，但不会直接增加成长价值。

## 第三步：难度匹配

```text
difficultyDifference = activityDifficulty - currentAbilityScore
```

| 难度差 | 含义 | 倍率 |
|---|---|---:|
| `<= -40` | 远低于当前能力 | 0.05 |
| `(-40, -20]` | 简单 | 0.20 |
| `(-20, -5]` | 略简单 | 0.50 |
| `(-5, 10]` | 难度匹配 | 1.00 |
| `(10, 25]` | 有效挑战 | 1.20 |
| `(25, 40]` | 很难且不确定 | 0.75 |
| `> 40` | 可疑跨级 | 0.30，并触发 Judge |

高水平用户重复完成基础任务时，难度倍率会把收益压低。

## 第四步：可信度微调

| profile confidence | 倍率 |
|---|---:|
| `0.00..0.20` | 0.85 |
| `0.20..0.40` | 0.92 |
| `0.40..0.60` | 1.00 |
| `0.60..0.80` | 1.05 |
| `0.80..1.00` | 1.10 |

最大差距从旧方案的 `3.25` 倍收窄为约 `1.29` 倍，避免强者恒强。

## 第五步：经验增量

```text
provisionalExperienceGain
= 12
× growthValue
× difficultyMatchMultiplier
```

```text
verifiedExperienceGain
= provisionalExperienceGain
× verificationStrength
× profileConfidenceMultiplier
```

单项活动最多增加 `20` 点经验。

验证强度低于 `0.75` 且没有触发 Judge 时，结果状态为 `PROVISIONAL`；达到阈值后为 `VERIFIED`。

## 第六步：能力分饱和更新

经验分可以一直累积，但能力分必须保持在 `0..100`。

```text
headroom = (100 - currentAbilityScore) / 100
challengeSignal = activityDifficulty / 100
challengeAdjustment = 0.5 + 0.5 × challengeSignal
```

```text
abilityGain
= verifiedExperienceGain
× 0.45
× headroom
× challengeAdjustment
```

```text
newAbilityScore
= clamp(currentAbilityScore + abilityGain, 0, 100)
```

能力越接近 100，剩余增长空间越小。同样一项经历对初学者和高水平用户产生的能力增量不同。

## 第七步：不确定度更新

```text
uncertaintyReduction
= 0.12
× verificationStrength
× assessmentConfidence
× (0.5 + 0.5 × novelty)
```

```text
newUncertainty
= oldUncertainty × (1 - uncertaintyReduction)
```

最低不确定度为 `0.05`。重复活动、弱证据和低置信度抽取不会明显降低不确定度。

## Judge 触发条件

当前规则包括：

- 活动难度比当前能力高 40 分以上。
- 单项已验证经验增量达到 16 分。
- 高难度活动但证据质量低。
- 强声明来自低可验证性来源。
- 新颖度小于等于 0.10，疑似重复刷分。
- 能力分跨越正式等级阈值。
- 低画像可信度用户提交很强的能力声明。
- 高影响结果的 LLM 抽取置信度过低。

评分可以先更新，但跨级后的等级名称不会在 Judge 通过前生效。

## 持久化链路

```text
ability_evidence_assessments
        +
ability_evidence_dimensions
        +
ability_scoring_profiles
        +
user_ability_states
        ↓
AbilityScoringService
        ↓
AbilityScoreCalculator
        ↓
ability_score_results
        ↓
更新 user_ability_states
```

`AbilityScoringService` 通过以下唯一条件复用已有结果：

```text
assessment_id
+ normalized_dimension
+ scoring_rule_version
```

因此同一条 MQ 消息重复消费不会再次加分。

## 当前边界

本阶段已经完成：

- 数学计算内核
- 评分结果持久化编排
- 结果幂等
- 能力状态更新
- Judge 触发标记
- 因素快照审计

尚未完成：

- Evidence Assessment Agent 自动生成评分输入
- 自动从证据列表计算 `evidenceQuality`
- 自动根据历史活动计算 `novelty`
- 自动创建和执行 Judge 考试
- 前端展示评分解释

