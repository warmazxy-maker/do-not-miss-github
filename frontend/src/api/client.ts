import type { AuthSession } from "@/types/auth";

export const AUTH_STORAGE_KEY = "do-not-miss-auth-session";
export const AUTH_UNAUTHORIZED_EVENT = "do-not-miss:unauthorized";

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");

export class ApiError extends Error {
  readonly status: number;
  readonly details: unknown;

  constructor(message: string, status: number, details?: unknown) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.details = details;
  }
}

interface RequestOptions extends Omit<RequestInit, "body"> {
  body?: unknown;
  auth?: boolean;
}

function readStoredToken(): string {
  const raw = localStorage.getItem(AUTH_STORAGE_KEY);
  if (!raw) {
    return "";
  }

  try {
    return (JSON.parse(raw) as AuthSession).token ?? "";
  } catch {
    localStorage.removeItem(AUTH_STORAGE_KEY);
    return "";
  }
}

async function parseResponse<T>(response: Response): Promise<T> {
  if (response.status === 204) {
    return undefined as T;
  }

  const contentType = response.headers.get("content-type") ?? "";
  const body: unknown = contentType.includes("application/json")
    ? await response.json()
    : await response.text();

  if (!response.ok) {
    const message =
      typeof body === "object" && body !== null && "message" in body
        ? String(body.message)
        : typeof body === "string" && body.trim()
          ? body
          : `请求失败（HTTP ${response.status}）`;

    if (response.status === 401) {
      localStorage.removeItem(AUTH_STORAGE_KEY);
      window.dispatchEvent(new Event(AUTH_UNAUTHORIZED_EVENT));
    }
    throw new ApiError(message, response.status, body);
  }

  return body as T;
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers(options.headers);
  const method = options.method ?? "GET";
  const token = options.auth === false ? "" : readStoredToken();

  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  if (options.body !== undefined && !(options.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(`${apiBaseUrl}${path}`, {
    ...options,
    method,
    headers,
    body:
      options.body === undefined
        ? undefined
        : options.body instanceof FormData
          ? options.body
          : JSON.stringify(options.body)
  });

  return parseResponse<T>(response);
}

/**
 * F1: POST that answers with server-sent events. Errors before the stream starts (401, 429, 400)
 * throw like any request; afterwards each event is handed to {@code onEvent} as it arrives.
 */
export async function apiStream(
  path: string,
  body: unknown,
  onEvent: (name: string, data: unknown) => void
): Promise<void> {
  const headers = new Headers({ "Content-Type": "application/json", Accept: "text/event-stream" });
  const token = readStoredToken();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const response = await fetch(`${apiBaseUrl}${path}`, { method: "POST", headers, body: JSON.stringify(body) });
  if (!response.ok || !response.body) {
    await parseResponse<unknown>(response);
    throw new ApiError(`请求失败（HTTP ${response.status}）`, response.status);
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  const flush = (block: string) => {
    let name = "message";
    const data: string[] = [];
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith("event:")) name = line.slice(6).trim();
      else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
    }
    if (!data.length) return;
    const text = data.join("\n");
    let parsed: unknown = text;
    try { parsed = JSON.parse(text); } catch { /* plain text event */ }
    onEvent(name, parsed);
  };
  const boundary = /\r?\n\r?\n/;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let match: RegExpExecArray | null;
    while ((match = boundary.exec(buffer)) !== null) {
      flush(buffer.slice(0, match.index));
      buffer = buffer.slice(match.index + match[0].length);
    }
  }
  if (buffer.trim()) flush(buffer);
}

export const api = {
  get<T>(path: string, options?: RequestOptions) {
    return apiRequest<T>(path, { ...options, method: "GET" });
  },
  post<T>(path: string, body?: unknown, options?: RequestOptions) {
    return apiRequest<T>(path, { ...options, method: "POST", body });
  },
  put<T>(path: string, body?: unknown, options?: RequestOptions) {
    return apiRequest<T>(path, { ...options, method: "PUT", body });
  },
  delete<T>(path: string, options?: RequestOptions) {
    return apiRequest<T>(path, { ...options, method: "DELETE" });
  }
};
