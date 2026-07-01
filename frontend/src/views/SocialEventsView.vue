<script setup lang="ts">
import { AlertCircle, BarChart3, CalendarDays, LoaderCircle, RefreshCw, Trash2, X } from "@lucide/vue";
import { onMounted, ref } from "vue";

import { ApiError } from "@/api/client";
import { socialApi } from "@/api/workspace";
import type { EventItem } from "@/types/events";
import type { EventQualityReport } from "@/types/workspace";

const events = ref<EventItem[]>([]);
const loading = ref(true);
const error = ref("");
const report = ref<EventQualityReport | null>(null);
const reportOpen = ref(false);
const reportLoading = ref(false);
const busyId = ref<number | null>(null);

onMounted(load);

async function load() {
  loading.value = true;
  try { events.value = await socialApi.mine(); }
  catch (cause) { error.value = text(cause, "我的事件加载失败"); }
  finally { loading.value = false; }
}

async function openReport(event: EventItem) {
  reportOpen.value = true;
  reportLoading.value = true;
  report.value = null;
  try { report.value = await socialApi.report(event.id); }
  catch (cause) { error.value = text(cause, "质量报告仍在生成中"); }
  finally { reportLoading.value = false; }
}

async function reanalyze(eventId: number) {
  busyId.value = eventId;
  try { await socialApi.reanalyze(eventId); await load(); }
  catch (cause) { error.value = text(cause, "重新分析失败"); }
  finally { busyId.value = null; }
}

async function remove(eventId: number) {
  busyId.value = eventId;
  try { await socialApi.remove(eventId); events.value = events.value.filter((event) => event.id !== eventId); }
  catch (cause) { error.value = text(cause, "删除失败"); }
  finally { busyId.value = null; }
}

function statusLabel(status: string) {
  return ({ PENDING_REVIEW: "审核中", APPROVED: "已通过", NEEDS_REVISION: "需修改", REJECTED: "已拒绝" }[status] ?? status);
}

function date(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

function text(cause: unknown, fallback: string) {
  return cause instanceof ApiError || cause instanceof Error ? cause.message : fallback;
}
</script>

<template>
  <section class="social-module-view">
    <header class="module-page-heading">
      <div><p class="eyebrow">Published Events</p><h2>我的事件</h2><p>查看发布内容、审核状态和活动质量预处理结果。</p></div>
      <button type="button" @click="load"><RefreshCw :class="{ spin: loading }" :size="18" />刷新</button>
    </header>
    <div v-if="loading" class="achievement-loading"><LoaderCircle class="spin" :size="24" />正在读取事件</div>
    <div v-else-if="!events.length" class="module-empty"><CalendarDays :size="30" /><h3>还没有发布事件</h3><p>发布后会先进入质量分析与审核。</p></div>
    <div v-else class="social-event-list">
      <article v-for="event in events" :key="event.id">
        <div class="social-event-top">
          <span :class="`status-${event.reviewStatus.toLowerCase()}`">{{ statusLabel(event.reviewStatus) }}</span>
          <small>{{ date(event.startTime) }}</small>
        </div>
        <h3>{{ event.title }}</h3>
        <p>{{ event.organizationName }} · {{ event.category }} · {{ event.location }}</p>
        <div class="event-result-tags"><span class="event-category-tag">{{ event.category }}</span><span v-if="event.skill" class="event-skill-tag">{{ event.skill }}</span></div>
        <footer>
          <button type="button" @click="openReport(event)"><BarChart3 :size="16" />质量报告</button>
          <button v-if="event.reviewStatus !== 'APPROVED'" type="button" :disabled="busyId === event.id" @click="reanalyze(event.id)"><RefreshCw :size="16" />重新分析</button>
          <button type="button" :disabled="busyId === event.id" @click="remove(event.id)"><Trash2 :size="16" />删除</button>
        </footer>
      </article>
    </div>
    <p v-if="error" class="module-error">{{ error }}</p>

    <Teleport to="body"><Transition name="overlay"><div v-if="reportOpen" class="simple-dialog-layer" @click.self="reportOpen=false">
      <section class="quality-report-dialog">
        <header><div><p class="eyebrow">Event Quality Agent</p><h3>活动质量报告</h3></div><button type="button" title="关闭" @click="reportOpen=false"><X :size="19" /></button></header>
        <div v-if="reportLoading" class="achievement-loading"><LoaderCircle class="spin" :size="23" />正在读取报告</div>
        <template v-else-if="report">
          <div class="quality-score"><strong>{{ report.qualityScore }}</strong><span>{{ report.qualityLevel }} · 置信度 {{ Math.round(report.confidence*100) }}%</span></div>
          <p>{{ report.summary }}</p>
          <div class="quality-report-grid">
            <section><strong>难度</strong><p>{{ report.difficulty }}</p></section>
            <section><strong>审核建议</strong><p>{{ report.reviewSuggestion }}</p></section>
            <section><strong>适合人群</strong><span v-for="item in report.targetStudents" :key="item">{{ item }}</span></section>
            <section><strong>学习产出</strong><span v-for="item in report.learningOutcomes" :key="item">{{ item }}</span></section>
            <section><strong>缺失信息</strong><span v-for="item in report.missingFields" :key="item">{{ item }}</span></section>
            <section><strong>风险标记</strong><span v-for="item in report.riskFlags" :key="item">{{ item }}</span></section>
          </div>
        </template>
        <div v-else class="module-empty"><AlertCircle :size="25" /><p>报告仍在异步生成。</p></div>
      </section>
    </div></Transition></Teleport>
  </section>
</template>
