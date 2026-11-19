import { NextResponse } from "next/server";
import { GATEWAY_URL, HOME_BY_ROLE, SESSION_COOKIE, type Role } from "@/lib/session";

/**
 * Forwards login/register to identity and keeps the returned JWT in an httpOnly cookie,
 * so page scripts never see the token.
 */
export async function exchangeForSession(request: Request, identityPath: "login" | "register") {
  let upstream: Response;
  try {
    upstream = await fetch(`${GATEWAY_URL}/api/v1/identity/auth/${identityPath}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: await request.text(),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({ code: "GATEWAY_UNAVAILABLE", message: "Không kết nối được máy chủ" }, { status: 503 });
  }
  const data = await upstream.json().catch(() => ({}));
  if (!upstream.ok) return NextResponse.json(data, { status: upstream.status });

  const role = data.role as Role;
  const response = NextResponse.json({ role, home: HOME_BY_ROLE[role] });
  response.cookies.set(SESSION_COOKIE, data.accessToken, {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: data.expiresIn,
  });
  return response;
}
