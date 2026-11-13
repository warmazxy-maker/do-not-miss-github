<script setup lang="ts">
import { Bike, Brain, Check, ChevronDown, CircleAlert, LoaderCircle, Send, Trash2, X } from "@lucide/vue";
import { computed, nextTick, onMounted, ref } from "vue";

import { ApiError } from "@/api/client";
import { tandemApi, type TandemMemory, type TandemMessage, type TandemProgress } from "@/api/tandem";

/**
 * F1: Tandem. One long conversation; while a turn runs, a step list grows as Tandem decides what
 * to look up (F1 decision 5). Memories are listed on the side, each deletable (F1 decision 2).
 */
interface LiveStep {
  key: string;
  kind: "thinking" | "tool";
  title: string;
  detail: string;
  status: TandemProgress["status"];
  summary: string;
}

const messages = ref<TandemMessage[]>([]);
const hasMore = ref(false);
const loading = ref(true);
const loadingOlder = ref(false);
const input = ref("");
const sending = ref(false);
const pendingQuestion = ref("");
const liveSteps = ref<LiveStep[]>([]);
const error = ref("");
const memories = ref<TandemMemory[]>([]);
const memoryLimit = ref(50);
const openSteps = ref<number | null>(null);
const messageList = ref<HTMLElement | null>(null);

const activeMemories = computed(() => memories.value.filter((memory) => !memory.expired));
const expiredMemories = computed(() => memories.value.filter((memory) => memory.expired));
const examples = ["我下周有什么安排？", "我现在哪方面最需要加强？", "帮我找点周末能参加、能练口语的活动", "上个月我都做了什么？"];

onMounted(async () => {
  try {
    const page = await tandemApi.messages();
    messages.value = page.messages;
    hasMore.value = page.hasMore;
    await loadMemories();
  } catch (cause) {
    error.value = text(cause, "对话读取失败");
  } finally {
    loading.value = false;
    await scrollBottom(false);
  }
});

async function loadOlder() {
  if (!messages.value.length) return;
  loadingOlder.value = true;
  try {
    const page = await tandemApi.messages(messages.value[0].id);
    messages.value = [...page.messages, ...messages.value];
    hasMore.value = page.hasMore;
  } catch (cause) {
    error.value = text(cause, "更早的对话读取失败");
  } finally {
    loadingOlder.value = false;
  }
}

async function loadMemories() {
  const result = await tandemApi.memories();
  memories.value = result.memories;
  memoryLimit.value = result.limit;
}

async function send(preset?: string) {
  const message = (preset ?? input.value).trim();
  if (!message || sending.value) return;
  sending.value = true;
  error.value = "";
  pendingQuestion.value = message;
  input.value = "";
  liveSteps.value = [];
  await scrollBottom();
  let replied = false;
  try {
    await tandemApi.chat(message, (name, data) => {
      if (name === "step") {
        applyProgress(data as TandemProgress);
        void scrollBottom();
      } else if (name === "reply") {
        replied = true;
        const reply = data as TandemMessage;
        messages.value.push(
          { id: reply.id - 0.5, role: "USER", content: message, steps: [], notices: [], degraded: false, createdAt: reply.createdAt },
          reply
        );
      } else if (name === "error") {
        error.value = (data as { message?: string }).message ?? "这次没能完成";
      }
    });
    if (!replied && !error.value) {
      error.value = "连接中断了，回答可能已经保存，刷新页面看看。";
    }
    if (replied) {
      // Real ids for the question, and any memory Tandem wrote this turn.
      const page = await tandemApi.messages();
      messages.value = page.messages;
      hasMore.value = page.hasMore;
      await loadMemories();
    }
  } catch (cause) {
    error.value = text(cause, "发送失败");
    if (!replied) input.value = message;
  } finally {
    sending.value = false;
    pendingQuestion.value = "";
    liveSteps.value = [];
    await scrollBottom();
  }
}

function applyProgress(progress: TandemProgress) {
  const steps = liveSteps.value;
  if (progress.kind === "thinking") {
    // One "thinking" row at the bottom, renamed as the turn moves on.
    const existing = steps.find((step) => step.key === "think");
    steps.forEach((step) => { if (step.kind === "tool" && step.status === "running") step.status = "ok"; });
    if (existing) {
      steps.splice(steps.indexOf(existing), 1);
    }
    steps.push({ key: "think", kind: "thinking", title: progress.title ?? "", detail: "", status: "running", summary: "" });
    return;
  }
  const existing = steps.find((step) => step.key === progress.key);
  if (existing) {
    existing.status = progress.status;
    existing.summary = progress.summary ?? "";
    return;
  }
  const think = steps.find((step) => step.key === "think");
  if (think) think.status = "ok";
  steps.push({
    key: progress.key, kind: "tool", title: progress.title ?? "", detail: progress.detail ?? "",
    status: progress.status, summary: progress.summary ?? ""
  });
}

