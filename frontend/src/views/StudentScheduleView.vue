<script setup lang="ts">
import {
  CalendarDays,
  ChevronLeft,
  ChevronRight,
  GripHorizontal,
  LoaderCircle,
  MapPin,
  Plus,
  Save,
  Trash2,
  X
} from "@lucide/vue";
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";

import { ApiError } from "@/api/client";
import { challengesApi, scheduleApi } from "@/api/workspace";
import type { Challenge, ScheduleItem } from "@/types/workspace";

const START_HOUR = 7;
const END_HOUR = 22;
const SLOT_MINUTES = 30;
const DAY_MINUTES = (END_HOUR - START_HOUR) * 60;
const palette = [
  ["#dcecf7", "#4f82a5"],
  ["#dff2ec", "#3d8b77"],
  ["#fff0ce", "#b77a17"],
  ["#eee5f7", "#7d5b96"],
  ["#f8dfdf", "#af5f62"],
  ["#dff1f4", "#458794"],
  ["#edf0ce", "#818c32"]
];
const route = useRoute();
const router = useRouter();

const items = ref<ScheduleItem[]>([]);
const challenges = ref<Challenge[]>([]);
const loading = ref(true);
const error = ref("");
const anchor = ref(startOfWeek(new Date()));
const editorOpen = ref(false);
const saving = ref(false);
const notice = ref("");
const selection = ref<{ dayIndex: number; startMinute: number; endMinute: number } | null>(null);
const draggingId = ref<number | null>(null);
const form = reactive({
  id: null as number | null,
  itemType: "CUSTOM" as ScheduleItem["itemType"],
  sourceId: null as number | null,
  title: "",
  startTime: "",
  endTime: "",
  location: "",
  notes: ""
});
let pointerCleanup: (() => void) | null = null;
let noticeTimer: ReturnType<typeof setTimeout> | undefined;

const weekDays = computed(() =>
  Array.from({ length: 7 }, (_, index) => addDays(anchor.value, index))
);
const weekLabel = computed(() => {
  const first = weekDays.value[0];
  const last = weekDays.value[6];
  return `${first.getFullYear()}年 ${formatMonthDay(first)} - ${formatMonthDay(last)}`;
});
const hours = computed(() =>
  Array.from({ length: END_HOUR - START_HOUR }, (_, index) => START_HOUR + index)
);
const weekItems = computed(() => {
  const start = anchor.value.getTime();
  const end = addDays(anchor.value, 7).getTime();
  return items.value.filter((item) => {
    const time = new Date(item.startTime).getTime();
    return time >= start && time < end;
  });
});

onMounted(async () => {
  await load();
  if (route.query.action === "create") {
    openQuickNew();
    await router.replace("/student/schedule");
  }
});
onBeforeUnmount(() => pointerCleanup?.());

async function load() {
  loading.value = true;
  error.value = "";
  try {
    [items.value, challenges.value] = await Promise.all([scheduleApi.list(), challengesApi.active()]);
  } catch (cause) {
    error.value = message(cause, "日程加载失败");
  } finally {
    loading.value = false;
  }
}

function previousWeek() {
  anchor.value = addDays(anchor.value, -7);
}

function nextWeek() {
  anchor.value = addDays(anchor.value, 7);
}

function today() {
  anchor.value = startOfWeek(new Date());
}

function dayItems(day: Date) {
  const key = dateKey(day);
  return weekItems.value.filter((item) => dateKey(new Date(item.startTime)) === key);
}

function itemStyle(item: ScheduleItem) {
  const start = new Date(item.startTime);
  const end = new Date(item.endTime);
  const startMinute = clamp(start.getHours() * 60 + start.getMinutes() - START_HOUR * 60, 0, DAY_MINUTES);
  const duration = clamp((end.getTime() - start.getTime()) / 60000, SLOT_MINUTES, DAY_MINUTES - startMinute);
  const [background, border] = palette[colorIndex(item)];
  return {
    top: `${(startMinute / DAY_MINUTES) * 100}%`,
    height: `${(duration / DAY_MINUTES) * 100}%`,
    background,
    borderColor: border
  };
}

function selectionStyle(dayIndex: number) {
  if (!selection.value || selection.value.dayIndex !== dayIndex) return {};
  return {
    top: `${(selection.value.startMinute / DAY_MINUTES) * 100}%`,
    height: `${((selection.value.endMinute - selection.value.startMinute) / DAY_MINUTES) * 100}%`
  };
}

