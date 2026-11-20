import { api, apiStream } from "@/api/client";

/** F1: Tandem, the master agent students talk to. F2: confirmation cards under its answers. */
export interface TandemStep {
  tool: string;
  title: string;
  detail: string;
  status: "OK" | "ERROR" | "DENIED" | "PENDING_CONFIRMATION";
  summary: string;
}

export interface ActionCard {
  kind: string;
  headline: string;
  fields: { label: string; value: string }[];
  effects: string[];
  warnings: string[];
  link: string | null;
}

/** A confirmation card. Only its buttons change data; nothing typed in the chat does. */
export interface AgentAction {
  id: number;
  tool: string;
  card: ActionCard;
  status: "PENDING" | "DONE" | "FAILED" | "CANCELLED" | "EXPIRED" | "SUPERSEDED" | "UNDONE";
  statusLabel: string;
  message: string | null;
  expiresAt: string;
  canConfirm: boolean;
  canUndo: boolean;
}

export interface ActionOutcome {
  action: AgentAction;
  ok: boolean;
  message: string;
}

export interface TandemMessage {
  id: number;
  role: "USER" | "ASSISTANT";
  content: string;
  steps: TandemStep[];
  notices: string[];
  degraded: boolean;
  createdAt: string;
  actions: AgentAction[];
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
  status: "running" | "ok" | "pending" | "error" | "denied";
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

export const agentActionApi = {
  confirm: (id: number) => api.post<ActionOutcome>(`/api/agent-actions/${id}/confirm`),
  cancel: (id: number) => api.post<ActionOutcome>(`/api/agent-actions/${id}/cancel`),
  undo: (id: number) => api.post<ActionOutcome>(`/api/agent-actions/${id}/undo`)
};
