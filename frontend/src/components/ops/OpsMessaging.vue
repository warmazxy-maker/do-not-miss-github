<script setup lang="ts">
import { RefreshCw, RotateCcw } from "@lucide/vue";
import { onMounted, ref, watch } from "vue";

import { formatTime, opsApi, prettyJson, type Messaging, type OutboxRow } from "@/api/ops";

const messaging = ref<Messaging | null>(null);
const view = ref("STUCK");
const routingKey = ref("");
const rows = ref<OutboxRow[]>([]);
const loading = ref(false);
const error = ref("");
const notice = ref("");
const retrying = ref<number | null>(null);
const openPayload = ref<number | null>(null);

const views = [
  { key: "STUCK", label: "需要关注（延后 / 耗尽）" },
  { key: "PENDING", label: "PENDING" },
  { key: "FAILED", label: "FAILED" },
  { key: "SENT", label: "SENT" },
  { key: "ALL", label: "全部" }
];

async function loadMessaging() {
  try {
    messaging.value = await opsApi.messaging();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "加载失败";
  }
}

async function loadOutbox() {
  loading.value = true;
  error.value = "";
  try {
    rows.value = await opsApi.outbox(view.value, routingKey.value || undefined, 60);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "加载失败";
  } finally {
    loading.value = false;
  }
}

async function retry(row: OutboxRow) {
  if (!window.confirm(`把消息 #${row.id}（${row.routingKey}）重置为待发送？尝试次数会清零，调度器几秒内重新投递。`)) {
    return;
  }
  retrying.value = row.id;
  notice.value = "";
  try {
    await opsApi.retryOutbox(row.id);
    notice.value = `消息 #${row.id} 已重新排队`;
    await Promise.all([loadMessaging(), loadOutbox()]);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "重试失败";
  } finally {
    retrying.value = null;
  }
}

function refresh() {
  void loadMessaging();
  void loadOutbox();
}

function rowState(row: OutboxRow) {
  if (row.exhausted) return { label: "重试耗尽", tone: "failed" };
  if (row.deferred) return { label: "延后重试", tone: "deferred" };
  return { label: row.status, tone: row.status.toLowerCase() };
}

onMounted(refresh);
watch([view, routingKey], loadOutbox);
</script>

