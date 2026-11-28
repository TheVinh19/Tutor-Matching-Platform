"use client";
import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Empty, ErrorBox } from "@/components/common";
import { fmtRate, MODE_LABEL } from "@/config/labels";
import { useLoad } from "@/hooks/use-load";
import { fmtDate } from "@/lib/api";
import { isoToday, norm, TimeGrid } from "@/components/time-grid";
import { useCatalog } from "@/features/tutors/catalog";
import { RequestWizard } from "@/features/booking/request-wizard";
import type { Role } from "@/lib/session";
import type { PublicTutor, Review, WeekWindow } from "@/types/api";

export function TutorDetail({ id, role }: { id: string; role: Role | null }) {
  const { data: tutor, error } = useLoad<PublicTutor>(`/v1/tutors/${id}`);
  const { data: free } = useLoad<WeekWindow[]>(`/v1/bookings/availability/tutors/${id}/free?fromDate=${isoToday()}&weeks=4`);
  const { data: reviews } = useLoad<Review[]>(`/v1/tutors/${id}/reviews`);
  const { subjectLevel, area } = useCatalog();

  if (error) return <ErrorBox error={error} />;
  if (!tutor) return <p className="text-muted-foreground">Đang tải…</p>;

  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_440px]">
      <div className="grid content-start gap-6">
        <div>
          <Link href="/tutors" className="text-sm text-muted-foreground hover:text-primary">← Danh sách gia sư</Link>
          <h1 className="mt-2 text-3xl font-semibold">{tutor.fullName}</h1>
          <div className="mt-2 flex flex-wrap gap-1.5">
            <Badge>Hạng {tutor.rank}</Badge>
            <Badge variant="outline">{MODE_LABEL[tutor.teachingMode]}</Badge>
            {tutor.newcomer && <Badge variant="secondary">Mới tham gia</Badge>}
          </div>
        </div>
        {tutor.videos && tutor.videos.length > 0 && (
          <Card>
            <CardHeader><CardTitle>Video dạy minh họa</CardTitle></CardHeader>
            <CardContent className="grid gap-3 sm:grid-cols-2">
              {tutor.videos.map((url) => {
                const id = /(?:v=|youtu\.be\/)([\w-]{11})/.exec(url)?.[1];
                return id ? (
                  <iframe key={url} className="aspect-video w-full rounded-md" src={`https://www.youtube-nocookie.com/embed/${id}`}
                    title={`Video dạy minh họa của ${tutor.fullName}`} loading="lazy" allowFullScreen
                    allow="accelerometer; encrypted-media; gyroscope; picture-in-picture" />
                ) : <a key={url} href={url} target="_blank" rel="noreferrer" className="text-primary hover:underline">{url}</a>;
              })}
            </CardContent>
          </Card>
        )}
        <Card>
          <CardHeader><CardTitle>Giới thiệu</CardTitle></CardHeader>
          <CardContent className="grid gap-3 text-sm">
            <p className="whitespace-pre-line">{tutor.bio || "Chưa có giới thiệu."}</p>
            <p><span className="font-medium">Kinh nghiệm:</span> {tutor.experience || "—"}</p>
            <p><span className="font-medium">Môn/cấp đã duyệt:</span> {tutor.subjects.map(subjectLevel).join(", ")}</p>
            {tutor.areaCodes.length > 0 && <p><span className="font-medium">Khu vực dạy trực tiếp:</span> {tutor.areaCodes.map(area).join("; ")}</p>}
            <p className="text-muted-foreground">
              {tutor.taughtSessions ?? 0} buổi đã dạy · Tỷ lệ hoàn thành: {fmtRate(tutor.completionRate)}
              {(tutor.tutorFaultFailed ?? 0) > 0 && <> · {tutor.tutorFaultFailed} buổi thất bại do gia sư</>}
            </p>
          </CardContent>
        </Card>
        {/* Learners see the same free time inside the booking form. */}
        {role !== "LEARNER" && (
          <Card>
            <CardHeader><CardTitle>Khoảng còn trống 4 tuần tới</CardTitle></CardHeader>
            <CardContent>
              {free?.length ? <TimeGrid label="Khoảng còn trống của gia sư" zones={free.map(norm)} />
                : <Empty>Gia sư chưa khai lịch rảnh hoặc đã kín lịch.</Empty>}
            </CardContent>
          </Card>
        )}
        <Card>
          <CardHeader><CardTitle>Nhận xét ({reviews?.length ?? 0})</CardTitle></CardHeader>
          <CardContent className="grid gap-3">
            {reviews?.length ? reviews.map((r) => (
              <blockquote key={r.id} className="border-l-2 border-primary pl-3 text-sm">
                <p className="whitespace-pre-line">{r.content}</p>
                <footer className="mt-1 text-xs text-muted-foreground">{fmtDate(r.createdAt)}</footer>
                {r.reply && (
                  <div className="mt-2 rounded-md bg-muted p-2">
                    <p className="text-xs font-medium">Gia sư phản hồi</p>
                    <p className="whitespace-pre-line">{r.reply}</p>
                  </div>
                )}
              </blockquote>
            )) : <Empty>Chưa có nhận xét.</Empty>}
          </CardContent>
        </Card>
      </div>
      <aside className="lg:sticky lg:top-20 lg:self-start">
        {role === "LEARNER" ? <RequestWizard tutor={tutor} /> : (
          <Card>
            <CardContent className="grid gap-3 pt-6 text-sm">
              <p>Đăng nhập bằng tài khoản Học sinh để gửi yêu cầu học.</p>
              {!role && <Link href={`/login?next=/tutors/${id}`} className={buttonVariants()}>Đăng nhập</Link>}
            </CardContent>
          </Card>
        )}
      </aside>
    </div>
  );
}
