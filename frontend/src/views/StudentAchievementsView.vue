<script setup lang="ts">
import {
  AlertCircle,
  BookOpenCheck,
  ChevronLeft,
  ChevronRight,
  CircleGauge,
  History,
  LoaderCircle,
  RefreshCw,
  Save,
  ShieldCheck
} from "@lucide/vue";
import { computed, onMounted, reactive, ref } from "vue";

import { achievementsApi } from "@/api/achievements";
import { ApiError } from "@/api/client";
import CapabilityBucketDetailDrawer from "@/components/achievements/CapabilityBucketDetailDrawer.vue";
import TypewriterText from "@/components/motion/TypewriterText.vue";
import { useAuthStore } from "@/stores/auth";
import type {
  AchievementRecord,
  CapabilityBucketSummary,
  CapabilityV2Profile,
  PageResponse
} from "@/types/achievements";

type AchievementView = "abilities" | "history";
type HeatmapDay = {
  date: string | null;
  count: number;
  level: number;
  titles: string[];
};

const authStore = useAuthStore();
const activeView = ref<AchievementView>("abilities");
const loading = ref(true);
const loadError = ref("");
const refreshing = ref(false);
const notice = ref("");
let noticeTimer: ReturnType<typeof setTimeout> | undefined;

const profile = ref<CapabilityV2Profile>(emptyProfile());
const historyPage = ref<PageResponse<AchievementRecord>>({
  content: [],
  page: 0,
  size: 5,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true
});
const allAchievementRecords = ref<AchievementRecord[]>([]);
const heatmapHoveredDay = ref<HeatmapDay | null>(null);
const heatmapYear = new Date().getFullYear();
const historyLoading = ref(false);
const reflectionDrafts = reactive<Record<number, { did: string; learned: string }>>({});
const savingRecordId = ref<number | null>(null);
const selectedBucket = ref<CapabilityBucketSummary | null>(null);

const allBuckets = computed(() => [
  ...profile.value.knowledgeBuckets,
  ...profile.value.functionBuckets
]);
const averageAbility = computed(() => {
  if (!allBuckets.value.length) return 0;
  return allBuckets.value.reduce((sum, bucket) => sum + bucket.abilityScore, 0) / allBuckets.value.length;
});
const avatarInitial = computed(() => authStore.user?.username.slice(0, 1).toUpperCase() ?? "D");
const strongestKnowledgeBuckets = computed(() =>
  [...profile.value.knowledgeBuckets]
    .sort((left, right) => right.abilityScore - left.abilityScore)
    .slice(0, 3)
);
const strongestKnowledgeBucketId = computed(() => highestBucketId(profile.value.knowledgeBuckets));
const strongestFunctionBucketId = computed(() => highestBucketId(profile.value.functionBuckets));
const recentEvidence = computed(() => historyPage.value.content.slice(0, 3));
const portfolioIntroRun = ref(0);
const portfolioIntroPhase = ref(0);
const overviewIntroPhase = ref(0);
const knowledgeTagsVisible = ref(false);
const functionTagsVisible = ref(false);
const overviewCardsVisible = ref(false);
const heatmapWeeks = computed(() => buildHeatmapWeeks(allAchievementRecords.value, heatmapYear));
const heatmapMonths = computed(() => {
  const months: Array<{ label: string; weekIndex: number }> = [];
  heatmapWeeks.value.forEach((week, weekIndex) => {
    const firstOfMonth = week.find((day) => day.date?.endsWith("-01"));
    if (firstOfMonth?.date) {
      months.push({
        label: new Intl.DateTimeFormat("zh-CN", { month: "short" }).format(new Date(`${firstOfMonth.date}T00:00:00`)),
        weekIndex
      });
    }
  });
  return months;
});
const heatmapCompletedDays = computed(() =>
  heatmapWeeks.value.flat().filter((day) => day.count > 0).length
);
const heatmapRecordCount = computed(() =>
  heatmapWeeks.value.flat().reduce((sum, day) => sum + day.count, 0)
);

onMounted(async () => {
  await loadAll();
});

async function loadAll() {
  loading.value = true;
  loadError.value = "";
  try {
    const [profileData, historyData, allHistoryData] = await Promise.all([
      achievementsApi.capabilityProfile(),
      achievementsApi.history(0, 5),
      achievementsApi.historyAll()
    ]);
    profile.value = normalizeProfile(profileData);
    assignHistory(historyData);
    allAchievementRecords.value = allHistoryData;
    replayPortfolioIntro();
  } catch (error) {
    loadError.value = errorMessage(error, "成长档案暂时没有加载出来");
  } finally {
    loading.value = false;
  }
}

