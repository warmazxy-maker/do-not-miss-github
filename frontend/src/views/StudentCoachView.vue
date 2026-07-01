<script setup lang="ts">
import { BookOpen, Brain, LoaderCircle, MessageCircle, Send, Sparkles } from "@lucide/vue";
import { computed, nextTick, onMounted, ref } from "vue";

import { ApiError } from "@/api/client";
import { coachApi } from "@/api/workspace";
import type { CoachLog, CoachMemory, CoachMessage } from "@/types/workspace";

const messages = ref<CoachMessage[]>([]);
const logs = ref<CoachLog[]>([]);
const memories = ref<CoachMemory[]>([]);
const input = ref("");
const loading = ref(true);
const sending = ref(false);
const generating = ref(false);
const error = ref("");
const messageList = ref<HTMLElement | null>(null);
const selectedLogId = ref<number | null>(null);
const selectedLog = computed(() => logs.value.find((log) => log.id === selectedLogId.value) ?? logs.value[0] ?? null);
const dueMemories = computed(() => memories.value.filter((memory) => new Date(memory.nextReviewAt) <= new Date()));

onMounted(load);

async function load() {
  loading.value = true;
  error.value = "";
  try {
    [messages.value, logs.value, memories.value] = await Promise.all([
      coachApi.messages(),
      coachApi.logs(),
      coachApi.memories()
    ]);
    selectedLogId.value = logs.value[0]?.id ?? null;
    await scrollBottom();
  } catch (cause) {
    error.value = text(cause, "教练加载失败");
  } finally {
    loading.value = false;
  }
}

async function send() {
  const content = input.value.trim();
  if (!content || sending.value) return;
  messages.value.push({
    id: -Date.now(),
    role: "USER",
    content,
    messageDate: new Date().toISOString().slice(0, 10),
    createdAt: new Date().toISOString()
  });
  input.value = "";
  sending.value = true;
  await scrollBottom();
  try {
    const response = await coachApi.chat(content);
    messages.value.push(response.assistantMessage);
    if (response.generatedLog) {
      logs.value = [response.generatedLog, ...logs.value.filter((log) => log.id !== response.generatedLog?.id)];
      selectedLogId.value = response.generatedLog.id;
    }
    memories.value = await coachApi.memories();
  } catch (cause) {
    error.value = text(cause, "发送失败");
  } finally {
    sending.value = false;
    await scrollBottom();
  }
}

async function generateLog() {
  generating.value = true;
  try {
    const log = await coachApi.generateLog();
    logs.value = [log, ...logs.value.filter((item) => item.id !== log.id)];
    selectedLogId.value = log.id;
    memories.value = await coachApi.memories();
  } catch (cause) {
    error.value = text(cause, "日志生成失败");
  } finally {
    generating.value = false;
  }
}

async function scrollBottom() {
  await nextTick();
  messageList.value?.scrollTo({ top: messageList.value.scrollHeight, behavior: "smooth" });
}

function date(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { month: "short", day: "numeric" }).format(new Date(value));
}

function text(cause: unknown, fallback: string) {
  return cause instanceof ApiError || cause instanceof Error ? cause.message : fallback;
}
</script>

<template>
  <section class="coach-workspace-vue">
    <section class="coach-conversation-panel">
      <header>
        <div><span><MessageCircle :size="21" /></span><div><p class="eyebrow">Coach</p><h2>日志与教练</h2></div></div>
        <button type="button" :disabled="generating" @click="generateLog">
          <LoaderCircle v-if="generating" class="spin" :size="17" /><Sparkles v-else :size="17" />
          {{ generating ? "生成中" : "生成今日日志" }}
        </button>
      </header>
      <div v-if="loading" class="achievement-loading"><LoaderCircle class="spin" :size="25" />正在读取对话</div>
      <div v-else ref="messageList" class="coach-message-stream">
        <article v-if="!messages.length" class="coach-welcome">
          <Brain :size="30" /><h3>今天想聊点什么？</h3>
          <p>可以聊技术、复盘今天，也可以直接让我生成日志。</p>
        </article>
        <article v-for="message in messages" :key="message.id" :class="`is-${message.role.toLowerCase()}`">
          <span>{{ message.role === "USER" ? "我" : "教练" }}</span>
          <p>{{ message.content }}</p>
        </article>
        <article v-if="sending" class="is-assistant"><span>教练</span><p><LoaderCircle class="spin" :size="16" />正在思考…</p></article>
      </div>
      <form class="coach-composer-vue" @submit.prevent="send">
        <textarea v-model="input" rows="3" maxlength="2000" placeholder="今天做了什么、卡在哪里、学到了什么，都可以直接说。" @keydown.ctrl.enter.prevent="send"></textarea>
        <button type="submit" :disabled="sending || !input.trim()" title="发送"><Send :size="19" /></button>
      </form>
      <p v-if="error" class="module-error">{{ error }}</p>
    </section>

    <aside class="coach-memory-panel">
      <section class="coach-review-summary">
        <header><Brain :size="19" /><strong>长期记忆复习</strong><span>{{ dueMemories.length }} 条到期</span></header>
        <div v-if="dueMemories.length">
          <article v-for="memory in dueMemories.slice(0, 3)" :key="memory.id">
            <strong>{{ memory.title }}</strong><p>{{ memory.memoryText }}</p>
            <small>已复习 {{ memory.reviewCount }} 次 · 强度 {{ memory.strength }}/5</small>
          </article>
        </div>
        <p v-else>当前没有到期复习卡片。日志会按间隔重复策略再次出现。</p>
      </section>
      <section class="coach-log-browser">
        <header><BookOpen :size="19" /><strong>成长日志</strong><span>{{ logs.length }}</span></header>
        <div class="coach-log-tabs">
          <button v-for="log in logs" :key="log.id" type="button" :class="{ 'is-active': selectedLog?.id === log.id }" @click="selectedLogId = log.id">
            <span>{{ date(log.logDate) }}</span><strong>{{ log.title }}</strong>
          </button>
        </div>
        <article v-if="selectedLog" class="coach-log-detail">
          <p>{{ selectedLog.summary }}</p><div>{{ selectedLog.content }}</div>
          <span v-for="tag in selectedLog.tags" :key="tag">{{ tag }}</span>
        </article>
        <div v-else class="module-empty"><BookOpen :size="25" /><p>聊完后生成日志，它会进入长期记忆。</p></div>
      </section>
    </aside>
  </section>
</template>
