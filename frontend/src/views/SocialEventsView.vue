<script setup lang="ts">
import {
  AlertCircle, BarChart3, CalendarDays, ClipboardCheck, History, LoaderCircle, PencilLine,
  PowerOff, RefreshCw, Send, Trash2, X
} from "@lucide/vue";
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";

import { ApiError } from "@/api/client";
import { socialApi } from "@/api/workspace";
import type { EventHistoryItem, OrganizerEventItem } from "@/types/events";
import type { EventQualityReport } from "@/types/workspace";
import { useOrganizerAttention } from "@/stores/organizerAttention";

const router = useRouter();
const events = ref<OrganizerEventItem[]>([]);
const loading = ref(true);
const error = ref("");
const info = ref("");
const report = ref<EventQualityReport | null>(null);
const reportOpen = ref(false);
const reportLoading = ref(false);
const busyId = ref<number | null>(null);

const history = ref<EventHistoryItem[]>([]);
const historyFor = ref<OrganizerEventItem | null>(null);
const checkInFor = ref<OrganizerEventItem | null>(null);
const checkInToken = ref("");

const attention = useOrganizerAttention();
// Opening this page is what "seen" means: the red dot clears.
onMounted(() => { load(); attention.markSeen(); });

async function load() {
  loading.value = true;
  try { events.value = await socialApi.mine(); }
  catch (cause) { error.value = text(cause, "我的事件加载失败"); }
  finally { loading.value = false; }
}

/** Runs one lifecycle action, then reloads: the backend is the only one who knows the next state. */
async function act(event: OrganizerEventItem, action: () => Promise<unknown>, done: string, fallback: string) {
  busyId.value = event.id; error.value = ""; info.value = "";
  try { await action(); info.value = done; await load(); }
  catch (cause) { error.value = text(cause, fallback); }
  finally { busyId.value = null; }
}

function submit(event: OrganizerEventItem) {
  act(event, () => socialApi.submit(event.id), `「${event.title}」已提交审核`, "提交失败");
}

function reanalyze(event: OrganizerEventItem) {
  act(event, () => socialApi.reanalyze(event.id), `「${event.title}」已重新提交分析`, "重新分析失败");
}

function takeOffline(event: OrganizerEventItem) {
  const reason = window.prompt(`下架「${event.title}」的原因（学生会在预约里看到）：`)?.trim();
  if (!reason) return;
  act(event, () => socialApi.takeOffline(event.id, reason), `「${event.title}」已下架，已有预约会保留并提示学生`, "下架失败");
}

function discard(event: OrganizerEventItem) {
  if (!window.confirm(`放弃「${event.title}」？放弃后不能恢复，需要重新发布。`)) return;
  act(event, () => socialApi.discard(event.id), `「${event.title}」已放弃`, "放弃失败");
}

function edit(event: OrganizerEventItem) {
  router.push({ path: "/social/publish", query: { edit: String(event.id) } });
}

async function openReport(event: OrganizerEventItem) {
  reportOpen.value = true;
  reportLoading.value = true;
  report.value = null;
  try { report.value = await socialApi.report(event.id); }
  catch (cause) { error.value = text(cause, "质量报告仍在生成中"); }
  finally { reportLoading.value = false; }
}

async function openHistory(event: OrganizerEventItem) {
  historyFor.value = event;
  history.value = [];
  try { history.value = await socialApi.history(event.id); }
  catch (cause) { error.value = text(cause, "状态历史读取失败"); }
}

function openCheckIn(event: OrganizerEventItem) {
  checkInFor.value = event;
  checkInToken.value = "";
  info.value = "";
  error.value = "";
}

/** The organisation types (or scans) the code shown on the student's ticket. */
async function checkIn() {
  const event = checkInFor.value;
  const token = checkInToken.value.trim();
  if (!event || !token) return;
  busyId.value = event.id; error.value = ""; info.value = "";
  try {
    const result = await socialApi.checkIn(event.id, token);
    info.value = `签到成功：学生 ${result.studentUserId}`;
    checkInToken.value = "";
  } catch (cause) { error.value = text(cause, "签到失败"); }
  finally { busyId.value = null; }
}

