<script setup lang="ts">
import {
  AlertCircle,
  BookOpenCheck,
  Check,
  ChevronLeft,
  ChevronRight,
  CircleGauge,
  ClipboardList,
  Flag,
  History,
  LoaderCircle,
  Medal,
  RefreshCw,
  Save,
  ShieldCheck,
  Sparkles,
  Star,
  Tags,
  Target,
  TrendingUp
} from "@lucide/vue";
import { computed, onMounted, reactive, ref } from "vue";

import { achievementsApi } from "@/api/achievements";
import { ApiError } from "@/api/client";
import JudgeDialog from "@/components/achievements/JudgeDialog.vue";
import { useAuthStore } from "@/stores/auth";
import type {
  AbilityScoreResult,
  AbilityCluster,
  AbilityEvidenceTimeline,
  AbilityState,
  AchievementRecord,
  AchievementSummary,
  GrowthEvidence,
  GrowthTag,
  GrowthTagDetail,
  JudgeAnswer,
  JudgeAssessment,
  PageResponse
} from "@/types/achievements";

type AchievementView = "abilities" | "history";

const authStore = useAuthStore();
const activeView = ref<AchievementView>("abilities");
const loading = ref(true);
const loadError = ref("");
const refreshing = ref(false);

const summary = ref<AchievementSummary>({
  completedCount: 0,
  categoryCount: 0,
  categoryCounts: []
});
const states = ref<AbilityState[]>([]);
const clusters = ref<AbilityCluster[]>([]);
const results = ref<AbilityScoreResult[]>([]);
const tags = ref<GrowthTag[]>([]);
const judges = ref<JudgeAssessment[]>([]);
const tagDetails = reactive(new Map<number, GrowthTagDetail>());
const abilityEvidenceTimelines = reactive(new Map<number, AbilityEvidenceTimeline>());
const loadingTagId = ref<number | null>(null);
const loadingEvidenceStateId = ref<number | null>(null);
const selectedCapabilityKey = ref("");
const selectedMemberStateId = ref<number | null>(null);

const historyPage = ref<PageResponse<AchievementRecord>>({
  content: [],
  page: 0,
  size: 5,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true
});
const historyLoading = ref(false);
const reflectionDrafts = reactive<Record<number, { did: string; learned: string }>>({});
const savingRecordId = ref<number | null>(null);

const appealDrafts = reactive<Record<number, string>>({});
const appealBusyId = ref<number | null>(null);
const submittedAppeals = ref<number[]>([]);
const notice = ref("");
let noticeTimer: ReturnType<typeof setTimeout> | undefined;

const judgeOpen = ref(false);
const activeJudge = ref<JudgeAssessment | null>(null);
const judgeBusy = ref(false);
const judgeError = ref("");

const scoredMode = computed(() => states.value.length > 0);
const capabilities = computed(() => {
  if (scoredMode.value) {
    if (clusters.value.length) {
      return clusters.value
        .slice()
        .sort((a, b) => b.abilityScore - a.abilityScore || b.experienceScore - a.experienceScore)
        .map((cluster) => {
          const representative = states.value.find(
            (state) => state.id === cluster.members[0]?.abilityStateId
          ) ?? null;
          return {
            key: cluster.clusterKey,
            id: representative?.id ?? 0,
            name: cluster.name,
            description:
              cluster.memberCount > 1
                ? `由 ${cluster.members.map((member) => member.dimension).join("、")} 聚合而成。`
                : representative
                  ? matchingTag(representative)?.description ?? "来自活动证据与确定性评分引擎。"
                  : "来自活动证据与确定性评分引擎。",
            strength: cluster.abilityScore,
            evidenceCount: cluster.members.reduce(
              (sum, member) =>
                sum + resultsForMember(member.normalizedDimension).length,
              0
            ),
            rank: rankLabel(cluster.rank),
            pendingJudge: cluster.members.some((member) =>
              Boolean(pendingJudgeForState(member.abilityStateId))
            ),
            source: "cluster" as const,
            state: representative,
            tag: representative ? matchingTag(representative) : null,
            cluster
          };
        });
    }
    return states.value
      .slice()
      .sort((a, b) => b.abilityScore - a.abilityScore || b.experienceScore - a.experienceScore)
      .map((state) => ({
        key: `state-${state.id}`,
        id: state.id,
        name: state.dimension,
        description: matchingTag(state)?.description ?? "来自活动证据与确定性评分引擎。",
        strength: state.abilityScore,
        evidenceCount: matchingTag(state)?.evidenceCount ?? resultsForState(state).length,
        rank: rankLabel(state.rank),
        pendingJudge: Boolean(pendingJudgeForState(state.id)),
        source: "state" as const,
        state,
        tag: matchingTag(state),
        cluster: null
      }));
  }

  return tags.value
    .slice()
    .sort((a, b) => b.score - a.score || b.importanceScore - a.importanceScore)
    .map((tag) => ({
      key: `tag-${tag.id}`,
      id: tag.id,
      name: tag.name,
      description: tag.description,
      strength: tag.score,
      evidenceCount: tag.evidenceCount,
      rank: "待评分",
      pendingJudge: false,
      source: "tag" as const,
      state: null,
      tag,
      cluster: null
    }));
});

