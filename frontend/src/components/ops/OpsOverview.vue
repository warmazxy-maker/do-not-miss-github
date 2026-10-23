<script setup lang="ts">
import { LoaderCircle, RefreshCw, Zap } from "@lucide/vue";
import { computed, onMounted, ref } from "vue";

import { formatMillis, formatTime, opsApi, type DependencyHealth, type OpsOverview } from "@/api/ops";

const emit = defineEmits<{ navigate: [tab: "journeys" | "agents" | "messaging" | "storage"] }>();

const overview = ref<OpsOverview | null>(null);
const loading = ref(false);
const probing = ref(false);
const error = ref("");

const stateLabel: Record<string, string> = { UP: "正常", DEGRADED: "降级", DOWN: "不可用", DISABLED: "未启用" };
const layerOf: Record<string, string> = {
  mysql: "SQL",
  redis: "缓存",
  rabbitmq: "消息队列",
  opensearch: "检索",
  llm: "Agent / 模型",
  embedding: "Agent / 模型"
};

const counters = computed(() => {
  const value = overview.value?.counters ?? {};
  return [
    { label: "24h Agent 运行", value: value.agentRuns24h, tab: "agents" as const },
    { label: "24h 失败运行", value: value.agentRunsFailed24h, tab: "agents" as const, alert: (value.agentRunsFailed24h ?? 0) > 0 },
    { label: "卡住的运行（>10 分钟仍 RUNNING）", value: value.agentRunsStuck, tab: "agents" as const, alert: (value.agentRunsStuck ?? 0) > 0 },
    { label: "Outbox 待发", value: value.outboxPending, tab: "messaging" as const },
    { label: "Outbox 延后重试中", value: value.outboxDeferred, tab: "messaging" as const },
    { label: "Outbox 重试耗尽", value: value.outboxExhausted, tab: "messaging" as const, alert: (value.outboxExhausted ?? 0) > 0 },
    { label: "评估任务失败", value: value.assessmentJobsFailed, tab: "journeys" as const, alert: (value.assessmentJobsFailed ?? 0) > 0 },
    { label: "注册用户", value: value.users, tab: "storage" as const }
  ];
});

async function load() {
  loading.value = true;
  error.value = "";
  try {
    overview.value = await opsApi.overview();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "加载失败";
  } finally {
    loading.value = false;
  }
}

async function probe() {
  if (!overview.value) {
    return;
  }
  probing.value = true;
  try {
    const results = await opsApi.probeModels();
    const byKey = new Map(results.map((item) => [item.key, item]));
    overview.value = {
      ...overview.value,
      dependencies: overview.value.dependencies.map((item) => byKey.get(item.key) ?? item)
    };
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "探活失败";
  } finally {
    probing.value = false;
  }
}

function detailEntries(item: DependencyHealth) {
  return Object.entries(item.details ?? {}).filter(([, value]) => value !== null && value !== "");
}

onMounted(load);
</script>

<template>
  <section class="ops-section">
    <div class="ops-toolbar">
      <span class="ops-muted">检查时间：{{ formatTime(overview?.checkedAt) }}</span>
      <div class="ops-toolbar-actions">
        <button type="button" class="ops-button" :disabled="probing || !overview" @click="probe">
          <LoaderCircle v-if="probing" class="spin" :size="16" />
          <Zap v-else :size="16" />
          <span>实测模型连通（消耗少量额度）</span>
        </button>
        <button type="button" class="ops-button" :disabled="loading" @click="load">
          <RefreshCw :size="16" :class="{ spin: loading }" />
          <span>刷新</span>
        </button>
      </div>
    </div>
    <p v-if="error" class="ops-error">{{ error }}</p>

    <div class="ops-health-grid">
      <article
        v-for="item in overview?.dependencies ?? []"
        :key="item.key"
        class="ops-health-card"
        :class="`state-${item.state.toLowerCase()}`"
      >
        <header>
          <span class="ops-layer">{{ layerOf[item.key] ?? "依赖" }}</span>
          <span class="ops-state-pill">{{ stateLabel[item.state] }}</span>
        </header>
        <h3>{{ item.name }}</h3>
        <p>{{ item.summary }}</p>
        <footer>
          <span v-if="item.latencyMillis !== null">延迟 {{ formatMillis(item.latencyMillis) }}</span>
          <details v-if="detailEntries(item).length">
            <summary>详情</summary>
            <dl>
              <template v-for="[key, value] in detailEntries(item)" :key="key">
                <dt>{{ key }}</dt>
                <dd>{{ typeof value === "object" ? JSON.stringify(value) : value }}</dd>
              </template>
            </dl>
          </details>
        </footer>
      </article>
    </div>

    <h2 class="ops-subtitle">关键计数</h2>
    <div class="ops-counter-grid">
      <button
        v-for="counter in counters"
        :key="counter.label"
        type="button"
        class="ops-counter"
        :class="{ alert: counter.alert }"
        @click="emit('navigate', counter.tab)"
      >
        <strong>{{ counter.value ?? "—" }}</strong>
        <span>{{ counter.label }}</span>
      </button>
    </div>
  </section>
</template>
