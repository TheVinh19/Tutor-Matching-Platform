export const SESSION_COOKIE = "giasu_token";
export type Role = "LEARNER" | "TUTOR" | "ADMIN";
export type Session = { accountId: string; role: Role; email: string; token: string };

export const HOME_BY_ROLE: Record<Role, string> = { LEARNER: "/learner", TUTOR: "/tutor", ADMIN: "/admin" };

export const GATEWAY_URL = process.env.API_GATEWAY_URL ?? "http://localhost:8080";

/**
 * Reads the JWT payload WITHOUT verifying the signature: only for routing and display.
 * Every API call is verified by the backend services, so a forged cookie gets nothing but 401.
 */
export function decodeSession(token: string | undefined): Session | null {
  if (!token) return null;
  try {
    const payload = JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
    if (typeof payload.exp !== "number" || payload.exp * 1000 <= Date.now()) return null;
    if (!["LEARNER", "TUTOR", "ADMIN"].includes(payload.role)) return null;
    return { accountId: payload.sub, role: payload.role, email: payload.email, token };
  } catch {
    return null;
  }
}
