<script setup lang="ts">
import { Bug, Send, X } from "@lucide/vue";
import { computed, nextTick, ref, watch } from "vue";
import { useRoute } from "vue-router";

import { agentApi, type AgentRunSummary, type BadCaseIssueType, type BadCaseSeverity } from "@/api/agent";
import { useAuthStore } from "@/stores/auth";

const route = useRoute();
const authStore = useAuthStore();

const open = ref(false);
const loadingRuns = ref(false);
const submitting = ref(false);
const runs = ref<AgentRunSummary[]>([]);
const selectedRunId = ref<number | null>(null);
const issueType = ref<BadCaseIssueType>("UNKNOWN");
const severity = ref<BadCaseSeverity>("MEDIUM");
const userMessage = ref("");
const expectedBehavior = ref("");
const actualBehavior = ref("");
const errorMessage = ref("");
const submittedCaseKey = ref("");
const submittedRootCauseSummary = ref("");
const messageInput = ref<HTMLTextAreaElement | null>(null);

const visible = computed(() => authStore.isAuthenticated && Boolean(route.meta.requiresAuth));
const canSubmit = computed(() => userMessage.value.trim().length >= 6 && !submitting.value);

const issueOptions: Array<{ value: BadCaseIssueType; label: string }> = [
  { value: "UNKNOWN", label: "先不确定" },
  { value: "RETRIEVAL_MISS", label: "该出现的没出现" },
  { value: "LOW_RELEVANCE", label: "结果相关性低" },
  { value: "QUERY_REWRITE_ERROR", label: "需求理解错了" },
  { value: "HALLUCINATION", label: "AI 乱编内容" },
  { value: "WRONG_CONTEXT", label: "上下文串了" },
  { value: "PLAN_UNEXECUTABLE", label: "计划不可执行" },
  { value: "COACH_OFF_TOPIC", label: "教练跑题" },
  { value: "SCORING_UNFAIR", label: "能力评分不公平" },
  { value: "TOOL_ERROR", label: "工具或接口异常" },
  { value: "UI_CONFUSION", label: "页面体验困惑" },
  { value: "LATENCY_TIMEOUT", label: "响应太慢或超时" }
];

const severityOptions: Array<{ value: BadCaseSeverity; label: string }> = [
  { value: "LOW", label: "轻微" },
  { value: "MEDIUM", label: "普通" },
  { value: "HIGH", label: "严重" },
  { value: "CRITICAL", label: "阻断" }
];

watch(open, async (nextOpen) => {
  if (!nextOpen) {
    return;
  }
  submittedCaseKey.value = "";
  submittedRootCauseSummary.value = "";
  errorMessage.value = "";
  await Promise.all([loadRuns(), nextTick()]);
  messageInput.value?.focus();
});

watch(
  () => route.fullPath,
  () => {
    if (open.value) {
      open.value = false;
    }
  }
);

async function loadRuns() {
  loadingRuns.value = true;
  try {
    runs.value = await agentApi.recentRuns();
    selectedRunId.value = runs.value[0]?.id ?? null;
  } catch {
    runs.value = [];
    selectedRunId.value = null;
  } finally {
    loadingRuns.value = false;
  }
}

function runLabel(run: AgentRunSummary) {
  const time = new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  }).format(new Date(run.startedAt));
  const goal = run.goal || run.inputSummary || run.runType;
  return `${time} · ${run.runType} · ${goal.slice(0, 28)}`;
}

function resetForm() {
  selectedRunId.value = runs.value[0]?.id ?? null;
  issueType.value = "UNKNOWN";
  severity.value = "MEDIUM";
  userMessage.value = "";
  expectedBehavior.value = "";
  actualBehavior.value = "";
  errorMessage.value = "";
  submittedCaseKey.value = "";
  submittedRootCauseSummary.value = "";
}

async function submit() {
  if (!canSubmit.value) {
    return;
  }
  submitting.value = true;
  errorMessage.value = "";
  submittedCaseKey.value = "";
  submittedRootCauseSummary.value = "";
  try {
    const created = await agentApi.createBadCase({
      agentRunId: selectedRunId.value,
      sourceType: "USER_FEEDBACK",
      issueType: issueType.value,
      severity: severity.value,
      pageUrl: window.location.pathname + window.location.search + window.location.hash,
      moduleKey: String(route.name ?? route.path),
      userMessage: userMessage.value.trim(),
      expectedBehavior: expectedBehavior.value.trim(),
      actualBehavior: actualBehavior.value.trim()
    });
    submittedCaseKey.value = created.caseKey;
    submittedRootCauseSummary.value = created.rootCauseSummary ?? "";
    userMessage.value = "";
    expectedBehavior.value = "";
    actualBehavior.value = "";
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "反馈提交失败";
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <Teleport to="body">
    <button v-if="visible && !open" class="badcase-fab" type="button" title="反馈本次体验" @click="open = true">
      <Bug :size="19" />
      <span>反馈</span>
    </button>

    <Transition name="badcase-panel">
      <div v-if="visible && open" class="badcase-layer" @click.self="open = false">
        <section class="badcase-dialog" role="dialog" aria-modal="true" aria-label="反馈本次体验">
          <header>
            <div>
              <p class="eyebrow">BAD CASE</p>
              <h2>反馈本次体验</h2>
            </div>
            <button type="button" title="关闭" @click="open = false">
              <X :size="20" />
            </button>
          </header>

          <div v-if="submittedCaseKey" class="badcase-success">
            已记录，编号 {{ submittedCaseKey }}。后续可以把它接入 Intake Agent 做自动归因。
          </div>

          <div v-if="submittedRootCauseSummary" class="badcase-intake-result">
            <strong>Intake Agent</strong>
            <span>{{ submittedRootCauseSummary }}</span>
          </div>

          <label>
            <span>关联的 Agent Run</span>
            <select v-model.number="selectedRunId" :disabled="loadingRuns">
              <option :value="null">不关联具体运行</option>
              <option v-for="run in runs" :key="run.id" :value="run.id">
                {{ runLabel(run) }}
              </option>
            </select>
          </label>

          <div class="badcase-grid">
            <label>
              <span>问题类型</span>
              <select v-model="issueType">
                <option v-for="option in issueOptions" :key="option.value" :value="option.value">
                  {{ option.label }}
                </option>
              </select>
            </label>

            <label>
              <span>严重程度</span>
              <select v-model="severity">
                <option v-for="option in severityOptions" :key="option.value" :value="option.value">
                  {{ option.label }}
                </option>
              </select>
            </label>
          </div>

          <label>
            <span>发生了什么问题</span>
            <textarea
              ref="messageInput"
              v-model="userMessage"
              rows="4"
              placeholder="比如：我明明想学 Java，但推荐结果没有召回 Java 项目设计大赛。"
            />
          </label>

          <label>
            <span>你原本期待它怎么做</span>
            <textarea v-model="expectedBehavior" rows="2" placeholder="可选，比如：应该优先返回 Java 相关活动。" />
          </label>

          <label>
            <span>实际看到的结果</span>
            <textarea v-model="actualBehavior" rows="2" placeholder="可选，比如：只返回了泛编程活动。" />
          </label>

          <p v-if="errorMessage" class="badcase-error">{{ errorMessage }}</p>

          <footer>
            <button class="secondary-button" type="button" @click="resetForm">清空</button>
            <button class="primary-button" type="button" :disabled="!canSubmit" @click="submit">
              <Send :size="17" />
              <span>{{ submitting ? "提交中" : "提交反馈" }}</span>
            </button>
          </footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>
