<script setup lang="ts">
import { RefreshCw } from "@lucide/vue";
import { computed, onMounted, ref } from "vue";

import { opsApi, type RateLimitOverview } from "@/api/ops";

/** D4: today's model calls against the display-only budget, and who hit a limit. */
const data = ref<RateLimitOverview | null>(null);
const loading = ref(false);
const error = ref("");

const totalCalls = computed(() => (data.value ? data.value.chatCalls + data.value.embeddingCalls : 0));
const budgetShare = computed(() => {
  const budget = data.value?.dailyModelBudget ?? 0;
  return budget > 0 ? Math.min(100, Math.round((totalCalls.value / budget) * 100)) : 0;
});
const overBudget = computed(() => !!data.value && data.value.dailyModelBudget > 0 && totalCalls.value > data.value.dailyModelBudget);

onMounted(load);

async function load() {
  loading.value = true;
  error.value = "";
  try { data.value = await opsApi.rateLimits(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "限流统计读取失败"; }
  finally { loading.value = false; }
}
</script>

<template>
  <section class="ops-section">
    <div class="ops-toolbar">
      <span class="ops-muted">统计日期：{{ data?.date ?? "—" }}（每天零点重新计数）</span>
      <div class="ops-toolbar-actions">
        <button type="button" class="ops-button" :disabled="loading" @click="load"><RefreshCw :size="15" /><span>刷新</span></button>
      </div>
    </div>
    <p v-if="error" class="ops-error">{{ error }}</p>

    <div class="ops-card">
      <h2 class="ops-subtitle">今天的模型调用</h2>
      <div class="ops-counter-grid compact">
        <div class="ops-counter" :class="{ alert: overBudget }"><strong>{{ totalCalls }}</strong><span>总调用 / 预算 {{ data?.dailyModelBudget ?? "—" }}</span></div>
        <div class="ops-counter"><strong>{{ data?.chatCalls ?? "—" }}</strong><span>对话调用</span></div>
        <div class="ops-counter"><strong>{{ data?.embeddingCalls ?? "—" }}</strong><span>向量调用</span></div>
      </div>
      <ul class="ops-bars">
        <li>
          <span class="ops-bar-label">预算使用</span>
          <span class="ops-bar-track"><span class="ops-bar-fill" :class="overBudget ? 'tone-bad' : 'tone-good'" :style="{ width: budgetShare + '%' }"></span></span>
          <span class="ops-bar-value">{{ budgetShare }}%</span>
        </li>
      </ul>
      <p class="ops-muted">预算只用来提醒，超过了也不会暂停任何功能。数值在 <code>AI_DAILY_MODEL_BUDGET</code> 里改。</p>
    </div>

    <div class="ops-two-col">
      <div class="ops-card">
        <h2 class="ops-subtitle">今天被限流的次数</h2>
        <div class="ops-table-wrap">
          <table class="ops-table">
            <thead><tr><th>档位</th><th>次数</th></tr></thead>
            <tbody>
              <tr v-for="row in data?.rejections ?? []" :key="row.code"><td>{{ row.label }}</td><td>{{ row.count }}</td></tr>
              <tr v-if="!data?.rejections.length && !loading"><td colspan="2" class="ops-muted">今天还没有人被限流</td></tr>
            </tbody>
          </table>
        </div>
      </div>
      <div class="ops-card">
        <h2 class="ops-subtitle">被限流最多的对象</h2>
        <div class="ops-table-wrap">
          <table class="ops-table">
            <thead><tr><th>对象</th><th>档位</th><th>次数</th></tr></thead>
            <tbody>
              <tr v-for="row in data?.mostRejected ?? []" :key="row.code + row.subject"><td>{{ row.subject }}</td><td>{{ row.label }}</td><td>{{ row.count }}</td></tr>
              <tr v-if="!data?.mostRejected.length && !loading"><td colspan="3" class="ops-muted">没有记录</td></tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </section>
</template>
