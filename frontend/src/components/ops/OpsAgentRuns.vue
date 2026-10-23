<script setup lang="ts">
import { RefreshCw, X } from "@lucide/vue";
import { computed, onMounted, ref, watch } from "vue";

import {
  formatMillis,
  formatTime,
  opsApi,
  prettyJson,
  type AgentRunDetail,
  type AgentRunStats,
  type OpsAgentRun
} from "@/api/ops";

const props = defineProps<{ focusRunId: number | null }>();
const emit = defineEmits<{ consumed: [] }>();

const RUN_TYPES = [
  "CAPABILITY_V2_PIPELINE",
  "ABILITY_EVIDENCE_ASSESSMENT",
  "ABILITY_JUDGE",
  "GROWTH_TAG_EXTRACTION",
  "EVENT_QUALITY_ANALYSIS",
  "EVENT_RECOMMENDATION",
  "PLAN_RECOMMENDATION",
  "GUIDE_ACTION_DECISION",
  "GUIDE_CONVERSATION",
  "GUIDE_CONTEXT_COMPRESSION",
  "RESUME_PARSER",
  "RESUME_INTERVIEWER",
  "RESUME_ANSWER_EVALUATOR",
  "RESUME_ABILITY_SCORING",
  "BAD_CASE_INTAKE"
];

const hours = ref(24);
const stats = ref<AgentRunStats | null>(null);
const stepRunType = ref("CAPABILITY_V2_PIPELINE");
const runs = ref<OpsAgentRun[]>([]);
const filters = ref({ runType: "", status: "", userId: "" });
const loading = ref(false);
const error = ref("");
const detail = ref<AgentRunDetail | null>(null);
const detailLoading = ref(false);
const openArtifacts = ref<Set<number>>(new Set());

const maxStepAvg = computed(() => Math.max(1, ...(stats.value?.steps ?? []).map((step) => step.avgMillis ?? 0)));
const artifactsByStep = computed(() => {
  const groups = new Map<string, AgentRunDetail["artifacts"]>();
  (detail.value?.artifacts ?? []).forEach((artifact) => {
    const key = artifact.stepName ?? "RUN";
    groups.set(key, [...(groups.get(key) ?? []), artifact]);
  });
  return groups;
});

async function loadStats() {
  try {
    stats.value = await opsApi.agentRunStats(hours.value, stepRunType.value);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "统计加载失败";
  }
}

async function loadRuns() {
  loading.value = true;
  error.value = "";
  try {
    runs.value = await opsApi.agentRuns({ ...filters.value, limit: 80 });
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "加载失败";
  } finally {
    loading.value = false;
  }
}

async function openRun(id: number) {
  detailLoading.value = true;
  openArtifacts.value = new Set();
  try {
    detail.value = await opsApi.agentRun(id);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "详情加载失败";
  } finally {
    detailLoading.value = false;
  }
}

function toggleArtifact(id: number) {
  const next = new Set(openArtifacts.value);
  if (next.has(id)) {
    next.delete(id);
  } else {
    next.add(id);
  }
  openArtifacts.value = next;
}

function refresh() {
  void loadStats();
  void loadRuns();
}

onMounted(() => {
  refresh();
  if (props.focusRunId) {
    void openRun(props.focusRunId);
    emit("consumed");
  }
});

watch([hours, stepRunType], loadStats);
watch(filters, loadRuns, { deep: true });
</script>