async function refreshAbilityData() {
  refreshing.value = true;
  try {
    const [profileData, allHistoryData] = await Promise.all([
      achievementsApi.capabilityProfile(),
      achievementsApi.historyAll()
    ]);
    profile.value = normalizeProfile(profileData);
    allAchievementRecords.value = allHistoryData;
    showNotice("能力档案已同步");
  } catch (error) {
    showNotice(errorMessage(error, "同步失败"));
  } finally {
    refreshing.value = false;
  }
}

/** After a promotion review the rank may change; refresh tags without replaying the intro animation. */
async function reloadProfileQuietly() {
  try {
    profile.value = normalizeProfile(await achievementsApi.capabilityProfile());
  } catch {
    // The drawer already shows the review result; a stale badge is corrected on the next sync.
  }
}

function tagLabel(bucket: CapabilityBucketSummary) {
  const base = `查看 ${bucket.displayName} 的证据与评分记录`;
  return bucket.promotionPending ? `${base}，晋级等待补充说明` : base;
}

function replayPortfolioIntro() {
  portfolioIntroRun.value += 1;
  portfolioIntroPhase.value = 0;
  overviewIntroPhase.value = 0;
  knowledgeTagsVisible.value = false;
  functionTagsVisible.value = false;
  overviewCardsVisible.value = false;
  window.setTimeout(() => {
    portfolioIntroPhase.value = 1;
    overviewIntroPhase.value = 1;
  }, 80);
}

function advancePortfolioIntro(nextPhase: number) {
  portfolioIntroPhase.value = Math.max(portfolioIntroPhase.value, nextPhase);
}

function revealFunctionTags() {
  functionTagsVisible.value = true;
}

function advanceOverviewIntro(nextPhase: number) {
  overviewIntroPhase.value = Math.max(overviewIntroPhase.value, nextPhase);
}

function revealOverviewCards() {
  overviewCardsVisible.value = true;
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
    showNotice("复盘已保存，能力档案会异步同步");
  } catch (error) {
    showNotice(errorMessage(error, "复盘保存失败"));
  } finally {
    savingRecordId.value = null;
  }
}

function emptyProfile(): CapabilityV2Profile {
  return {
    knowledgeBuckets: [],
    functionBuckets: [],
    totalEvidenceCount: 0,
    lastUpdatedAt: null
  };
}

function normalizeProfile(value: CapabilityV2Profile): CapabilityV2Profile {
  return {
    knowledgeBuckets: (value.knowledgeBuckets ?? []).map(normalizeBucket),
    functionBuckets: (value.functionBuckets ?? []).map(normalizeBucket),
    totalEvidenceCount: Number(value.totalEvidenceCount ?? 0),
    lastUpdatedAt: value.lastUpdatedAt ?? null
  };
}

function normalizeBucket(bucket: CapabilityBucketSummary): CapabilityBucketSummary {
  return {
    ...bucket,
    experienceScore: Number(bucket.experienceScore ?? 0),
    abilityScore: Number(bucket.abilityScore ?? 0),
    abilityUncertainty: Number(bucket.abilityUncertainty ?? 1),
    evidenceCount: Number(bucket.evidenceCount ?? 0)
  };
}

function highestBucketId(buckets: CapabilityBucketSummary[]) {
  return [...buckets]
    .sort((left, right) => right.abilityScore - left.abilityScore || left.bucketId - right.bucketId)[0]
    ?.bucketId ?? null;
}

function confidence(bucket: CapabilityBucketSummary) {
  return Math.round(Math.max(0, 1 - bucket.abilityUncertainty) * 100);
}

function scoreWidth(bucket: CapabilityBucketSummary) {
  return String(Math.max(4, Math.min(bucket.abilityScore, 100))) + "%";
}

function formatNumber(value: number) {
  return Number.isInteger(value) ? String(value) : value.toFixed(1);
}

function formatDate(value: string | null) {
  if (!value) return "暂无更新";
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  }).format(new Date(value));
}

