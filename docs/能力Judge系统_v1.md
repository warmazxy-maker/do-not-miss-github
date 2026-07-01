# 能力 Judge 系统 v1

## 目标

Java 评分引擎可以根据活动、复盘和证据估算能力增长，但以下结果不能直接被系统完全相信：

- 能力等级跨越正式阈值。
- 单次经历产生过高收益。
- 活动难度远高于当前能力。
- 高难度声明缺少可靠证据。
- 重复经历疑似刷分。
- Agent 抽取置信度不足。

Judge 系统负责验证这些高影响结果。它不会重新计算活动分数，而是通过针对性问答确认学生是否真正理解和完成了相关工作。

## 完整链路

```text
Evidence Assessment Agent
-> Java 确定性评分
-> 产生 Judge Flags
-> ability_score_results.status = REVIEW_REQUIRED
-> 自动创建 judge_assessments
-> 用户开始考核
-> Judge LLM 根据经历和能力维度生成 3 道题
-> 用户提交答案
-> Judge LLM 按 Rubric 逐题判分
-> Java 根据总分确定 PASS / FAIL / MANUAL_REVIEW
-> 有界调整 profile confidence
-> PASS 时确认待晋升等级
```

## 状态机

```text
PENDING
  -> IN_PROGRESS
  -> COMPLETED

异常时：
IN_PROGRESS -> FAILED
```

判定结果：

- `PASS`
- `FAIL`
- `MANUAL_REVIEW`
- `PENDING`

## 自动创建

`AbilityScoringService` 保存一条 `REVIEW_REQUIRED` 评分结果后，调用：

```text
JudgeAssessmentService.createIfRequired(...)
```

数据库约束：

```text
judge_assessments.score_result_id UNIQUE
```

因此同一评分结果即使被 MQ 重复消费、接口重复调用，也只能创建一个 Judge 任务。

创建任务时不会调用远程模型，避免把 LLM 延迟引入完成活动和能力评分的主事务。

## 出题

用户调用：

```http
POST /api/ability-judges/{judgeId}/start
Authorization: Bearer <token>
```

Judge 读取：

- 目标能力维度。
- Judge 触发原因。
- 评分前后的能力值。
- 评分因素快照。
- 对应活动或挑战。
- 学生填写的 `did` 和 `learned`。
- Evidence Assessment Agent 的能力结论和证据引用。

生成三类题目：

1. 本人实际贡献和可验证细节。
2. 目标能力的核心原理或方法。
3. 故障定位、迁移应用或局限性分析。

模型不可用时，系统会生成三道规则兜底题，不会阻止用户进入考核页面。

## 判卷

用户提交：

```http
POST /api/ability-judges/{judgeId}/submit
Authorization: Bearer <token>
Content-Type: application/json

{
  "answers": [
    {
      "questionId": "q1",
      "answer": "我负责了接口设计、实现和测试……"
    },
    {
      "questionId": "q2",
      "answer": "核心原理是……"
    },
    {
      "questionId": "q3",
      "answer": "我会先复现问题，再……"
    }
  ]
}
```

Judge LLM 只负责逐题给出：

- `score`
- `feedback`
- `evidence`

最终判定由 Java 完成：

| 总分 | 决策 |
|---|---|
| `>= 70` | `PASS` |
| `< 50` | `FAIL` |
| `50..69` | `MANUAL_REVIEW` |

如果模型不可用或返回无效结果，强制进入 `MANUAL_REVIEW`，不会根据规则答案长度自动判定通过。

## 可信度调整

Judge 是当前唯一自动改变 `ability_scoring_profiles.profile_confidence` 的流程。

### PASS

```text
delta = 0.02 + (score - 70) / 1000
```

最高不超过 `+0.08`。

### FAIL

```text
delta = -(0.02 + (50 - score) / 1000)
```

最低不超过 `-0.08`。

### MANUAL_REVIEW

```text
delta = 0
```

数据库和 Java 同时将可信度限制在 `0..1`。

## 对能力状态的影响

### PASS

- `ability_score_results.status` 改为 `VERIFIED`。
- 确认评分结果中保存的 `proposedRank`。
- 小幅提高画像可信度。

### FAIL

- 已发生的活动事实和能力增量不会被粗暴删除。
- 评分结果改为 `PROVISIONAL`。
- 不确认跨级后的等级。
- 小幅降低画像可信度。

### MANUAL_REVIEW

- 评分结果继续保持 `REVIEW_REQUIRED`。
- 不改变等级。
- 不改变画像可信度。

## 接口

```http
GET /api/ability-judges
GET /api/ability-judges/{judgeId}
POST /api/ability-judges/{judgeId}/start
POST /api/ability-judges/{judgeId}/submit
```

## 并发和审计

- `score_result_id` 唯一约束防止重复任务。
- `judge_version` 使用 JPA 乐观锁，防止重复提交答案导致重复改变可信度。
- `ability_scoring_profiles.confidence_version` 防止多个 Judge 同时覆盖画像可信度。
- `user_ability_states.state_version` 防止等级并发更新丢失。
- 题目、答案、逐题 Rubric、模型名称、可信度前后值和最终原因全部持久化。
- Agent Run 记录：
  - `JUDGE_QUESTION_GENERATION`
  - `JUDGE_EVALUATION`
  - `JUDGE_APPLY`

## 当前边界

- `MANUAL_REVIEW` 已有明确状态，但管理端人工处理接口尚未实现。
- 历史 `REVIEW_REQUIRED` 结果不会自动批量补建任务；重新执行对应成就记录的评估时会补建。

## 学生端展示

个人成就页已经接入：

- `GET /api/ability-scoring/states`
- `GET /api/ability-scoring/results`
- `GET /api/ability-judges`

页面以能力状态为主数据，展示能力分、经验分、不确定度、等级和评分历史。处于 `REVIEW_REQUIRED` 的能力会出现 Judge 入口，学生可以在弹窗中启动考核、回答问题并查看逐题反馈和可信度变化。

旧成长标签数据继续作为证据层使用，用于展示活动、挑战、复盘和里程碑；它不再承担最终能力等级的主展示职责。评分记录同时提供申诉入口。
