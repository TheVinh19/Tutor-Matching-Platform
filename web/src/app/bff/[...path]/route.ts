import { cookies } from "next/headers";
import { GATEWAY_URL, SESSION_COOKIE } from "@/lib/session";

/**
 * Backend-for-frontend: browser calls /bff/v1/..., this adds the Bearer token from the httpOnly cookie
 * and forwards to the gateway /api/v1/... . The cookie is SameSite=Lax, so other sites cannot POST with it.
 */
async function forward(request: Request, { params }: { params: Promise<{ path: string[] }> }) {
  const { path } = await params;
  const url = new URL(request.url);
  const target = `${GATEWAY_URL}/api/${path.map(encodeURIComponent).join("/")}${url.search}`;
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  const headers: Record<string, string> = { Accept: "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  const hasBody = !["GET", "HEAD"].includes(request.method);
  if (hasBody) headers["Content-Type"] = request.headers.get("content-type") ?? "application/json";
  try {
    const upstream = await fetch(target, {
      method: request.method,
      headers,
      body: hasBody ? await request.text() : undefined,
      cache: "no-store",
    });
    return new Response(upstream.status === 204 ? null : await upstream.text(), {
      status: upstream.status,
      headers: { "Content-Type": upstream.headers.get("content-type") ?? "application/json" },
    });
  } catch {
    return Response.json({ code: "GATEWAY_UNAVAILABLE", message: "Không kết nối được máy chủ" }, { status: 503 });
  }
}

export { forward as GET, forward as POST, forward as PUT, forward as PATCH, forward as DELETE };
