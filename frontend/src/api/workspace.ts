import { api } from "@/api/client";
import type { EventItem, FollowItem } from "@/types/events";
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

export const socialApi = {
  mine() {
    return api.get<EventItem[]>("/api/events/mine");
  },
  create(payload: {
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
  }) {
    return api.post<EventItem>("/api/events", payload);
  },
  remove(eventId: number) {
    return api.delete<void>(`/api/events/${eventId}`);
  },
  report(eventId: number) {
    return api.get<EventQualityReport>(`/api/events/${eventId}/quality-report`);
  },
  reanalyze(eventId: number) {
    return api.post<{ status: string }>(`/api/events/${eventId}/quality/reanalyze`);
  }
};
