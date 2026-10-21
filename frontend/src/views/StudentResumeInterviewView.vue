<script setup lang="ts">
import {
  AlertCircle,
  ArrowRight,
  BadgeCheck,
  Bot,
  Check,
  CheckCircle2,
  ClipboardList,
  FileText,
  LoaderCircle,
  RefreshCw,
  Send,
  ShieldCheck,
  Sparkles,
  Target
} from "@lucide/vue";
import { computed, onMounted, reactive, ref } from "vue";
import { RouterLink } from "vue-router";

import { ApiError } from "@/api/client";
import { resumeInterviewsApi } from "@/api/resumeInterviews";
import type {
  ParsedResume,
  ResumeAssessmentDetailResponse,
  ResumeAssessmentSessionView,
  ResumeInterviewSessionStatus,
  ResumeSection,
  UnifiedAbilityEvidence
} from "@/types/resumeInterview";

type BusyAction = "load" | "create" | "parse" | "questions" | `answer-${number}` | "score" | "";

interface ParsedSectionGroup {
  key: keyof Pick<
    ParsedResume,
    "education" | "projects" | "internships" | "contests" | "certificates"
  >;
  label: string;
  items: ResumeSection[];
}

interface EvidenceSummary {
  id: number;
  status: string;
  traceRunId: number | null;
  dimensions: string[];
  confidence: number;
  difficulty: number;
  quality: number;
  contribution: number;
  riskFlags: string[];
  judgeSuggested: boolean;
}

const sessions = ref<ResumeAssessmentSessionView[]>([]);
const currentDetail = ref<ResumeAssessmentDetailResponse | null>(null);
const selectedSessionId = ref<number | null>(null);
const targetRole = ref("Agent 后端实习");
const resumeText = ref("");
const answerDrafts = reactive<Record<number, string>>({});
const busy = ref<BusyAction>("");
const notice = ref("");
const errorText = ref("");
let noticeTimer: ReturnType<typeof setTimeout> | undefined;

const currentSession = computed(() => currentDetail.value?.session ?? null);
const activeSnapshot = computed(() => currentDetail.value?.activeResumeSnapshot ?? null);
const parsedResume = computed(() =>
  parseJson<ParsedResume>(activeSnapshot.value?.parsedResumeJson, {})
);
const parsedSectionGroups = computed<ParsedSectionGroup[]>(() =>
  [
    ["education", "教育经历"],
    ["projects", "项目经历"],
    ["internships", "实习经历"],
    ["contests", "比赛经历"],
    ["certificates", "证书与考试"]
  ]
    .map(([key, label]) => ({
      key: key as ParsedSectionGroup["key"],
      label,
      items: parsedResume.value[key as keyof ParsedResume] as ResumeSection[] | undefined
    }))
    .filter((group): group is ParsedSectionGroup => Boolean(group.items?.length))
);
const evidenceSummaries = computed<EvidenceSummary[]>(() =>
  (currentDetail.value?.evidenceCandidates ?? []).map((candidate) => {
    const evidence = parseJson<UnifiedAbilityEvidence>(candidate.abilityEvidenceJson, {});
    const assessment = evidence.assessment ?? {};
    return {
      id: candidate.id,
      status: candidate.status,
      traceRunId: candidate.agentTraceRunId,
      dimensions:
        assessment.dimensions
          ?.map((item) => item.dimension || item.normalizedDimension || "")
          .filter(Boolean)
          .slice(0, 4) ?? [],
      confidence: numberValue(assessment.assessmentConfidence),
      difficulty: numberValue(assessment.activityDifficulty, 0),
      quality: numberValue(assessment.completionQuality),
      contribution: numberValue(assessment.personalContribution),
      riskFlags: assessment.riskFlags ?? [],
      judgeSuggested: Boolean(assessment.judgeRecommendation?.suggestReview)
    };
  })
);
const scoreResultIds = computed(() =>
  parseJson<number[]>(currentDetail.value?.scoreSummary?.abilityScoreResultIdsJson, [])
);
const judgeTriggered = computed(() =>
  parseJson<unknown[]>(currentDetail.value?.scoreSummary?.judgeTriggeredJson, [])
);
const stageItems = computed(() => [
  {
    label: "提交简历",
    done: Boolean(currentDetail.value),
    trace: currentSession.value ? `session #${currentSession.value.id}` : ""
  },
  {
    label: "Parser 抽事实",
    done: Boolean(activeSnapshot.value?.parserTraceRunId),
    trace: activeSnapshot.value?.parserTraceRunId
      ? `run #${activeSnapshot.value.parserTraceRunId}`
      : ""
  },
  {
    label: "Interviewer 追问",
    done: Boolean(currentDetail.value?.questions.length),
    trace: uniqueTraceIds(currentDetail.value?.questions.map((item) => item.agentTraceRunId)).join(", ")
  },
  {
    label: "Evaluator 抽证据",
    done: Boolean(currentDetail.value?.evidenceCandidates.length),
    trace: uniqueTraceIds(
      currentDetail.value?.evidenceCandidates.map((item) => item.agentTraceRunId)
    ).join(", ")
  },
  {
    label: "评分引擎定段",
    done: currentDetail.value?.scoreSummary?.status === "SCORED",
    trace: currentDetail.value?.scoreSummary?.scoringTraceRunId
      ? `run #${currentDetail.value.scoreSummary.scoringTraceRunId}`
      : ""
  }
]);
const canParse = computed(() => currentSession.value?.status === "CREATED");
const canGenerateQuestions = computed(() => currentSession.value?.status === "RESUME_PARSED");
const canScore = computed(() => currentSession.value?.status === "EVIDENCE_READY");
const scored = computed(() => currentSession.value?.status === "SCORED" || currentSession.value?.status === "COMPLETED");

