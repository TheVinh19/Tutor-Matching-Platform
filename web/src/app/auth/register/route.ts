import { exchangeForSession } from "@/lib/auth-route";

/** Sign-up logs the new account in right away. */
export function POST(request: Request) {
  return exchangeForSession(request, "register");
}
