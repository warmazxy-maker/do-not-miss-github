<script setup lang="ts">
import { LoaderCircle, X } from "@lucide/vue";
import { computed, ref, watch } from "vue";

import { ApiError } from "@/api/client";
import { operatorApi, type ReviewDetail } from "@/api/operator";

/**
 * Side drawer with everything an operator needs to decide (D3, L3): the event, the AI's revision
 * notes, the latest AI report and the status history; plus the actions that fit the event's state.
 */
const props = defineProps<{ eventId: number }>();
const emit = defineEmits<{ close: []; changed: [] }>();

const detail = ref<ReviewDetail | null>(null);
const loading = ref(false);
const busy = ref(false);
const error = ref("");
/** Which action is waiting for a written reason. */
const reasonFor = ref<"revision" | "offline" | "spot-offline" | null>(null);
const reason = ref("");

const event = computed(() => detail.value?.event ?? null);
const isManual = computed(() => event.value?.reviewStatus === "PENDING_REVIEW" && event.value.reviewRoute === "MANUAL");
const canRelist = computed(() =>
  event.value?.reviewStatus === "OFFLINE"
  && event.value.offlineReasonCode !== "DISCARDED"
  && event.value.timePhase !== "ENDED");
const canReanalyze = computed(() =>
  (event.value?.reviewStatus === "PENDING_REVIEW" || event.value?.reviewStatus === "NEEDS_REVISION")
  && event.value.timePhase !== "ENDED");

const actorLabels: Record<string, string> = { SYSTEM: "系统", AI: "AI 审核", ORGANIZER: "组织", OPERATOR: "运营" };

watch(() => props.eventId, load, { immediate: true });

async function load() {
  loading.value = true;
  error.value = "";
  reasonFor.value = null;
  try { detail.value = await operatorApi.detail(props.eventId); }
  catch (cause) { error.value = message(cause, "活动读取失败"); }
  finally { loading.value = false; }
}

async function run(action: () => Promise<unknown>) {
  busy.value = true;
  error.value = "";
  try {
    await action();
    emit("changed");
    await load();
  } catch (cause) {
    // 409: someone else decided first (optimistic lock, D3 L2). Reload to show what happened.
    error.value = message(cause, "操作失败");
    if (cause instanceof ApiError && cause.status === 409) await load();
  } finally { busy.value = false; }
}

function askReason(kind: "revision" | "offline" | "spot-offline") {
  reasonFor.value = kind;
  reason.value = "";
}

function submitReason() {
  const id = props.eventId;
  const text = reason.value.trim();
  if (!text) return;
  if (reasonFor.value === "revision") run(() => operatorApi.requestRevision(id, text));
  else if (reasonFor.value === "offline") run(() => operatorApi.takeOffline(id, text));
  else if (reasonFor.value === "spot-offline") run(() => operatorApi.failSpotCheck(id, text));
}

function time(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false })
    .format(new Date(value));
}

function message(cause: unknown, fallback: string) {
  return cause instanceof ApiError || cause instanceof Error ? cause.message : fallback;
}
</script>

