"use client";
import Link from "next/link";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ErrorBox, useAction } from "@/components/common";
import { ApiError } from "@/lib/api";

const ROLES = [
  { value: "LEARNER", label: "Học sinh", hint: "Tìm gia sư và đặt lịch học" },
  { value: "TUTOR", label: "Gia sư", hint: "Khai hồ sơ, chờ duyệt rồi nhận học sinh" },
] as const;

export function RegisterForm() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [role, setRole] = useState<"LEARNER" | "TUTOR">("LEARNER");
  const { pending, error, run } = useAction();

  const submit = () => run(async () => {
    if (password !== confirm) throw new Error("Mật khẩu nhập lại không khớp");
    const response = await fetch("/auth/register", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ username, password, role }),
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new ApiError(response.status, data.code ?? "REGISTER_FAILED", data.message ?? "Đăng ký thất bại", data.details);
    window.location.href = role === "TUTOR" ? "/tutor/profile" : data.home;
  });

  return (
    <Card className="mx-auto max-w-md">
      <CardHeader>
        <CardTitle className="text-xl">Tạo tài khoản</CardTitle>
        <CardDescription>Chỉ cần tên đăng nhập và mật khẩu. Email, số điện thoại có thể bổ sung sau.</CardDescription>
      </CardHeader>
      <CardContent>
        <form className="grid gap-4" onSubmit={(e) => { e.preventDefault(); void submit(); }}>
          <fieldset className="grid gap-2">
            <legend className="mb-1 text-sm font-medium">Bạn là</legend>
            <div className="grid grid-cols-2 gap-2">
              {ROLES.map((r) => (
                <Button key={r.value} type="button" variant={role === r.value ? "default" : "outline"} aria-pressed={role === r.value}
                  className="h-auto flex-col items-start gap-0.5 py-2 text-left whitespace-normal" onClick={() => setRole(r.value)}>
                  <span className="font-semibold">{r.label}</span>
                  <span className="text-xs font-normal opacity-80">{r.hint}</span>
                </Button>
              ))}
            </div>
          </fieldset>
          <div className="grid gap-1.5">
            <Label htmlFor="username">Tên đăng nhập</Label>
            <Input id="username" autoComplete="username" required minLength={3} maxLength={50} pattern="[A-Za-z0-9._]{3,50}"
              title="3–50 ký tự: chữ không dấu, số, dấu chấm hoặc gạch dưới" value={username} onChange={(e) => setUsername(e.target.value)} />
            <p className="text-xs text-muted-foreground">3–50 ký tự: chữ không dấu, số, dấu chấm hoặc gạch dưới.</p>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="password">Mật khẩu</Label>
            <Input id="password" type="password" autoComplete="new-password" required minLength={8} maxLength={128}
              value={password} onChange={(e) => setPassword(e.target.value)} />
            <p className="text-xs text-muted-foreground">Ít nhất 8 ký tự.</p>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="confirm">Nhập lại mật khẩu</Label>
            <Input id="confirm" type="password" autoComplete="new-password" required value={confirm} onChange={(e) => setConfirm(e.target.value)} />
          </div>
          <ErrorBox error={error} />
          <Button type="submit" size="lg" disabled={pending}>{pending ? "Đang tạo…" : "Đăng ký"}</Button>
          <p className="text-center text-sm text-muted-foreground">
            Đã có tài khoản? <Link href="/login" className="font-medium text-primary hover:underline">Đăng nhập</Link>
          </p>
        </form>
      </CardContent>
    </Card>
  );
}