const selectedCapability = computed(
  () =>
    capabilities.value.find((item) => item.key === selectedCapabilityKey.value) ??
    capabilities.value[0] ??
    null
);
const selectedCluster = computed(() => selectedCapability.value?.cluster ?? null);
const selectedClusterStates = computed(() =>
  selectedCluster.value
    ? selectedCluster.value.members
        .map((member) => states.value.find((state) => state.id === member.abilityStateId))
        .filter((state): state is AbilityState => Boolean(state))
    : []
);
const selectedState = computed(() => {
  if (!selectedCluster.value) return selectedCapability.value?.state ?? null;
  return (
    selectedClusterStates.value.find((state) => state.id === selectedMemberStateId.value) ??
    selectedClusterStates.value[0] ??
    null
  );
});
const selectedTag = computed(() =>
  selectedState.value ? matchingTag(selectedState.value) : selectedCapability.value?.tag ?? null
);
const selectedTagDetail = computed(() =>
  selectedTag.value ? tagDetails.get(selectedTag.value.id) ?? null : null
);
const selectedAbilityEvidence = computed(() =>
  selectedState.value ? abilityEvidenceTimelines.get(selectedState.value.id) ?? null : null
);
const selectedEvidenceItems = computed(() =>
  selectedState.value
    ? selectedAbilityEvidence.value?.evidences ?? []
    : selectedTagDetail.value?.evidences ?? []
);
const selectedResults = computed(() =>
  selectedState.value ? resultsForState(selectedState.value).slice(0, 6) : []
);
const selectedJudge = computed(() =>
  selectedState.value ? pendingJudgeForState(selectedState.value.id) : null
);
const averageAbility = computed(() => {
  if (clusters.value.length) {
    return clusters.value.reduce((sum, item) => sum + item.abilityScore, 0) / clusters.value.length;
  }
  if (!states.value.length) return 0;
  return states.value.reduce((sum, item) => sum + Number(item.abilityScore), 0) / states.value.length;
});
const verifiedResultCount = computed(
  () => results.value.filter((result) => result.status === "VERIFIED").length
);
const milestoneCount = computed(() =>
  [...tagDetails.values()].reduce(
    (sum, detail) => sum + detail.evidences.filter((evidence) => evidence.milestone).length,
    0
  )
);
const avatarInitial = computed(() => authStore.user?.username.slice(0, 1).toUpperCase() ?? "D");

onMounted(async () => {
  await loadAll();
});

async function loadAll() {
  loading.value = true;
  loadError.value = "";
  try {
    const [summaryData, stateData, clusterData, resultData, tagData, judgeData, historyData] =
      await Promise.all([
        achievementsApi.summary(),
        achievementsApi.abilityStates(),
        achievementsApi.abilityClusters().catch(() => []),
        achievementsApi.abilityResults(),
        achievementsApi.growthTags(),
        achievementsApi.judges(),
        achievementsApi.history(0, 5)
      ]);
    summary.value = summaryData;
    states.value = stateData.map(normalizeState);
    clusters.value = clusterData.map(normalizeCluster);
    results.value = resultData.map(normalizeResult);
    tags.value = tagData;
    judges.value = judgeData.map(normalizeJudge);
    assignHistory(historyData);
    ensureSelection();
    await loadSelectedTagDetail();
  } catch (error) {
    loadError.value = errorMessage(error, "成长档案暂时没有加载出来");
  } finally {
    loading.value = false;
  }
}

