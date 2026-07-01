<script setup lang="ts">
import {
  Banknote,
  Building2,
  CalendarDays,
  Check,
  Clock3,
  Heart,
  LoaderCircle,
  MapPin,
  TicketCheck
} from "@lucide/vue";
import { computed } from "vue";

import type { EventItem } from "@/types/events";

const props = withDefaults(
  defineProps<{
    event: EventItem;
    followed?: boolean;
    reserved?: boolean;
    following?: boolean;
    reserving?: boolean;
    compact?: boolean;
    score?: number | null;
  }>(),
  {
    followed: false,
    reserved: false,
    following: false,
    reserving: false,
    compact: false,
    score: null
  }
);

defineEmits<{
  follow: [event: EventItem];
  reserve: [event: EventItem];
}>();

const startDate = computed(() => new Date(props.event.startTime));
const endDate = computed(() => new Date(props.event.endTime));

const dateLabel = computed(() => {
  const dayFormatter = new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    weekday: "short"
  });
  const startDay = dayFormatter.format(startDate.value);
  const endDay = dayFormatter.format(endDate.value);
  return startDay === endDay
    ? `${startDay} ${formatTime(startDate.value)} - ${formatTime(endDate.value)}`
    : `${startDay} ${formatTime(startDate.value)} - ${endDay} ${formatTime(endDate.value)}`;
});

const skillList = computed(() =>
  (props.event.skill ?? "")
    .split(/[,，、/]/)
    .map((item) => item.trim())
    .filter(Boolean)
    .slice(0, props.compact ? 2 : 4)
);

const moneyLabel = computed(() => {
  if (props.event.moneyAmount === null || props.event.moneyAmount === undefined) {
    return "";
  }
  return new Intl.NumberFormat("zh-CN", {
    style: "currency",
    currency: "CNY",
    maximumFractionDigits: 0
  }).format(props.event.moneyAmount);
});

function formatTime(date: Date) {
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false
  }).format(date);
}
</script>

<template>
  <article class="event-result-card" :class="{ 'is-compact': compact }">
    <div class="event-result-main">
      <div class="event-result-date">
        <CalendarDays :size="16" />
        <span>{{ dateLabel }}</span>
      </div>

      <div class="event-result-heading">
        <div>
          <h3>{{ event.title }}</h3>
          <p>
            <Building2 :size="15" />
            <span>{{ event.organizationName }}</span>
          </p>
        </div>
        <span v-if="score !== null" class="event-match-score">{{ score }}</span>
      </div>

      <div class="event-result-meta">
        <span><MapPin :size="15" />{{ event.location }}</span>
        <span><Clock3 :size="15" />{{ event.category }}</span>
        <span v-if="moneyLabel"><Banknote :size="15" />{{ moneyLabel }}</span>
      </div>

      <p v-if="!compact" class="event-result-content">{{ event.content }}</p>

      <div class="event-result-tags">
        <span class="event-category-tag">{{ event.category }}</span>
        <span v-for="skill in skillList" :key="skill" class="event-skill-tag">{{ skill }}</span>
        <span v-if="moneyLabel" class="event-money-tag">{{ moneyLabel }}</span>
      </div>
    </div>

    <div class="event-result-actions">
      <button
        class="event-action-button"
        type="button"
        :disabled="following"
        :class="{ 'is-active': followed }"
        @click="$emit('follow', event)"
      >
        <LoaderCircle v-if="following" class="spin" :size="17" />
        <Heart v-else :size="17" :fill="followed ? 'currentColor' : 'none'" />
        <span>{{ followed ? "已关注" : "关注" }}</span>
      </button>
      <button
        class="event-action-button event-reserve-button"
        type="button"
        :disabled="reserved || reserving"
        :class="{ 'is-active': reserved }"
        @click="$emit('reserve', event)"
      >
        <LoaderCircle v-if="reserving" class="spin" :size="17" />
        <Check v-else-if="reserved" :size="17" />
        <TicketCheck v-else :size="17" />
        <span>{{ reserved ? "已预约" : "预约" }}</span>
      </button>
    </div>
  </article>
</template>
