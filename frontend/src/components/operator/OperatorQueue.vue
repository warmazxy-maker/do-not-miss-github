<script setup lang="ts">
import { RefreshCw, ScanSearch } from "@lucide/vue";
import { onMounted, ref } from "vue";

import { ApiError } from "@/api/client";
import { operatorApi, type QueueItem } from "@/api/operator";
import OperatorEventDetail from "@/components/operator/OperatorEventDetail.vue";

/**
 * Review queue (D3): events the AI handed over after three revision requests, plus spot checks of
 * AI-approved events an operator asked for. First in, first out.
 */
const items = ref<QueueItem[]>([]);
const loading = ref(false);
const drawing = ref(false);
const error = ref("");
const notice = ref("");
const openId = ref<number | null>(null);
const now = ref(Date.now());

/** Waiting longer than this is shown in red. */
const OVERDUE_MS = 24 * 60 * 60 * 1000;

onMounted(load);

async function load() {
  loading.value = true;
  error.value = "";
  now.value = Date.now();
  try { items.value = await operatorApi.queue(); }
  catch (cause) { error.value = message(cause, "队列读取失败"); }
  finally { loading.value = false; }
}

async function draw() {
  drawing.value = true;
  error.value = "";
  notice.value = "";
  try {
    const { drawn } = await operatorApi.drawSpotChecks();
    notice.value = drawn ? `已抽出 ${drawn} 个最近由 AI 通过的活动` : "没有可抽检的活动（最近 AI 通过的都已抽过）";
    await load();
  } catch (cause) { error.value = message(cause, "抽检失败"); }
  finally { drawing.value = false; }
}

function waited(since: string) {
  const minutes = Math.max(0, Math.round((now.value - new Date(since).getTime()) / 60000));
  if (minutes < 60) return `${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);
  return hours < 24 ? `${hours} 小时` : `${Math.floor(hours / 24)} 天 ${hours % 24} 小时`;
}

function overdue(since: string) {
  return now.value - new Date(since).getTime() > OVERDUE_MS;
}

function message(cause: unknown, fallback: string) {
  return cause instanceof ApiError || cause instanceof Error ? cause.message : fallback;
}
</script>

<template>
  <div class="ops-card">
    <div class="ops-card-head">
      <h2 class="ops-subtitle">审核队列</h2>
      <div class="ops-toolbar-actions">
        <button type="button" class="ops-button" :disabled="drawing" @click="draw"><ScanSearch :size="15" /><span>抽检</span></button>
        <button type="button" class="ops-button" :disabled="loading" @click="load"><RefreshCw :size="15" /><span>刷新</span></button>
      </div>
    </div>
    <p class="ops-muted">
      “转人工”：AI 已经连续三次要求修改，组织再次提交后由你决定。
      “抽检”：每点一次，从最近由 AI 通过、还没抽过的活动里取 5 个复查，复查期间活动照常上架。
    </p>
    <p v-if="notice" class="ops-notice">{{ notice }}</p>
    <p v-if="error" class="ops-error">{{ error }}</p>

    <table class="ops-table">
      <thead><tr><th>类型</th><th>活动</th><th>组织</th><th>已等待</th></tr></thead>
      <tbody>
        <tr v-for="item in items" :key="`${item.kind}-${item.eventId}`" class="ops-clickable" @click="openId = item.eventId">
          <td><span class="ops-chip" :class="{ 'tone-bad': item.kind === 'MANUAL' }">{{ item.kind === "MANUAL" ? "转人工" : "抽检" }}</span></td>
          <td>#{{ item.eventId }} {{ item.title }}</td>
          <td>{{ item.organizationName }}</td>
          <td :class="{ 'ops-bad': overdue(item.waitingSince) }">{{ waited(item.waitingSince) }}</td>
        </tr>
        <tr v-if="!items.length && !loading"><td colspan="4" class="ops-muted">队列是空的</td></tr>
      </tbody>
    </table>

    <OperatorEventDetail v-if="openId !== null" :event-id="openId" @close="openId = null" @changed="load" />
  </div>
</template>
