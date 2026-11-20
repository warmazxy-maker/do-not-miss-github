<script setup lang="ts">
import { CircleCheck, Circle, CircleSlash, Flag, RotateCcw, Trash2, X } from "@lucide/vue";
import { onMounted, ref } from "vue";

import { ApiError } from "@/api/client";
import { goalApi, type TandemGoal, type TandemGoals } from "@/api/tandem";

/**
 * F3: the student's goals, beside the conversation (F3 decision 9). Progress comes from the
 * platform (checked in, challenge completed), so there is nothing to tick here; the student can
 * still complete, abandon, reopen or trim a goal directly, without a card (decision 7).
 */
const goals = ref<TandemGoals | null>(null);
const open = ref<number | null>(null);
const error = ref("");

onMounted(reload);

async function reload() {
  try {
    goals.value = await goalApi.list();
    if (open.value === null && goals.value.active.length === 1) open.value = goals.value.active[0].id;
  } catch (cause) {
    error.value = text(cause, "目标读取失败");
  }
}

defineExpose({ reload });

async function run(action: () => Promise<unknown>, question?: string) {
  if (question && !window.confirm(question)) return;
  error.value = "";
  try {
    await action();
    await reload();
  } catch (cause) {
    error.value = text(cause, "操作失败");
  }
}

function percent(goal: TandemGoal) {
  return goal.total ? Math.round((goal.done / goal.total) * 100) : 0;
}

function text(cause: unknown, fallback: string) {
  return cause instanceof ApiError || cause instanceof Error ? cause.message : fallback;
}
</script>

<template>
  <section class="coach-review-summary goals">
    <header><Flag :size="19" /><strong>目标</strong><span v-if="goals">{{ goals.active.length }} / {{ goals.maxActive }}</span></header>
    <p v-if="error" class="module-error">{{ error }}</p>
    <p v-if="goals && !goals.active.length" class="hint">
      还没有进行中的目标。跟 Tandem 说说你想达成什么，它会帮你拆成一步步的活动和挑战。
    </p>
    <article v-for="goal in goals?.active ?? []" :key="goal.id" class="goal">
      <button type="button" class="goal-head" @click="open = open === goal.id ? null : goal.id">
        <span class="title">{{ goal.title }}</span>
        <small>{{ goal.typeLabel }}<template v-if="goal.targetDate"> · {{ goal.targetDate }} 前</template></small>
        <span class="bar"><span :style="{ width: `${percent(goal)}%` }"></span></span>
        <small>{{ goal.done }} / {{ goal.total }} 步</small>
      </button>
      <div v-if="open === goal.id" class="goal-body">
        <p v-if="goal.description" class="desc">{{ goal.description }}</p>
        <ol>
          <li v-for="step in goal.milestones" :key="step.id" :class="step.state.toLowerCase()">
            <CircleCheck v-if="step.done" :size="15" />
            <CircleSlash v-else-if="step.state === 'UNAVAILABLE' || step.state === 'MISSED'" :size="15" />
            <Circle v-else :size="15" />
            <div>
              <span>{{ step.kindLabel }}：{{ step.title }}</span>
              <small>{{ step.stateLabel }}<template v-if="step.when"> · {{ step.when }}</template></small>
              <small v-if="step.note" class="note">{{ step.note }}</small>
            </div>
            <button type="button" title="从目标里去掉这一步"
              @click="run(() => goalApi.removeMilestone(goal.id, step.id), `从目标里去掉「${step.title}」？活动或挑战本身不受影响。`)">
              <X :size="14" />
            </button>
          </li>
        </ol>
        <p v-if="goal.growth.length" class="growth">带来成长：<span v-for="name in goal.growth" :key="name">{{ name }}</span></p>
        <p v-if="goal.lastReviewNote" class="review">上次回顾：{{ goal.lastReviewNote }}</p>
        <footer>
          <button type="button" @click="run(() => goalApi.close(goal.id, 'COMPLETED'), `把「${goal.title}」标为完成？`)">完成</button>
          <button type="button" @click="run(() => goalApi.close(goal.id, 'ABANDONED'), `放弃「${goal.title}」？以后可以重新开始。`)">放弃</button>
        </footer>
      </div>
    </article>
    <details v-if="goals?.past.length" class="past">
      <summary>过去的目标 {{ goals.past.length }} 个</summary>
      <article v-for="goal in goals.past" :key="goal.id" class="past-goal">
        <div>
          <span>{{ goal.title }}</span>
          <small>{{ goal.statusLabel }} · {{ goal.done }} / {{ goal.total }} 步</small>
        </div>
        <button type="button" title="重新开始" @click="run(() => goalApi.reopen(goal.id))"><RotateCcw :size="14" /></button>
        <button type="button" title="删除"
          @click="run(() => goalApi.remove(goal.id), `删除目标「${goal.title}」？里面的活动和挑战不受影响。`)"><Trash2 :size="14" /></button>
      </article>
    </details>
  </section>
</template>

<style scoped>
.hint {
  font-size: 13px;
}

.goal {
  display: grid;
  gap: 6px;
  padding: 8px 0;
  border-top: 1px solid var(--line);
}

.goal-head {
  display: grid;
  gap: 3px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}

.goal-head .title {
  font-weight: 600;
}

.goal small,
.past small {
  color: var(--muted);
  font-size: 12px;
}

.bar {
  display: block;
  height: 6px;
  border-radius: 3px;
  background: var(--surface-soft);
  overflow: hidden;
}

.bar span {
  display: block;
  height: 100%;
  background: var(--teal-dark);
}

.goal-body {
  display: grid;
  gap: 6px;
  font-size: 13px;
}

.desc,
.growth,
.review {
  margin: 0;
}

ol {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

li {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 6px;
  align-items: start;
}

li > svg {
  margin-top: 2px;
  color: var(--muted);
}

li.done > svg {
  color: var(--teal-dark);
}

li div {
  display: grid;
  gap: 1px;
}

li .note {
  font-style: italic;
}

li button,
.past button {
  padding: 2px;
  border: 0;
  background: none;
  color: var(--muted);
  cursor: pointer;
}

li button:hover,
.past button:hover {
  color: #a33a28;
}

.growth span {
  display: inline-block;
  margin: 0 4px 2px 0;
  padding: 0 6px;
  border-radius: 999px;
  background: #e8f6f2;
  color: var(--teal-dark);
}

.review {
  color: var(--muted);
}

footer {
  display: flex;
  gap: 8px;
}

footer button {
  padding: 3px 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  color: var(--ink);
  cursor: pointer;
}

.past summary {
  cursor: pointer;
  color: var(--muted);
  font-size: 13px;
}

.past-goal {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 4px;
  align-items: center;
  padding: 6px 0;
  font-size: 13px;
}

.past-goal div {
  display: grid;
}
</style>