async function refreshAbilityData() {
  refreshing.value = true;
  try {
    const [summaryData, stateData, clusterData, resultData, tagData, judgeData] = await Promise.all([
      achievementsApi.summary(),
      achievementsApi.abilityStates(),
      achievementsApi.abilityClusters().catch(() => []),
      achievementsApi.abilityResults(),
      achievementsApi.growthTags(),
      achievementsApi.judges()
    ]);
    summary.value = summaryData;
    states.value = stateData.map(normalizeState);
    clusters.value = clusterData.map(normalizeCluster);
    results.value = resultData.map(normalizeResult);
    tags.value = tagData;
    judges.value = judgeData.map(normalizeJudge);
    tagDetails.clear();
    abilityEvidenceTimelines.clear();
    ensureSelection();
    await loadSelectedTagDetail();
    showNotice("能力状态已同步");
  } catch (error) {
    showNotice(errorMessage(error, "同步失败"));
  } finally {
    refreshing.value = false;
  }
}

function ensureSelection() {
  if (!capabilities.value.some((item) => item.key === selectedCapabilityKey.value)) {
    selectedCapabilityKey.value = capabilities.value[0]?.key ?? "";
  }
  ensureMemberSelection();
}

async function selectCapability(key: string) {
  selectedCapabilityKey.value = key;
  selectedMemberStateId.value = null;
  ensureMemberSelection();
  await loadSelectedTagDetail();
}

function selectMember(stateId: number) {
  selectedMemberStateId.value = stateId;
  void loadSelectedTagDetail();
}

function ensureMemberSelection() {
  if (!selectedCluster.value) {
    selectedMemberStateId.value = selectedCapability.value?.state?.id ?? null;
    return;
  }
  if (!selectedClusterStates.value.some((state) => state.id === selectedMemberStateId.value)) {
    selectedMemberStateId.value = selectedClusterStates.value[0]?.id ?? null;
  }
}

async function loadSelectedTagDetail() {
  const state = selectedState.value;
  if (state && !abilityEvidenceTimelines.has(state.id) && loadingEvidenceStateId.value !== state.id) {
    loadingEvidenceStateId.value = state.id;
    try {
      abilityEvidenceTimelines.set(state.id, await achievementsApi.abilityEvidence(state.id));
    } catch (error) {
      showNotice(errorMessage(error, "能力证据读取失败"));
    } finally {
      loadingEvidenceStateId.value = null;
    }
    return;
  }

  const tag = selectedTag.value;
  if (!tag || tagDetails.has(tag.id) || loadingTagId.value === tag.id) {
    return;
  }
  loadingTagId.value = tag.id;
  try {
    tagDetails.set(tag.id, await achievementsApi.growthTagDetail(tag.id));
  } catch (error) {
    showNotice(errorMessage(error, "成长证据读取失败"));
  } finally {
    loadingTagId.value = null;
  }
}

async function toggleMilestone(evidence: GrowthEvidence) {
  try {
    const next = !evidence.milestone;
    const abilityName = selectedState.value?.dimension ?? selectedTag.value?.name ?? selectedCapability.value?.name;
    const updated = await achievementsApi.markMilestone(
      evidence.id,
      next,
      next ? evidence.milestoneReason || `${evidence.title} 是「${abilityName}」的重要成长节点。` : ""
    );
    const tag = selectedTag.value;
    const detail = tag ? tagDetails.get(tag.id) : null;
    if (detail) {
      detail.evidences = detail.evidences.map((item) => (item.id === updated.id ? updated : item));
    }
    if (selectedState.value) {
      const timeline = abilityEvidenceTimelines.get(selectedState.value.id);
      if (timeline) {
        timeline.evidences = timeline.evidences.map((item) => (item.id === updated.id ? updated : item));
      }
    }
    showNotice(next ? "已标记为重要里程碑" : "已取消里程碑");
  } catch (error) {
    showNotice(errorMessage(error, "里程碑更新失败"));
  }
}

async function loadHistory(page: number) {
  historyLoading.value = true;
  try {
    assignHistory(await achievementsApi.history(page, 5));
  } catch (error) {
    showNotice(errorMessage(error, "历史记录加载失败"));
  } finally {
    historyLoading.value = false;
  }
}