<template>
  <section class="ops-section">
    <div class="ops-toolbar">
      <label class="ops-field">
        <span>统计窗口</span>
        <select v-model.number="hours">
          <option :value="1">最近 1 小时</option>
          <option :value="24">最近 24 小时</option>
          <option :value="168">最近 7 天</option>
          <option :value="720">最近 30 天</option>
        </select>
      </label>
      <button type="button" class="ops-button" :disabled="loading" @click="refresh">
        <RefreshCw :size="16" :class="{ spin: loading }" />
        <span>刷新</span>
      </button>
    </div>
    <p v-if="error" class="ops-error">{{ error }}</p>

    <div class="ops-two-col">
      <div class="ops-card">
        <h2 class="ops-subtitle">按 Agent 类型</h2>
        <table class="ops-table">
          <thead>
            <tr><th>类型</th><th>次数</th><th>失败</th><th>运行中</th><th>平均</th><th>P95</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in stats?.byType ?? []" :key="row.runType">
              <td><button type="button" class="ops-link" @click="filters.runType = row.runType">{{ row.runType }}</button></td>
              <td>{{ row.total }}</td>
              <td :class="{ 'ops-bad': row.failed > 0 }">{{ row.failed }}</td>
              <td>{{ row.running }}</td>
              <td>{{ formatMillis(row.avgMillis) }}</td>
              <td>{{ formatMillis(row.p95Millis) }}</td>
            </tr>
            <tr v-if="!stats?.byType.length"><td colspan="6" class="ops-muted">窗口内没有运行</td></tr>
          </tbody>
        </table>
      </div>

      <div class="ops-card">
        <div class="ops-card-head">
          <h2 class="ops-subtitle">步骤耗时</h2>
          <select v-model="stepRunType" class="ops-compact-select">
            <option v-for="type in RUN_TYPES" :key="type" :value="type">{{ type }}</option>
          </select>
        </div>
        <ul class="ops-bars">
          <li v-for="step in stats?.steps ?? []" :key="step.stepName">
            <span class="ops-bar-label">{{ step.stepName }}</span>
            <span class="ops-bar-track">
              <span class="ops-bar-fill" :style="{ width: `${((step.avgMillis ?? 0) / maxStepAvg) * 100}%` }" />
            </span>
            <span class="ops-bar-value">
              {{ formatMillis(step.avgMillis) }} <small>P95 {{ formatMillis(step.p95Millis) }}</small>
              <small v-if="step.failed" class="ops-bad"> · 失败 {{ step.failed }}</small>
            </span>
          </li>
          <li v-if="!stats?.steps.length" class="ops-muted">该类型在窗口内没有步骤记录</li>
        </ul>
        <template v-if="stats?.topErrors.length">
          <h3 class="ops-mini-title">高频错误</h3>
          <ul class="ops-errors">
            <li v-for="row in stats.topErrors" :key="row.error"><b>{{ row.count }}×</b> {{ row.error }}</li>
          </ul>
        </template>
      </div>
    </div>

    <div class="ops-card">
      <div class="ops-card-head">
        <h2 class="ops-subtitle">运行列表</h2>
        <div class="ops-filters">
          <select v-model="filters.runType">
            <option value="">全部类型</option>
            <option v-for="type in RUN_TYPES" :key="type" :value="type">{{ type }}</option>
          </select>
          <select v-model="filters.status">
            <option value="">全部状态</option>
            <option value="SUCCEEDED">成功</option>
            <option value="FAILED">失败</option>
            <option value="RUNNING">运行中</option>
          </select>
          <input v-model.lazy.trim="filters.userId" placeholder="用户 ID（回车）" />
        </div>
      </div>
      <div class="ops-table-wrap">
        <table class="ops-table">
          <thead>
            <tr><th>#</th><th>类型</th><th>状态</th><th>用户</th><th>目标</th><th>开始</th><th>耗时</th></tr>
          </thead>
          <tbody>
            <tr v-for="run in runs" :key="run.id" class="ops-clickable" @click="openRun(run.id)">
              <td>{{ run.id }}</td>
              <td>{{ run.runType }}</td>
              <td><span class="ops-status" :class="`status-${run.status.toLowerCase()}`">{{ run.status }}</span></td>
              <td>{{ run.userId }}</td>
              <td class="ops-ellipsis" :title="run.errorMessage ?? run.goal">{{ run.goal }}</td>
              <td>{{ formatTime(run.startedAt) }}</td>
              <td>{{ formatMillis(run.durationMillis) }}</td>
            </tr>
            <tr v-if="!runs.length && !loading"><td colspan="7" class="ops-muted">没有匹配的运行</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="detail || detailLoading" class="ops-drawer-backdrop" @click.self="detail = null">
        <aside class="ops-drawer">
          <header>
            <div>
              <p class="eyebrow">Agent Run #{{ detail?.run.id }}</p>
              <h2>{{ detail?.run.goal ?? "加载中…" }}</h2>
            </div>
            <button type="button" class="ops-icon-button" title="关闭" @click="detail = null"><X :size="18" /></button>
          </header>
          <template v-if="detail">
            <dl class="ops-kv">
              <dt>类型</dt><dd>{{ detail.run.runType }}</dd>
              <dt>状态</dt><dd><span class="ops-status" :class="`status-${detail.run.status.toLowerCase()}`">{{ detail.run.status }}</span></dd>
              <dt>耗时</dt><dd>{{ formatMillis(detail.run.durationMillis) }}</dd>
              <dt>输入</dt><dd>{{ detail.run.inputSummary ?? "—" }}</dd>
              <dt>输出</dt><dd>{{ detail.run.outputSummary ?? "—" }}</dd>
              <dt v-if="detail.run.errorMessage">错误</dt><dd v-if="detail.run.errorMessage" class="ops-bad">{{ detail.run.errorMessage }}</dd>
            </dl>
            <h3 class="ops-mini-title">步骤时间线</h3>
            <ol class="ops-steps">
              <li v-for="step in detail.steps" :key="step.id" :class="`status-${step.status.toLowerCase()}`">
                <div class="ops-step-head">
                  <strong>{{ step.sequenceNo }}. {{ step.stepName }}</strong>
                  <span>{{ formatMillis(step.durationMillis) }}</span>
                </div>
                <p v-if="step.outputSummary">{{ step.outputSummary }}</p>
                <p v-if="step.errorMessage" class="ops-bad">{{ step.errorMessage }}</p>
                <div v-for="artifact in artifactsByStep.get(step.stepName) ?? []" :key="artifact.id" class="ops-artifact">
                  <button type="button" class="ops-link" @click="toggleArtifact(artifact.id)">
                    {{ openArtifacts.has(artifact.id) ? "▾" : "▸" }} {{ artifact.artifactType }}
                    <small>{{ artifact.contentSummary }}</small>
                  </button>
                  <pre v-if="openArtifacts.has(artifact.id)">{{ prettyJson(artifact.contentJson) }}</pre>
                </div>
              </li>
              <li v-if="!detail.steps.length" class="ops-muted">该运行没有记录步骤</li>
            </ol>
            <template v-if="artifactsByStep.get('RUN')?.length">
              <h3 class="ops-mini-title">运行级产物</h3>
              <div v-for="artifact in artifactsByStep.get('RUN')" :key="artifact.id" class="ops-artifact">
                <button type="button" class="ops-link" @click="toggleArtifact(artifact.id)">
                  {{ openArtifacts.has(artifact.id) ? "▾" : "▸" }} {{ artifact.artifactType }}
                </button>
                <pre v-if="openArtifacts.has(artifact.id)">{{ prettyJson(artifact.contentJson) }}</pre>
              </div>
            </template>
          </template>
        </aside>
      </div>
    </Teleport>
  </section>
</template>
