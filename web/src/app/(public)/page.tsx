import Link from "next/link";
import { CalendarCheck, Search, ShieldCheck } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { getSession } from "@/lib/server-session";

const FEATURES = [
  { icon: Search, title: "Tìm đúng gia sư", text: "Lọc theo môn, cấp học, Hạng, hình thức, địa bàn và lịch rảnh." },
  { icon: CalendarCheck, title: "Đặt lịch rõ ràng", text: "Ca 120 phút, hệ thống tự kiểm tra trùng lịch và giữ chỗ 24 giờ." },
  { icon: ShieldCheck, title: "Hồ sơ đã duyệt", text: "Chỉ gia sư được admin duyệt mới xuất hiện trong tìm kiếm." },
];

export default async function Home() {
  const session = await getSession();
  return (
    <div className="grid gap-12">
      <section className="rounded-2xl bg-gradient-to-br from-accent to-background px-6 py-14 text-center sm:px-12">
        <h1 className="text-3xl font-bold tracking-tight sm:text-5xl">
          Kết nối gia sư <span className="text-primary">đúng nhu cầu</span>
        </h1>
        <p className="mx-auto mt-4 max-w-2xl text-muted-foreground">
          Tìm gia sư theo môn và lịch học, nhận kế hoạch học cụ thể, theo dõi từng buổi và xác nhận khi hoàn thành.
        </p>
        <div className="mt-8 flex flex-wrap justify-center gap-3">
          <Link href="/tutors" className={buttonVariants({ size: "lg" })}>Tìm gia sư</Link>
          {session ? (
            <Link href={`/${session.role.toLowerCase()}`} className={buttonVariants({ size: "lg", variant: "outline" })}>Vào trang của tôi</Link>
          ) : (
            <Link href="/login" className={buttonVariants({ size: "lg", variant: "outline" })}>Đăng nhập</Link>
          )}
        </div>
      </section>
      <section className="grid gap-4 sm:grid-cols-3">
        {FEATURES.map(({ icon: Icon, title, text }) => (
          <div key={title} className="rounded-xl border p-5">
            <Icon className="size-8 text-primary" aria-hidden />
            <h2 className="mt-3 font-semibold">{title}</h2>
            <p className="mt-1 text-sm text-muted-foreground">{text}</p>
          </div>
        ))}
      </section>
    </div>
  );
}