function assignHistory(page: PageResponse<AchievementRecord>) {
  historyPage.value = page;
  page.content.forEach((record) => {
    reflectionDrafts[record.id] = {
      did: record.did ?? "",
      learned: record.learned ?? ""
    };
  });
}

async function saveReflection(record: AchievementRecord) {
  const draft = reflectionDrafts[record.id];
  savingRecordId.value = record.id;
  try {
    const updated = await achievementsApi.updateReflection(record.id, draft.did, draft.learned);
    historyPage.value.content = historyPage.value.content.map((item) =>
      item.id === updated.id ? updated : item
    );
    showNotice("复盘已保存，画像与能力证据将异步更新");
  } catch (error) {
    showNotice(errorMessage(error, "复盘保存失败"));
  } finally {
    savingRecordId.value = null;
  }
}

async function submitAppeal(result: AbilityScoreResult) {
  const reason = (appealDrafts[result.id] ?? "").trim();
  if (!reason) {
    showNotice("请先说明评分中遗漏或判断不准确的证据");
    return;
  }
  appealBusyId.value = result.id;
  try {
    await achievementsApi.appeal(result.id, reason);
    submittedAppeals.value = [...submittedAppeals.value, result.id];
    appealDrafts[result.id] = "";
    showNotice("评分申诉已提交");
  } catch (error) {
    showNotice(errorMessage(error, "申诉提交失败"));
  } finally {
    appealBusyId.value = null;
  }
}

async function openJudge(judge: JudgeAssessment) {
  judgeError.value = "";
  try {
    activeJudge.value = normalizeJudge(await achievementsApi.judge(judge.id));
  } catch {
    activeJudge.value = judge;
  }
  judgeOpen.value = true;
}

async function startJudge(judge: JudgeAssessment) {
  judgeBusy.value = true;
  judgeError.value = "";
  try {
    activeJudge.value = normalizeJudge(await achievementsApi.startJudge(judge.id));
    replaceJudge(activeJudge.value);
  } catch (error) {
    judgeError.value = errorMessage(error, "题目生成失败");
  } finally {
    judgeBusy.value = false;
  }
}

async function submitJudge(judge: JudgeAssessment, answers: JudgeAnswer[]) {
  judgeBusy.value = true;
  judgeError.value = "";
  try {
    activeJudge.value = normalizeJudge(await achievementsApi.submitJudge(judge.id, answers));
    replaceJudge(activeJudge.value);
    await refreshAbilityData();
  } catch (error) {
    judgeError.value = errorMessage(error, "Judge 判卷失败");
  } finally {
    judgeBusy.value = false;
  }
}

function replaceJudge(updated: JudgeAssessment) {
  const index = judges.value.findIndex((item) => item.id === updated.id);
  if (index >= 0) {
    judges.value[index] = updated;
  } else {
    judges.value.unshift(updated);
  }
}

function matchingTag(state: AbilityState) {
  const normalized = canonical(state.normalizedDimension || state.dimension);
  return (
    tags.value.find((tag) => canonical(tag.normalizedName || tag.name) === normalized) ??
    tags.value.find((tag) => {
      const candidate = canonical(tag.name);
      const dimension = canonical(state.dimension);
      return candidate && dimension && (candidate.includes(dimension) || dimension.includes(candidate));
    }) ??
    null
  );
}

function resultsForState(state: AbilityState) {
  return results.value
    .filter((result) => canonical(result.dimension) === canonical(state.dimension))
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
}

function resultsForMember(normalizedDimension: string) {
  const state = states.value.find(
    (item) => canonical(item.normalizedDimension) === canonical(normalizedDimension)
  );
  return state ? resultsForState(state) : [];
}

function pendingJudgeForState(stateId: number) {
  return judges.value
    .filter((judge) => judge.abilityStateId === stateId)
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
    .find((judge) => judge.status !== "COMPLETED" || judge.decision === "MANUAL_REVIEW");
}

function capabilityLevel(strength: number) {
  if (strength >= 60) return "high";
  if (strength >= 25) return "medium";
  return "low";
}

function statusLabel(status: AbilityScoreResult["status"]) {
  return (
    {
      PROVISIONAL: "暂定",
      VERIFIED: "已验证",
      REVIEW_REQUIRED: "待 Judge",
      SUPERSEDED: "已替换"
    }[status] ?? status
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

function formatNumber(value: number) {
  return Number.isInteger(value) ? String(value) : value.toFixed(1);
}

function formatDate(value: string | null) {
  if (!value) return "时间未知";
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  }).format(new Date(value));
}

