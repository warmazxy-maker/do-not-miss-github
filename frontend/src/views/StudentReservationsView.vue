<script setup lang="ts">
import {
  CalendarCheck2,
  CalendarDays,
  CheckCircle2,
  Clock3,
  Copy,
  LoaderCircle,
  MapPin,
  QrCode,
  Search,
  TicketCheck,
  Trash2,
  X
} from "@lucide/vue";
import { computed, onMounted, ref } from "vue";
import { RouterLink } from "vue-router";

import { ApiError } from "@/api/client";
import { eventsApi } from "@/api/events";
import type { ReservationItem } from "@/types/events";

const reservations = ref<ReservationItem[]>([]);
const selected = ref<ReservationItem | null>(null);
const query = ref("");
const loading = ref(true);
const busyId = ref<number | null>(null);
const notice = ref("");
const noticeType = ref<"success" | "error">("success");

const now = ref(Date.now());
const filteredReservations = computed(() => {
  const keyword = query.value.trim().toLowerCase();
  if (!keyword) return reservations.value;
  return reservations.value.filter(({ event }) =>
    [event.title, event.organizationName, event.location, event.category, event.skill ?? ""]
      .some((value) => value.toLowerCase().includes(keyword))
  );
});
const upcomingCount = computed(() =>
  reservations.value.filter(({ event }) => new Date(event.startTime).getTime() >= now.value).length
);
const onlineCount = computed(() =>
  reservations.value.filter(({ event }) => event.location.includes("线上")).length
);
const nextReservation = computed(() =>
  [...reservations.value]
    .filter(({ event }) => new Date(event.startTime).getTime() >= now.value)
    .sort((a, b) => new Date(a.event.startTime).getTime() - new Date(b.event.startTime).getTime())[0] ?? null
);

onMounted(load);

async function load() {
  loading.value = true;
  notice.value = "";
  try {
    reservations.value = await eventsApi.reservations();
    if (selected.value) {
      selected.value = reservations.value.find((item) => item.id === selected.value?.id) ?? null;
    }
  } catch (cause) {
    showError(cause, "预约加载失败");
  } finally {
    loading.value = false;
  }
}

function openTicket(item: ReservationItem) {
  selected.value = item;
  notice.value = "";
}

async function cancel(item: ReservationItem) {
  if (!window.confirm(`确认取消「${item.event.title}」的预约吗？对应日程也会一并移除。`)) return;
  busyId.value = item.id;
  try {
    await eventsApi.cancelReservation(item.id);
    selected.value = null;
    reservations.value = reservations.value.filter((reservation) => reservation.id !== item.id);
    showNotice("预约已取消，对应日程已同步移除");
  } catch (cause) {
    showError(cause, "取消预约失败");
  } finally {
    busyId.value = null;
  }
}

async function complete(item: ReservationItem) {
  if (!window.confirm(`模拟扫描「${item.event.title}」的签到凭证并完成活动？`)) return;
  busyId.value = item.id;
  try {
    await eventsApi.completeReservation(item.qrToken);
    selected.value = null;
    reservations.value = reservations.value.filter((reservation) => reservation.id !== item.id);
    showNotice("签到完成，活动已迁移到个人成就");
  } catch (cause) {
    showError(cause, "扫码完成失败");
  } finally {
    busyId.value = null;
  }
}

async function copyToken(item: ReservationItem) {
  try {
    await navigator.clipboard.writeText(item.qrToken);
    showNotice("签到凭证已复制");
  } catch {
    showError(null, "浏览器未允许复制，请手动选择凭证");
  }
}

function status(item: ReservationItem) {
  const start = new Date(item.event.startTime).getTime();
  const end = new Date(item.event.endTime).getTime();
  if (end < now.value) return { label: "待补签", tone: "late" };
  if (start - now.value < 24 * 60 * 60 * 1000) return { label: "即将开始", tone: "soon" };
  return { label: "已预约", tone: "booked" };
}

function dateTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    weekday: "short",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false
  }).format(new Date(value));
}