onMounted(async () => {
  await loadSessions();
});

async function loadSessions(selectFirst = true) {
  busy.value = busy.value || "load";
  errorText.value = "";
  try {
    sessions.value = await resumeInterviewsApi.list();
    if (selectFirst && !currentDetail.value && sessions.value[0]) {
      await selectSession(sessions.value[0].id);
    }
  } catch (error) {
    errorText.value = errorMessage(error, "简历评估列表加载失败");
  } finally {
    if (busy.value === "load") busy.value = "";
  }
}

async function selectSession(sessionId: number) {
  busy.value = "load";
  errorText.value = "";
  try {
    setDetail(await resumeInterviewsApi.detail(sessionId));
  } catch (error) {
    errorText.value = errorMessage(error, "评估详情加载失败");
  } finally {
    busy.value = "";
  }
}

async function submitResume() {
  const text = resumeText.value.trim();
  if (!text || busy.value) return;
  busy.value = "create";
  errorText.value = "";
  try {
    const detail = await resumeInterviewsApi.submitResumeText(targetRole.value.trim(), text);
    setDetail(detail);
    resumeText.value = "";
    await loadSessions(false);
    showNotice("简历已保存，可以开始 Parser 抽取");
  } catch (error) {
    errorText.value = errorMessage(error, "简历提交失败");
  } finally {
    busy.value = "";
  }
}

async function runParse() {
  await runSessionAction("parse", () =>
    resumeInterviewsApi.parse(requiredSessionId())
  );
}

async function runQuestionGeneration() {
  await runSessionAction("questions", () =>
    resumeInterviewsApi.generateQuestions(requiredSessionId())
  );
}

async function submitAnswer(questionId: number) {
  const answer = answerDrafts[questionId]?.trim();
  if (!answer) {
    showNotice("先写一点回答，再交给 Evaluator");
    return;
  }
  await runSessionAction(`answer-${questionId}`, () =>
    resumeInterviewsApi.answerQuestion(requiredSessionId(), questionId, answer)
  );
}

async function runScore() {
  await runSessionAction("score", () =>
    resumeInterviewsApi.score(requiredSessionId())
  );
}

async function runSessionAction(
  action: Exclude<BusyAction, "load" | "create" | "">,
  request: () => Promise<ResumeAssessmentDetailResponse>
) {
  if (busy.value) return;
  busy.value = action;
  errorText.value = "";
  try {
    setDetail(await request());
    await loadSessions(false);
    showNotice(actionNotice(action));
  } catch (error) {
    errorText.value = errorMessage(error, "操作失败");
  } finally {
    busy.value = "";
  }
}

function setDetail(detail: ResumeAssessmentDetailResponse) {
  currentDetail.value = detail;
  selectedSessionId.value = detail.session.id;
  detail.questions.forEach((question) => {
    if (!(question.id in answerDrafts)) {
      answerDrafts[question.id] =
        detail.answers.find((answer) => answer.questionId === question.id)?.answerPreview ?? "";
    }
  });
}

