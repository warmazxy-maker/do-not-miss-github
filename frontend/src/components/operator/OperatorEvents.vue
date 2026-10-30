<script setup lang="ts">
import { RefreshCw } from "@lucide/vue";
import { onMounted, ref, watch } from "vue";

import { ApiError } from "@/api/client";
import { operatorApi, type EventPage } from "@/api/operator";
import OperatorEventDetail from "@/components/operator/OperatorEventDetail.vue";

/** Every event on the platform, newest first; open one to take it offline, relist it, or read its history. */
const PAGE_SIZE = 20;
const status = ref("");
const keyword = ref("");
const page = ref(0);
const result = ref<EventPage | null>(null);
const loading = ref(false);
const error = ref("");
const openId = ref<number | null>(null);

const statuses = [
  ["", "全部"], ["APPROVED", "已上架"], ["PENDING_REVIEW", "审核中"], ["NEEDS_REVISION", "需修改"],
  ["OFFLINE", "已下架"], ["DRAFT", "已保存"]
];

onMounted(load);
watch(status, () => { page.value = 0; load(); });

async function load() {
  loading.value = true;
  error.value = "";
  try {
    result.value = await operatorApi.events({ status: status.value, keyword: keyword.value.trim(), page: page.value, size: PAGE_SIZE });
  } catch (cause) {
    error.value = cause instanceof ApiError || cause instanceof Error ? cause.message : "活动读取失败";
  } finally { loading.value = false; }
}

function search() {
  page.value = 0;
  load();
}

function go(delta: number) {
  page.value = Math.max(0, page.value + delta);
  load();
}

function time(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false })
    .format(new Date(value));
}
</script>

<template>
  <div class="ops-card">
    <div class="ops-card-head">
      <h2 class="ops-subtitle">活动管理</h2>
      <form class="ops-filters" @submit.prevent="search">
        <select v-model="status">
          <option v-for="[value, label] in statuses" :key="value" :value="value">{{ label }}</option>
        </select>
        <input v-model="keyword" placeholder="标题或组织" />
        <button type="submit" class="ops-button" :disabled="loading"><RefreshCw :size="15" /><span>查询</span></button>
      </form>
    </div>
    <p v-if="error" class="ops-error">{{ error }}</p>

    <table class="ops-table">
      <thead><tr><th>活动</th><th>组织</th><th>状态</th><th>时间</th><th>发布于</th></tr></thead>
      <tbody>
        <tr v-for="event in result?.items ?? []" :key="event.id" class="ops-clickable" @click="openId = event.id">
          <td>#{{ event.id }} {{ event.title }}</td>
          <td>{{ event.organizationName }}</td>
          <td>
            <span class="ops-status">{{ event.reviewStatusLabel }}</span>
            <small v-if="event.offlineReason" class="ops-muted"> {{ event.offlineReason }}</small>
          </td>
          <td>{{ time(event.startTime) }}（{{ event.timePhaseLabel }}）</td>
          <td>{{ time(event.createdAt) }}</td>
        </tr>
        <tr v-if="result && !result.items.length"><td colspan="5" class="ops-muted">没有符合条件的活动</td></tr>
      </tbody>
    </table>

    <div v-if="result && result.total > PAGE_SIZE" class="ops-toolbar-actions operator-pager">
      <button type="button" class="ops-button small" :disabled="page === 0 || loading" @click="go(-1)">上一页</button>
      <span class="ops-muted">第 {{ page + 1 }} / {{ Math.ceil(result.total / PAGE_SIZE) }} 页，共 {{ result.total }} 个</span>
      <button type="button" class="ops-button small" :disabled="(page + 1) * PAGE_SIZE >= result.total || loading" @click="go(1)">下一页</button>
    </div>

    <OperatorEventDetail v-if="openId !== null" :event-id="openId" @close="openId = null" @changed="load" />
  </div>
</template>