<template>
  <section class="ops-section">
    <div class="ops-toolbar">
      <span class="ops-muted">交换机：{{ messaging?.exchange ?? "—" }}</span>
      <button type="button" class="ops-button" :disabled="loading" @click="refresh">
        <RefreshCw :size="16" :class="{ spin: loading }" />
        <span>刷新</span>
      </button>
    </div>
    <p v-if="error" class="ops-error">{{ error }}</p>
    <p v-if="notice" class="ops-notice">{{ notice }}</p>

    <div class="ops-card">
      <h2 class="ops-subtitle">RabbitMQ 队列（已发布、等待消费）</h2>
      <div class="ops-table-wrap">
        <table class="ops-table">
          <thead>
            <tr><th>队列</th><th>路由键</th><th>积压消息</th><th>消费者</th><th>本机监听器</th></tr>
          </thead>
          <tbody>
            <tr v-for="queue in messaging?.queues ?? []" :key="queue.queue">
              <td>{{ queue.queue }}</td>
              <td><button type="button" class="ops-link" @click="routingKey = queue.routingKey; view = 'ALL'">{{ queue.routingKey }}</button></td>
              <td :class="{ 'ops-warn': (queue.messageCount ?? 0) > 0 }">{{ queue.messageCount ?? "—" }}</td>
              <td :class="{ 'ops-bad': queue.consumerCount === 0 }">{{ queue.consumerCount ?? "—" }}</td>
              <td>
                <span v-if="queue.error" class="ops-bad">{{ queue.error }}</span>
                <span v-else-if="queue.listenerRunning === null" class="ops-muted">本实例未监听</span>
                <span v-else :class="queue.listenerRunning ? 'ops-good' : 'ops-bad'">{{ queue.listenerRunning ? "运行中" : "已停止" }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="ops-card">
      <h2 class="ops-subtitle">Outbox（业务事务里写入、等待发布）</h2>
      <div class="ops-counter-grid compact">
        <div class="ops-counter"><strong>{{ messaging?.outbox.pending ?? "—" }}</strong><span>PENDING</span></div>
        <div class="ops-counter"><strong>{{ messaging?.outbox.deferred ?? "—" }}</strong><span>延后重试中</span></div>
        <div class="ops-counter" :class="{ alert: (messaging?.outbox.failed ?? 0) > 0 }"><strong>{{ messaging?.outbox.failed ?? "—" }}</strong><span>FAILED</span></div>
        <div class="ops-counter" :class="{ alert: (messaging?.outbox.exhausted ?? 0) > 0 }"><strong>{{ messaging?.outbox.exhausted ?? "—" }}</strong><span>耗尽（≥{{ messaging?.outbox.maxAttempts }} 次）</span></div>
      </div>
      <div class="ops-table-wrap">
        <table class="ops-table">
          <thead>
            <tr><th>路由键</th><th>SENT</th><th>PENDING</th><th>FAILED</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in messaging?.outbox.byRoutingKey ?? []" :key="String(row.routingKey)">
              <td><button type="button" class="ops-link" @click="routingKey = String(row.routingKey); view = 'ALL'">{{ row.routingKey }}</button></td>
              <td>{{ row.SENT }}</td>
              <td :class="{ 'ops-warn': Number(row.PENDING) > 0 }">{{ row.PENDING }}</td>
              <td :class="{ 'ops-bad': Number(row.FAILED) > 0 }">{{ row.FAILED }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="ops-card">
      <div class="ops-card-head">
        <h2 class="ops-subtitle">消息明细</h2>
        <div class="ops-filters">
          <select v-model="view">
            <option v-for="item in views" :key="item.key" :value="item.key">{{ item.label }}</option>
          </select>
          <select v-model="routingKey" :disabled="view === 'STUCK'">
            <option value="">全部路由键</option>
            <option v-for="queue in messaging?.queues ?? []" :key="queue.routingKey" :value="queue.routingKey">{{ queue.routingKey }}</option>
          </select>
        </div>
      </div>
      <div class="ops-table-wrap">
        <table class="ops-table">
          <thead>
            <tr><th>#</th><th>路由键</th><th>载荷</th><th>状态</th><th>尝试</th><th>下次尝试</th><th>最近错误</th><th></th></tr>
          </thead>
          <tbody>
            <template v-for="row in rows" :key="row.id">
              <tr>
                <td>{{ row.id }}</td>
                <td>{{ row.routingKey }}</td>
                <td><button type="button" class="ops-link" @click="openPayload = openPayload === row.id ? null : row.id">{{ row.payloadType }}</button></td>
                <td><span class="ops-status" :class="`status-${rowState(row).tone}`">{{ rowState(row).label }}</span></td>
                <td>{{ row.attempts }}</td>
                <td>{{ row.status === "SENT" ? formatTime(row.sentAt) : formatTime(row.nextAttemptAt) }}</td>
                <td class="ops-ellipsis" :title="row.lastError ?? ''">{{ row.lastError ?? "—" }}</td>
                <td>
                  <button
                    v-if="row.status !== 'SENT'"
                    type="button"
                    class="ops-button small"
                    :disabled="retrying === row.id"
                    @click="retry(row)"
                  >
                    <RotateCcw :size="14" />
                    <span>立即重试</span>
                  </button>
                </td>
              </tr>
              <tr v-if="openPayload === row.id" class="ops-payload-row">
                <td colspan="8"><pre>{{ prettyJson(row.payloadJson) }}</pre></td>
              </tr>
            </template>
            <tr v-if="!rows.length && !loading"><td colspan="8" class="ops-muted">没有消息{{ view === "STUCK" ? "需要关注，一切正常" : "" }}</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>
