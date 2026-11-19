import { NextResponse, type NextRequest } from "next/server";
import { decodeSession, HOME_BY_ROLE, SESSION_COOKIE, type Role } from "@/lib/session";

const AREA_ROLE: Record<string, Role> = { "/learner": "LEARNER", "/tutor": "TUTOR", "/admin": "ADMIN" };

/** UX guard only: sends people to /login or to their own area. Backend services still check every request. */
export function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const session = decodeSession(request.cookies.get(SESSION_COOKIE)?.value);

  if (pathname === "/login" || pathname === "/register") {
    return session ? NextResponse.redirect(new URL(HOME_BY_ROLE[session.role], request.url)) : NextResponse.next();
  }
  const area = Object.keys(AREA_ROLE).find((prefix) => pathname === prefix || pathname.startsWith(prefix + "/"));
  if (!area) return NextResponse.next();
  if (!session) {
    const login = new URL("/login", request.url);
    login.searchParams.set("next", pathname + search);
    return NextResponse.redirect(login);
  }
  if (session.role !== AREA_ROLE[area]) {
    return NextResponse.redirect(new URL(HOME_BY_ROLE[session.role], request.url));
  }
  return NextResponse.next();
}

export const config = { matcher: ["/login", "/register", "/learner/:path*", "/tutor/:path*", "/admin/:path*"] };
