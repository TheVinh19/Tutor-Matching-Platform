"use client";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Empty, ErrorBox, PageTitle } from "@/components/common";
import { useLoad } from "@/hooks/use-load";
import { SessionCard } from "@/features/booking/session-card";
import type { Session } from "@/types/api";

/** Sessions from 7 days ago to 60 days ahead, split into "needs action / upcoming" and "past". */
export function ScheduleView({ role }: { role: "LEARNER" | "TUTOR" }) {
  const { data, error, reload } = useLoad<Session[]>("/v1/bookings/sessions/me");
  const now = Date.now();
  const upcoming = data?.filter((s) => new Date(s.endAt).getTime() >= now || s.status === "SCHEDULED" || s.status === "COMPLETED_PENDING_CONFIRM") ?? [];
  const past = data?.filter((s) => !upcoming.includes(s)) ?? [];
  return (
    <>
      <PageTitle title={role === "TUTOR" ? "Lịch dạy" : "Lịch học"} description="Giờ Việt Nam. Mỗi ca 120 phút." />
      <ErrorBox error={error} />
      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader><CardTitle>Sắp tới & cần xử lý</CardTitle></CardHeader>
          <CardContent>
            {upcoming.length ? <ul className="grid gap-2">{upcoming.map((s) => <SessionCard key={s.id} session={s} role={role} onChange={reload} />)}</ul>
              : <Empty>Không có buổi nào sắp tới.</Empty>}
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle>Đã qua (7 ngày)</CardTitle></CardHeader>
          <CardContent>
            {past.length ? <ul className="grid gap-2">{past.map((s) => <SessionCard key={s.id} session={s} role={role} onChange={reload} />)}</ul>
              : <Empty>Chưa có buổi nào.</Empty>}
          </CardContent>
        </Card>
      </div>
    </>
  );
}