function beginSelection(event: PointerEvent, dayIndex: number) {
  if ((event.target as HTMLElement).closest(".week-event-block")) return;
  const column = event.currentTarget as HTMLElement;
  const minute = minuteFromPointer(event.clientY, column);
  selection.value = { dayIndex, startMinute: minute, endMinute: minute + SLOT_MINUTES };
  const move = (next: PointerEvent) => {
    const current = minuteFromPointer(next.clientY, column);
    selection.value = {
      dayIndex,
      startMinute: Math.min(minute, current),
      endMinute: Math.max(minute + SLOT_MINUTES, current + SLOT_MINUTES)
    };
  };
  const up = () => {
    const value = selection.value;
    cleanup();
    if (value) openNew(value.dayIndex, value.startMinute, value.endMinute);
    selection.value = null;
  };
  const cleanup = () => {
    window.removeEventListener("pointermove", move);
    window.removeEventListener("pointerup", up);
    pointerCleanup = null;
  };
  pointerCleanup = cleanup;
  window.addEventListener("pointermove", move);
  window.addEventListener("pointerup", up, { once: true });
}

function openNew(dayIndex: number, startMinute: number, endMinute: number) {
  const date = weekDays.value[dayIndex];
  Object.assign(form, {
    id: null,
    itemType: "CUSTOM",
    sourceId: null,
    title: "",
    startTime: localDateTime(atMinute(date, startMinute)),
    endTime: localDateTime(atMinute(date, endMinute)),
    location: "",
    notes: ""
  });
  editorOpen.value = true;
}

function openEditor(item: ScheduleItem) {
  Object.assign(form, {
    id: item.id,
    itemType: item.itemType,
    sourceId: item.sourceId,
    title: item.title,
    startTime: item.startTime.slice(0, 16),
    endTime: item.endTime.slice(0, 16),
    location: item.location ?? "",
    notes: item.notes ?? ""
  });
  editorOpen.value = true;
}

function openQuickNew() {
  const now = new Date();
  const dayIndex = weekDays.value.findIndex((day) => dateKey(day) === dateKey(now));
  const index = dayIndex >= 0 ? dayIndex : 0;
  const minutes = clamp(snap((now.getHours() - START_HOUR) * 60 + now.getMinutes()), 0, DAY_MINUTES - 60);
  openNew(index, minutes, minutes + 60);
}

function selectChallenge() {
  const challenge = challenges.value.find((item) => item.id === form.sourceId);
  if (!challenge) {
    form.itemType = "CUSTOM";
    return;
  }
  form.itemType = "CHALLENGE";
  form.title ||= challenge.title;
  form.notes ||= `${challenge.goal}\n${challenge.description}`;
}

async function save() {
  if (!form.title.trim() || !form.startTime || !form.endTime) {
    showNotice("请填写标题和时间");
    return;
  }
  saving.value = true;
  try {
    if (form.id) {
      const updated = await scheduleApi.update(form.id, {
        title: form.title.trim(),
        startTime: form.startTime,
        endTime: form.endTime,
        location: form.location || null,
        notes: form.notes || null
      });
      replace(updated);
    } else {
      const created = await scheduleApi.create({
        itemType: form.itemType,
        sourceId: form.itemType === "CHALLENGE" ? form.sourceId : null,
        title: form.title.trim(),
        startTime: form.startTime,
        endTime: form.endTime,
        location: form.location || null,
        notes: form.notes || null
      });
      items.value.push(created);
    }
    editorOpen.value = false;
    showNotice("日程已保存");
  } catch (cause) {
    showNotice(message(cause, "保存失败"));
  } finally {
    saving.value = false;
  }
}

async function remove() {
  if (!form.id) return;
  saving.value = true;
  try {
    await scheduleApi.remove(form.id);
    items.value = items.value.filter((item) => item.id !== form.id);
    editorOpen.value = false;
    showNotice("日程已删除");
  } catch (cause) {
    showNotice(message(cause, "删除失败"));
  } finally {
    saving.value = false;
  }
}

function dragStart(item: ScheduleItem, event: DragEvent) {
  draggingId.value = item.id;
  event.dataTransfer?.setData("text/plain", String(item.id));
  if (event.dataTransfer) event.dataTransfer.effectAllowed = "move";
}

async function dropItem(event: DragEvent, dayIndex: number) {
  event.preventDefault();
  const id = Number(event.dataTransfer?.getData("text/plain") || draggingId.value);
  const item = items.value.find((candidate) => candidate.id === id);
  const column = event.currentTarget as HTMLElement;
  draggingId.value = null;
  if (!item) return;
  const startMinute = minuteFromPointer(event.clientY, column);
  const duration = Math.max((new Date(item.endTime).getTime() - new Date(item.startTime).getTime()) / 60000, SLOT_MINUTES);
  const start = atMinute(weekDays.value[dayIndex], startMinute);
  await quickUpdate(item, start, new Date(start.getTime() + duration * 60000));
}

