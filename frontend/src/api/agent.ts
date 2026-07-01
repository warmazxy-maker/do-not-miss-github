import { api } from "@/api/client";

export interface AgentRunSummary {
  id: number;
  runType: string;
  status: string;
  goal: string;
  inputSummary: string;
  outputSummary: string;
  errorMessage: string | null;
  startedAt: string;
  finishedAt: string | null;
  durationMillis: number;
}

export type BadCaseIssueType =
  | "UNKNOWN"
  | "RETRIEVAL_MISS"
  | "LOW_RELEVANCE"
  | "QUERY_REWRITE_ERROR"
  | "HALLUCINATION"
  | "WRONG_CONTEXT"
  | "PLAN_UNEXECUTABLE"
  | "COACH_OFF_TOPIC"
  | "SCORING_UNFAIR"
  | "TOOL_ERROR"
  | "UI_CONFUSION"
  | "LATENCY_TIMEOUT";

export type BadCaseSeverity = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

export interface CreateBadCasePayload {
  agentRunId: number | null;
  sourceType: "USER_FEEDBACK";
  issueType: BadCaseIssueType;
  severity: BadCaseSeverity;
  pageUrl: string;
  moduleKey: string;
  userMessage: string;
  expectedBehavior: string;
  actualBehavior: string;
}

export interface AgentBadCase {
  id: number;
  caseKey: string;
  userId: string;
  agentRunId: number | null;
  runType: string | null;
  sourceType: string;
  issueType: BadCaseIssueType;
  severity: BadCaseSeverity;
  status: string;
  pageUrl: string;
  moduleKey: string;
  userMessage: string;
  expectedBehavior: string;
  actualBehavior: string;
  rootCauseStep: string | null;
  rootCauseSummary: string | null;
  relevantArtifactIds: string | null;
  analysisJson: string | null;
  agentMemoryCandidate: boolean;
  evalCaseCandidate: boolean;
  resolutionSummary: string | null;
  reviewerId: string | null;
  createdAt: string;
  updatedAt: string;
  triagedAt: string | null;
  resolvedAt: string | null;
}

export const agentApi = {
  recentRuns() {
    return api.get<AgentRunSummary[]>("/api/ai/agent-runs");
  },
  createBadCase(payload: CreateBadCasePayload) {
    return api.post<AgentBadCase>("/api/ai/bad-cases", payload);
  },
  badCases() {
    return api.get<AgentBadCase[]>("/api/ai/bad-cases");
  }
};
