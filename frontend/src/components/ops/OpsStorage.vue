<script setup lang="ts">
import { RefreshCw } from "@lucide/vue";
import { onMounted, ref } from "vue";

import { formatMillis, formatTime, opsApi, type DatabaseInfo, type RedisInfo } from "@/api/ops";

const redis = ref<RedisInfo | null>(null);
const database = ref<DatabaseInfo | null>(null);
const loading = ref(false);
const error = ref("");

async function load() {
  loading.value = true;
  error.value = "";
  const [redisResult, databaseResult] = await Promise.allSettled([opsApi.redis(), opsApi.database()]);
  if (redisResult.status === "fulfilled") {
    redis.value = redisResult.value;
  }
  if (databaseResult.status === "fulfilled") {
    database.value = databaseResult.value;
  } else {
    error.value = databaseResult.reason instanceof Error ? databaseResult.reason.message : "数据库信息加载失败";
  }
  loading.value = false;
}

function size(kb: number) {
  return kb >= 1024 ? `${(kb / 1024).toFixed(1)} MB` : `${kb} KB`;
}

onMounted(load);
</script>

<template>
  <section class="ops-section">
    <div class="ops-toolbar">
      <span class="ops-muted">Redis 只统计键的数量（SCAN，不阻塞），不显示任何键名或值；登录会话是令牌，永远不出 Redis。</span>
      <button type="button" class="ops-button" :disabled="loading" @click="load">
        <RefreshCw :size="16" :class="{ spin: loading }" />
        <span>刷新</span>
      </button>
    </div>
    <p v-if="error" class="ops-error">{{ error }}</p>

    <div class="ops-two-col">
      <div class="ops-card">
        <h2 class="ops-subtitle">Redis</h2>
        <p v-if="redis?.error" class="ops-bad">{{ redis.error }}</p>
        <dl v-else class="ops-kv">
          <dt>状态</dt><dd>{{ redis?.state ?? "—" }}（PING {{ formatMillis(redis?.latencyMillis) }}）</dd>
          <dt>键总数</dt><dd>{{ redis?.dbSize ?? "—" }}</dd>
          <dt>内存</dt><dd>{{ redis?.usedMemory ?? "—" }} / 上限 {{ redis?.maxMemory === "0B" ? "不限" : redis?.maxMemory ?? "—" }}</dd>
        </dl>
        <table class="ops-table">
          <thead><tr><th>前缀</th><th>用途</th><th>数量</th></tr></thead>
          <tbody>
            <tr v-for="row in redis?.prefixes ?? []" :key="row.prefix">
              <td><code>{{ row.prefix }}</code></td>
              <td>{{ row.meaning }}</td>
              <td>{{ row.count }}{{ row.capped ? "+" : "" }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="ops-card">
        <h2 class="ops-subtitle">MySQL</h2>
        <dl class="ops-kv">
          <dt>版本</dt><dd>{{ database?.product ?? "—" }}</dd>
          <dt>Flyway</dt><dd>V{{ database?.latestVersion ?? "—" }}</dd>
          <dt>连接池</dt>
          <dd>
            使用中 {{ database?.pool.active ?? "—" }} · 空闲 {{ database?.pool.idle ?? "—" }} · 上限 {{ database?.pool.max ?? "—" }}
            <span :class="{ 'ops-bad': (database?.pool.waiting ?? 0) > 0 }"> · 排队等待 {{ database?.pool.waiting ?? "—" }}</span>
          </dd>
        </dl>
        <h3 class="ops-mini-title">最近迁移</h3>
        <table class="ops-table">
          <thead><tr><th>版本</th><th>说明</th><th>执行时间</th><th>耗时</th></tr></thead>
          <tbody>
            <tr v-for="migration in database?.migrations ?? []" :key="migration.version">
              <td :class="{ 'ops-bad': !migration.success }">V{{ migration.version }}</td>
              <td>{{ migration.description }}</td>
              <td>{{ formatTime(migration.installedOn) }}</td>
              <td>{{ formatMillis(migration.executionMillis) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="ops-card">
      <h2 class="ops-subtitle">表大小（information_schema 估算值）</h2>
      <div class="ops-table-wrap">
        <table class="ops-table">
          <thead><tr><th>表</th><th>约行数</th><th>数据</th><th>索引</th></tr></thead>
          <tbody>
            <tr v-for="table in database?.tables ?? []" :key="table.table">
              <td><code>{{ table.table }}</code></td>
              <td>{{ table.approxRows }}</td>
              <td>{{ size(table.dataKb) }}</td>
              <td>{{ size(table.indexKb) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>