function requiredSessionId() {
  if (!currentSession.value) {
    throw new Error("请先创建或选择一次简历评估");
  }
  return currentSession.value.id;
}

function hasAnswer(questionId: number) {
  return currentDetail.value?.answers.some((answer) => answer.questionId === questionId) ?? false;
}

function questionBusy(questionId: number) {
  return busy.value === `answer-${questionId}`;
}

function statusLabel(status: ResumeInterviewSessionStatus | string | undefined) {
  return (
    {
      CREATED: "待解析",
      RESUME_PARSED: "已解析",
      INTERVIEWING: "面试中",
      EVIDENCE_READY: "证据就绪",
      SCORED: "已定段",
      COMPLETED: "已完成",
      ASKED: "待回答",
      ANSWERED: "已回答",
      EVALUATED: "已评估",
      SKIPPED: "已跳过",
      DRAFT: "草稿",
      SCORING: "评分中",
      FAILED: "失败"
    }[String(status)] ?? String(status ?? "未知")
  );
}

function actionNotice(action: BusyAction) {
  if (action === "parse") return "Parser 已完成结构化抽取";
  if (action === "questions") return "Interviewer 已生成追问";
  if (action === "score") return "评分引擎已写入能力档案";
  if (String(action).startsWith("answer-")) return "Evaluator 已生成能力证据";
  return "操作完成";
}

function parseSectionDescription(section: ResumeSection) {
  return [section.organization, section.timeRange, section.description]
    .filter((item) => item && item.trim())
    .join(" · ");
}

function formatDate(value: string | null | undefined) {
  if (!value) return "时间未知";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  }).format(new Date(value));
}

function formatPercent(value: number) {
  return `${Math.round(value * 100)}%`;
}

function formatNumber(value: number) {
  return Number.isInteger(value) ? String(value) : value.toFixed(1);
}

function numberValue(value: unknown, fallback = 0.5) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function parseJson<T>(json: string | null | undefined, fallback: T): T {
  if (!json) return fallback;
  try {
    return JSON.parse(json) as T;
  } catch {
    return fallback;
  }
}

function uniqueTraceIds(ids: Array<number | null | undefined> | undefined) {
  return [...new Set((ids ?? []).filter((id): id is number => Boolean(id)).map((id) => `run #${id}`))];
}

function showNotice(message: string) {
  notice.value = message;
  if (noticeTimer) clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => {
    notice.value = "";
  }, 2800);
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError || error instanceof Error ? error.message : fallback;
}
</script>

