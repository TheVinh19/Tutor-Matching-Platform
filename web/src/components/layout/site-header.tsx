import Link from "next/link";
import { GraduationCap } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { LogoutButton } from "@/components/layout/logout-button";
import { getSession } from "@/lib/server-session";
import type { Role } from "@/lib/session";

const NAV: Record<Role, [string, string][]> = {
  LEARNER: [["/learner", "Tổng quan"], ["/learner/schedule", "Lịch học"], ["/tutors", "Tìm gia sư"], ["/learner/notifications", "Thông báo"]],
  TUTOR: [["/tutor", "Tổng quan"], ["/tutor/profile", "Hồ sơ"], ["/tutor/availability", "Lịch rảnh"], ["/tutor/schedule", "Lịch dạy"], ["/tutor/notifications", "Thông báo"]],
  ADMIN: [["/admin", "Duyệt hồ sơ"], ["/tutors", "Xem gia sư"]],
};
const ROLE_LABEL: Record<Role, string> = { LEARNER: "Học sinh", TUTOR: "Gia sư", ADMIN: "Quản trị" };

export async function SiteHeader() {
  const session = await getSession();
  const links = session ? NAV[session.role] : [["/tutors", "Tìm gia sư"]];
  return (
    <header className="sticky top-0 z-10 border-b bg-background/90 backdrop-blur">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3">
        <Link href="/" className="flex items-center gap-2 font-semibold text-primary">
          <GraduationCap className="size-6" aria-hidden /> Gia Sư Cam
        </Link>
        <nav aria-label="Điều hướng chính" className="flex flex-1 flex-wrap gap-1 text-sm">
          {links.map(([href, label]) => (
            <Link key={href} href={href} className="rounded-md px-2.5 py-1.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground">
              {label}
            </Link>
          ))}
        </nav>
        {session ? (
          <div className="flex items-center gap-3 text-sm">
            <span className="hidden text-muted-foreground sm:inline">{session.email} · {ROLE_LABEL[session.role]}</span>
            <LogoutButton />
          </div>
        ) : (
          <Link href="/login" className={buttonVariants({ size: "sm" })}>Đăng nhập</Link>
        )}
      </div>
    </header>
  );
}
