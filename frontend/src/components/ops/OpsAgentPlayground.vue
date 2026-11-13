<script setup lang="ts">
import { Play } from "@lucide/vue";
import { computed, onMounted, ref } from "vue";

import { formatMillis, opsApi, type AgentPlaygroundResult, type AgentSpecRow } from "@/api/ops";

/**
 * F0: run an agent on one user's real data and see which tools it picks, with what arguments,
 * what came back, and the final answer. The run is recorded under the admin, not that user.
 */
const emit = defineEmits<{ (event: "open-run", runId: number): void }>();

const agents = ref<AgentSpecRow[]>([]);
const agent = ref("master");
const username = ref("");
const message = ref("");
const running = ref(false);
const error = ref("");
const result = ref<AgentPlaygroundResult | null>(null);
const openCall = ref<number | null>(null);

const statusLabels: Record<string, string> = { OK: "成功", ERROR: "出错", DENIED: "被拒绝", PENDING_CONFIRMATION: "等待确认" };
const stopLabels: Record<string, string> = {
  ANSWERED: "正常回答",
  STEP_LIMIT: "步数用完",
  TIME_LIMIT: "超时",
  MODEL_UNAVAILABLE: "模型不可用"
};
const examples = ["我下周有哪些安排？", "我现在哪方面能力最弱？", "上个月我都做了什么，写了心得吗？", "帮我预约一个周末的活动"];

const canRun = computed(() => !running.value && agent.value && username.value.trim() && message.value.trim());

onMounted(async () => {
  try {
    agents.value = (await opsApi.agentTools()).agents;
    if (agents.value.length && !agents.value.some((item) => item.name === agent.value)) {
      agent.value = agents.value[0].name;
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "Agent 列表读取失败";
  }
});

async function run() {
  if (!canRun.value) return;
  running.value = true;
  error.value = "";
  result.value = null;
  openCall.value = null;
  try {
    result.value = await opsApi.agentPlayground(agent.value, username.value.trim(), message.value.trim());
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "运行失败";
  } finally {
    running.value = false;
  }
}

function pretty(value: unknown) {
  if (typeof value === "string") {
    try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; }
  }
  return JSON.stringify(value, null, 2);
}
</script>

<template>
  <section class="ops-section">
    <div class="ops-card">
      <h2 class="ops-subtitle">输入</h2>
      <div class="pg-form">
        <label class="ops-field">Agent
          <select v-model="agent" class="ops-compact-select">
            <option v-for="item in agents" :key="item.name" :value="item.name">{{ item.title }}（{{ item.name }}）</option>
          </select>
        </label>
        <label class="ops-field">以哪个用户的数据
          <input v-model="username" class="pg-input" placeholder="用户名，比如 student_2b3c" />
        </label>
        <textarea v-model="message" class="pg-textarea" rows="3" maxlength="2000" placeholder="像学生一样说一句话" @keydown.ctrl.enter="run"></textarea>
        <div class="pg-examples">
          <span class="ops-muted">试试：</span>
          <button v-for="example in examples" :key="example" type="button" class="ops-link" @click="message = example">{{ example }}</button>
        </div>
        <div>
          <button type="button" class="ops-button" :disabled="!canRun" @click="run"><Play :size="15" /><span>{{ running ? "运行中…（可能要十几秒）" : "运行" }}</span></button>
          <span class="ops-muted pg-hint">会真实调用模型，计入你自己的“Agent 对话”限流（每分钟 8 次）。工具看到的是所选用户的数据，运行记录记在你名下。调试台是只读的：记忆写入和会调用模型的工具（如搜活动）不会执行，避免在这个用户身上留下记录。</span>
        </div>
      </div>
    </div>
    <p v-if="error" class="ops-error">{{ error }}</p>

    <template v-if="result">
      <div class="ops-card">
        <div class="ops-card-head">
          <h2 class="ops-subtitle">回答</h2>
          <span>
            <span class="ops-status" :class="result.reply.degraded ? 'status-failed' : 'status-succeeded'">{{ stopLabels[result.reply.stopReason] ?? result.reply.stopReason }}</span>
            <span class="ops-muted"> · 模型 {{ result.reply.modelRounds }} 轮 · 工具 {{ result.reply.toolCalls.length }} 次 · 用户 {{ result.username }}（{{ result.role }}）</span>
            <button v-if="result.reply.runId" type="button" class="ops-link" @click="emit('open-run', result.reply.runId)">查看运行记录 #{{ result.reply.runId }}</button>
          </span>
        </div>
        <ul v-if="result.reply.notices.length" class="pg-notices">
          <li v-for="(notice, index) in result.reply.notices" :key="index">{{ notice }}</li>
        </ul>
        <p class="pg-reply">{{ result.reply.reply }}</p>
        <p class="ops-muted">上面黄色的提示是系统附加的，不管模型怎么说都会显示给学生（工具出错、被拒绝、结果被截断等）。</p>
      </div>

      <div class="ops-card">
        <h2 class="ops-subtitle">工具调用</h2>
        <p v-if="!result.reply.toolCalls.length" class="ops-muted">这次没有调用工具。</p>
        <ol class="pg-calls">
          <li v-for="(call, index) in result.reply.toolCalls" :key="index">
            <button type="button" class="pg-call-head" @click="openCall = openCall === index ? null : index">
              <span class="ops-muted">第 {{ call.round }} 轮</span>
              <strong>{{ call.title }}</strong>
              <code>{{ call.argumentsJson }}</code>
              <span class="ops-status" :class="call.status === 'OK' ? 'status-succeeded' : 'status-failed'">{{ statusLabels[call.status] ?? call.status }}</span>
              <span class="ops-muted">{{ call.summaryForUser }} · {{ formatMillis(call.durationMillis) }}</span>
            </button>
            <div v-if="openCall === index" class="pg-call-body">
              <p v-if="call.messageForModel"><strong>告诉模型的话：</strong>{{ call.messageForModel }}</p>
              <p><strong>给模型的数据：</strong></p>
              <pre>{{ call.data === null ? "（无）" : pretty(call.data) }}</pre>
            </div>
          </li>
        </ol>
      </div>
    </template>
  </section>
</template>

<style scoped>
.pg-form {
  display: grid;
  gap: 10px;
}

.pg-input {
  min-width: 240px;
  padding: 6px 8px;
  border: 1px solid var(--line);
  border-radius: 7px;
}

.pg-textarea {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  font: inherit;
  resize: vertical;
}

.pg-examples {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  align-items: center;
}

.pg-hint {
  margin-left: 10px;
}

.pg-notices {
  margin: 0 0 10px;
  padding: 10px 12px 10px 28px;
  border-radius: 8px;
  background: #fbf1dc;
  color: #8a5f0b;
}

.pg-reply {
  margin: 0 0 8px;
  white-space: pre-wrap;
  line-height: 1.7;
}

.pg-calls {
  display: grid;
  gap: 8px;
  margin: 0;
  padding-left: 20px;
}

.pg-call-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 6px 0;
  border: 0;
  background: none;
  text-align: left;
  cursor: pointer;
  font: inherit;
}

.pg-call-head code {
  overflow-wrap: anywhere;
}

.pg-call-body pre {
  max-height: 320px;
  margin: 4px 0 0;
  padding: 10px;
  overflow: auto;
  border-radius: 8px;
  background: #1d2729;
  color: #e8efe9;
  font-size: 12px;
}
</style>
