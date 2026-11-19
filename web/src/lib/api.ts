/** Error body from the backend: {code,message,details,requestId}. */
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public details?: unknown) {
    super(message);
  }
}

/** Calls the gateway through the /bff proxy. path starts with /v1/... */
export async function api<T = unknown>(path: string, init?: { method?: string; body?: unknown }): Promise<T> {
  const response = await fetch(`/bff${path}`, {
    method: init?.method ?? "GET",
    headers: init?.body !== undefined ? { "Content-Type": "application/json" } : undefined,
    body: init?.body !== undefined ? JSON.stringify(init.body) : undefined,
  });
  if (response.status === 204) return undefined as T;
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    if (response.status === 401 && typeof window !== "undefined" && !window.location.pathname.startsWith("/login")) {
      window.location.href = `/login?next=${encodeURIComponent(window.location.pathname)}`;
    }
    throw new ApiError(response.status, data?.code ?? `HTTP_${response.status}`, data?.message ?? "Có lỗi xảy ra", data?.details);
  }
  return data as T;
}

const dateTime = new Intl.DateTimeFormat("vi-VN", {
  timeZone: "Asia/Ho_Chi_Minh", weekday: "short", day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit",
});
const dateOnly = new Intl.DateTimeFormat("vi-VN", { timeZone: "Asia/Ho_Chi_Minh", day: "2-digit", month: "2-digit", year: "numeric" });

export const fmtDateTime = (iso?: string | null) => (iso ? dateTime.format(new Date(iso)) : "—");
export const fmtDate = (iso?: string | null) => (iso ? dateOnly.format(new Date(iso)) : "—");
export const fmtTime = (t: string) => t.slice(0, 5);
const clock = new Intl.DateTimeFormat("vi-VN", { timeZone: "Asia/Ho_Chi_Minh", hour: "2-digit", minute: "2-digit" });
/** "10:00" from an ISO instant, in Vietnam time. */
export const fmtClock = (iso: string) => clock.format(new Date(iso));
