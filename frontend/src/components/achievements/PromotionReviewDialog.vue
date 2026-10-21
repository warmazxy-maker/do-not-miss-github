<script setup lang="ts">
import { ArrowRight, Award, CheckCircle2, LoaderCircle, MessageSquareText, X } from "@lucide/vue";
import { computed, reactive, watch } from "vue";

import type { CapabilityPromotionReview, JudgeAnswer } from "@/types/achievements";

const props = defineProps<{
  review: CapabilityPromotionReview | null;
  bucketName: string;
  busy: boolean;
  error: string;
}>();
const emit = defineEmits<{
  close: [];
  submit: [answers: JudgeAnswer[]];
}>();

const RANK_LABELS: Record<string, string> = {
  UNRATED: "待评估",
  FOUNDATION: "基础",
  DEVELOPING: "成长中",
  PROFICIENT: "熟练",
  ADVANCED: "进阶",
  EXPERT: "专家"
};
// q1/q2 are anchored in the user's own evidence, q3 is the related extension question.
const QUESTION_KINDS = ["你的经历", "你的经历", "相关延伸"];

const answerValues = reactive<Record<string, string>>({});
const canSubmit = computed(
  () =>
    Boolean(props.review?.questions.length) &&
    props.review!.questions.every((question) => (answerValues[question.id] ?? "").trim().length >= 8)
);

watch(
  () => props.review?.id,
  () => {
    Object.keys(answerValues).forEach((key) => delete answerValues[key]);
    props.review?.answers.forEach((answer) => {
      answerValues[answer.questionId] = answer.answer;
    });
  },
  { immediate: true }
);

function rankLabel(rank: string | null) {
  return rank ? RANK_LABELS[rank] ?? rank : "";
}

function resultTitle(review: CapabilityPromotionReview) {
  if (review.decision === "PASS") return `复核通过，已晋级「${rankLabel(review.appliedRank)}」`;
  if (review.decision === "FAIL") return "这次还没有通过";
  if (!review.modelEvaluated) return "判卷服务暂不可用";
  return "暂未得出结论";
}

function resultHint(review: CapabilityPromotionReview) {
  if (review.decision === "PASS") return "分数没有变化，只是阶段解锁了。";
  if (!review.modelEvaluated) return "本次不影响任何数据，稍后可以直接重新发起补充说明。";
  return "阶段保持不变。补充一段新的经历，或 7 天后可以再试一次。";
}

function submit() {
  if (!props.review || !canSubmit.value) return;
  emit(
    "submit",
    props.review.questions.map((question) => ({ questionId: question.id, answer: answerValues[question.id].trim() }))
  );
}
</script>

<template>
  <Teleport to="body">
    <Transition name="overlay">
      <div v-if="review" class="judge-dialog-layer promotion-review-layer" @click.self="emit('close')">
        <section class="judge-dialog" role="dialog" aria-modal="true" aria-labelledby="promotion-review-title">
          <header class="judge-dialog-header">
            <div>
              <p class="eyebrow">补充说明</p>
              <h2 id="promotion-review-title">{{ bucketName }}</h2>
            </div>
            <button class="icon-button" type="button" title="关闭" @click="emit('close')"><X :size="20" /></button>
          </header>

          <div class="judge-dialog-body">
            <section class="judge-context">
              <span class="judge-context-icon"><MessageSquareText :size="24" /></span>
              <div>
                <span>{{ review.status === "COMPLETED" ? "已完成" : "进行中" }}</span>
                <h3>你的分数已经够晋级了，聊聊你是怎么做到的</h3>
                <p>
                  阶段 {{ rankLabel(review.fromRank) }}
                  <ArrowRight :size="14" />
                  {{ rankLabel(review.targetRank) }} 或更高。补充说明只决定阶段，不会增加或扣除分数。
                </p>
              </div>
            </section>

            <div v-if="error" class="judge-error">{{ error }}</div>

            <form v-if="review.status === 'IN_PROGRESS'" class="judge-question-form" @submit.prevent="submit">
              <p>前两题围绕你自己的经历，请写出真实做过的事和当时的判断；第三题是相关的延伸思考。</p>
              <label v-for="(question, index) in review.questions" :key="question.id">
                <span>{{ String(index + 1).padStart(2, "0") }}</span>
                <div>
                  <strong>{{ question.prompt }}</strong>
                  <small>{{ QUESTION_KINDS[index] ?? "延伸" }} · {{ question.focus }}</small>
                  <textarea
                    v-model="answerValues[question.id]"
                    rows="5"
                    maxlength="4000"
                    placeholder="写下你实际做过什么、为什么这样判断（至少 8 个字）"
                  ></textarea>
                </div>
              </label>
              <button class="judge-submit-button" type="submit" :disabled="busy || !canSubmit">
                <LoaderCircle v-if="busy" class="spin" :size="18" />
                <span>{{ busy ? "正在判卷" : "提交补充说明" }}</span>
              </button>
            </form>

            <section v-else class="judge-result-view">
              <div class="judge-result-summary">
                <span :class="`is-${review.decision.toLowerCase()}`">
                  <CheckCircle2 v-if="review.decision === 'PASS'" :size="22" />
                  <Award v-else :size="22" />
                  {{ resultTitle(review) }}
                </span>
                <template v-if="review.rubric && review.modelEvaluated">
                  <strong>{{ review.rubric.earnedScore }}</strong>
                  <small>/ {{ review.rubric.maxScore }} 分</small>
                </template>
              </div>
              <p>{{ resultHint(review) }}</p>
              <p v-if="review.rubric?.overallFeedback">{{ review.rubric.overallFeedback }}</p>
              <div class="judge-rubric-grid">
                <article v-for="(item, index) in review.rubric?.items ?? []" :key="item.questionId">
                  <div>
                    <strong>第 {{ index + 1 }} 题</strong>
                    <span>{{ item.score }} / {{ item.maxScore }}</span>
                  </div>
                  <p>{{ item.feedback }}</p>
                </article>
              </div>
            </section>
          </div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>