<template>
  <section class="resume-interview-workspace">
    <header class="resume-interview-hero">
      <div>
        <p class="eyebrow">Resume Interview</p>
        <h2>简历定段 / 面试评估</h2>
        <p>把外部简历和追问回答转成统一能力证据，再交给 Java 评分引擎计算初始能力。</p>
      </div>
      <RouterLink to="/student/achievements">
        <BadgeCheck :size="18" />
        查看能力档案
      </RouterLink>
    </header>

    <div class="resume-interview-layout">
      <aside class="resume-session-panel">
        <section class="resume-submit-card">
          <header>
            <FileText :size="20" />
            <div>
              <strong>新建评估</strong>
              <small>第一版先支持粘贴文本</small>
            </div>
          </header>
          <label>
            <span>目标岗位</span>
            <input v-model="targetRole" maxlength="160" placeholder="例如：Agent 后端实习" />
          </label>
          <label>
            <span>简历文本</span>
            <textarea
              v-model="resumeText"
              rows="8"
              maxlength="20000"
              placeholder="粘贴教育经历、项目、实习、比赛、技能等内容"
            ></textarea>
          </label>
          <button type="button" :disabled="!resumeText.trim() || busy === 'create'" @click="submitResume">
            <LoaderCircle v-if="busy === 'create'" class="spin" :size="17" />
            <Sparkles v-else :size="17" />
            创建评估
          </button>
        </section>

        <section class="resume-session-list">
          <header>
            <strong>历史评估</strong>
            <button type="button" title="刷新" @click="loadSessions(false)">
              <RefreshCw :class="{ spin: busy === 'load' }" :size="16" />
            </button>
          </header>
          <button
            v-for="session in sessions"
            :key="session.id"
            type="button"
            :class="{ 'is-active': selectedSessionId === session.id }"
            @click="selectSession(session.id)"
          >
            <span>
              <strong>{{ session.targetRole || "未命名岗位" }}</strong>
              <small>{{ formatDate(session.createdAt) }}</small>
            </span>
            <em>{{ statusLabel(session.status) }}</em>
          </button>
          <p v-if="!sessions.length">还没有简历评估记录。</p>
        </section>
      </aside>

      <main class="resume-flow-panel">
        <div v-if="!currentDetail" class="resume-empty-state">
          <Bot :size="34" />
          <h3>从左侧粘贴一份简历开始</h3>
          <p>系统会依次调用 Parser、Interviewer、Evaluator 和确定性评分引擎。</p>
        </div>

        <template v-else>
          <section class="resume-flow-progress">
            <article v-for="stage in stageItems" :key="stage.label" :class="{ 'is-done': stage.done }">
              <span>
                <Check v-if="stage.done" :size="16" />
                <ArrowRight v-else :size="16" />
              </span>
              <strong>{{ stage.label }}</strong>
              <small>{{ stage.trace || "等待执行" }}</small>
            </article>
          </section>

          <section class="resume-current-card">
            <header>
              <div>
                <p class="eyebrow">Current Session</p>
                <h3>{{ currentSession?.targetRole || "未命名岗位" }}</h3>
                <span>状态：{{ statusLabel(currentSession?.status) }} · 下一步：{{ currentDetail.nextStep }}</span>
              </div>
              <div class="resume-action-row">
                <button type="button" :disabled="!canParse || busy === 'parse'" @click="runParse">
                  <LoaderCircle v-if="busy === 'parse'" class="spin" :size="17" />
                  <FileText v-else :size="17" />
                  Parser
                </button>
                <button
                  type="button"
                  :disabled="!canGenerateQuestions || busy === 'questions'"
                  @click="runQuestionGeneration"
                >
                  <LoaderCircle v-if="busy === 'questions'" class="spin" :size="17" />
                  <Bot v-else :size="17" />
                  生成追问
                </button>
                <button type="button" :disabled="!canScore || busy === 'score'" @click="runScore">
                  <LoaderCircle v-if="busy === 'score'" class="spin" :size="17" />
                  <ShieldCheck v-else :size="17" />
                  计算定段
                </button>
              </div>
            </header>

            <div v-if="activeSnapshot" class="resume-snapshot-box">
              <span>简历快照 #{{ activeSnapshot.id }}</span>
              <p>{{ activeSnapshot.rawResumePreview }}</p>
              <small>
                hash {{ activeSnapshot.resumeHash.slice(0, 10) }} · {{ activeSnapshot.textLength }} 字
              </small>
            </div>
          </section>

          <section v-if="parsedSectionGroups.length || parsedResume.skills?.length" class="resume-parsed-panel">
            <header>
              <div>
                <h4>Parser 抽取结果</h4>
                <p>这里只展示事实结构化结果，不参与最终打分。</p>
              </div>
              <span v-if="activeSnapshot?.parserTraceRunId">run #{{ activeSnapshot.parserTraceRunId }}</span>
            </header>
            <div class="resume-parsed-grid">
              <article v-for="group in parsedSectionGroups" :key="group.key">
                <strong>{{ group.label }}</strong>
                <div v-for="item in group.items" :key="item.id || item.title">
                  <h5>{{ item.title || "未命名经历" }}</h5>
                  <p>{{ parseSectionDescription(item) }}</p>
                </div>
              </article>
              <article v-if="parsedResume.skills?.length">
                <strong>技能声明</strong>
                <div class="resume-skill-tags">
                  <span v-for="skill in parsedResume.skills" :key="skill">{{ skill }}</span>
                </div>
              </article>
            </div>
          </section>

          <section v-if="currentDetail.questions.length" class="resume-question-panel">
            <header>
              <div>
                <h4>Interviewer 追问</h4>
                <p>每个回答会交给 Evaluator 转成统一能力证据。</p>
              </div>
              <span>{{ currentDetail.questions.length }} 题</span>
            </header>
            <article v-for="question in currentDetail.questions" :key="question.id">
              <div class="resume-question-heading">
                <span>{{ question.sequenceNo }}</span>
                <div>
                  <strong>{{ question.targetDimension }}</strong>
                  <small>{{ statusLabel(question.status) }} · run #{{ question.agentTraceRunId }}</small>
                </div>
              </div>
              <p>{{ question.questionText }}</p>
              <label>
                <span>你的回答</span>
                <textarea
                  v-model="answerDrafts[question.id]"
                  rows="4"
                  maxlength="5000"
                  :disabled="hasAnswer(question.id)"
                  placeholder="用项目细节、数据、取舍和失败处理来回答"
                ></textarea>
              </label>
              <button
                type="button"
                :disabled="hasAnswer(question.id) || questionBusy(question.id)"
                @click="submitAnswer(question.id)"
              >
                <CheckCircle2 v-if="hasAnswer(question.id)" :size="17" />
                <LoaderCircle v-else-if="questionBusy(question.id)" class="spin" :size="17" />
                <Send v-else :size="17" />
                {{ hasAnswer(question.id) ? "已生成证据" : "提交给 Evaluator" }}
              </button>
            </article>
          </section>

          <section v-if="evidenceSummaries.length" class="resume-evidence-panel">
            <header>
              <div>
                <h4>Evaluator 证据候选</h4>
                <p>LLM 只输出评分原料，最终分数交给 Java 引擎。</p>
              </div>
              <span>{{ evidenceSummaries.length }} 条</span>
            </header>
            <article v-for="item in evidenceSummaries" :key="item.id">
              <div>
                <strong>证据 #{{ item.id }}</strong>
                <small>{{ statusLabel(item.status) }} · run #{{ item.traceRunId }}</small>
              </div>
              <div class="resume-evidence-metrics">
                <span>置信 {{ formatPercent(item.confidence) }}</span>
                <span>质量 {{ formatPercent(item.quality) }}</span>
                <span>贡献 {{ formatPercent(item.contribution) }}</span>
                <span>难度 {{ formatNumber(item.difficulty) }}</span>
              </div>
              <div class="resume-skill-tags">
                <span v-for="dimension in item.dimensions" :key="dimension">{{ dimension }}</span>
                <span v-if="item.judgeSuggested" class="is-warning">建议 Judge</span>
                <span v-for="risk in item.riskFlags" :key="risk" class="is-risk">{{ risk }}</span>
              </div>
            </article>
          </section>

          <section v-if="currentDetail.scoreSummary" class="resume-score-panel">
            <header>
              <div>
                <h4>初始能力结果</h4>
                <p>结果已经写入个人成就里的能力标签页面。</p>
              </div>
              <span>{{ statusLabel(currentDetail.scoreSummary.status) }}</span>
            </header>
            <div class="resume-score-grid">
              <article>
                <ClipboardList :size="20" />
                <strong>{{ scoreResultIds.length }}</strong>
                <small>评分结果</small>
              </article>
              <article>
                <Target :size="20" />
                <strong>{{ judgeTriggered.length }}</strong>
                <small>Judge 触发</small>
              </article>
              <article>
                <ShieldCheck :size="20" />
                <strong>{{ currentDetail.scoreSummary.scoringRuleVersion || "未完成" }}</strong>
                <small>规则版本</small>
              </article>
            </div>
            <RouterLink v-if="scored" to="/student/achievements">
              <BadgeCheck :size="18" />
              去能力档案查看
            </RouterLink>
          </section>
        </template>
      </main>

      <aside class="resume-trace-panel">
        <header>
          <Bot :size="20" />
          <div>
            <strong>Agent Trace</strong>
            <small>每个阶段都能回溯 run id</small>
          </div>
        </header>
        <article v-for="stage in stageItems" :key="stage.label">
          <span :class="{ 'is-done': stage.done }"></span>
          <div>
            <strong>{{ stage.label }}</strong>
            <small>{{ stage.trace || "暂无 trace" }}</small>
          </div>
        </article>
        <div class="resume-trace-note">
          <AlertCircle :size="17" />
          <p>Parser、Interviewer、Evaluator、Scoring 都进入后端 Agent Run 日志。Bad case 后续可以按 run id 复盘。</p>
        </div>
      </aside>
    </div>

    <Transition name="menu">
      <div v-if="notice" class="events-toast">{{ notice }}</div>
    </Transition>
    <Transition name="menu">
      <div v-if="errorText" class="events-toast is-error">{{ errorText }}</div>
    </Transition>
  </section>
</template>