const actorLabels: Record<string, string> = { SYSTEM: "系统", AI: "AI 审核", ORGANIZER: "组织", OPERATOR: "运营" };

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
    <p v-if="info" class="publish-success">{{ info }}</p>
    <p v-if="error" class="module-error">{{ error }}</p>
    <div v-if="loading" class="achievement-loading"><LoaderCircle class="spin" :size="24" />正在读取事件</div>
    <div v-else-if="!events.length" class="module-empty"><CalendarDays :size="30" /><h3>还没有发布事件</h3><p>发布后会先进入质量分析与审核。</p></div>
    <div v-else class="social-event-list">
      <article v-for="event in events" :key="event.id">
        <div class="social-event-top">
          <span :class="`status-${event.reviewStatus.toLowerCase()}`">{{ event.reviewStatusLabel }}</span>
          <small>{{ date(event.startTime) }} · {{ event.timePhaseLabel }}</small>
        </div>
        <h3>{{ event.title }}</h3>
        <p>{{ event.organizationName }} · {{ event.category }} · {{ event.location }}</p>
        <p v-if="event.revisionNote" class="event-state-note is-revision">审核意见：{{ event.revisionNote }}</p>
        <p v-if="event.reviewStatus === 'PENDING_REVIEW' && event.reviewRoute === 'MANUAL'" class="event-state-note">
          AI 已多次要求修改，本次转由运营人工审核。
        </p>
        <p v-if="event.reviewStatus === 'OFFLINE' && event.offlineReason" class="event-state-note is-offline">
          下架原因：{{ event.offlineReason }}
        </p>
        <div class="event-result-tags"><span class="event-category-tag">{{ event.category }}</span><span v-if="event.skill" class="event-skill-tag">{{ event.skill }}</span></div>
        <footer>
          <button type="button" @click="openReport(event)"><BarChart3 :size="16" />质量报告</button>
          <button type="button" @click="openHistory(event)"><History :size="16" />状态历史</button>
          <button v-if="event.canEdit" type="button" :disabled="busyId === event.id" @click="edit(event)"><PencilLine :size="16" />编辑</button>
          <button v-if="event.canSubmit" type="button" :disabled="busyId === event.id" @click="submit(event)"><Send :size="16" />提交审核</button>
          <button v-if="event.reviewStatus === 'NEEDS_REVISION'" type="button" :disabled="busyId === event.id" @click="reanalyze(event)"><RefreshCw :size="16" />不修改，重新分析</button>
          <button v-if="event.canCheckIn" type="button" :disabled="busyId === event.id" @click="openCheckIn(event)"><ClipboardCheck :size="16" />签到</button>
          <button v-if="event.canTakeOffline" type="button" :disabled="busyId === event.id" @click="takeOffline(event)"><PowerOff :size="16" />下架</button>
          <button v-if="event.canDiscard" type="button" :disabled="busyId === event.id" @click="discard(event)"><Trash2 :size="16" />放弃</button>
        </footer>
      </article>
    </div>

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

    <Teleport to="body"><Transition name="overlay"><div v-if="historyFor" class="simple-dialog-layer" @click.self="historyFor=null">
      <section class="quality-report-dialog">
        <header><div><p class="eyebrow">Status History</p><h3>{{ historyFor.title }}</h3></div><button type="button" title="关闭" @click="historyFor=null"><X :size="19" /></button></header>
        <ol class="event-history-list">
          <li v-for="(row, index) in history" :key="index">
            <strong>{{ row.fromStatusLabel ? `${row.fromStatusLabel} → ` : "" }}{{ row.toStatusLabel }}</strong>
            <span>{{ actorLabels[row.actorType] ?? row.actorType }} · {{ date(row.createdAt) }}</span>
            <p v-if="row.reason">{{ row.reason }}</p>
          </li>
        </ol>
      </section>
    </div></Transition></Teleport>

    <Teleport to="body"><Transition name="overlay"><div v-if="checkInFor" class="simple-dialog-layer" @click.self="checkInFor=null">
      <section class="quality-report-dialog">
        <header><div><p class="eyebrow">Check-in</p><h3>签到：{{ checkInFor.title }}</h3></div><button type="button" title="关闭" @click="checkInFor=null"><X :size="19" /></button></header>
        <p>扫描学生出示的签到凭证，或手动输入凭证上的 token。</p>
        <form class="event-check-in-form" @submit.prevent="checkIn">
          <input v-model="checkInToken" placeholder="签到 token" autofocus />
          <button type="submit" :disabled="busyId === checkInFor.id || !checkInToken.trim()">
            <LoaderCircle v-if="busyId === checkInFor.id" class="spin" :size="16" /><ClipboardCheck v-else :size="16" />确认签到
          </button>
        </form>
        <p v-if="info" class="publish-success">{{ info }}</p>
        <p v-if="error" class="module-error">{{ error }}</p>
      </section>
    </div></Transition></Teleport>
  </section>
</template>
