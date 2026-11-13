import { api, apiStream } from "@/api/client";

/** F1: Tandem, the master agent students talk to. */
export interface TandemStep {
  tool: string;
  title: string;
  detail: string;
  status: "OK" | "ERROR" | "DENIED" | "PENDING_CONFIRMATION";
  summary: string;
}

export interface TandemMessage {
  id: number;
  role: "USER" | "ASSISTANT";
  content: string;
  steps: TandemStep[];
  notices: string[];
  degraded: boolean;
  createdAt: string;
}

export interface TandemMessagePage {
  messages: TandemMessage[];
  hasMore: boolean;
}

export interface TandemMemory {
  id: number;
  category: string;
  categoryLabel: string;
  content: string;
  quote: string | null;
  validUntil: string | null;
  expired: boolean;
  updatedAt: string;
}

/** One change of the live step list: a new row (running) or a finished one. */
export interface TandemProgress {
  key: string;
  kind: "thinking" | "tool";
  title: string | null;
  detail: string | null;
  status: "running" | "ok" | "error" | "denied";
  summary: string | null;
}

export const tandemApi = {
  messages: (before?: number) =>
    api.get<TandemMessagePage>(`/api/tandem/messages${before ? `?before=${before}` : ""}`),
  clear: () => api.delete<void>("/api/tandem/messages"),
  memories: () => api.get<{ memories: TandemMemory[]; limit: number }>("/api/tandem/memories"),
  deleteMemory: (id: number) => api.delete<void>(`/api/tandem/memories/${id}`),
  chat: (message: string, onEvent: (name: string, data: unknown) => void) =>
    apiStream("/api/tandem/chat", { message }, onEvent)
};
