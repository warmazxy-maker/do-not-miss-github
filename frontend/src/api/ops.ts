import { api } from "@/api/client";

export type HealthState = "UP" | "DEGRADED" | "DOWN" | "DISABLED";

export interface DependencyHealth {
  key: string;
  name: string;
  state: HealthState;
  latencyMillis: number | null;
  summary: string;
  details: Record<string, unknown>;
}

export interface OpsOverview {
  checkedAt: string;
  dependencies: DependencyHealth[];
  counters: Record<string, number>;
}

export interface OpsAgentRun {
  id: number;
  userId: string;
  runType: string;
  status: "RUNNING" | "SUCCEEDED" | "FAILED";
  goal: string;
  inputSummary: string | null;
  outputSummary: string | null;
  errorMessage: string | null;
  startedAt: string;
  finishedAt: string | null;
  durationMillis: number | null;
}

export interface AgentRunTypeStats {
  runType: string;
  total: number;
  failed: number;
  running: number;
  avgMillis: number | null;
  p95Millis: number | null;
}

export interface StepStats {
  stepName: string;
  total: number;
  failed: number;
  avgMillis: number | null;
  p95Millis: number | null;
}

export interface AgentRunStats {
  windowHours: number;
  byType: AgentRunTypeStats[];
  stepRunType: string;
  steps: StepStats[];
  topErrors: { error: string; count: number }[];
}

export interface AgentRunStep {
  id: number;
  sequenceNo: number;
  stepName: string;
  status: string;
  inputSummary: string | null;
  outputSummary: string | null;
  errorMessage: string | null;
  startedAt: string;
  finishedAt: string | null;
  durationMillis: number;
}

export interface AgentArtifact {
  id: number;
  runId: number;
  stepName: string | null;
  artifactType: string;
  contentSummary: string | null;
  contentJson: string;
  contentHash: string;
  redacted: boolean;
  createdAt: string;
}

export interface AgentRunDetail {
  run: Omit<OpsAgentRun, "userId"> & { durationMillis: number };
  steps: AgentRunStep[];
  artifacts: AgentArtifact[];
}

export interface QueueRow {
  queue: string;
  routingKey: string;
  messageCount: number | null;
  consumerCount: number | null;
  listenerRunning: boolean | null;
  error: string | null;
}

export interface OutboxRow {
  id: number;
  routingKey: string;
  payloadType: string;
  status: "PENDING" | "FAILED" | "SENT";
  attempts: number;
  nextAttemptAt: string | null;
  lastError: string | null;
  createdAt: string;
  sentAt: string | null;
  payloadJson: string;
  deferred: boolean;
  exhausted: boolean;
}

export interface OutboxStats {
  maxAttempts: number;
  byRoutingKey: Array<Record<string, string | number>>;
  pending: number;
  deferred: number;
  failed: number;
  exhausted: number;
}

export interface Messaging {
  exchange: string;
  queues: QueueRow[];
  outbox: OutboxStats;
}

export interface RedisInfo {
  state: HealthState;
  latencyMillis: number | null;
  dbSize: number | null;
  usedMemory: string | null;
  maxMemory: string | null;
  prefixes: { prefix: string; meaning: string; count: number; capped: boolean }[];
  error: string | null;
}

export interface DatabaseInfo {
  product: string;
  latestVersion: string | null;
  migrations: { version: string; description: string; success: boolean; installedOn: string | null; executionMillis: number }[];
  pool: Record<string, number>;
  tables: { table: string; approxRows: number; dataKb: number; indexKb: number }[];
}

export interface JourneyStep {
  layer: "SQL" | "MQ" | "AGENT";
  name: string;
  status: string;
  at: string | null;
  detail: string | null;
  refId: number | null;
}

export interface Journey {
  recordId: number;
  title: string;
  sourceType: string | null;
  completedAt: string;
  verdict: string;
  verdictLevel: "OK" | "WARN" | "ERROR" | "MUTED";
  steps: JourneyStep[];
}

export interface OpsUser {
  userId: string;
  username: string;
  role: string | null;
}

export interface RecommendationFunnel {
  impressions: number;
  interested: number;
  notRelevant: number;
  reserved: number;
  cancelled: number;
  completed: number;
  followed: number;
  withExplicitFeedback: number;
  interestedRate: number;
  notRelevantRate: number;
  reserveRate: number;
  completeRate: number;
  followRate: number;
  explicitFeedbackRate: number;
}

