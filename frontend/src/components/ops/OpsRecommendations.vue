<script setup lang="ts">
import { RefreshCw } from "@lucide/vue";
import { computed, onMounted, ref, watch } from "vue";

import OpsEvalCandidates from "@/components/ops/OpsEvalCandidates.vue";
import {
  formatMillis,
  formatPercent,
  formatTime,
  opsApi,
  type RecommendationRunRow,
  type RecommendationStats
} from "@/api/ops";

const emit = defineEmits<{ "open-run": [runId: number] }>();

const days = ref(7);
const stats = ref<RecommendationStats | null>(null);
const runs = ref<RecommendationRunRow[]>([]);
const loading = ref(false);
const error = ref("");

const feedbackLabel: Record<string, string> = {
  INTERESTED: "感兴趣",
  NOT_RELEVANT: "不相关",
  RESERVED: "已预约",
  RESERVATION_CANCELLED: "取消预约",
  COMPLETED: "已完成",
  FOLLOWED: "关注组织"
};

const funnelRows = computed(() => {
  const funnel = stats.value?.funnel;
  if (!funnel) {
    return [];
  }
  return [
    { label: "曝光（推荐出去的活动次数）", count: funnel.impressions, rate: 1, tone: "base" },
    { label: "感兴趣", count: funnel.interested, rate: funnel.interestedRate, tone: "good" },
    { label: "预约", count: funnel.reserved, rate: funnel.reserveRate, tone: "good" },
    { label: "完成", count: funnel.completed, rate: funnel.completeRate, tone: "good" },
    { label: "关注组织", count: funnel.followed, rate: funnel.followRate, tone: "good" },
    { label: "不相关", count: funnel.notRelevant, rate: funnel.notRelevantRate, tone: "bad" }
  ];
});

const maxDailyImpressions = computed(() => Math.max(1, ...(stats.value?.daily ?? []).map((day) => day.impressions)));

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [statsResult, runsResult] = await Promise.all([
      opsApi.recommendationStats(days.value),
      opsApi.recommendationRuns(20)
    ]);
    stats.value = statsResult;
    runs.value = runsResult;
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "加载失败";
  } finally {
    loading.value = false;
  }
}

function chipTone(type: string) {
  if (type === "NOT_RELEVANT" || type === "RESERVATION_CANCELLED") return "bad";
  return "good";
}

onMounted(load);
watch(days, load);
</script>

