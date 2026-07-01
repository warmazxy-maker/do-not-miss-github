<script setup lang="ts">
import {
  ArrowRight,
  Award,
  CheckCircle2,
  ClipboardCheck,
  LoaderCircle,
  ShieldCheck,
  X
} from "@lucide/vue";
import { computed, reactive, watch } from "vue";

import type { JudgeAnswer, JudgeAssessment } from "@/types/achievements";

const props = defineProps<{
  open: boolean;
  judge: JudgeAssessment | null;
  busy: boolean;
  error: string;
}>();

const emit = defineEmits<{
  close: [];
  start: [judge: JudgeAssessment];
  submit: [judge: JudgeAssessment, answers: JudgeAnswer[]];
}>();

const answerValues = reactive<Record<string, string>>({});

const canSubmit = computed(
  () =>
    Boolean(props.judge?.questions.length) &&
    props.judge?.questions.every((question) => (answerValues[question.id] ?? "").trim().length >= 8)
);

watch(
  () => props.judge,
  (judge) => {
    Object.keys(answerValues).forEach((key) => delete answerValues[key]);
    judge?.answers.forEach((answer) => {
      answerValues[answer.questionId] = answer.answer;
    });
  },
  { immediate: true }
);

function submit() {
  if (!props.judge || !canSubmit.value) {
    return;
  }
  emit(
    "submit",
    props.judge,
    props.judge.questions.map((question) => ({
      questionId: question.id,
      answer: answerValues[question.id].trim()
    }))
  );
}

function rankLabel(rank: string) {
  return (
    {
      UNRATED: "待评估",
      FOUNDATION: "基础",
      DEVELOPING: "成长中",
      PROFICIENT: "熟练",
      ADVANCED: "进阶",
      EXPERT: "专家"
    }[rank] ?? rank
  );
}

function decisionLabel(judge: JudgeAssessment) {
  if (judge.status === "PENDING") return "待开始";
  if (judge.status === "IN_PROGRESS") return "验证中";
  return (
    {
      PASS: "验证通过",
      FAIL: "暂未通过",
      MANUAL_REVIEW: "转人工复核",
      PENDING: "待判定"
    }[judge.decision] ?? judge.decision
  );
}

function reasonLabel(reason: string) {
  return (
    {
      DIFFICULTY_GAP_OVER_40: "活动难度明显高于当前能力",
      HIGH_SINGLE_ACTIVITY_GAIN: "单次经历带来的能力增量较高",
      HIGH_DIFFICULTY_WITH_WEAK_EVIDENCE: "高难度声明的证据仍然偏弱",
      STRONG_CLAIM_WITH_LOW_SOURCE_VERIFIABILITY: "强能力声明缺少可验证来源",
      POSSIBLE_SCORE_FARMING: "经历与历史记录过于相似",
      RANK_PROMOTION_REQUIRES_JUDGE: "能力等级即将跨越正式阈值",
      LOW_CONFIDENCE_WITH_STRONG_CLAIM: "画像可信度较低但能力声明较强",
      LOW_EXTRACTION_CONFIDENCE_FOR_HIGH_IMPACT_RESULT: "高影响结果的证据抽取置信度不足"
    }[reason] ?? reason
  );
}
</script>

<template>
  <Teleport to="body">
    <Transition name="overlay">
      <div v-if="open && judge" class="judge-dialog-layer" @click.self="emit('close')">
        <section class="judge-dialog" role="dialog" aria-modal="true" aria-labelledby="judge-title">
          <header class="judge-dialog-header">
            <div>
              <p class="eyebrow">Ability Judge</p>
              <h2 id="judge-title">{{ judge.dimension }}</h2>
            </div>
            <button class="icon-button" type="button" title="关闭" @click="emit('close')">
              <X :size="20" />
            </button>
          </header>

          <div class="judge-dialog-body">
            <section class="judge-context">
              <span class="judge-context-icon"><ShieldCheck :size="25" /></span>
              <div>
                <span>{{ decisionLabel(judge) }}</span>
                <h3>确认这次能力变化是否与你的真实水平一致</h3>
                <p>
                  触发时能力分 {{ judge.abilityScoreAtTrigger }}，等级
                  {{ rankLabel(judge.currentRank) }}
                  <ArrowRight :size="14" />
                  {{ rankLabel(judge.proposedRank) }}
                </p>
              </div>
            </section>

            <div v-if="judge.triggerReasons.length" class="judge-trigger-reasons">
              <strong>为什么需要验证</strong>
              <span v-for="reason in judge.triggerReasons" :key="reason">{{ reasonLabel(reason) }}</span>
            </div>

            <div v-if="error" class="judge-error">{{ error }}</div>

            <section v-if="judge.status === 'PENDING'" class="judge-start-state">
              <ClipboardCheck :size="34" />
              <h3>准备一次短能力验证</h3>
              <p>题目围绕你的真实贡献、核心原理与排错思路生成。它不会改变活动完成事实，只用于确认等级和可信度。</p>
              <button type="button" :disabled="busy" @click="emit('start', judge)">
                <LoaderCircle v-if="busy" class="spin" :size="18" />
                <span>{{ busy ? "正在生成题目" : "开始验证" }}</span>
              </button>
            </section>

            <form v-else-if="judge.status === 'IN_PROGRESS'" class="judge-question-form" @submit.prevent="submit">
              <p>请结合真实经历作答。每道题至少写出一个完整的事实、判断或解决思路。</p>
              <label v-for="(question, index) in judge.questions" :key="question.id">
                <span>{{ String(index + 1).padStart(2, "0") }}</span>
                <div>
                  <strong>{{ question.prompt }}</strong>
                  <small>{{ question.focus }} · {{ question.maxScore }} 分</small>
                  <textarea
                    v-model="answerValues[question.id]"
                    rows="5"
                    maxlength="4000"
                    placeholder="写下你实际做过什么、为什么这样判断"
                  ></textarea>
                </div>
              </label>
              <button class="judge-submit-button" type="submit" :disabled="busy || !canSubmit">
                <LoaderCircle v-if="busy" class="spin" :size="18" />
                <span>{{ busy ? "正在判卷" : "提交验证" }}</span>
              </button>
            </form>

            <section v-else-if="judge.status === 'COMPLETED'" class="judge-result-view">
              <div class="judge-result-summary">
                <span :class="`is-${judge.decision.toLowerCase()}`">
                  <CheckCircle2 v-if="judge.decision === 'PASS'" :size="22" />
                  <Award v-else :size="22" />
                  {{ decisionLabel(judge) }}
                </span>
                <strong>{{ judge.rubric?.earnedScore ?? "—" }}</strong>
                <small>/ {{ judge.rubric?.maxScore ?? 100 }} 分</small>
              </div>
              <p>{{ judge.reviewReason || judge.rubric?.overallFeedback }}</p>
              <div v-if="judge.confidenceAfter !== null" class="judge-confidence-result">
                画像可信度 {{ Math.round(judge.confidenceBefore * 100) }}%
                <ArrowRight :size="14" />
                {{ Math.round(judge.confidenceAfter * 100) }}%
              </div>
              <div class="judge-rubric-grid">
                <article v-for="(item, index) in judge.rubric?.items ?? []" :key="item.questionId">
                  <div>
                    <strong>第 {{ index + 1 }} 题</strong>
                    <span>{{ item.score }} / {{ item.maxScore }}</span>
                  </div>
                  <p>{{ item.feedback }}</p>
                </article>
              </div>
            </section>

            <section v-else class="judge-start-state">
              <p>本次验证任务没有正常完成，请稍后重新进入。</p>
            </section>
          </div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>
