import { exchangeForSession } from "@/lib/auth-route";

export function POST(request: Request) {
  return exchangeForSession(request, "login");
}
