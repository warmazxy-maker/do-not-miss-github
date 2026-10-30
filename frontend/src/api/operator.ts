import { api } from "@/api/client";
import type { EventHistoryItem, OrganizerEventItem } from "@/types/events";
import type { EventQualityReport } from "@/types/workspace";

export type QueueKind = "MANUAL" | "SPOT_CHECK";

export interface QueueItem {
  eventId: number;
  title: string;
  organizationName: string;
  kind: QueueKind;
  waitingSince: string;
  aiRevisionCount: number;
}

export interface ReviewDetail {
  event: OrganizerEventItem;
  latestReport: EventQualityReport | null;
  aiRevisionNotes: { note: string | null; createdAt: string }[];
  history: EventHistoryItem[];
  pendingSpotCheck: boolean;
}

export interface EventPage {
  items: OrganizerEventItem[];
  total: number;
  page: number;
  size: number;
}

function query(params: Record<string, string | number | undefined>) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== "") search.set(key, String(value));
  });
  const text = search.toString();
  return text ? `?${text}` : "";
}

/** Operator console (D3). State changes reuse the D2 event endpoints. */
export const operatorApi = {
  queue: () => api.get<QueueItem[]>("/api/operator/queue"),
  detail: (eventId: number) => api.get<ReviewDetail>(`/api/operator/events/${eventId}`),
  events: (filters: { status?: string; keyword?: string; page?: number; size?: number }) =>
    api.get<EventPage>(`/api/operator/events${query(filters)}`),
  drawSpotChecks: () => api.post<{ drawn: number }>("/api/operator/spot-checks/draw"),
  passSpotCheck: (eventId: number) => api.post<void>(`/api/operator/spot-checks/${eventId}/pass`),
  failSpotCheck: (eventId: number, reason: string) =>
    api.post<OrganizerEventItem>(`/api/operator/spot-checks/${eventId}/offline`, { reason }),
  approve: (eventId: number) => api.post<{ status: string }>(`/api/events/${eventId}/review/approve`),
  requestRevision: (eventId: number, reason: string) =>
    api.post<{ status: string }>(`/api/events/${eventId}/review/reject`, { reason }),
  reanalyze: (eventId: number) => api.post<{ status: string }>(`/api/events/${eventId}/quality/reanalyze`),
  takeOffline: (eventId: number, reason: string) =>
    api.post<OrganizerEventItem>(`/api/events/${eventId}/offline`, { reason }),
  relist: (eventId: number) => api.post<OrganizerEventItem>(`/api/events/${eventId}/relist`)
};
