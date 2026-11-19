"use client";
import { LogOut } from "lucide-react";
import { Button } from "@/components/ui/button";

export function LogoutButton() {
  return (
    <Button size="sm" variant="outline" onClick={async () => {
      await fetch("/auth/logout", { method: "POST" });
      window.location.href = "/login";
    }}>
      <LogOut aria-hidden /> Đăng xuất
    </Button>
  );
}