function beginResize(item: ScheduleItem, event: PointerEvent) {
  event.stopPropagation();
  const block = (event.currentTarget as HTMLElement).closest(".week-event-block") as HTMLElement;
  const column = block.parentElement as HTMLElement;
  const start = new Date(item.startTime);
  const move = (next: PointerEvent) => {
    const endMinute = Math.max(
      minuteFromPointer(next.clientY, column),
      start.getHours() * 60 + start.getMinutes() - START_HOUR * 60 + SLOT_MINUTES
    );
    block.style.height = `${((endMinute - (start.getHours() * 60 + start.getMinutes() - START_HOUR * 60)) / DAY_MINUTES) * 100}%`;
  };
  const up = async (next: PointerEvent) => {
    cleanup();
    const endMinute = Math.max(
      minuteFromPointer(next.clientY, column),
      start.getHours() * 60 + start.getMinutes() - START_HOUR * 60 + SLOT_MINUTES
    );
    await quickUpdate(item, start, atMinute(start, endMinute));
  };
  const cleanup = () => {
    window.removeEventListener("pointermove", move);
    window.removeEventListener("pointerup", up);
    pointerCleanup = null;
  };
  pointerCleanup = cleanup;
  window.addEventListener("pointermove", move);
  window.addEventListener("pointerup", up, { once: true });
}

async function quickUpdate(item: ScheduleItem, start: Date, end: Date) {
  try {
    replace(
      await scheduleApi.update(item.id, {
        title: item.title,
        startTime: localDateTime(start),
        endTime: localDateTime(end),
        location: item.location,
        notes: item.notes
      })
    );
    showNotice("时间已更新");
  } catch (cause) {
    showNotice(message(cause, "拖拽更新失败"));
    await load();
  }
}

function replace(updated: ScheduleItem) {
  items.value = items.value.map((item) => (item.id === updated.id ? updated : item));
}

function minuteFromPointer(clientY: number, column: HTMLElement) {
  const rect = column.getBoundingClientRect();
  return clamp(snap(((clientY - rect.top) / rect.height) * DAY_MINUTES), 0, DAY_MINUTES - SLOT_MINUTES);
}

function atMinute(date: Date, minute: number) {
  const result = new Date(date);
  result.setHours(START_HOUR, minute, 0, 0);
  return result;
}

function startOfWeek(value: Date) {
  const date = new Date(value);
  date.setHours(0, 0, 0, 0);
  date.setDate(date.getDate() - ((date.getDay() + 6) % 7));
  return date;
}

function addDays(value: Date, days: number) {
  const date = new Date(value);
  date.setDate(date.getDate() + days);
  return date;
}

