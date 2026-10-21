<script setup lang="ts">
import { AlertCircle, AlertTriangle, CheckCircle2, Flag, LoaderCircle, MessageSquareText, ShieldAlert, X } from "@lucide/vue";
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";

import { achievementsApi } from "@/api/achievements";
import { ApiError } from "@/api/client";
import PromotionReviewDialog from "@/components/achievements/PromotionReviewDialog.vue";
import type {
  CapabilityBucketDetail,
  CapabilityMilestone,
  CapabilityPromotionReview,
  CapabilityTimelineItem,
  JudgeAnswer
} from "@/types/achievements";

const props = defineProps<{
  bucketId: number | null;
  bucketName: string;
}>();
const emit = defineEmits<{ close: []; changed: [] }>();

const detail = ref<CapabilityBucketDetail | null>(null);
const loading = ref(false);
const loadError = ref("");
const highlightedLinkId = ref<number | null>(null);
const activeReview = ref<CapabilityPromotionReview | null>(null);
const reviewBusy = ref(false);
const reviewError = ref("");
let requestSeq = 0;

const RANK_LABELS: Record<string, string> = {
  UNRATED: "待评估",
  FOUNDATION: "基础",
  DEVELOPING: "成长中",
  PROFICIENT: "熟练",
  ADVANCED: "进阶",
  EXPERT: "专家"
};
const SOURCE_LABELS: Record<string, string> = {
  ACTIVITY_COMPLETION: "活动",
  CHALLENGE_COMPLETION: "挑战",
  RESUME_INTERVIEW: "简历面试",
  COACH_LOG: "成长日志"
};
const FLAG_LABELS: Record<string, string> = {
  DIFFICULTY_GAP_OVER_40: "活动难度远高于当前水平",
  HIGH_SINGLE_ACTIVITY_GAIN: "单次成长幅度偏大",
  HIGH_DIFFICULTY_WITH_WEAK_EVIDENCE: "高难度但证据偏弱",
  STRONG_CLAIM_WITH_LOW_SOURCE_VERIFIABILITY: "来源可验证性较低",
  POSSIBLE_SCORE_FARMING: "与已有经历高度重复",
  RANK_PROMOTION_REQUIRES_JUDGE: "阶段晋级需要复核",
  LOW_CONFIDENCE_WITH_STRONG_CLAIM: "画像置信度低但声明较强",
  LOW_EXTRACTION_CONFIDENCE_FOR_HIGH_IMPACT_RESULT: "高影响结果的抽取置信度偏低"
};

// Newest first reads naturally as "what changed recently"; milestones stay chronological.
const timelineNewestFirst = computed(() => [...(detail.value?.timeline ?? [])].reverse());
const milestonesByLink = computed(() => {
  const map = new Map<number, CapabilityMilestone[]>();
  detail.value?.milestones.forEach((milestone) => {
    const linkId = milestone.evidenceLinkId;
    if (linkId !== null) map.set(linkId, [...(map.get(linkId) ?? []), milestone]);
  });
  return map;
});
const promotion = computed(() => detail.value?.promotion ?? null);
const passedTriggerIds = computed(
  () =>
    new Set(
      (promotion.value?.history ?? [])
        .filter((review) => review.decision === "PASS" && review.triggerEvidenceLinkId !== null)
        .map((review) => review.triggerEvidenceLinkId as number)
    )
);
const trajectory = computed(() => {
  const points = (detail.value?.timeline ?? [])
    .map((item) => item.score?.newAbilityScore)
    .filter((value): value is number => value !== null && value !== undefined)
    .map(Number);
  if (!points.length) return "";
  const series = [0, ...points];
  const step = series.length > 1 ? 300 / (series.length - 1) : 0;
  return series.map((value, index) => `${(index * step).toFixed(1)},${(60 - (value / 100) * 56).toFixed(1)}`).join(" ");
});

watch(
  () => props.bucketId,
  (bucketId) => {
    activeReview.value = null;
    reviewError.value = "";
    if (bucketId !== null) void load(bucketId);
  },
  { immediate: true }
);

onMounted(() => window.addEventListener("keydown", onKeydown));
onBeforeUnmount(() => window.removeEventListener("keydown", onKeydown));

async function load(bucketId: number, keepContent = false) {
  const seq = ++requestSeq;
  loading.value = !keepContent;
  loadError.value = "";
  if (!keepContent) detail.value = null;
  try {
    const data = await achievementsApi.capabilityBucketDetail(bucketId);
    if (seq === requestSeq) detail.value = data;
  } catch (error) {
    if (seq === requestSeq) {
      loadError.value = error instanceof ApiError || error instanceof Error ? error.message : "标签详情加载失败";
    }
  } finally {
    if (seq === requestSeq) loading.value = false;
  }
}

