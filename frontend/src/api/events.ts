import { api } from "@/api/client";
import type {
  EventItem,
  EventRecommendationResponse,
  EventSearchFilters,
  FollowItem,
  ImportPlanResponse,
  PlanRecommendationResponse,
  RecommendedPlan,
  ReservationItem,
  ToolContextRequest
} from "@/types/events";

function queryString(filters: EventSearchFilters) {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value.trim()) {
      params.set(key, value.trim());
    }
  });
  const query = params.toString();
  return query ? `?${query}` : "";
}

export const eventsApi = {
  search(filters: EventSearchFilters) {
    return api.get<EventItem[]>(`/api/events${queryString(filters)}`);
  },
  follows() {
    return api.get<FollowItem[]>("/api/follows");
  },
  follow(organizationName: string) {
    return api.post<FollowItem>("/api/follows", { organizationName });
  },
  unfollow(organizationName: string) {
    return api.delete<void>(`/api/follows/${encodeURIComponent(organizationName)}`);
  },
  reservations() {
    return api.get<ReservationItem[]>("/api/reservations");
  },
  reserve(eventId: number) {
    return api.post<ReservationItem>("/api/reservations", { eventId });
  },
  cancelReservation(reservationId: number) {
    return api.delete<void>(`/api/reservations/${reservationId}`);
  },
  completeReservation(qrToken: string) {
    return api.post<ReservationItem>("/api/reservations/scan-complete", { qrToken });
  },
  recommendEvents(payload: {
    need: string;
    category?: string;
    benefitType?: string;
    location?: string;
    toolContext: ToolContextRequest;
  }) {
    return api.post<EventRecommendationResponse>("/api/ai/event-recommendations", payload);
  },
  recommendPlans(payload: {
    goal: string;
    horizonDays: number;
    intensity: string;
    location?: string;
    toolContext: ToolContextRequest;
  }) {
    return api.post<PlanRecommendationResponse>("/api/ai/action-plans", payload);
  },
  importPlan(plan: RecommendedPlan, goal: string) {
    return api.post<ImportPlanResponse>("/api/schedule/import-ai-plan", {
      title: plan.title,
      style: plan.style,
      goal,
      steps: plan.steps.map((step, index) => ({
        order: Number.isInteger(step.order) ? step.order : index + 1,
        dateLabel: step.dateLabel ?? "",
        title: step.title || `步骤 ${index + 1}`,
        itemType: step.itemType || "STUDY",
        eventId: step.eventId ?? null,
        scheduleHint: step.scheduleHint ?? "",
        reason: step.reason ?? ""
      }))
    });
  }
};