function dateKey(value: Date) {
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, "0")}-${String(value.getDate()).padStart(2, "0")}`;
}

function localDateTime(value: Date) {
  return `${dateKey(value)}T${String(value.getHours()).padStart(2, "0")}:${String(value.getMinutes()).padStart(2, "0")}`;
}

function formatMonthDay(value: Date) {
  return new Intl.DateTimeFormat("zh-CN", { month: "short", day: "numeric" }).format(value);
}

function weekday(value: Date) {
  return new Intl.DateTimeFormat("zh-CN", { weekday: "short" }).format(value);
}

function time(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(value));
}

function colorIndex(item: ScheduleItem) {
  const text = `${item.itemType}-${item.id}-${item.title}`;
  let hash = 0;
  for (const char of text) hash = (hash * 31 + char.charCodeAt(0)) | 0;
  return Math.abs(hash) % palette.length;
}

function snap(value: number) {
  return Math.round(value / SLOT_MINUTES) * SLOT_MINUTES;
}

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}

function showNotice(value: string) {
  notice.value = value;
  if (noticeTimer) clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => (notice.value = ""), 2600);
}

function message(cause: unknown, fallback: string) {
  return cause instanceof ApiError || cause instanceof Error ? cause.message : fallback;
}
</script>

<template>
  <section class="schedule-workspace-vue">
    <header class="schedule-page-header">
      <div>
        <p class="eyebrow">Weekly Schedule</p>
        <h2>把目标放进真正可执行的时间里</h2>
        <p>在空白处拖出时间块，拖动卡片改期，拖底部调整时长。</p>
      </div>
      <button type="button" @click="openQuickNew"><Plus :size="18" />新建安排</button>
    </header>

    <div class="schedule-navigation">
      <div>
        <button type="button" title="上一周" @click="previousWeek"><ChevronLeft :size="19" /></button>
        <strong>{{ weekLabel }}</strong>
        <button type="button" title="下一周" @click="nextWeek"><ChevronRight :size="19" /></button>
      </div>
      <button type="button" @click="today"><CalendarDays :size="17" />今天</button>
    </div>

    <div v-if="loading" class="achievement-loading"><LoaderCircle class="spin" :size="25" />正在读取日程</div>
    <div v-else-if="error" class="achievement-error"><p>{{ error }}</p><button @click="load">重新加载</button></div>
    <div v-else class="week-calendar-scroll">
      <div class="week-calendar">
        <div class="week-calendar-header">
          <span class="week-time-corner">时间</span>
          <div
            v-for="day in weekDays"
            :key="dateKey(day)"
            :class="{ 'is-today': dateKey(day) === dateKey(new Date()) }"
          >
            <span>{{ weekday(day) }}</span>
            <strong>{{ day.getDate() }}</strong>
            <small>{{ dayItems(day).length ? `${dayItems(day).length} 项` : "" }}</small>
          </div>
        </div>
        <div class="week-calendar-body">
          <div class="week-time-axis">
            <span v-for="hour in hours" :key="hour">{{ String(hour).padStart(2, "0") }}:00</span>
          </div>
          <div
            v-for="(day, dayIndex) in weekDays"
            :key="dateKey(day)"
            class="week-day-column"
            :class="{ 'is-today': dateKey(day) === dateKey(new Date()) }"
            @pointerdown="beginSelection($event, dayIndex)"
            @dragover.prevent
            @drop="dropItem($event, dayIndex)"
          >
            <span v-for="slot in 30" :key="slot" class="week-grid-line"></span>
            <div
              v-if="selection?.dayIndex === dayIndex"
              class="week-selection-block"
              :style="selectionStyle(dayIndex)"
            ></div>
            <article
              v-for="item in dayItems(day)"
              :key="item.id"
              class="week-event-block"
              :class="{ 'is-dragging': draggingId === item.id }"
              :style="itemStyle(item)"
              draggable="true"
              @dragstart="dragStart(item, $event)"
              @click.stop="openEditor(item)"
            >
              <strong>{{ item.title }}</strong>
              <span>{{ time(item.startTime) }} - {{ time(item.endTime) }}</span>
              <small v-if="item.location"><MapPin :size="11" />{{ item.location }}</small>
              <button
                class="week-resize-handle"
                type="button"
                title="拖动调整时长"
                @pointerdown="beginResize(item, $event)"
                @click.stop
              ><GripHorizontal :size="14" /></button>
            </article>
          </div>
        </div>
      </div>
    </div>

    <Teleport to="body">
      <Transition name="overlay">
        <div v-if="editorOpen" class="schedule-editor-layer" @click.self="editorOpen = false">
          <form class="schedule-editor-dialog" @submit.prevent="save">
            <header>
              <div><p class="eyebrow">Time Block</p><h3>{{ form.id ? "编辑安排" : "添加安排" }}</h3></div>
              <button type="button" title="关闭" @click="editorOpen = false"><X :size="19" /></button>
            </header>
            <label>
              <span>关联挑战</span>
              <select v-model="form.sourceId" :disabled="Boolean(form.id)" @change="selectChallenge">
                <option :value="null">自定义安排</option>
                <option v-for="challenge in challenges" :key="challenge.id" :value="challenge.id">
                  {{ challenge.title }}
                </option>
              </select>
            </label>
            <label><span>标题</span><input v-model="form.title" required placeholder="学习 Go 语言" /></label>
            <div class="schedule-editor-times">
              <label><span>开始</span><input v-model="form.startTime" type="datetime-local" required /></label>
              <label><span>结束</span><input v-model="form.endTime" type="datetime-local" required /></label>
            </div>
            <label><span>地点</span><input v-model="form.location" placeholder="自习室 / 线上" /></label>
            <label><span>备注</span><textarea v-model="form.notes" rows="4" placeholder="产出目标或提醒"></textarea></label>
            <footer>
              <button v-if="form.id" class="schedule-delete-button" type="button" :disabled="saving" @click="remove">
                <Trash2 :size="17" />删除
              </button>
              <button class="schedule-save-button" type="submit" :disabled="saving">
                <LoaderCircle v-if="saving" class="spin" :size="17" /><Save v-else :size="17" />
                {{ saving ? "保存中" : "保存安排" }}
              </button>
            </footer>
          </form>
        </div>
      </Transition>
    </Teleport>

    <Transition name="menu"><div v-if="notice" class="events-toast">{{ notice }}</div></Transition>
  </section>
</template>
