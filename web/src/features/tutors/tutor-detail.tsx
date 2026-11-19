"use client";
import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Empty, ErrorBox, NativeSelect, useAction } from "@/components/common";
import { DAY_LABEL, MODE_LABEL } from "@/config/labels";
import { useLoad } from "@/hooks/use-load";
import { api, fmtDate, fmtTime } from "@/lib/api";
import { useCatalog } from "@/features/tutors/catalog";
import type { Role } from "@/lib/session";
import type { PlanDetail, PublicTutor, Review, Slot } from "@/types/api";

export function TutorDetail({ id, role }: { id: string; role: Role | null }) {
  const { data: tutor, error } = useLoad<PublicTutor>(`/v1/tutors/${id}`);
  const { data: slots } = useLoad<Slot[]>(`/v1/bookings/availability/tutors/${id}`);
  const { data: reviews } = useLoad<Review[]>(`/v1/tutors/${id}/reviews`);
  const { subjectLevel, area } = useCatalog();

  if (error) return <ErrorBox error={error} />;
  if (!tutor) return <p className="text-muted-foreground">Đang tải…</p>;

  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_340px]">
      <div className="grid content-start gap-6">
        <div>
          <Link href="/tutors" className="text-sm text-muted-foreground hover:text-primary">← Danh sách gia sư</Link>
          <h1 className="mt-2 text-3xl font-semibold">{tutor.fullName}</h1>
          <div className="mt-2 flex flex-wrap gap-1.5">
            <Badge>Hạng {tutor.rank}</Badge>
            <Badge variant="outline">{MODE_LABEL[tutor.teachingMode]}</Badge>
          </div>
        </div>
        <Card>
          <CardHeader><CardTitle>Giới thiệu</CardTitle></CardHeader>
          <CardContent className="grid gap-3 text-sm">
            <p className="whitespace-pre-line">{tutor.bio || "Chưa có giới thiệu."}</p>
            <p><span className="font-medium">Kinh nghiệm:</span> {tutor.experience || "—"}</p>
            <p><span className="font-medium">Môn/cấp đã duyệt:</span> {tutor.subjects.map(subjectLevel).join(", ")}</p>
            {tutor.areaCodes.length > 0 && <p><span className="font-medium">Khu vực dạy trực tiếp:</span> {tutor.areaCodes.map(area).join("; ")}</p>}
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle>Lịch nhận dạy hằng tuần</CardTitle></CardHeader>
          <CardContent>
            {slots?.length ? (
              <ul className="grid gap-1.5 text-sm">
                {slots.map((s) => (
                  <li key={s.id} className="flex flex-wrap justify-between gap-2 rounded-md bg-muted/50 px-3 py-2">
                    <span className="font-medium">{DAY_LABEL[s.dayOfWeek]} · {fmtTime(s.startTime)}–{fmtTime(s.endTime)}</span>
                    <span className="text-muted-foreground">{MODE_LABEL[s.mode]} · từ {fmtDate(s.effectiveFrom)}{s.effectiveTo ? ` đến ${fmtDate(s.effectiveTo)}` : ""}</span>
                  </li>
                ))}
              </ul>
            ) : <Empty>Gia sư chưa khai lịch rảnh.</Empty>}
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle>Nhận xét ({reviews?.length ?? 0})</CardTitle></CardHeader>
          <CardContent className="grid gap-3">
            {reviews?.length ? reviews.map((r) => (
              <blockquote key={r.id} className="border-l-2 border-primary pl-3 text-sm">
                <p className="whitespace-pre-line">{r.content}</p>
                <footer className="mt-1 text-xs text-muted-foreground">{fmtDate(r.createdAt)}</footer>
              </blockquote>
            )) : <Empty>Chưa có nhận xét.</Empty>}
          </CardContent>
        </Card>
      </div>
      <aside className="lg:sticky lg:top-20 lg:self-start">
        {role === "LEARNER" ? <RequestForm tutor={tutor} /> : (
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

function RequestForm({ tutor }: { tutor: PublicTutor }) {
  const router = useRouter();
  const { subjectLevel } = useCatalog();
  const modes = tutor.teachingMode === "FLEX" ? ["ONLINE", "OFFLINE"] : [tutor.teachingMode];
  const [subject, setSubject] = useState(0);
  const [mode, setMode] = useState(modes[0]);
  const [note, setNote] = useState("");
  const { pending, error, run } = useAction();

  async function submit() {
    const s = tutor.subjects[subject];
    await run(async () => {
      const detail = await api<PlanDetail>("/v1/bookings/plans", {
        method: "POST", body: { tutorId: tutor.accountId, subjectCode: s.subjectCode, levelCode: s.levelCode, mode, note },
      });
      router.push(`/learner/plans/${detail.plan.id}`);
    });
  }

  return (
    <Card className="border-primary/40">
      <CardHeader><CardTitle>Gửi yêu cầu học</CardTitle></CardHeader>
      <CardContent>
        <form className="grid gap-3" onSubmit={(e) => { e.preventDefault(); void submit(); }}>
          <div className="grid gap-1.5">
            <Label htmlFor="req-subject">Môn và cấp học</Label>
            <NativeSelect id="req-subject" value={subject} onChange={(e) => setSubject(Number(e.target.value))}>
              {tutor.subjects.map((s, i) => <option key={i} value={i}>{subjectLevel(s)}</option>)}
            </NativeSelect>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="req-mode">Hình thức</Label>
            <NativeSelect id="req-mode" value={mode} onChange={(e) => setMode(e.target.value as typeof mode)}>
              {modes.map((m) => <option key={m} value={m}>{MODE_LABEL[m as keyof typeof MODE_LABEL]}</option>)}
            </NativeSelect>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="req-note">Nhu cầu, lịch mong muốn</Label>
            <Textarea id="req-note" maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)}
              placeholder="VD: Ôn thi vào 10, muốn học tối thứ 3 và thứ 5" />
          </div>
          <ErrorBox error={error} />
          <Button type="submit" disabled={pending}>{pending ? "Đang gửi…" : "Gửi yêu cầu"}</Button>
          <p className="text-xs text-muted-foreground">Gia sư sẽ lập kế hoạch (các buổi cụ thể). Bạn xác nhận trong 24 giờ để chốt lịch.</p>
        </form>
      </CardContent>
    </Card>
  );
}