function canonical(value: string) {
  return String(value ?? "")
    .trim()
    .toLowerCase()
    .replace(/[\s_]+/g, "-")
    .replace(/[^a-z0-9\u4e00-\u9fff-]/g, "");
}

function normalizeState(state: AbilityState): AbilityState {
  return {
    ...state,
    experienceScore: Number(state.experienceScore ?? 0),
    abilityScore: Number(state.abilityScore ?? 0),
    abilityUncertainty: Number(state.abilityUncertainty ?? 1)
  };
}

function normalizeCluster(cluster: AbilityCluster): AbilityCluster {
  return {
    ...cluster,
    abilityScore: Number(cluster.abilityScore ?? 0),
    experienceScore: Number(cluster.experienceScore ?? 0),
    abilityUncertainty: Number(cluster.abilityUncertainty ?? 1),
    members: (cluster.members ?? []).map((member) => ({
      ...member,
      experienceScore: Number(member.experienceScore ?? 0),
      abilityScore: Number(member.abilityScore ?? 0),
      abilityUncertainty: Number(member.abilityUncertainty ?? 1),
      similarityToCluster: Number(member.similarityToCluster ?? 0)
    }))
  };
}

function normalizeResult(result: AbilityScoreResult): AbilityScoreResult {
  return {
    ...result,
    verifiedExperienceGain: Number(result.verifiedExperienceGain ?? 0),
    oldAbilityScore: Number(result.oldAbilityScore ?? 0),
    newAbilityScore: Number(result.newAbilityScore ?? 0),
    newAbilityUncertainty: Number(result.newAbilityUncertainty ?? 1)
  };
}

function normalizeJudge(judge: JudgeAssessment): JudgeAssessment {
  return {
    ...judge,
    abilityScoreAtTrigger: Number(judge.abilityScoreAtTrigger ?? 0),
    confidenceBefore: Number(judge.confidenceBefore ?? 0.5),
    confidenceDelta: Number(judge.confidenceDelta ?? 0),
    confidenceAfter: judge.confidenceAfter === null ? null : Number(judge.confidenceAfter),
    questions: judge.questions ?? [],
    answers: judge.answers ?? [],
    triggerReasons: judge.triggerReasons ?? []
  };
}

function showNotice(message: string) {
  notice.value = message;
  if (noticeTimer) clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => {
    notice.value = "";
  }, 3000);
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError || error instanceof Error ? error.message : fallback;
}
</script>

