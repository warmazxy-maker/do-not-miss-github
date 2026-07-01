export interface EventItem {
  id: number;
  title: string;
  organizationName: string;
  category: string;
  categoryCode: string;
  startTime: string;
  endTime: string;
  expired: boolean;
  reviewStatus: string;
  reviewStatusLabel: string;
  location: string;
  content: string;
  benefitType: string;
  benefitTypeCode: string;
  skill: string | null;
  moneyAmount: number | null;
  createdByUserId: string;
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