function duration(item: ReservationItem) {
  const minutes = Math.max(0, Math.round(
    (new Date(item.event.endTime).getTime() - new Date(item.event.startTime).getTime()) / 60000
  ));
  if (minutes < 60) return `${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest ? `${hours} 小时 ${rest} 分钟` : `${hours} 小时`;
}

function showNotice(message: string) {
  noticeType.value = "success";
  notice.value = message;
}

function showError(cause: unknown, fallback: string) {
  noticeType.value = "error";
  notice.value = cause instanceof ApiError || cause instanceof Error ? cause.message : fallback;
}
</script>

<template>
  <section class="reservations-workspace-vue">
    <header class="module-page-heading">
      <div>
        <p class="eyebrow">Reservations</p>
        <h2>已经决定参加的事情，都在这里</h2>
        <p>预约会自动进入日程。到场后使用签到凭证完成活动，记录将迁移到个人成就。</p>
      </div>
      <RouterLink class="reservation-discover-link" to="/student/events">
        <Search :size="18" />
        继续找事件
      </RouterLink>
    </header>

    <div class="reservation-overview">
      <article>
        <span><TicketCheck :size="19" /></span>
        <div><small>有效预约</small><strong>{{ reservations.length }}</strong></div>
      </article>
      <article>
        <span><CalendarCheck2 :size="19" /></span>
        <div><small>未来活动</small><strong>{{ upcomingCount }}</strong></div>
      </article>
      <article>
        <span><QrCode :size="19" /></span>
        <div><small>线上活动</small><strong>{{ onlineCount }}</strong></div>
      </article>
      <article class="reservation-next">
        <div>
          <small>下一项安排</small>
          <strong>{{ nextReservation?.event.title ?? "暂无未来活动" }}</strong>
          <span v-if="nextReservation">{{ dateTime(nextReservation.event.startTime) }}</span>
        </div>
        <RouterLink to="/student/schedule" title="查看日程"><CalendarDays :size="19" /></RouterLink>
      </article>
    </div>

    <div class="reservation-toolbar">
      <label>
        <Search :size="18" />
        <input v-model="query" placeholder="搜索活动、组织、地点或技能" />
      </label>
      <span>{{ filteredReservations.length }} 项预约</span>
    </div>

    <div v-if="notice" class="reservation-notice" :class="`is-${noticeType}`">
      <CheckCircle2 v-if="noticeType === 'success'" :size="18" />
      <span>{{ notice }}</span>
      <button type="button" title="关闭" @click="notice = ''"><X :size="16" /></button>
    </div>

    <div v-if="loading" class="achievement-loading">
      <LoaderCircle class="spin" :size="24" />
      正在读取预约
    </div>

    <div v-else-if="!filteredReservations.length" class="module-empty">
      <TicketCheck :size="31" />
      <h3>{{ query ? "没有匹配的预约" : "还没有预约活动" }}</h3>
      <p>{{ query ? "换一个关键词试试。" : "去事件页找到想参加的活动，预约后会自动加入日程。" }}</p>
      <RouterLink v-if="!query" class="reservation-discover-link" to="/student/events">浏览事件</RouterLink>
    </div>

    <div v-else class="reservation-list-vue">
      <article v-for="item in filteredReservations" :key="item.id">
        <div class="reservation-date-block">
          <strong>{{ new Date(item.event.startTime).getDate() }}</strong>
          <span>{{ new Intl.DateTimeFormat("zh-CN", { month: "short" }).format(new Date(item.event.startTime)) }}</span>
        </div>
        <div class="reservation-card-main">
          <header>
            <div>
              <span class="reservation-state" :class="`is-${status(item).tone}`">{{ status(item).label }}</span>
              <small>{{ item.event.category }}</small>
            </div>
            <small>预约于 {{ dateTime(item.reservedAt) }}</small>
          </header>
          <h3>{{ item.event.title }}</h3>
          <p class="reservation-organization">{{ item.event.organizationName }}</p>
          <div class="reservation-facts">
            <span><Clock3 :size="16" />{{ dateTime(item.event.startTime) }} - {{ dateTime(item.event.endTime).split(" ").at(-1) }}</span>
            <span><MapPin :size="16" />{{ item.event.location }}</span>
            <span>{{ duration(item) }}</span>
          </div>
          <p>{{ item.event.content }}</p>
          <footer>
            <button type="button" class="reservation-cancel" :disabled="busyId === item.id" @click="cancel(item)">
              <Trash2 :size="16" />取消预约
            </button>
            <button type="button" class="reservation-ticket" :disabled="busyId === item.id" @click="openTicket(item)">
              <QrCode :size="17" />签到凭证
            </button>
          </footer>
        </div>
      </article>
    </div>

    <Teleport to="body">
      <Transition name="overlay">
        <div v-if="selected" class="simple-dialog-layer" @click.self="selected = null">
          <section class="reservation-ticket-dialog" role="dialog" aria-modal="true" aria-label="活动签到凭证">
            <header>
              <div><p class="eyebrow">Check-in Ticket</p><h3>活动签到凭证</h3></div>
              <button type="button" title="关闭" @click="selected = null"><X :size="19" /></button>
            </header>
            <div class="reservation-ticket-mark"><QrCode :size="58" /></div>
            <strong>{{ selected.event.title }}</strong>
            <p>{{ selected.event.organizationName }} · {{ selected.event.location }}</p>
            <div class="reservation-ticket-time">
              <Clock3 :size="18" />
              <span>{{ dateTime(selected.event.startTime) }}<br />{{ duration(selected) }}</span>
            </div>
            <label>
              <span>签到 token</span>
              <div><code>{{ selected.qrToken }}</code><button type="button" title="复制凭证" @click="copyToken(selected)"><Copy :size="17" /></button></div>
            </label>
            <p class="reservation-ticket-help">当前为开发环境，点击下方按钮模拟现场扫码。正式环境可由组织端扫描该凭证。</p>
            <button class="reservation-complete-button" type="button" :disabled="busyId === selected.id" @click="complete(selected)">
              <LoaderCircle v-if="busyId === selected.id" class="spin" :size="18" />
              <CheckCircle2 v-else :size="18" />
              模拟扫码并完成活动
            </button>
          </section>
        </div>
      </Transition>
    </Teleport>
  </section>
</template>