async function clearConversation() {
  if (!window.confirm("清空对话后，Tandem 就不记得之前聊过什么了。它记住的关键事实（右侧列表）不受影响。确定清空吗？")) return;
  try {
    await tandemApi.clear();
    messages.value = [];
    hasMore.value = false;
  } catch (cause) {
    error.value = text(cause, "清空失败");
  }
}

async function removeMemory(memory: TandemMemory) {
  if (!window.confirm(`让 Tandem 忘掉“${memory.content}”？`)) return;
  try {
    await tandemApi.deleteMemory(memory.id);
    memories.value = memories.value.filter((item) => item.id !== memory.id);
  } catch (cause) {
    error.value = text(cause, "删除失败");
  }
}

async function scrollBottom(smooth = true) {
  await nextTick();
  messageList.value?.scrollTo({ top: messageList.value.scrollHeight, behavior: smooth ? "smooth" : "auto" });
}

/** Escapes HTML, then renders **bold** only; line breaks come from white-space: pre-wrap. */
function render(content: string) {
  const escaped = content
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  return escaped.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
}

function stepLabel(status: string) {
  return status === "OK" ? "" : status === "DENIED" ? "被拒绝" : "没成功";
}

function text(cause: unknown, fallback: string) {
  return cause instanceof ApiError || cause instanceof Error ? cause.message : fallback;
}
</script>

<template>
  <section class="coach-workspace-vue tandem">
    <section class="coach-conversation-panel">
      <header>
        <div><span><Bike :size="21" /></span><div><p class="eyebrow">Tandem</p><h2>和你一起往前骑</h2></div></div>
        <button type="button" :disabled="sending || !messages.length" @click="clearConversation"><Trash2 :size="17" />清空对话</button>
      </header>
      <div v-if="loading" class="achievement-loading"><LoaderCircle class="spin" :size="25" />正在读取对话</div>
      <div v-else ref="messageList" class="coach-message-stream">
        <button v-if="hasMore" type="button" class="tandem-older" :disabled="loadingOlder" @click="loadOlder">
          {{ loadingOlder ? "读取中…" : "看更早的对话" }}
        </button>
        <article v-if="!messages.length && !sending" class="coach-welcome">
          <Bike :size="30" /><h3>我是 Tandem</h3>
          <p>我能看到你的预约、日程、挑战、成长记录和能力档案，也会记住你告诉我的关键事情。想聊什么都可以，比如：</p>
          <div class="tandem-examples">
            <button v-for="example in examples" :key="example" type="button" @click="send(example)">{{ example }}</button>
          </div>
        </article>

        <article v-for="(message, index) in messages" :key="message.id" :class="`is-${message.role.toLowerCase()}`">
          <span>{{ message.role === "USER" ? "我" : "Tandem" }}</span>
          <div class="tandem-body">
          <div v-if="message.notices.length" class="tandem-notices">
            <p v-for="(notice, n) in message.notices" :key="n"><CircleAlert :size="15" />{{ notice }}</p>
          </div>
          <p class="tandem-text" v-html="render(message.content)"></p>
          <div v-if="message.steps.length" class="tandem-used">
            <button type="button" @click="openSteps = openSteps === index ? null : index">
              查了 {{ message.steps.length }} 项：{{ message.steps.map((step) => step.title).join("、") }}
              <ChevronDown :size="14" :class="{ flipped: openSteps === index }" />
            </button>
            <ul v-if="openSteps === index">
              <li v-for="(step, s) in message.steps" :key="s" :class="{ failed: step.status !== 'OK' }">
                <strong>{{ step.title }}</strong><span v-if="step.detail">{{ step.detail }}</span>
                <em>{{ stepLabel(step.status) }} {{ step.summary }}</em>
              </li>
            </ul>
          </div>
          </div>
        </article>

        <template v-if="sending">
          <article class="is-user"><span>我</span><div class="tandem-body"><p class="tandem-text">{{ pendingQuestion }}</p></div></article>
          <article class="is-assistant">
            <span>Tandem</span>
            <div class="tandem-body">
            <ol class="tandem-live">
              <li v-if="!liveSteps.length" class="running"><LoaderCircle class="spin" :size="15" />理解你的问题</li>
              <li v-for="step in liveSteps" :key="step.key" :class="step.status">
                <LoaderCircle v-if="step.status === 'running'" class="spin" :size="15" />
                <Check v-else-if="step.status === 'ok'" :size="15" />
                <X v-else :size="15" />
                <strong>{{ step.title }}</strong><span v-if="step.detail">{{ step.detail }}</span>
                <em v-if="step.summary">{{ step.summary }}</em>
              </li>
            </ol>
            </div>
          </article>
        </template>
      </div>
      <form class="coach-composer-vue" @submit.prevent="send()">
        <textarea v-model="input" rows="3" maxlength="2000" placeholder="想找什么、卡在哪、最近怎么样，都可以直接说。Ctrl + Enter 发送" @keydown.ctrl.enter.prevent="send()"></textarea>
        <button type="submit" :disabled="sending || !input.trim()" title="发送"><Send :size="19" /></button>
      </form>
      <p v-if="error" class="module-error">{{ error }}</p>
    </section>

    <aside class="coach-memory-panel">
      <section class="coach-review-summary">
        <header><Brain :size="19" /><strong>Tandem 记住的事</strong><span>{{ activeMemories.length }} / {{ memoryLimit }}</span></header>
        <p class="tandem-memory-hint">只记你亲口说过的关键事实。活动、预约、成绩这些它会随时去查，不会记在这里。不对的可以删掉。</p>
        <div v-if="activeMemories.length">
          <article v-for="memory in activeMemories" :key="memory.id" class="tandem-memory">
            <div>
              <small>{{ memory.categoryLabel }}<template v-if="memory.validUntil"> · 到 {{ memory.validUntil }}</template></small>
              <p>{{ memory.content }}</p>
              <small v-if="memory.quote" class="quote">“{{ memory.quote }}”</small>
            </div>
            <button type="button" title="忘掉这条" @click="removeMemory(memory)"><Trash2 :size="15" /></button>
          </article>
        </div>
        <p v-else>还没有。聊天时说到的关键事，Tandem 会自己记下来。</p>
        <details v-if="expiredMemories.length" class="tandem-expired">
          <summary>已过期 {{ expiredMemories.length }} 条（不再使用）</summary>
          <article v-for="memory in expiredMemories" :key="memory.id" class="tandem-memory">
            <div><small>{{ memory.categoryLabel }} · 到 {{ memory.validUntil }}</small><p>{{ memory.content }}</p></div>
            <button type="button" title="删除" @click="removeMemory(memory)"><Trash2 :size="15" /></button>
          </article>
        </details>
      </section>
    </aside>
  </section>