<template>
  <div class="ops-drawer-backdrop" @click.self="emit('close')">
    <aside class="ops-drawer operator-detail">
      <header class="ops-card-head">
        <h2 class="ops-subtitle">{{ event?.title ?? "活动详情" }}</h2>
        <button type="button" class="ops-icon-button" title="关闭" @click="emit('close')"><X :size="18" /></button>
      </header>
      <p v-if="loading" class="ops-muted"><LoaderCircle class="spin" :size="16" /> 正在读取</p>
      <p v-if="error" class="ops-error">{{ error }}</p>

      <template v-if="detail && event">
        <section class="operator-actions">
          <span class="ops-status">{{ event.reviewStatusLabel }}</span>
          <span v-if="detail.pendingSpotCheck" class="ops-chip">抽检中</span>
          <span v-if="isManual" class="ops-chip tone-bad">AI 已要求修改 {{ event.aiRevisionCount }} 次，转人工</span>
          <div class="ops-toolbar-actions">
            <template v-if="isManual">
              <button type="button" class="ops-button" :disabled="busy" @click="run(() => operatorApi.approve(event!.id))">通过</button>
              <button type="button" class="ops-button" :disabled="busy" @click="askReason('revision')">要求修改</button>
            </template>
            <template v-if="detail.pendingSpotCheck">
              <button type="button" class="ops-button" :disabled="busy" @click="run(() => operatorApi.passSpotCheck(event!.id))">没问题</button>
              <button type="button" class="ops-button" :disabled="busy" @click="askReason('spot-offline')">有问题，下架</button>
            </template>
            <button v-else-if="event.reviewStatus === 'APPROVED'" type="button" class="ops-button" :disabled="busy" @click="askReason('offline')">下架</button>
            <button v-if="canRelist" type="button" class="ops-button" :disabled="busy" @click="run(() => operatorApi.relist(event!.id))">重新上架</button>
            <button v-if="canReanalyze" type="button" class="ops-button" :disabled="busy" @click="run(() => operatorApi.reanalyze(event!.id))">交回 AI 重新审核</button>
          </div>
          <form v-if="reasonFor" class="ops-approve-form" @submit.prevent="submitReason">
            <label>
              <span>{{ reasonFor === "revision" ? "修改意见（组织会看到）" : "下架原因（组织和已预约的学生会看到）" }}</span>
              <textarea v-model="reason" rows="3" maxlength="300" required />
            </label>
            <div class="ops-toolbar-actions">
              <button type="submit" class="ops-button small" :disabled="busy || !reason.trim()">确认</button>
              <button type="button" class="ops-button small" @click="reasonFor = null">取消</button>
            </div>
          </form>
        </section>

        <section>
          <h3 class="ops-mini-title">活动信息</h3>
          <dl class="ops-kv">
            <dt>组织</dt><dd>{{ event.organizationName }}</dd>
            <dt>时间</dt><dd>{{ time(event.startTime) }} - {{ time(event.endTime) }}（{{ event.timePhaseLabel }}）</dd>
            <dt>地点</dt><dd>{{ event.location }}</dd>
            <dt>分类</dt><dd>{{ event.category }}</dd>
            <dt>收益</dt><dd>{{ event.benefitType }}{{ event.skill ? ` · ${event.skill}` : "" }}{{ event.moneyAmount != null ? ` · ${event.moneyAmount}` : "" }}</dd>
            <dt v-if="event.offlineReason">下架原因</dt><dd v-if="event.offlineReason">{{ event.offlineReason }}</dd>
          </dl>
          <p class="ops-wrap operator-content">{{ event.content }}</p>
        </section>

        <section v-if="detail.aiRevisionNotes.length">
          <h3 class="ops-mini-title">AI 历次要求修改的理由</h3>
          <ol class="operator-notes">
            <li v-for="(note, index) in detail.aiRevisionNotes" :key="index">
              <small class="ops-muted">{{ time(note.createdAt) }}</small>
              <p>{{ note.note ?? "（未记录）" }}</p>
            </li>
          </ol>
        </section>

        <section v-if="detail.latestReport">
          <h3 class="ops-mini-title">最新一份 AI 质量报告</h3>
          <dl class="ops-kv">
            <dt>质量分</dt><dd>{{ detail.latestReport.qualityScore }}（{{ detail.latestReport.qualityLevel }}）</dd>
            <dt>审核建议</dt><dd>{{ detail.latestReport.reviewSuggestion }}</dd>
            <dt>摘要</dt><dd>{{ detail.latestReport.summary }}</dd>
            <dt>缺失信息</dt><dd>{{ detail.latestReport.missingFields.join("、") || "无" }}</dd>
            <dt>风险标记</dt><dd>{{ detail.latestReport.riskFlags.join("、") || "无" }}</dd>
          </dl>
        </section>

        <section>
          <h3 class="ops-mini-title">状态历史</h3>
          <ol class="operator-notes">
            <li v-for="(row, index) in detail.history" :key="index">
              <strong>{{ row.fromStatusLabel ? `${row.fromStatusLabel} → ` : "" }}{{ row.toStatusLabel }}</strong>
              <small class="ops-muted"> · {{ actorLabels[row.actorType] ?? row.actorType }} · {{ time(row.createdAt) }}</small>
              <p v-if="row.reason">{{ row.reason }}</p>
            </li>
          </ol>
        </section>
      </template>
    </aside>
  </div>
</template>