<template>
  <section class="achievement-workspace">
    <header class="achievement-hero">
      <div>
        <p class="eyebrow">Growth Portfolio</p>
        <h2>你的成长，不只是一串参加记录</h2>
        <p>活动证据、个人复盘、确定性评分与 Judge 验证共同组成这份能力档案。</p>
      </div>
      <button type="button" :disabled="refreshing" @click="refreshAbilityData">
        <RefreshCw :class="{ spin: refreshing }" :size="18" />
        同步评分状态
      </button>
    </header>

    <div v-if="loading" class="achievement-loading">
      <LoaderCircle class="spin" :size="28" />
      <span>正在整理成长证据</span>
    </div>
    <div v-else-if="loadError" class="achievement-error">
      <AlertCircle :size="26" />
      <h3>成长档案暂时没有加载出来</h3>
      <p>{{ loadError }}</p>
      <button type="button" @click="loadAll">重新加载</button>
    </div>

    <template v-else>
      <section class="achievement-stats">
        <article>
          <span><BookOpenCheck :size="19" /></span>
          <div><strong>{{ summary.completedCount }}</strong><small>完成经历</small></div>
        </article>
        <article>
          <span><Tags :size="19" /></span>
          <div><strong>{{ capabilities.length }}</strong><small>能力方向</small></div>
        </article>
        <article>
          <span><CircleGauge :size="19" /></span>
          <div><strong>{{ formatNumber(averageAbility) }}</strong><small>能力均值</small></div>
        </article>
        <article>
          <span><ShieldCheck :size="19" /></span>
          <div><strong>{{ verifiedResultCount }}</strong><small>已验证评分</small></div>
        </article>
      </section>

      <nav class="achievement-view-tabs" aria-label="个人成就视图">
        <button
          type="button"
          :class="{ 'is-active': activeView === 'abilities' }"
          @click="activeView = 'abilities'"
        >
          <Medal :size="18" />
          能力档案
        </button>
        <button
          type="button"
          :class="{ 'is-active': activeView === 'history' }"
          @click="activeView = 'history'"
        >
          <History :size="18" />
          成长记录
        </button>
      </nav>

      <section v-if="activeView === 'abilities'" class="ability-portfolio">
        <section class="ability-index-panel">
          <header>
            <div>
              <p class="eyebrow">Ability Map</p>
              <h3>{{ scoredMode ? "已评分能力" : "已抽取成长标签" }}</h3>
            </div>
            <span>{{ scoredMode ? "评分引擎已接入" : "等待异步评分" }}</span>
          </header>

          <div v-if="!capabilities.length" class="ability-empty">
            <Target :size="30" />
            <h3>能力档案正在等待第一条证据</h3>
            <p>完成活动或挑战后，系统会异步抽取证据并生成能力状态。</p>
          </div>

          <template v-else>
            <div class="ability-owner">
              <span>{{ avatarInitial }}</span>
              <div>
                <strong>{{ authStore.user?.username }}</strong>
                <small>
                  {{ capabilities.length }} 项方向
                  <template v-if="milestoneCount"> · {{ milestoneCount }} 个里程碑</template>
                </small>
              </div>
            </div>

            <div class="ability-tag-grid">
              <button
                v-for="capability in capabilities"
                :key="capability.key"
                type="button"
                :class="[
                  `is-${capabilityLevel(capability.strength)}`,
                  { 'is-active': selectedCapability?.key === capability.key }
                ]"
                @click="selectCapability(capability.key)"
              >
                <span>
                  <strong>{{ capability.name }}</strong>
                  <em v-if="capability.pendingJudge">待验证</em>
                </span>
                <small>
                  {{ capability.source !== "tag" ? `${formatNumber(capability.strength)} 分` : `${capability.evidenceCount} 条证据` }}
                  · {{ capability.rank }}
                </small>
                <i :style="{ width: `${Math.max(8, Math.min(capability.strength, 100))}%` }"></i>
              </button>
            </div>

            <div v-if="!scoredMode" class="ability-stage-note">
              <Sparkles :size="18" />
              <p>
                这些标签已经由成长标签 Agent 从经历中抽取，但尚未产生新版确定性能力分。评分完成后会自动显示能力分、可信度和 Judge 状态。
              </p>
            </div>
          </template>
        </section>

        <section v-if="selectedCapability" class="ability-detail-panel">
          <header class="ability-detail-main">
            <div>
              <span>{{ selectedCapability.rank }}</span>
              <h3>{{ selectedCapability.name }}</h3>
              <p>{{ selectedCapability.description }}</p>
            </div>
            <div
              class="ability-score-ring"
              :style="{ '--score-progress': `${Math.min(selectedCapability.strength, 100)}%` }"
            >
              <strong>{{ formatNumber(selectedCapability.strength) }}</strong>
              <small>{{ scoredMode ? "能力分 / 100" : "标签经验" }}</small>
            </div>
          </header>

          <div v-if="selectedState" class="ability-metrics">
            <article>
              <TrendingUp :size="18" />
              <span>
                <strong>{{ formatNumber(selectedCluster?.experienceScore ?? selectedState.experienceScore) }}</strong>
                <small>{{ selectedCluster?.memberCount && selectedCluster.memberCount > 1 ? "聚合经验" : "经验值" }}</small>
              </span>
            </article>
            <article>
              <ShieldCheck :size="18" />
              <span>
                <strong>{{ Math.round((1 - (selectedCluster?.abilityUncertainty ?? selectedState.abilityUncertainty)) * 100) }}%</strong>
                <small>可信程度</small>
              </span>
            </article>
            <article>
              <CircleGauge :size="18" />
              <span>
                <strong>{{ (selectedCluster?.abilityUncertainty ?? selectedState.abilityUncertainty).toFixed(2) }}</strong>
                <small>不确定度</small>
              </span>
            </article>
          </div>

          <section v-if="selectedCluster && selectedCluster.memberCount > 1" class="ability-subskill-panel">
            <header>
              <div>
                <h4>子能力构成</h4>
                <p>主能力用于总览，评分、Judge 和证据仍绑定到具体子能力。</p>
              </div>
              <span>HAC · {{ selectedCluster.memberCount }} 项</span>
            </header>
            <div>
              <button
                v-for="member in selectedCluster.members"
                :key="member.abilityStateId"
                type="button"
                :class="{ 'is-active': selectedState?.id === member.abilityStateId }"
                @click="selectMember(member.abilityStateId)"
              >
                <span>
                  <strong>{{ member.dimension }}</strong>
                  <small>与主能力相似度 {{ Math.round(member.similarityToCluster * 100) }}%</small>
                </span>
                <em>{{ formatNumber(member.abilityScore) }}</em>
              </button>
            </div>
          </section>

          <section v-if="selectedJudge" class="ability-judge-callout">
            <div>
              <span>需要能力验证</span>
              <strong>这次高影响评分需要进一步确认</strong>
              <p>完成短验证后，系统会确认等级并调整画像可信度。</p>
            </div>
            <button type="button" @click="openJudge(selectedJudge)">
              {{ selectedJudge.status === "IN_PROGRESS" ? "继续作答" : "开始验证" }}
            </button>
          </section>

          <section class="ability-section">
            <header>
              <div>
                <h4>评分记录</h4>
                <p>每次能力变化都保留规则版本和状态</p>
              </div>
              <span>{{ selectedResults.length }}</span>
            </header>

            <div v-if="!selectedResults.length" class="ability-section-empty">
              <ClipboardList :size="24" />
              <p>{{ scoredMode ? "当前能力还没有可展示的评分记录。" : "等待新版评分引擎生成记录。" }}</p>
            </div>
            <div v-else class="ability-result-list-vue">
              <article v-for="result in selectedResults" :key="result.id">
                <div class="ability-result-summary-vue">
                  <span :class="`status-${result.status.toLowerCase()}`">{{ statusLabel(result.status) }}</span>
                  <strong>经验 +{{ formatNumber(result.verifiedExperienceGain) }}</strong>
                </div>
                <p>
                  能力分 {{ formatNumber(result.oldAbilityScore) }}
                  <ChevronRight :size="14" />
                  {{ formatNumber(result.newAbilityScore) }}
                </p>
                <small>{{ formatDate(result.createdAt) }} · {{ result.scoringRuleVersion }}</small>
                <details class="ability-appeal-panel">
                  <summary>对这次评分有疑问</summary>
                  <textarea
                    v-model="appealDrafts[result.id]"
                    rows="3"
                    maxlength="1200"
                    placeholder="说明遗漏或判断不准确的证据"
                  ></textarea>
                  <button
                    type="button"
                    :disabled="appealBusyId === result.id || submittedAppeals.includes(result.id)"
                    @click="submitAppeal(result)"
                  >
                    <Check v-if="submittedAppeals.includes(result.id)" :size="16" />
                    <LoaderCircle v-else-if="appealBusyId === result.id" class="spin" :size="16" />
                    {{ submittedAppeals.includes(result.id) ? "已提交" : "提交申诉" }}
                  </button>
                </details>
              </article>
            </div>
          </section>

          <section class="ability-section milestone-section">
            <header>
              <div>
                <h4>成长证据与里程碑</h4>
                <p>查看这个能力由哪些真实经历构成</p>
              </div>
              <span>{{ selectedEvidenceItems.length || selectedCapability.evidenceCount }}</span>
            </header>

            <div
              v-if="loadingEvidenceStateId === selectedState?.id || loadingTagId === selectedTag?.id"
              class="ability-section-empty"
            >
              <LoaderCircle class="spin" :size="22" />
              <p>正在读取成长证据</p>
            </div>
            <div
              v-else-if="selectedAbilityEvidence?.status === 'TAG_EXTRACTION_PENDING'"
              class="ability-section-empty"
            >
              <LoaderCircle class="spin" :size="22" />
              <p>能力评分已经完成，成长标签证据仍在异步整理，稍后会自动关联。</p>
            </div>
            <div v-else-if="selectedState && !selectedEvidenceItems.length" class="ability-section-empty">
              <AlertCircle :size="23" />
              <p>这项能力已有评分记录，但对应活动暂时没有可展示的成长证据。</p>
            </div>
            <div v-else-if="!selectedEvidenceItems.length" class="ability-section-empty">
              <Flag :size="23" />
              <p>当前标签还没有可展示的证据。</p>
            </div>
            <div v-else class="milestone-timeline">
              <article
                v-for="(evidence, index) in selectedEvidenceItems"
                :key="evidence.id"
                :class="{ 'is-milestone': evidence.milestone }"
              >
                <div class="milestone-marker">
                  <Star v-if="evidence.milestone" :size="16" fill="currentColor" />
                  <span v-else>{{ index + 1 }}</span>
                </div>
                <div class="milestone-card">
                  <div>
                    <span>{{ formatDate(evidence.occurredAt) }}</span>
                    <strong>+{{ evidence.scoreDelta }}</strong>
                  </div>
                  <h5>{{ evidence.title }}</h5>
                  <p>{{ evidence.summary }}</p>
                  <small v-if="evidence.did">做了：{{ evidence.did }}</small>
                  <small v-if="evidence.learned">学到：{{ evidence.learned }}</small>
                  <button type="button" @click="toggleMilestone(evidence)">
                    <Star :size="15" :fill="evidence.milestone ? 'currentColor' : 'none'" />
                    {{ evidence.milestone ? "取消里程碑" : "标记里程碑" }}
                  </button>
                </div>
              </article>
            </div>
          </section>
        </section>
      </section>

      <section v-else class="achievement-history-view">
        <header>
          <div>
            <p class="eyebrow">History</p>
            <h3>已完成的活动与挑战</h3>
            <p>补充“做了什么”和“学到了什么”，这些内容会进入画像与能力证据异步更新。</p>
          </div>
          <span>{{ historyPage.totalElements }} 条</span>
        </header>

        <div v-if="historyLoading" class="achievement-loading">
          <LoaderCircle class="spin" :size="24" />
          <span>正在翻页</span>
        </div>
        <div v-else class="achievement-history-list">
          <article v-for="record in historyPage.content" :key="record.id">
            <div class="history-card-heading">
              <span :class="`source-${record.sourceType.toLowerCase()}`">
                {{ record.sourceType === "CHALLENGE" ? "挑战" : "活动" }}
              </span>
              <div>
                <h4>{{ record.eventTitle }}</h4>
                <p>{{ record.organizationName }} · {{ record.category }} · {{ formatDate(record.completedAt) }}</p>
              </div>
            </div>
            <p class="history-card-content">{{ record.content }}</p>
            <div class="history-reflection-fields">
              <label>
                <span>我做了什么</span>
                <textarea
                  v-model="reflectionDrafts[record.id].did"
                  rows="3"
                  maxlength="2000"
                  placeholder="记录你的具体行动和个人贡献"
                ></textarea>
              </label>
              <label>
                <span>我学到了什么</span>
                <textarea
                  v-model="reflectionDrafts[record.id].learned"
                  rows="3"
                  maxlength="2000"
                  placeholder="记录新的理解、方法和待改进之处"
                ></textarea>
              </label>
            </div>
            <button type="button" :disabled="savingRecordId === record.id" @click="saveReflection(record)">
              <LoaderCircle v-if="savingRecordId === record.id" class="spin" :size="17" />
              <Save v-else :size="17" />
              {{ savingRecordId === record.id ? "保存中" : "保存复盘" }}
            </button>
          </article>
        </div>

        <nav v-if="historyPage.totalPages > 1" class="events-pagination">
          <button type="button" :disabled="historyPage.first" @click="loadHistory(historyPage.page - 1)">
            <ChevronLeft :size="17" />上一页
          </button>
          <span>{{ historyPage.page + 1 }} / {{ historyPage.totalPages }}</span>
          <button type="button" :disabled="historyPage.last" @click="loadHistory(historyPage.page + 1)">
            下一页<ChevronRight :size="17" />
          </button>
        </nav>
      </section>
    </template>

    <Transition name="menu">
      <div v-if="notice" class="events-toast">{{ notice }}</div>
    </Transition>

    <JudgeDialog
      :open="judgeOpen"
      :judge="activeJudge"
      :busy="judgeBusy"
      :error="judgeError"
      @close="judgeOpen = false"
      @start="startJudge"
      @submit="submitJudge"
    />
  </section>
</template>