function buildHeatmapWeeks(records: AchievementRecord[], year: number) {
  const recordsByDate = new Map<string, AchievementRecord[]>();
  records.forEach((record) => {
    const completedAt = new Date(record.completedAt);
    if (Number.isNaN(completedAt.getTime()) || completedAt.getFullYear() !== year) return;
    const dateKey = localDateKey(completedAt);
    recordsByDate.set(dateKey, [...(recordsByDate.get(dateKey) ?? []), record]);
  });

  const januaryFirst = new Date(year, 0, 1);
  const firstSunday = new Date(year, 0, 1 - januaryFirst.getDay());
  return Array.from({ length: 53 }, (_, weekIndex) =>
    Array.from({ length: 7 }, (_, dayIndex): HeatmapDay => {
      const currentDate = new Date(firstSunday);
      currentDate.setDate(firstSunday.getDate() + weekIndex * 7 + dayIndex);
      if (currentDate.getFullYear() !== year) {
        return { date: null, count: 0, level: 0, titles: [] };
      }
      const date = localDateKey(currentDate);
      const dailyRecords = recordsByDate.get(date) ?? [];
      return {
        date,
        count: dailyRecords.length,
        level: Math.min(dailyRecords.length, 4),
        titles: dailyRecords.map((record) => record.eventTitle)
      };
    })
  );
}