function onKeydown(event: KeyboardEvent) {
  if (event.key !== "Escape" || props.bucketId === null) return;
  // Esc closes the topmost layer only.
  if (activeReview.value) activeReview.value = null;
  else emit("close");
}

async function openReview() {
  if (!detail.value) return;
  reviewError.value = "";
  if (promotion.value?.openReview) {
    activeReview.value = promotion.value.openReview;
    return;
  }
  reviewBusy.value = true;
  try {
    activeReview.value = await achievementsApi.startPromotionReview(detail.value.bucketId);
  } catch (error) {
    reviewError.value = errorText(error, "暂时无法生成问题");
  } finally {
    reviewBusy.value = false;
  }
}

async function submitReview(answers: JudgeAnswer[]) {
  if (!activeReview.value || !detail.value) return;
  reviewBusy.value = true;
  reviewError.value = "";
  try {
    activeReview.value = await achievementsApi.submitPromotionReview(activeReview.value.id, answers);
    await load(detail.value.bucketId, true);
    emit("changed");
  } catch (error) {
    reviewError.value = errorText(error, "提交失败，请稍后重试");
  } finally {
    reviewBusy.value = false;
  }
}

function errorText(error: unknown, fallback: string) {
  return error instanceof ApiError || error instanceof Error ? error.message : fallback;
}

function isBlocking(item: CapabilityTimelineItem) {
  return Boolean(promotion.value?.pending) && promotion.value?.triggerEvidenceLinkId === item.evidenceLinkId;
}

async function focusEvidence(linkId: number) {
  highlightedLinkId.value = linkId;
  await nextTick();
  document.getElementById(`capability-evidence-${linkId}`)?.scrollIntoView({ behavior: "smooth", block: "center" });
}

function rankLabel(rank: string | null) {
  return rank ? RANK_LABELS[rank] ?? rank : "";
}

function milestoneTitle(milestone: CapabilityMilestone) {
  switch (milestone.type) {
    case "FIRST_EVIDENCE":
      return "第一次点亮";
    case "RANK_UP":
      return `晋级「${rankLabel(milestone.toRank)}」`;
    case "PROMOTION_PENDING_REVIEW":
      return `「${rankLabel(milestone.toRank)}」待补充说明`;
    case "REVIEW_PASSED":
      return `复核通过，晋级「${rankLabel(milestone.toRank)}」`;
    case "LARGEST_GAIN":
      return `单次最大成长 +${num(milestone.experienceGain)}`;
  }
}

/**
 * Only the evidence that opened the current hold needs action. Older REVIEW_REQUIRED rows from
 * before the promotion review existed are ordinary scored evidence for the user.
 */
function statusLabel(item: CapabilityTimelineItem) {
  if (isBlocking(item)) return "卡住晋级";
  if (passedTriggerIds.value.has(item.evidenceLinkId)) return "已通过复核";
  return "已计分";
}

function statusClass(item: CapabilityTimelineItem) {
  if (isBlocking(item)) return "is-blocking";
  if (passedTriggerIds.value.has(item.evidenceLinkId)) return "is-passed";
  return "is-scored";
}

function reviewDecisionLabel(review: CapabilityPromotionReview) {
  if (review.decision === "PASS") return `通过 · 晋级「${rankLabel(review.appliedRank)}」`;
  if (review.decision === "FAIL") return "未通过";
  return review.modelEvaluated ? "未得出结论" : "判卷不可用";
}

function roleLabel(item: CapabilityTimelineItem) {
  if (detail.value?.bucketType !== "FUNCTION") return "";
  return item.role === "PRIMARY" ? "主要职能" : item.role === "SECONDARY" ? "次要职能" : "";
}

function num(value: number | null | undefined, digits = 1) {
  if (value === null || value === undefined) return "—";
  const n = Number(value);
  return Number.isInteger(n) ? String(n) : n.toFixed(digits);
}

function pct(value: number | null | undefined) {
  if (value === null || value === undefined) return "—";
  return `${Math.round(Number(value) * 100)}%`;
}

