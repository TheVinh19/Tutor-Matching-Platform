import { cookies } from "next/headers";
import { decodeSession, SESSION_COOKIE } from "@/lib/session";

export async function getSession() {
  return decodeSession((await cookies()).get(SESSION_COOKIE)?.value);
}