export interface RecommendationDay {
  date: string;
  runs: number;
  zeroResultRuns: number;
  impressions: number;
  interested: number;
  notRelevant: number;
  reserved: number;
}

export interface RecommendationStats {
  windowDays: number;
  runs: number;
  failedRuns: number;
  zeroResultRuns: number;
  zeroResultRate: number;
  avgMillis: number | null;
  studentsReached: number;
  suppressedByFeedback: number;
  trackingSince: string | null;
  funnel: RecommendationFunnel;
  daily: RecommendationDay[];
  notRelevantReasons: { eventId: number; eventTitle: string | null; userId: string; reason: string | null; at: string; runId: number | null }[];
}

export interface RecommendationRunRow {
  runId: number;
  userId: string;
  need: string;
  status: string;
  startedAt: string;
  durationMillis: number | null;
  recommendationCount: number | null;
  shown: { eventId: number; title: string | null; rank: number; score: number; feedback: string[] }[];
}

export interface EvalCandidateRow {
  id: number;
  runId: number | null;
  userId: string;
  eventId: number;
  eventTitle: string | null;
  needText: string | null;
  reason: string | null;
  status: "PENDING" | "APPROVED" | "REJECTED";
  reviewerNote: string | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  createdAt: string;
  inCurrentEvalSet: boolean;
}

export interface EvalExport {
  evalSetVersion: string;
  corpusVersion: string;
  newCaseIds: string[];
  addedEventIds: number[];
  warnings: string[];
  evalSetFileName: string;
  evalSetJson: string;
  corpusFileName: string | null;
  corpusJson: string | null;
}

function query(params: Record<string, string | number | undefined>) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== "") {
      search.set(key, String(value));
    }
  });
  const text = search.toString();
  return text ? `?${text}` : "";
}

export const opsApi = {
  access: () => api.get<{ allowed: boolean }>("/api/ops/access"),
  overview: () => api.get<OpsOverview>("/api/ops/overview"),
  probeModels: () => api.post<DependencyHealth[]>("/api/ops/probe/models"),
  agentRuns: (filters: { runType?: string; status?: string; userId?: string; limit?: number }) =>
    api.get<OpsAgentRun[]>(`/api/ops/agent-runs${query(filters)}`),
  agentRunStats: (hours: number, stepRunType?: string) =>
    api.get<AgentRunStats>(`/api/ops/agent-runs/stats${query({ hours, stepRunType })}`),
  agentRun: (id: number) => api.get<AgentRunDetail>(`/api/ops/agent-runs/${id}`),
  messaging: () => api.get<Messaging>("/api/ops/messaging"),
  outbox: (view: string, routingKey?: string, limit = 50) =>
    api.get<OutboxRow[]>(`/api/ops/outbox${query({ view, routingKey, limit })}`),
  retryOutbox: (id: number) => api.post<OutboxRow>(`/api/ops/outbox/${id}/retry`),
  redis: () => api.get<RedisInfo>("/api/ops/redis"),
  database: () => api.get<DatabaseInfo>("/api/ops/database"),
  recommendationStats: (days: number) => api.get<RecommendationStats>(`/api/ops/recommendations/stats${query({ days })}`),
  recommendationRuns: (limit = 20) => api.get<RecommendationRunRow[]>(`/api/ops/recommendations/runs${query({ limit })}`),
  evalCandidates: (status: string) => api.get<EvalCandidateRow[]>(`/api/ops/eval-candidates${query({ status })}`),
  reviewEvalCandidate: (id: number, decision: "APPROVED" | "REJECTED", needText?: string, note?: string) =>
    api.post<void>(`/api/ops/eval-candidates/${id}/review`, { decision, needText, note }),
  exportEvalSet: () => api.get<EvalExport>("/api/ops/eval-candidates/export"),
  users: () => api.get<OpsUser[]>("/api/ops/users"),
  journeys: (userId: string, limit = 15, kind: "achievement" | "resume" = "achievement") =>
    api.get<Journey[]>(`/api/ops/journeys${query({ userId, kind, limit })}`)
};

export function formatMillis(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return "—";
  }
  if (value < 1000) {
    return `${value} ms`;
  }
  return `${(value / 1000).toFixed(value < 10_000 ? 1 : 0)} s`;
}

export function formatPercent(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return "—";
  }
  return `${(value * 100).toFixed(1)}%`;
}

export function formatTime(value: string | null | undefined) {
  if (!value) {
    return "—";
  }
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  }).format(new Date(value));
}

export function prettyJson(value: string) {
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}