function localDateKey(date: Date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function heatmapCellLabel(day: HeatmapDay) {
  if (!day.date) return "";
  const dateLabel = new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "long",
    day: "numeric",
    weekday: "short"
  }).format(new Date(`${day.date}T00:00:00`));
  if (!day.count) return `${dateLabel}：暂无有效学习记录`;
  const titles = day.titles.slice(0, 2).join("、");
  const suffix = day.titles.length > 2 ? "等" : "";
  return `${dateLabel}：${day.count} 条有效学习记录${titles ? `（${titles}${suffix}）` : ""}`;
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
    <div v-if="loading" class="achievement-loading">
      <LoaderCircle class="spin" :size="28" />
      <span>正在整理能力档案</span>
    </div>
    <div v-else-if="loadError" class="achievement-error">
      <AlertCircle :size="26" />
      <h3>成长档案暂时没有加载出来</h3>
      <p>{{ loadError }}</p>
      <button type="button" @click="loadAll">重新加载</button>
    </div>

    <template v-else>
      <section class="portfolio-profile-shell">
        <header class="portfolio-profile-header">
          <div class="portfolio-identity">
            <span class="portfolio-avatar">{{ avatarInitial }}</span>
            <div>
              <p class="eyebrow"><TypewriterText :key="`portfolio-eyebrow-${portfolioIntroRun}`" text="Growth Portfolio" :start="portfolioIntroPhase >= 1" :typing-speed="24" :show-cursor="false" @complete="advancePortfolioIntro(2)" /></p>
              <h2><TypewriterText :key="`portfolio-name-${portfolioIntroRun}`" :text="authStore.user?.username ?? '我的能力档案'" :start="portfolioIntroPhase >= 2" :typing-speed="44" :show-cursor="false" @complete="advancePortfolioIntro(3)" /></h2>
              <p><TypewriterText :key="`portfolio-subtitle-${portfolioIntroRun}`" text="持续积累真实学习证据" :start="portfolioIntroPhase >= 3" :typing-speed="36" :show-cursor="false" @complete="advancePortfolioIntro(4)" /></p>
              <small><ShieldCheck :size="13" /><TypewriterText :key="`portfolio-status-${portfolioIntroRun}`" text="评分引擎已接入" :start="portfolioIntroPhase >= 4" :typing-speed="33" :show-cursor="false" @complete="advancePortfolioIntro(5)" /></small>
            </div>
          </div>
          <div class="portfolio-header-actions">
            <button type="button" :disabled="refreshing" @click="refreshAbilityData">
              <RefreshCw :class="{ spin: refreshing }" :size="16" />
              同步评分状态
            </button>
            <nav class="portfolio-view-switch" aria-label="个人成就视图">
              <button type="button" :class="{ 'is-active': activeView === 'abilities' }" @click="activeView = 'abilities'">
                <CircleGauge :size="16" />能力档案
              </button>
              <button type="button" :class="{ 'is-active': activeView === 'history' }" @click="activeView = 'history'">
                <History :size="16" />成长记录
              </button>
            </nav>
          </div>
        </header>

        <section class="portfolio-tag-board">
          <div>
            <span><TypewriterText :key="`knowledge-label-${portfolioIntroRun}`" text="能力标签" :start="portfolioIntroPhase >= 5" :typing-speed="49" :show-cursor="false" @complete="knowledgeTagsVisible = true; advancePortfolioIntro(6)" /></span>
            <div v-if="knowledgeTagsVisible && profile.knowledgeBuckets.length" class="portfolio-tag-list">
              <button
                v-for="(bucket, index) in profile.knowledgeBuckets"
                :key="bucket.bucketId"
                type="button"
                :class="{
                  'portfolio-tag': true,
                  'has-star-border': bucket.bucketId === strongestKnowledgeBucketId
                }"
                :style="{ '--tag-delay': `${Math.min(index, 9) * 58}ms` }"
                :aria-label="tagLabel(bucket)"
                :title="bucket.promotionPending ? '晋级等待补充说明' : undefined"
                @click="selectedBucket = bucket"
              ><span>{{ bucket.displayName }}</span><small>{{ formatNumber(bucket.abilityScore) }}</small><i v-if="bucket.promotionPending" class="portfolio-tag-alert" aria-hidden="true">!</i></button>
            </div>
            <p v-else-if="knowledgeTagsVisible">完成一项学习或实践后，能力标签会在这里出现。</p>
          </div>
          <div>
            <span><TypewriterText :key="`function-label-${portfolioIntroRun}`" text="职能标签" :start="portfolioIntroPhase >= 6" :typing-speed="49" :show-cursor="false" @complete="revealFunctionTags" /></span>
            <div v-if="functionTagsVisible && profile.functionBuckets.length" class="portfolio-tag-list portfolio-function-tags">
              <button
                v-for="(bucket, index) in profile.functionBuckets"
                :key="bucket.bucketId"
                type="button"
                :class="{
                  'portfolio-tag': true,
                  'has-star-border': bucket.bucketId === strongestFunctionBucketId
                }"
                :style="{ '--tag-delay': `${Math.min(index, 9) * 58}ms` }"
                :aria-label="tagLabel(bucket)"
                :title="bucket.promotionPending ? '晋级等待补充说明' : undefined"
                @click="selectedBucket = bucket"
              ><span>{{ bucket.displayName }}</span><small>{{ formatNumber(bucket.abilityScore) }}</small><i v-if="bucket.promotionPending" class="portfolio-tag-alert" aria-hidden="true">!</i></button>
            </div>
            <p v-else-if="functionTagsVisible">有真实职责或交付的经历，会沉淀为职能标签。</p>
          </div>
        </section>
      </section>

      <section v-if="activeView === 'abilities'" class="portfolio-dashboard">
        <div v-if="!allBuckets.length" class="v2-ability-empty portfolio-empty">
          <BookOpenCheck :size="30" />
          <h3>能力档案正在等待第一条证据</h3>
          <p>完成活动或挑战后，系统会异步归纳能力标签，并基于真实交付补充职能标签。</p>
        </div>

        <template v-else>
          <section class="portfolio-overview-panel">
            <header>
              <div>
                <p class="eyebrow"><TypewriterText :key="`overview-eyebrow-${portfolioIntroRun}`" text="Ability Overview" :start="overviewIntroPhase >= 1" :typing-speed="24" :show-cursor="false" @complete="advanceOverviewIntro(2)" /></p>
                <h3><TypewriterText :key="`overview-title-${portfolioIntroRun}`" text="能力概览" :start="overviewIntroPhase >= 2" :typing-speed="44" :show-cursor="false" @complete="advanceOverviewIntro(3)" /></h3>
              </div>
              <span><TypewriterText :key="`overview-average-${portfolioIntroRun}`" :text="`平均 ${formatNumber(averageAbility)} / 100`" :start="overviewIntroPhase >= 3" :typing-speed="36" :show-cursor="false" @complete="revealOverviewCards" /></span>
            </header>
            <div v-if="overviewCardsVisible" class="portfolio-score-list">
              <article v-for="bucket in strongestKnowledgeBuckets" :key="bucket.bucketId">
                <div>
                  <strong><TypewriterText :key="`overview-name-${portfolioIntroRun}-${bucket.bucketId}`" :text="bucket.displayName" :start="overviewCardsVisible" :typing-speed="36" :show-cursor="false" /></strong>
                  <span><TypewriterText :key="`overview-score-${portfolioIntroRun}-${bucket.bucketId}`" :text="formatNumber(bucket.abilityScore)" :start="overviewCardsVisible" :typing-speed="36" :show-cursor="false" /><small>/ 100</small></span>
                </div>
                <i><b class="is-glowing" :style="{ width: scoreWidth(bucket) }"></b></i>
                <small><TypewriterText :key="`overview-confidence-${portfolioIntroRun}-${bucket.bucketId}`" :text="`可信度 ${confidence(bucket)}% · ${bucket.evidenceCount} 条证据`" :start="overviewCardsVisible" :typing-speed="36" :show-cursor="false" /></small>
              </article>
            </div>
            <p v-else-if="overviewCardsVisible && !strongestKnowledgeBuckets.length" class="portfolio-panel-empty"><TypewriterText :key="`overview-empty-${portfolioIntroRun}`" text="正在等待知识与技术标签。" :start="overviewCardsVisible" :typing-speed="36" :show-cursor="false" /></p>
          </section>

          <section class="portfolio-evidence-panel">
            <header>
              <div>
                <p class="eyebrow">Recent Evidence</p>
                <h3>近期成长证据</h3>
              </div>
              <button type="button" @click="activeView = 'history'">查看全部<ChevronRight :size="15" /></button>
            </header>
            <div v-if="recentEvidence.length" class="portfolio-evidence-timeline">
              <article v-for="record in recentEvidence" :key="record.id">
                <i></i>
                <div>
                  <small>{{ formatDate(record.completedAt) }} · {{ record.sourceType === "CHALLENGE" ? "挑战" : "活动" }}</small>
                  <h4>{{ record.eventTitle }}</h4>
                  <p>{{ record.learned || record.did || record.content }}</p>
                </div>
              </article>
            </div>
            <p v-else class="portfolio-panel-empty">尚未产生完成记录。</p>
          </section>

          <section class="portfolio-heatmap-panel">
            <header>
              <div>
                <p class="eyebrow">Learning Rhythm</p>
                <h3>{{ heatmapYear }} 学习热力图</h3>
              </div>
              <span>{{ heatmapCompletedDays }} 天 · {{ heatmapRecordCount }} 条记录</span>
            </header>
            <div class="portfolio-heatmap-scroll">
              <div class="portfolio-heatmap-months" aria-hidden="true">
                <i></i>
                <span
                  v-for="month in heatmapMonths"
                  :key="`${month.label}-${month.weekIndex}`"
                  :style="{ gridColumn: month.weekIndex + 2 }"
                >{{ month.label }}</span>
              </div>
              <div class="portfolio-heatmap-grid" role="grid" :aria-label="`${heatmapYear} 学习热力图`">
                <div v-for="dayIndex in 7" :key="dayIndex" class="portfolio-heatmap-row" role="row">
                  <span class="portfolio-heatmap-day-label">{{ ["日", "", "二", "", "四", "", "六"][dayIndex - 1] }}</span>
                  <template v-for="(week, weekIndex) in heatmapWeeks" :key="weekIndex">
                    <button
                      v-if="week[dayIndex - 1].date"
                      type="button"
                      class="portfolio-heatmap-cell"
                      :class="`level-${week[dayIndex - 1].level}`"
                      :aria-label="heatmapCellLabel(week[dayIndex - 1])"
                      @mouseenter="heatmapHoveredDay = week[dayIndex - 1]"
                      @mouseleave="heatmapHoveredDay = null"
                      @focus="heatmapHoveredDay = week[dayIndex - 1]"
                      @blur="heatmapHoveredDay = null"
                    ></button>
                    <span v-else class="portfolio-heatmap-cell is-outside" aria-hidden="true"></span>
                  </template>
                </div>
              </div>
            </div>
            <footer>
              <p>{{ heatmapHoveredDay ? heatmapCellLabel(heatmapHoveredDay) : "悬停查看某一天完成的活动与挑战。" }}</p>
              <div aria-label="学习记录强度图例"><span>少</span><i class="level-0"></i><i class="level-1"></i><i class="level-2"></i><i class="level-3"></i><i class="level-4"></i><span>多</span></div>
            </footer>
          </section>
        </template>
      </section>

      <section v-else class="achievement-history-view">
        <header>
          <div>
            <p class="eyebrow">History</p>
            <h3>已完成的活动与挑战</h3>
            <p>补充“做了什么”和“学到了什么”，让后续能力判断拥有更完整的真实证据。</p>
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
              <span :class="'source-' + record.sourceType.toLowerCase()">
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

    <CapabilityBucketDetailDrawer
      :bucket-id="selectedBucket?.bucketId ?? null"
      :bucket-name="selectedBucket?.displayName ?? ''"
      @close="selectedBucket = null"
      @changed="reloadProfileQuietly"
    />

    <Transition name="menu">
      <div v-if="notice" class="events-toast">{{ notice }}</div>
    </Transition>
  </section>
</template>