<template>
  <section class="ops-section">
    <div class="ops-toolbar">
      <label class="ops-field">
        <span>统计窗口</span>
        <select v-model.number="days">
          <option :value="1">最近 1 天</option>
          <option :value="7">最近 7 天</option>
          <option :value="30">最近 30 天</option>
          <option :value="90">最近 90 天</option>
        </select>
      </label>
      <button type="button" class="ops-button" :disabled="loading" @click="load">
        <RefreshCw :size="16" :class="{ spin: loading }" />
        <span>刷新</span>
      </button>
    </div>
    <p class="ops-muted">
      所有比率都以“曝光”为分母：一次推荐中展示的一个活动算一次曝光。
      <template v-if="stats?.trackingSince">曝光从 {{ formatTime(stats.trackingSince) }} 开始记录，之前的推荐只计入运行次数和零结果率。</template>
    </p>
    <p v-if="error" class="ops-error">{{ error }}</p>

    <div class="ops-counter-grid">
      <div class="ops-counter"><strong>{{ stats?.runs ?? "—" }}</strong><span>推荐次数</span></div>
      <div class="ops-counter" :class="{ alert: (stats?.zeroResultRate ?? 0) > 0.3 }">
        <strong>{{ formatPercent(stats?.zeroResultRate) }}</strong><span>零结果率（{{ stats?.zeroResultRuns ?? 0 }} 次什么都没推荐）</span>
      </div>
      <div class="ops-counter" :class="{ alert: (stats?.failedRuns ?? 0) > 0 }">
        <strong>{{ stats?.failedRuns ?? "—" }}</strong><span>失败次数</span>
      </div>
      <div class="ops-counter"><strong>{{ formatMillis(stats?.avgMillis) }}</strong><span>平均耗时</span></div>
      <div class="ops-counter"><strong>{{ stats?.studentsReached ?? "—" }}</strong><span>覆盖学生数</span></div>
      <div class="ops-counter"><strong>{{ formatPercent(stats?.funnel.explicitFeedbackRate) }}</strong><span>主动反馈率（点了按钮的曝光）</span></div>
      <div class="ops-counter"><strong>{{ stats?.suppressedByFeedback ?? "—" }}</strong><span>因“不相关”被过滤的推荐次数</span></div>
    </div>

    <div class="ops-two-col">
      <div class="ops-card">
        <h2 class="ops-subtitle">反馈漏斗</h2>
        <ul class="ops-bars">
          <li v-for="row in funnelRows" :key="row.label">
            <span class="ops-bar-label">{{ row.label }}</span>
            <span class="ops-bar-track">
              <span class="ops-bar-fill" :class="`tone-${row.tone}`" :style="{ width: `${Math.min(100, row.rate * 100)}%` }" />
            </span>
            <span class="ops-bar-value">{{ row.count }} <small>{{ row.tone === "base" ? "" : formatPercent(row.rate) }}</small></span>
          </li>
        </ul>
        <p v-if="stats && !stats.funnel.impressions" class="ops-muted">窗口内还没有曝光记录。</p>
      </div>

      <div class="ops-card">
        <h2 class="ops-subtitle">每天</h2>
        <table class="ops-table">
          <thead>
            <tr><th>日期</th><th>推荐</th><th>零结果</th><th>曝光</th><th>感兴趣</th><th>不相关</th><th>预约</th></tr>
          </thead>
          <tbody>
            <tr v-for="day in stats?.daily ?? []" :key="day.date">
              <td>{{ day.date }}</td>
              <td>{{ day.runs }}</td>
              <td :class="{ 'ops-warn': day.zeroResultRuns > 0 }">{{ day.zeroResultRuns }}</td>
              <td>
                <span class="ops-inline-bar"><span :style="{ width: `${(day.impressions / maxDailyImpressions) * 100}%` }" /></span>
                {{ day.impressions }}
              </td>
              <td>{{ day.interested }}</td>
              <td :class="{ 'ops-bad': day.notRelevant > 0 }">{{ day.notRelevant }}</td>
              <td>{{ day.reserved }}</td>
            </tr>
            <tr v-if="!stats?.daily.length"><td colspan="7" class="ops-muted">窗口内没有推荐</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="ops-card">
      <h2 class="ops-subtitle">“不相关”的原因</h2>
      <table class="ops-table">
        <thead><tr><th>时间</th><th>活动</th><th>学生</th><th>原因</th><th></th></tr></thead>
        <tbody>
          <tr v-for="item in stats?.notRelevantReasons ?? []" :key="`${item.eventId}-${item.at}`">
            <td>{{ formatTime(item.at) }}</td>
            <td>#{{ item.eventId }} {{ item.eventTitle ?? "" }}</td>
            <td>{{ item.userId }}</td>
            <td>{{ item.reason || "（未填写）" }}</td>
            <td><button v-if="item.runId" type="button" class="ops-link" @click="emit('open-run', item.runId)">查看那次推荐</button></td>
          </tr>
          <tr v-if="!stats?.notRelevantReasons.length"><td colspan="5" class="ops-muted">窗口内没有“不相关”反馈</td></tr>
        </tbody>
      </table>
    </div>

    <OpsEvalCandidates @open-run="(runId) => emit('open-run', runId)" />

    <div class="ops-card">
      <h2 class="ops-subtitle">最近的推荐</h2>
      <article v-for="run in runs" :key="run.runId" class="ops-rec-run">
        <header>
          <button type="button" class="ops-link" @click="emit('open-run', run.runId)">运行 #{{ run.runId }}</button>
          <span class="ops-muted">{{ formatTime(run.startedAt) }} · {{ run.userId }} · {{ formatMillis(run.durationMillis) }}</span>
          <span v-if="run.recommendationCount === 0" class="ops-status status-pending">零结果</span>
          <span v-else-if="run.status === 'FAILED'" class="ops-status status-failed">失败</span>
        </header>
        <p class="ops-rec-need">{{ run.need }}</p>
        <ol v-if="run.shown.length" class="ops-rec-shown">
          <li v-for="item in run.shown" :key="item.eventId">
            <span class="ops-muted">#{{ item.rank }}</span>
            <span>{{ item.title ?? `活动 #${item.eventId}` }}</span>
            <small class="ops-muted">{{ item.score }} 分</small>
            <span v-for="type in item.feedback" :key="type" class="ops-chip" :class="`tone-${chipTone(type)}`">{{ feedbackLabel[type] ?? type }}</span>
          </li>
        </ol>
        <p v-else-if="run.recommendationCount" class="ops-muted">这次推荐发生在开始记录曝光之前。</p>
      </article>
      <p v-if="!runs.length && !loading" class="ops-muted">还没有推荐记录</p>
    </div>
  </section>
</template>
