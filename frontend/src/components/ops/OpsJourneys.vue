<script setup lang="ts">
import { RefreshCw } from "@lucide/vue";
import { onMounted, ref, watch } from "vue";

import { formatTime, opsApi, type Journey, type OpsUser } from "@/api/ops";
import { useAuthStore } from "@/stores/auth";

const emit = defineEmits<{ "open-run": [runId: number] }>();

const authStore = useAuthStore();
const users = ref<OpsUser[]>([]);
const userId = ref("");
const kind = ref<"achievement" | "resume">("achievement");
const journeys = ref<Journey[]>([]);
const loading = ref(false);
const error = ref("");
const expanded = ref<Set<number>>(new Set());

const layerLabel: Record<string, string> = { SQL: "SQL", MQ: "消息队列", AGENT: "Agent" };
const statusLabel: Record<string, string> = {
  OK: "完成",
  FAILED: "失败",
  RUNNING: "运行中",
  PENDING: "待发送",
  RETRYING: "重试中",
  DEFERRED: "延后重试",
  REVIEW: "需复核",
  EMPTY: "无新增",
  WAITING: "等待用户",
  CANCELLED: "已取消",
  RETRIED: "已重试"
};

const kindHint = {
  achievement:
    "每张卡片是一次“完成活动/挑战”：成果写入 MySQL → Outbox 发 MQ → 证据评估 Agent → 评估任务 → 再发 MQ → 双标签归因 Agent → 证据入账。",
  resume:
    "每张卡片是一次“简历定段”：会话 → 简历解析 Agent → 面试追问 Agent → 用户回答 → 回答评估 Agent → 证据候选 → 初始定段 Agent → 每条证据一条 MQ 消息 → 双标签归因 Agent → 证据入账。"
};

function sourceLabel(journey: Journey) {
  if (journey.sourceType === "RESUME_INTERVIEW") return "简历定段会话";
  return journey.sourceType === "CHALLENGE" ? "挑战" : "活动";
}

async function load() {
  if (!userId.value) {
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    journeys.value = await opsApi.journeys(userId.value, 15, kind.value);
    expanded.value = new Set(
      journeys.value.filter((journey) => journey.verdictLevel === "ERROR" || journey.verdictLevel === "WARN").map((journey) => journey.recordId)
    );
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "加载失败";
  } finally {
    loading.value = false;
  }
}

function toggle(recordId: number) {
  const next = new Set(expanded.value);
  if (next.has(recordId)) {
    next.delete(recordId);
  } else {
    next.add(recordId);
  }
  expanded.value = next;
}

onMounted(async () => {
  try {
    users.value = await opsApi.users();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "加载用户失败";
  }
  const own = authStore.user?.userId;
  userId.value =
    users.value.find((user) => user.userId === own)?.userId ??
    users.value.find((user) => user.role === "STUDENT")?.userId ??
    users.value[0]?.userId ??
    "";
});

watch([userId, kind], load);
</script>

<template>
  <section class="ops-section">
    <div class="ops-toolbar">
      <label class="ops-field">
        <span>用户</span>
        <select v-model="userId">
          <option v-for="user in users" :key="user.userId" :value="user.userId">
            {{ user.username }}（{{ user.userId }} · {{ user.role }}）
          </option>
        </select>
      </label>
      <div class="ops-segmented" role="tablist" aria-label="链路类型">
        <button type="button" :class="{ active: kind === 'achievement' }" @click="kind = 'achievement'">活动 / 挑战</button>
        <button type="button" :class="{ active: kind === 'resume' }" @click="kind = 'resume'">简历定段</button>
      </div>
      <button type="button" class="ops-button" :disabled="loading" @click="load">
        <RefreshCw :size="16" :class="{ spin: loading }" />
        <span>刷新</span>
      </button>
    </div>
    <p class="ops-muted">{{ kindHint[kind] }}卡在哪一环、为什么，一眼可见。</p>
    <p v-if="error" class="ops-error">{{ error }}</p>
    <p v-if="!loading && userId && !journeys.length" class="ops-empty-inline">{{ kind === "resume" ? "该用户还没有简历定段记录。" : "该用户还没有成果记录。" }}</p>

    <article
      v-for="journey in journeys"
      :key="journey.recordId"
      class="ops-journey"
      :class="`level-${journey.verdictLevel.toLowerCase()}`"
    >
      <button type="button" class="ops-journey-head" @click="toggle(journey.recordId)">
        <span class="ops-dot" />
        <strong>{{ journey.title }}</strong>
        <small>#{{ journey.recordId }} · {{ sourceLabel(journey) }} · {{ formatTime(journey.completedAt) }}</small>
        <span class="ops-verdict">{{ journey.verdict }}</span>
      </button>
      <ol v-if="expanded.has(journey.recordId)" class="ops-timeline">
        <li v-for="(step, index) in journey.steps" :key="index" :class="`status-${step.status.toLowerCase()}`">
          <span class="ops-layer" :class="`layer-${step.layer.toLowerCase()}`">{{ layerLabel[step.layer] }}</span>
          <div>
            <strong>{{ step.name }}</strong>
            <small>{{ formatTime(step.at) }} · {{ statusLabel[step.status] ?? step.status }}</small>
            <p v-if="step.detail">{{ step.detail }}</p>
          </div>
          <button
            v-if="step.layer === 'AGENT' && step.refId"
            type="button"
            class="ops-link"
            @click="emit('open-run', step.refId)"
          >
            查看运行 #{{ step.refId }}
          </button>
          <span v-else-if="step.refId" class="ops-muted">#{{ step.refId }}</span>
        </li>
      </ol>
    </article>
  </section>
</template>