function formatDate(value: string | null) {
  if (!value) return "";
  return new Intl.DateTimeFormat("zh-CN", { year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date(value));
}
</script>

<template>
  <Transition name="capability-drawer">
    <div v-if="bucketId !== null" class="capability-drawer-layer" @click.self="emit('close')">
      <aside class="capability-drawer" role="dialog" aria-modal="true" :aria-label="`${bucketName} 标签详情`">
        <header class="capability-drawer-header">
          <div>
            <p class="eyebrow">{{ detail?.bucketType === "FUNCTION" ? "核心职能" : "知识与技术" }}</p>
            <h2>{{ detail?.displayName ?? bucketName }}</h2>
            <small v-if="detail?.domainName || detail?.groupName">{{ [detail?.domainName, detail?.groupName].filter(Boolean).join(" · ") }}</small>
          </div>
          <button type="button" class="capability-drawer-close" aria-label="关闭" @click="emit('close')"><X :size="18" /></button>
        </header>

        <div class="capability-drawer-body">
          <div v-if="loading" class="achievement-loading"><LoaderCircle class="spin" :size="24" /><span>正在读取证据链</span></div>
          <div v-else-if="loadError" class="achievement-error"><AlertCircle :size="22" /><p>{{ loadError }}</p></div>

          <template v-else-if="detail">
            <section class="capability-score-summary">
              <div>
                <span>能力分</span>
                <strong>{{ num(detail.abilityScore) }}<small>/ 100</small></strong>
              </div>
              <div><span>阶段</span><strong>{{ rankLabel(detail.rankName) }}</strong></div>
              <div><span>可信度</span><strong>{{ pct(1 - detail.abilityUncertainty) }}</strong></div>
              <div><span>经验值</span><strong>{{ num(detail.experienceScore) }}</strong></div>
            </section>

            <svg v-if="trajectory" class="capability-trajectory" viewBox="0 0 300 62" preserveAspectRatio="none" aria-label="能力分变化轨迹">
              <polyline :points="trajectory" />
            </svg>

            <p class="capability-reconcile" :class="{ 'is-warning': !detail.consistency.experienceReconciled }">
              <CheckCircle2 v-if="detail.consistency.experienceReconciled" :size="14" />
              <ShieldAlert v-else :size="14" />
              <template v-if="detail.consistency.experienceReconciled">
                当前经验 {{ num(detail.experienceScore) }} 由下方 {{ detail.timeline.length }} 条证据逐条累加得到
              </template>
              <template v-else>
                证据合计 {{ num(detail.consistency.explainedExperience) }}，与当前经验 {{ num(detail.consistency.stateExperience) }} 不一致，可能存在早期迁移数据
              </template>
            </p>
            <section v-if="promotion?.pending" class="capability-promotion-banner" role="status">
              <AlertTriangle :size="18" />
              <div>
                <strong>分数已经够「{{ rankLabel(promotion.targetRank) }}」了，阶段还停在「{{ rankLabel(promotion.currentRank) }}」</strong>
                <p>
                  分数已全部计入。补充说明一下你的经历，通过后阶段会直接更新为当前分数对应的阶段。
                  <button v-if="promotion.triggerEvidenceLinkId" type="button" class="capability-link-button" @click="focusEvidence(promotion.triggerEvidenceLinkId)">查看是哪条经历</button>
                </p>
                <p v-if="!promotion.canStartReview && !promotion.openReview && promotion.retryAvailableAt" class="capability-promotion-wait">
                  上次补充说明未通过。补充一段新的经历，或在 {{ formatDate(promotion.retryAvailableAt) }} 之后可以再试。
                </p>
                <p v-if="reviewError && !activeReview" class="capability-promotion-error">{{ reviewError }}</p>
              </div>
              <button
                v-if="promotion.canStartReview || promotion.openReview"
                type="button"
                class="capability-review-button"
                :disabled="reviewBusy"
                @click="openReview"
              >
                <LoaderCircle v-if="reviewBusy && !activeReview" class="spin" :size="15" />
                <MessageSquareText v-else :size="15" />
                {{ promotion.openReview ? "继续补充说明" : reviewBusy ? "正在出题" : "补充说明" }}
              </button>
            </section>

            <section v-if="detail.milestones.length" class="capability-milestones" aria-label="里程碑">
              <button
                v-for="milestone in detail.milestones"
                :key="`${milestone.type}-${milestone.evidenceLinkId}`"
                type="button"
                :class="`is-${milestone.type.toLowerCase()}`"
                :disabled="milestone.evidenceLinkId === null"
                @click="milestone.evidenceLinkId !== null && focusEvidence(milestone.evidenceLinkId)"
              >
                <Flag :size="13" />
                <span>{{ milestoneTitle(milestone) }}</span>
                <small>{{ formatDate(milestone.occurredAt) }}</small>
              </button>
            </section>

            <p v-if="!detail.timeline.length" class="portfolio-panel-empty">该标签还没有可展示的计分证据。</p>
            <ol v-else class="capability-evidence-list">
              <li
                v-for="item in timelineNewestFirst"
                :id="`capability-evidence-${item.evidenceLinkId}`"
                :key="item.evidenceLinkId"
                :class="{ 'is-highlighted': highlightedLinkId === item.evidenceLinkId, 'is-review': isBlocking(item) }"
              >
                <i aria-hidden="true"></i>
                <article>
                  <header>
                    <small>{{ formatDate(item.occurredAt) }} · {{ SOURCE_LABELS[item.sourceKind] ?? item.sourceKind }}<template v-if="roleLabel(item)"> · {{ roleLabel(item) }}</template></small>
                    <span class="capability-status" :class="statusClass(item)">
                      <AlertTriangle v-if="isBlocking(item)" :size="11" />{{ statusLabel(item) }}
                    </span>
                  </header>
                  <h4>{{ item.title ?? `${SOURCE_LABELS[item.sourceKind] ?? "证据"} #${item.sourceId}` }}</h4>
                  <div v-if="item.score" class="capability-delta">
                    <strong>经验 +{{ num(item.score.experienceGain, 2) }}</strong>
                    <span>能力 {{ num(item.score.oldAbilityScore) }} → {{ num(item.score.newAbilityScore) }}</span>
                    <span>相关度 {{ pct(item.relevance) }}</span>
                  </div>
                  <div v-if="milestonesByLink.get(item.evidenceLinkId)" class="capability-inline-milestones">
                    <span v-for="milestone in milestonesByLink.get(item.evidenceLinkId)" :key="milestone.type"><Flag :size="11" />{{ milestoneTitle(milestone) }}</span>
                  </div>
                  <div v-if="isBlocking(item)" class="capability-blocking-note">
                    <p>这次经历让分数跨过了「{{ rankLabel(promotion?.targetRank ?? null) }}」的门槛，晋级前需要你补充说明。</p>
                    <button
                      v-if="promotion?.canStartReview || promotion?.openReview"
                      type="button"
                      class="capability-review-button"
                      :disabled="reviewBusy"
                      @click="openReview"
                    >
                      <MessageSquareText :size="14" />{{ promotion?.openReview ? "继续补充说明" : "补充说明" }}
                    </button>
                  </div>
                  <p v-if="item.claimedOutcome" class="capability-outcome">{{ item.claimedOutcome }}</p>
                  <ul v-if="item.evidenceFindings.length" class="capability-findings">
                    <li v-for="finding in item.evidenceFindings" :key="finding">{{ finding }}</li>
                  </ul>
                  <div v-if="item.judgeFlags.length || item.riskFlags.length" class="capability-flags">
                    <span v-for="flag in item.judgeFlags" :key="`j-${flag}`" class="is-judge">{{ FLAG_LABELS[flag] ?? flag }}</span>
                    <span v-for="flag in item.riskFlags" :key="`r-${flag}`">{{ FLAG_LABELS[flag] ?? flag }}</span>
                  </div>
                  <details v-if="item.factors" class="capability-factors">
                    <summary>评分依据</summary>
                    <dl>
                      <div><dt>活动难度</dt><dd>{{ num(item.factors.activityDifficulty) }} / 100</dd></div>
                      <div><dt>完成质量</dt><dd>{{ pct(item.factors.completionQuality) }}</dd></div>
                      <div><dt>个人贡献</dt><dd>{{ pct(item.factors.personalContribution) }}</dd></div>
                      <div><dt>证据质量</dt><dd>{{ pct(item.factors.evidenceQuality) }}</dd></div>
                      <div><dt>新颖度</dt><dd>{{ pct(item.factors.novelty) }}</dd></div>
                      <div><dt>来源可信度</dt><dd>{{ pct(item.factors.sourceCredibilityPrior) }}</dd></div>
                      <div><dt>评估置信度</dt><dd>{{ pct(item.factors.assessmentConfidence) }}</dd></div>
                      <div>
                        <dt>相关度</dt>
                        <dd>{{ pct(item.factors.originalRelevance) }}<template v-if="item.factors.adjustedRelevance !== item.factors.originalRelevance"> → {{ pct(item.factors.adjustedRelevance) }}</template></dd>
                      </div>
                    </dl>
                    <small>规则版本 {{ item.score?.scoringRuleVersion }}。分数由 Java 评分引擎按固定公式计算，模型只提供归因和事实点。<template v-if="item.agentRunId"> 追踪编号 #{{ item.agentRunId }}，反馈问题时可以附上。</template></small>
                  </details>
                </article>
              </li>
            </ol>

            <section v-if="promotion?.history.length" class="capability-review-history" aria-label="补充说明记录">
              <h4>补充说明记录</h4>
              <ul>
                <li v-for="review in promotion.history" :key="review.id" :class="`is-${review.decision.toLowerCase()}`">
                  <span>{{ formatDate(review.completedAt) }}</span>
                  <strong>{{ reviewDecisionLabel(review) }}</strong>
                  <small v-if="review.rubric && review.modelEvaluated">{{ review.rubric.earnedScore }} / {{ review.rubric.maxScore }}</small>
                </li>
              </ul>
            </section>
          </template>
        </div>
      </aside>
    </div>
  </Transition>

  <PromotionReviewDialog
    :review="activeReview"
    :bucket-name="detail?.displayName ?? bucketName"
    :busy="reviewBusy"
    :error="reviewError"
    @close="activeReview = null"
    @submit="submitReview"
  />
</template>
