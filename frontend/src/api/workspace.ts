import { api } from "@/api/client";
import type { EventHistoryItem, FollowItem, OrganizerEventItem } from "@/types/events";
import type {
  Challenge,
  ChallengePage,
  CoachLog,
  CoachMemory,
  CoachMessage,
  EventQualityReport,
  Organization,
  ScheduleItem
} from "@/types/workspace";

export const scheduleApi = {
  list() {
    return api.get<ScheduleItem[]>("/api/schedule");
  },
  create(payload: Omit<ScheduleItem, "id" | "status" | "createdAt" | "updatedAt">) {
    return api.post<ScheduleItem>("/api/schedule", payload);
  },
  update(itemId: number, payload: Pick<ScheduleItem, "title" | "startTime" | "endTime" | "location" | "notes">) {
    return api.put<ScheduleItem>(`/api/schedule/${itemId}`, payload);
  },
  remove(itemId: number) {
    return api.delete<void>(`/api/schedule/${itemId}`);
  }
};

export const challengesApi = {
  page(status = "", page = 0, size = 6) {
    const query = new URLSearchParams({ page: String(page), size: String(size) });
    if (status) query.set("status", status);
    return api.get<ChallengePage>(`/api/challenges/page?${query}`);
  },
  active() {
    return api.get<Challenge[]>("/api/challenges?status=ACTIVE");
  },
  create(payload: Pick<Challenge, "title" | "category" | "goal" | "description">) {
    return api.post<Challenge>("/api/challenges", payload);
  },
  complete(challengeId: number, did: string, learned: string) {
    return api.post<Challenge>(`/api/challenges/${challengeId}/complete`, { did, learned });
  },
  cancel(challengeId: number) {
    return api.delete<void>(`/api/challenges/${challengeId}`);
  }
};

export const coachApi = {
  messages() {
    return api.get<CoachMessage[]>("/api/coach/messages");
  },
  chat(message: string) {
    return api.post<{ assistantMessage: CoachMessage; generatedLog: CoachLog | null; logGenerated: boolean }>(
      "/api/coach/chat",
      { message }
    );
  },
  logs() {
    return api.get<CoachLog[]>("/api/coach/logs");
  },
  memories() {
    return api.get<CoachMemory[]>("/api/coach/memory-reviews");
  },
  generateLog() {
    return api.post<CoachLog>("/api/coach/logs/generate", {});
  }
};

export const organizationsApi = {
  list() {
    return api.get<Organization[]>("/api/organizations");
  },
  follows() {
    return api.get<FollowItem[]>("/api/follows");
  },
  unfollow(name: string) {
    return api.delete<void>(`/api/follows/${encodeURIComponent(name)}`);
  }
};

export interface EventPayload {
  title: string;
  organizationName: string;
  category: string;
  startTime: string;
  endTime: string;
  location: string;
  content: string;
  benefitType: string;
  skill: string | null;
  moneyAmount: number | null;
  /** false: only save ("已保存"); true: save and send to review. */
  submit: boolean;
}

export const socialApi = {
  mine() {
    return api.get<OrganizerEventItem[]>("/api/events/mine");
  },
  create(payload: EventPayload) {
    return api.post<OrganizerEventItem>("/api/events", payload);
  },
  /** Editing a published event sends it back to review whatever submit says. */
  update(eventId: number, payload: EventPayload) {
    return api.put<OrganizerEventItem>(`/api/events/${eventId}`, payload);
  },
  submit(eventId: number) {
    return api.post<OrganizerEventItem>(`/api/events/${eventId}/submit`);
  },
  takeOffline(eventId: number, reason: string) {
    return api.post<OrganizerEventItem>(`/api/events/${eventId}/offline`, { reason });
  },
  history(eventId: number) {
    return api.get<EventHistoryItem[]>(`/api/events/${eventId}/history`);
  },
  checkIn(eventId: number, qrToken: string) {
    return api.post<{ reservationId: number; studentUserId: string; completedAt: string }>(
      `/api/events/${eventId}/check-in`, { qrToken });
  },
  /** Red dot: events the AI or an operator changed since "my events" was last opened. */
  attention() {
    return api.get<{ count: number }>("/api/events/mine/attention");
  },
  markSeen() {
    return api.post<void>("/api/events/mine/seen");
  },
  /** Discard a never-published event (soft: nothing is deleted). */
  discard(eventId: number) {
    return api.delete<void>(`/api/events/${eventId}`);
  },
  report(eventId: number) {
    return api.get<EventQualityReport>(`/api/events/${eventId}/quality-report`);
  },
  reanalyze(eventId: number) {
    return api.post<{ status: string }>(`/api/events/${eventId}/quality/reanalyze`);
  }
};
