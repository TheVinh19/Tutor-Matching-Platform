"use client";
import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ErrorBox, useAction } from "@/components/common";
import { ApiError } from "@/lib/api";

const DEMO_ACCOUNTS = [
  { email: "admin@demo.giasu.vn", label: "Quản trị viên" },
  { email: "tutor1@demo.giasu.vn", label: "Gia sư 1 · đã duyệt" },
  { email: "tutor2@demo.giasu.vn", label: "Gia sư 2 · chờ duyệt" },
  { email: "learner1@demo.giasu.vn", label: "Học sinh 1 · có lịch" },
  { email: "learner2@demo.giasu.vn", label: "Học sinh 2 · trống" },
];

/** Only same-site relative paths, so ?next= cannot redirect to another site. */
function safeNext(next: string | null) {
  return next && next.startsWith("/") && !next.startsWith("//") ? next : null;
}

export function LoginForm({ demoPassword }: { demoPassword: string | null }) {
  const next = safeNext(useSearchParams().get("next"));
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const { pending, error, run } = useAction();

  const login = (e: string, p: string) => run(async () => {
    const response = await fetch("/auth/login", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email: e, password: p }),
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new ApiError(response.status, data.code ?? "LOGIN_FAILED", data.message ?? "Đăng nhập thất bại", data.details);
    // Follow ?next only inside the user's own area (or public pages); otherwise go to the role's home.
    window.location.href = next && (next.startsWith(data.home) || next.startsWith("/tutors")) ? next : data.home;
  });

  return (
    <div className="mx-auto grid max-w-4xl gap-6 md:grid-cols-2">
      <Card>
        <CardHeader>
          <CardTitle className="text-xl">Đăng nhập</CardTitle>
          <CardDescription>Dùng email và mật khẩu của bạn.</CardDescription>
        </CardHeader>
        <CardContent>
          <form className="grid gap-4" onSubmit={(ev) => { ev.preventDefault(); void login(email, password); }}>
            <div className="grid gap-1.5">
              <Label htmlFor="email">Email</Label>
              <Input id="email" type="email" autoComplete="username" required value={email} onChange={(ev) => setEmail(ev.target.value)} />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="password">Mật khẩu</Label>
              <Input id="password" type="password" autoComplete="current-password" required value={password} onChange={(ev) => setPassword(ev.target.value)} />
            </div>
            <ErrorBox error={error} />
            <Button type="submit" size="lg" disabled={pending}>{pending ? "Đang đăng nhập…" : "Đăng nhập"}</Button>
          </form>
        </CardContent>
      </Card>

      <Card className="border-primary/30 bg-accent/40">
        <CardHeader>
          <CardTitle className="text-xl">Tài khoản demo</CardTitle>
          <CardDescription>
            {demoPassword ? "Bấm để đăng nhập nhanh." : "Bấm để điền email, rồi nhập mật khẩu demo (DEMO_PASSWORD)."}
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-2">
          {DEMO_ACCOUNTS.map((a) => (
            <Button key={a.email} variant="outline" className="h-auto justify-between bg-background py-2" disabled={pending}
              onClick={() => (demoPassword ? void login(a.email, demoPassword) : setEmail(a.email))}>
              <span className="font-medium">{a.label}</span>
              <span className="text-xs text-muted-foreground">{a.email}</span>
            </Button>
          ))}
        </CardContent>
      </Card>
    </div>
  );
}
