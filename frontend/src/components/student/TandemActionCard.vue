<script setup lang="ts">
import { CircleAlert, ClipboardCheck, LoaderCircle, RotateCcw } from "@lucide/vue";
import { computed, ref } from "vue";
import { RouterLink } from "vue-router";

import { ApiError } from "@/api/client";
import { agentActionApi, type AgentAction } from "@/api/tandem";

/**
 * F2: a confirmation card under a Tandem answer. What it shows was built by the server from the
 * database; confirming runs exactly that. A done card can be rolled back while its result is
 * still as the action left it (F2 decision 7).
 */
const props = defineProps<{ action: AgentAction }>();
const emit = defineEmits<{ updated: [action: AgentAction] }>();

const busy = ref<"" | "confirm" | "cancel" | "undo">("");
const note = ref("");
const noteOk = ref(true);

const expires = computed(() => {
  const date = new Date(props.action.expiresAt);
  return `${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
});

const linkLabel = computed(() => {
  const link = props.action.card.link ?? "";
  if (link.includes("schedule")) return "去日程页看看";
  if (link.includes("challenges")) return "去挑战页看看";
  if (link.includes("reservations")) return "去预约页看看";
  return "去看看";
});

async function run(kind: "confirm" | "cancel" | "undo") {
  if (busy.value) return;
  if (kind === "undo" && !window.confirm(`撤销「${props.action.card.headline}」？撤销会把这次的改动恢复原样。`)) return;
  busy.value = kind;
  note.value = "";
  try {
    const outcome = await agentActionApi[kind](props.action.id);
    // On success the card's own result line already says what happened; a cancel has none.
    note.value = !outcome.ok || kind === "cancel" ? outcome.message : "";
    noteOk.value = outcome.ok;
    emit("updated", outcome.action);
  } catch (cause) {
    note.value = cause instanceof ApiError || cause instanceof Error ? cause.message : "操作失败，请稍后再试";
    noteOk.value = false;
  } finally {
    busy.value = "";
  }
}
</script>

<template>
  <section class="action-card" :class="`is-${action.status.toLowerCase()}`">
    <header>
      <span class="kind"><ClipboardCheck :size="14" />{{ action.card.kind }}</span>
      <span class="state">{{ action.statusLabel }}</span>
    </header>
    <h4>{{ action.card.headline }}</h4>
    <dl v-if="action.card.fields.length">
      <template v-for="field in action.card.fields" :key="field.label">
        <dt>{{ field.label }}</dt>
        <dd>{{ field.value }}</dd>
      </template>
    </dl>
    <ul v-if="action.card.warnings.length" class="warnings">
      <li v-for="warning in action.card.warnings" :key="warning"><CircleAlert :size="14" />{{ warning }}</li>
    </ul>
    <ul v-if="action.card.effects.length && action.canConfirm" class="effects">
      <li v-for="effect in action.card.effects" :key="effect">{{ effect }}</li>
    </ul>
    <p v-if="action.message && !action.canConfirm" class="result">{{ action.message }}</p>
    <p v-if="note" class="note" :class="{ bad: !noteOk }">{{ note }}</p>
    <footer>
      <template v-if="action.canConfirm">
        <button type="button" class="primary" :disabled="!!busy" @click="run('confirm')">
          <LoaderCircle v-if="busy === 'confirm'" class="spin" :size="14" />确认
        </button>
        <button type="button" :disabled="!!busy" @click="run('cancel')">取消</button>
        <small>{{ expires }} 前有效</small>
      </template>
      <template v-else-if="action.canUndo">
        <button type="button" :disabled="!!busy" @click="run('undo')">
          <LoaderCircle v-if="busy === 'undo'" class="spin" :size="14" /><RotateCcw v-else :size="14" />撤销
        </button>
      </template>
      <RouterLink v-if="action.card.link && (action.status === 'DONE' || action.status === 'UNDONE')" :to="action.card.link">
        {{ linkLabel }}
      </RouterLink>
      <small v-if="action.status === 'EXPIRED'">已过期，可以让 Tandem 重新准备一张</small>
    </footer>
  </section>
</template>

<style scoped>
.action-card {
  display: grid;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid #b6d8d1;
  border-radius: 7px;
  background: #f7fbfa;
  font-size: 13px;
}

.action-card.is-pending {
  border-color: var(--teal-dark);
  box-shadow: 0 0 0 1px rgba(15, 118, 110, 0.08);
}

.action-card.is-cancelled,
.action-card.is-expired,
.action-card.is-superseded,
.action-card.is-undone {
  border-color: var(--line);
  background: var(--surface-soft);
  color: var(--muted);
}

.action-card.is-failed {
  border-color: #e3b5ab;
  background: #fdf4f2;
}

header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.kind {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--teal-dark);
  font-weight: 600;
}

.state {
  color: var(--muted);
}

.is-done .state {
  color: var(--teal-dark);
}

.is-failed .state {
  color: #a33a28;
}

h4 {
  margin: 0;
  font-size: 15px;
  color: var(--ink);
}

.is-cancelled h4,
.is-expired h4,
.is-superseded h4,
.is-undone h4 {
  color: var(--muted);
  text-decoration: line-through;
}

dl {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 2px 10px;
  margin: 0;
}

dt {
  color: var(--muted);
}

dd {
  margin: 0;
  overflow-wrap: anywhere;
}

ul {
  display: grid;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.warnings li {
  display: flex;
  gap: 4px;
  align-items: center;
  color: #8a5f0b;
}

.effects li {
  color: var(--muted);
}

.effects li::before {
  content: "· ";
}

.result,
.note {
  margin: 0;
}

.note.bad,
.is-failed .result {
  color: #a33a28;
}

footer {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

footer button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  color: var(--ink);
  cursor: pointer;
}

footer button.primary {
  border-color: var(--teal-dark);
  background: var(--teal-dark);
  color: #fff;
}

footer button:disabled {
  opacity: 0.6;
  cursor: default;
}

footer small {
  color: var(--muted);
}

footer a {
  color: var(--teal-dark);
}
</style>
