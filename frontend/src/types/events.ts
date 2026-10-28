export interface EventItem {
  id: number;
  title: string;
  organizationName: string;
  category: string;
  categoryCode: string;
  startTime: string;
  endTime: string;
  /** Time dimension, derived by the backend: NOT_STARTED / ONGOING / ENDED. */
  timePhase: string;
  timePhaseLabel: string;
  /** What a student sees: the time phase, or OFFLINE; studentNotice says why. */
  studentStatus: string;
  studentStatusLabel: string;
  studentNotice: string | null;
  location: string;
  content: string;
  benefitType: string;
  benefitTypeCode: string;
  skill: string | null;
  moneyAmount: number | null;
  createdByUserId: string;
  createdAt: string;
}

/** The organisation's view of its own event: publication state and the actions open now. */
export interface OrganizerEventItem extends EventItem {
  reviewStatus: "DRAFT" | "PENDING_REVIEW" | "NEEDS_REVISION" | "APPROVED" | "OFFLINE" | "REJECTED";
  reviewStatusLabel: string;
  reviewRoute: "AI" | "MANUAL";
  offlineReasonCode: "ORGANIZER" | "OPERATOR" | "ENDED" | "DISCARDED" | null;
  offlineReason: string | null;
  revisionNote: string | null;
  aiRevisionCount: number;
  version: number;
  canEdit: boolean;
  canSubmit: boolean;
  canTakeOffline: boolean;
  canDiscard: boolean;
  canCheckIn: boolean;
}

export interface EventHistoryItem {
  fromStatus: string | null;
  fromStatusLabel: string | null;
  toStatus: string;
  toStatusLabel: string;
  action: string;
  actorType: "SYSTEM" | "AI" | "ORGANIZER" | "OPERATOR";
  reason: string | null;
  createdAt: string;
}

export interface EventSearchFilters {
  keyword: string;
  category: string;
  benefitType: string;
  location: string;
}

export interface FollowItem {
  id: number;
  organizationName: string;
  createdAt: string;
}

export interface ReservationItem {
  id: number;
  event: EventItem;
  status: string;
  qrToken: string;
  reservedAt: string;
  completedAt: string | null;
  /** Set when the event ended or was taken offline; the reservation itself is kept. */
  notice: string | null;
}

export interface ToolContextRequest {
  timezone: string;
  clientNow: string;
  latitude?: number;
  longitude?: number;
  locationText?: string;
}

export interface ToolContextResponse {
  currentTime?: {
    toolName: string;
    timezone: string;
    serverTime: string;
    localDate: string;
    dayOfWeek: string;
  };
  location?: {
    toolName: string;
    source: string;
    latitude?: number;
    longitude?: number;
    label?: string;
    queryText?: string;
  };
  toolTrace?: string[];
}

export interface RecommendedEvent {
  event: EventItem;
  score: number;
  confidence: string;
  evidence: string[];
  reason: string;
}

export interface EventRecommendationResponse {
  mode: string;
  need: string;
  message: string;
  recommendations: RecommendedEvent[];
  toolContext?: ToolContextResponse;
  runId?: number | null;
}

export type GuideAction = "CALL_RAG_NEW" | "CALL_RAG_REFINE" | "CHAT_ONLY" | "ASK_CLARIFY";

export interface GuideMessage {
  role: string;
  content: string;
  at: string;
}

export interface GuideSessionView {
  sessionId: string;
  currentGoal: string;
  sessionSummary: string;
  lastAction: string;
  lastRagNeed: string;
  recentMessages: GuideMessage[];
  lastRecommendedEventIds: number[];
  acceptedPreferences: string[];
  rejectedConditions: string[];
  updatedAt: string;
  expiresAt: string;
}

export interface GuideChatResponse {
  sessionId: string;
  reply: string;
  action: GuideAction;
  usedRag: boolean;
  recommendations: RecommendedEvent[];
  session: GuideSessionView;
  toolContext?: ToolContextResponse;
  recommendationRunId?: number | null;
}

export type RecommendationFeedbackType = "INTERESTED" | "NOT_RELEVANT";

export interface RecommendationFeedbackState {
  eventId: number;
  type: RecommendationFeedbackType | null;
  reason: string | null;
}

export interface PlanStep {
  order: number;
  dateLabel: string;
  title: string;
  itemType: string;
  eventId: number | null;
  scheduleHint: string;
  reason: string;
}

export interface PlanNode {
  id: string;
  type: string;
  title: string;
  subtitle: string;
  eventId: number | null;
  order: number;
  agent: string;
}

export interface PlanEdge {
  from: string;
  to: string;
  label: string;
}

export interface RecommendedPlan {
  title: string;
  style: string;
  summary: string;
  steps: PlanStep[];
  warnings: string[];
  qualityScore: number | null;
  agentTrace: string[];
  nodes: PlanNode[];
  edges: PlanEdge[];
}

export interface PlanRecommendationResponse {
  mode: string;
  goal: string;
  message: string;
  plans: RecommendedPlan[];
  toolContext?: ToolContextResponse;
}

export interface ImportPlanResponse {
  importedCount: number;
  skippedCount: number;
  warnings: string[];
  items: Array<{
    id: number;
    title: string;
    startTime: string;
    endTime: string;
  }>;
}
