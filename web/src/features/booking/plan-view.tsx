"use client";
import Link from "next/link";
import { useState } from "react";
import { Plus, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Empty, ErrorBox, NativeSelect, ReasonAction, StatusBadge, useAction } from "@/components/common";
import { DAY_LABEL, MODE_LABEL, PLAN_STATUS } from "@/config/labels";
import { useLoad } from "@/hooks/use-load";
import { api, fmtDate, fmtDateTime, fmtTime } from "@/lib/api";
import { SessionCard } from "@/features/booking/session-card";
import { useCatalog } from "@/features/tutors/catalog";
import type { PlanDetail, Slot } from "@/types/api";

type Role = "LEARNER" | "TUTOR";

export function PlanView({ id, role }: { id: string; role: Role }) {
  const { data, error, reload } = useLoad<PlanDetail>(`/v1/bookings/plans/${id}`);
  const { subject, level } = useCatalog();
  const action = useAction();
  const base = role === "TUTOR" ? "/tutor" : "/learner";

  if (error) return <ErrorBox error={error} />;
  if (!data) return <p className="text-muted-foreground">Đang tải…</p>;
  const { plan, sessions } = data;
  const post = (path: string, body?: unknown) => action.run(async () => {
    await api(`/v1/bookings/plans/${id}/${path}`, { method: "POST", body });
    await reload();
  });

  return (
    <div className="grid gap-6">
      <div>
        <Link href={base} className="text-sm text-muted-foreground hover:text-primary">← Tổng quan</Link>
        <div className="mt-2 flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-semibold">{subject(plan.subjectCode)} {level(plan.levelCode)}</h1>
          <StatusBadge value={PLAN_STATUS[plan.status]} />
        </div>
      </div>

      <div className="grid gap-6 lg:grid-cols-[1fr_360px]">
        <Card>
          <CardHeader><CardTitle>Các buổi học ({sessions.length})</CardTitle></CardHeader>
          <CardContent>
            {sessions.length ? (
              <ul className="grid gap-2">{sessions.map((s) => <SessionCard key={s.id} session={s} role={role} onChange={reload} />)}</ul>
            ) : <Empty>{plan.status === "REQUESTED" ? "Gia sư chưa lập kế hoạch." : "Không có buổi nào."}</Empty>}
          </CardContent>
        </Card>

        <div className="grid content-start gap-4">
          <Card>
            <CardHeader><CardTitle>Thông tin</CardTitle></CardHeader>
            <CardContent className="grid gap-1.5 text-sm">
              <p><span className="font-medium">Hình thức:</span> {MODE_LABEL[plan.mode]}</p>
              {plan.note && <p><span className="font-medium">Nhu cầu:</span> {plan.note}</p>}
              {plan.startDate && <p><span className="font-medium">Thời gian:</span> {fmtDate(plan.startDate)} → {fmtDate(plan.endDate)}</p>}
              {plan.status === "PROPOSED" && plan.holdExpiresAt && (
                <p className="font-medium text-primary">Giữ lịch đến {fmtDateTime(plan.holdExpiresAt)}</p>
              )}
              {plan.paymentStatus === "PAYMENT_PENDING_MOCK" && <p className="text-muted-foreground">Thanh toán: mô phỏng, tích hợp ở mục 4.</p>}
              {plan.rejectReason && <p><span className="font-medium">Lý do từ chối:</span> {plan.rejectReason}</p>}
            </CardContent>
          </Card>

          <ErrorBox error={action.error} />
          {role === "LEARNER" && plan.status === "PROPOSED" && (
            <Card className="border-primary/40">
              <CardContent className="grid gap-3 pt-6 text-sm">
                <p>Kiểm tra {sessions.length} buổi bên cạnh. Xác nhận để chốt toàn bộ lịch.</p>
                <Button disabled={action.pending} onClick={() => post("confirm")}>Xác nhận kế hoạch</Button>
              </CardContent>
            </Card>
          )}
          {role === "LEARNER" && (plan.status === "REQUESTED" || plan.status === "PROPOSED") && (
            <Button variant="outline" disabled={action.pending} onClick={() => post("cancel")}>Hủy yêu cầu</Button>
          )}
          {role === "LEARNER" && plan.status === "COMPLETED" && <ReviewForm planId={plan.id} closedAt={plan.closedAt} />}
          {role === "TUTOR" && plan.status === "REQUESTED" && (
            <>
              <ProposeForm planId={plan.id} onDone={reload} />
              <ReasonAction label="Từ chối yêu cầu" variant="destructive" placeholder="Lý do từ chối" onSubmit={(reason) => post("reject", { reason })} />
            </>
          )}
        </div>
      </div>
    </div>
  );
}

type SlotRow = { dayOfWeek: number; startTime: string };