</template>

<style scoped>
/* The conversation is the main thing here; the memory list gets a narrow column. */
.tandem.coach-workspace-vue {
  grid-template-columns: minmax(0, 1fr) minmax(260px, 300px);
}

/* With the sidebar, two columns get cramped well before the shared 920px breakpoint. */
@media (max-width: 1180px) {
  .tandem.coach-workspace-vue {
    grid-template-columns: 1fr;
  }
}

.tandem .coach-message-stream > article:not(.coach-welcome) {
  max-width: 92%;
}

/* One column per message: notices, the bubble and the "looked up" chip stack instead of sitting side by side. */
.tandem-body {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.tandem-text {
  margin: 0;
  padding: 11px 12px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: #fff;
  white-space: pre-wrap;
  line-height: 1.7;
}

.is-user .tandem-text {
  border-color: #b6d8d1;
  background: #e8f6f2;
}

.tandem-live {
  padding: 10px 12px !important;
  border: 1px dashed var(--line);
  border-radius: 7px;
  background: #fff;
}

.coach-conversation-panel > header button {
  white-space: nowrap;
}

.tandem-older {
  align-self: center;
  border: 0;
  background: none;
  color: var(--teal-dark);
  cursor: pointer;
}

.tandem-examples {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  margin-top: 10px;
}

.tandem-examples button {
  padding: 6px 12px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
  color: var(--ink);
  cursor: pointer;
}

.tandem-notices p {
  display: flex;
  gap: 6px;
  align-items: center;
  margin: 0 0 6px;
  padding: 6px 10px;
  border-radius: 8px;
  background: #fbf1dc;
  color: #8a5f0b;
  font-size: 13px;
}

.tandem-used {
  margin-top: 6px;
  font-size: 13px;
}

.tandem-used > button {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 2px 8px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface-soft);
  color: var(--muted);
  cursor: pointer;
}

.tandem-used .flipped {
  transform: rotate(180deg);
}

.tandem-used ul,
.tandem-live {
  display: grid;
  gap: 4px;
  margin: 6px 0 0;
  padding: 0;
  list-style: none;
  font-size: 13px;
}

.tandem-used li,
.tandem-live li {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  color: var(--muted);
}

.tandem-used li strong,
.tandem-live li strong {
  color: var(--ink);
}

.tandem-used li.failed em,
.tandem-live li.error,
.tandem-live li.denied {
  color: #a33a28;
}

.tandem-live li.ok svg {
  color: var(--teal-dark);
}

.tandem-live em,
.tandem-used em {
  font-style: normal;
}

.tandem-memory-hint {
  font-size: 13px;
}

.tandem-memory {
  display: flex;
  gap: 8px;
  justify-content: space-between;
  align-items: flex-start;
}

.tandem-memory p {
  margin: 2px 0;
}

.tandem-memory .quote {
  color: var(--muted);
}

.tandem-memory button {
  flex: none;
  padding: 4px;
  border: 0;
  background: none;
  color: var(--muted);
  cursor: pointer;
}

.tandem-memory button:hover {
  color: #a33a28;
}

.tandem-expired summary {
  cursor: pointer;
  color: var(--muted);
  font-size: 13px;
}
</style>
