<script setup lang="ts">
import { RefreshCw } from "@lucide/vue";
import { onMounted, ref, watch } from "vue";

import { formatTime, opsApi, type AuditLogRow } from "@/api/ops";

/** D1 audit log: sensitive writes that went through, and every refused attempt. */
const rows = ref<AuditLogRow[]>([]);
const outcome = ref("");
const userId = ref("");
const loading = ref(false);
const error = ref("");
const outcomeLabels: Record<string, string> = { ALLOWED: "已放行", DENIED: "被拒绝", FAILED: "执行出错" };

onMounted(load);
watch(outcome, load);

async function load() {
  loading.value = true;
  error.value = "";
  try { rows.value = await opsApi.auditLogs({ outcome: outcome.value, userId: userId.value.trim(), limit: 200 }); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "审计日志读取失败"; }
  finally { loading.value = false; }
}
</script>

<template>
  <div class="ops-card">
    <div class="ops-card-head">
      <h2 class="ops-subtitle">审计日志</h2>
      <form class="ops-filters" @submit.prevent="load">
        <select v-model="outcome">
          <option value="">全部</option>
          <option value="ALLOWED">已放行</option>
          <option value="DENIED">被拒绝</option>
          <option value="FAILED">执行出错</option>
        </select>
        <input v-model="userId" placeholder="用户 ID" />
        <button type="submit" class="ops-button" :disabled="loading"><RefreshCw :size="15" /><span>查询</span></button>
      </form>
    </div>
    <p class="ops-muted">只记录敏感权限下成功的写操作，以及所有被拒绝的尝试。反复被拒绝本身就是安全信号。</p>
    <p v-if="error" class="ops-error">{{ error }}</p>
    <div class="ops-table-wrap">
      <table class="ops-table">
        <thead><tr><th>时间</th><th>用户</th><th>操作</th><th>请求</th><th>结果</th></tr></thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id">
            <td>{{ formatTime(row.createdAt) }}</td>
            <td>{{ row.userId ?? "（未登录）" }}<br /><small class="ops-muted">{{ row.roleCode ?? "" }}</small></td>
            <td>{{ row.action }}<br /><small class="ops-muted">需要 {{ row.permission }}</small></td>
            <td class="ops-ellipsis">{{ row.httpMethod }} {{ row.path }}</td>
            <td>
              <span class="ops-chip" :class="row.outcome === 'ALLOWED' ? 'tone-good' : 'tone-bad'">{{ outcomeLabels[row.outcome] ?? row.outcome }}</span>
              <small v-if="row.detail" class="ops-muted"> {{ row.detail }}</small>
            </td>
          </tr>
          <tr v-if="!rows.length && !loading"><td colspan="5" class="ops-muted">没有记录</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