function ProposeForm({ planId, onDone }: { planId: string; onDone: () => void }) {
  const { data: availability } = useLoad<Slot[]>("/v1/bookings/availability/me");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [meetLink, setMeetLink] = useState("");
  const [slots, setSlots] = useState<SlotRow[]>([{ dayOfWeek: 1, startTime: "08:00" }]);
  const { pending, error, run } = useAction();
  const update = (i: number, patch: Partial<SlotRow>) => setSlots(slots.map((s, j) => (i === j ? { ...s, ...patch } : s)));

  return (
    <Card className="border-primary/40">
      <CardHeader><CardTitle>Lập kế hoạch học</CardTitle></CardHeader>
      <CardContent>
        <form className="grid gap-3" onSubmit={(e) => {
          e.preventDefault();
          void run(async () => {
            await api(`/v1/bookings/plans/${planId}/propose`, {
              method: "POST", body: { startDate, endDate: endDate || null, slots, meetLink: meetLink || null },
            });
            onDone();
          });
        }}>
          <div className="grid grid-cols-2 gap-2">
            <div className="grid gap-1.5">
              <Label htmlFor="p-start">Ngày bắt đầu</Label>
              <Input id="p-start" type="date" required value={startDate} onChange={(e) => setStartDate(e.target.value)} />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="p-end">Ngày kết thúc</Label>
              <Input id="p-end" type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)} />
            </div>
          </div>
          <p className="text-xs text-muted-foreground">Bỏ trống ngày kết thúc = 4 tuần. Mỗi ca 120 phút, cách ca khác ít nhất 60 phút.</p>
          <fieldset className="grid gap-2">
            <legend className="mb-1 text-sm font-medium">Ca học hằng tuần</legend>
            {slots.map((s, i) => (
              <div key={i} className="flex gap-2">
                <NativeSelect aria-label="Thứ" value={s.dayOfWeek} onChange={(e) => update(i, { dayOfWeek: Number(e.target.value) })}>
                  {DAY_LABEL.slice(1).map((d, k) => <option key={d} value={k + 1}>{d}</option>)}
                </NativeSelect>
                <Input type="time" aria-label="Giờ bắt đầu" required min="08:00" max="20:00" value={s.startTime}
                  onChange={(e) => update(i, { startTime: e.target.value })} className="w-32" />
                <Button type="button" size="icon" variant="ghost" aria-label="Xóa ca" disabled={slots.length === 1}
                  onClick={() => setSlots(slots.filter((_, j) => j !== i))}><Trash2 /></Button>
              </div>
            ))}
            <Button type="button" size="sm" variant="outline" className="w-fit" disabled={slots.length >= 14}
              onClick={() => setSlots([...slots, { dayOfWeek: 3, startTime: "08:00" }])}><Plus /> Thêm ca</Button>
          </fieldset>
          {availability && availability.length > 0 && (
            <p className="text-xs text-muted-foreground">
              Lịch rảnh của bạn: {availability.map((a) => `${DAY_LABEL[a.dayOfWeek]} ${fmtTime(a.startTime)}–${fmtTime(a.endTime)}`).join("; ")}
            </p>
          )}
          <div className="grid gap-1.5">
            <Label htmlFor="p-link">Link học online (tùy chọn)</Label>
            <Input id="p-link" type="url" pattern="https://.*" placeholder="https://meet.google.com/..." value={meetLink} onChange={(e) => setMeetLink(e.target.value)} />
          </div>
          <ErrorBox error={error} />
          <Button type="submit" disabled={pending}>{pending ? "Đang gửi…" : "Gửi kế hoạch"}</Button>
        </form>
      </CardContent>
    </Card>
  );
}

function ReviewForm({ planId, closedAt }: { planId: string; closedAt: string | null }) {
  const [content, setContent] = useState("");
  const [sent, setSent] = useState(false);
  const { pending, error, run } = useAction();
  if (sent) return <p className="rounded-lg bg-accent p-3 text-sm">Cảm ơn bạn đã gửi nhận xét.</p>;
  return (
    <Card>
      <CardHeader><CardTitle>Nhận xét gia sư</CardTitle></CardHeader>
      <CardContent>
        <form className="grid gap-3" onSubmit={(e) => {
          e.preventDefault();
          void run(async () => { await api("/v1/tutors/reviews", { method: "POST", body: { planId, content } }); setSent(true); });
        }}>
          <Textarea aria-label="Nội dung nhận xét" required minLength={10} maxLength={2000} value={content} onChange={(e) => setContent(e.target.value)}
            placeholder="Chia sẻ trải nghiệm học (10–2000 ký tự). Không sửa được sau khi gửi." />
          {closedAt && <p className="text-xs text-muted-foreground">Kỳ đóng {fmtDate(closedAt)}; được nhận xét trong 14 ngày.</p>}
          <ErrorBox error={error} />
          <Button type="submit" disabled={pending}>Gửi nhận xét</Button>
        </form>
      </CardContent>
    </Card>
  );
}
