"use client";
import Link from "next/link";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Empty, ErrorBox, ReasonAction, StatusBadge, useAction } from "@/components/common";
import { addDays, block, clashes, countSessions, describeBlocks, GAP, inside, isoToday, norm, SESSION, TimeGrid, toMin, type Block } from "@/components/time-grid";
import { CLOSED_PLAN, DAY_LABEL, MODE_LABEL, PLAN_STATUS } from "@/config/labels";
import { useLoad } from "@/hooks/use-load";
import { api, fmtDate, fmtDateTime, fmtTime } from "@/lib/api";
import { RequestWizard } from "@/features/booking/request-wizard";
import { SessionCard } from "@/features/booking/session-card";
import { useCatalog } from "@/features/tutors/catalog";
import type { Me, PlanContact, PlanDetail, PublicTutor, Session, WeekWindow } from "@/types/api";

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

      <div className="grid gap-6 lg:grid-cols-[1fr_440px]">
        <Card>
          <CardHeader><CardTitle>Các buổi học ({sessions.length})</CardTitle></CardHeader>
          <CardContent>
            {sessions.length ? (
              <ul className="grid gap-2">{sessions.map((s) => <SessionCard key={s.id} session={s} role={role} onChange={reload} />)}</ul>
            ) : <Empty>{plan.status === "REQUESTED" ? "Gia sư chưa chốt lịch." : "Không có buổi nào."}</Empty>}
          </CardContent>
        </Card>

        <div className="grid content-start gap-4">
          <Card>
            <CardHeader><CardTitle>Thông tin</CardTitle></CardHeader>
            <CardContent className="grid gap-1.5 text-sm">
              <p><span className="font-medium">Hình thức:</span> {MODE_LABEL[plan.mode]}</p>
              {plan.sessionsPerWeek !== null && <p><span className="font-medium">Nhu cầu:</span> {plan.sessionsPerWeek} buổi/tuần · {plan.weeks} tuần</p>}
              {plan.status === "REQUESTED" && data.preferredSlots.length > 0 && (
                <p><span className="font-medium">Ca học sinh chọn:</span> {describeBlocks(data.preferredSlots.map(norm))}</p>
              )}
              {plan.note && <p><span className="font-medium">Lời nhắn:</span> {plan.note}</p>}
              {plan.startDate && <p><span className="font-medium">Thời gian:</span> {fmtDate(plan.startDate)} → {fmtDate(plan.endDate)}</p>}
              {plan.status === "PROPOSED" && plan.holdExpiresAt && (
                <p className="font-medium text-primary">Giữ lịch đến {fmtDateTime(plan.holdExpiresAt)}</p>
              )}
              {plan.paymentStatus === "PAYMENT_PENDING_MOCK" && <p className="text-muted-foreground">Thanh toán: mô phỏng, tích hợp ở mục 4.</p>}
              {plan.rejectReason && <p><span className="font-medium">Lý do từ chối:</span> {plan.rejectReason}</p>}
              {plan.renewedFrom && (
                <p><span className="font-medium">Gia hạn từ:</span> <Link className="text-primary hover:underline" href={`${base}/plans/${plan.renewedFrom}`}>kỳ trước</Link>
                  {plan.renewalQualifiedAt && <span className="text-muted-foreground"> · gia hạn hợp lệ</span>}</p>
              )}
              {plan.status === "HET_KY_CON_NGHIA_VU" && <p className="text-destructive">Còn buổi đang phản đối hoặc báo vắng chờ xử lý; kỳ chưa đóng.</p>}
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
          {(plan.status === "CONFIRMED" || plan.status === "HET_KY_CON_NGHIA_VU" || CLOSED_PLAN.includes(plan.status)) && <ContactCard planId={plan.id} role={role} />}
          {plan.status === "CONFIRMED" && <SlotChangeCard detail={data} role={role} onDone={reload} />}
          {role === "LEARNER" && CLOSED_PLAN.includes(plan.status) && <ReviewForm planId={plan.id} closedAt={plan.closedAt} />}
          {role === "LEARNER" && (plan.status === "CONFIRMED" || CLOSED_PLAN.includes(plan.status)) && <RenewCard detail={data} />}
          {role === "TUTOR" && plan.status === "REQUESTED" && (
            <>
              <ProposeForm detail={data} onDone={reload} />
              <ReasonAction label="Từ chối yêu cầu" variant="destructive" placeholder="Lý do từ chối, VD: các ca này em đã kín" onSubmit={(reason) => post("reject", { reason })} />
            </>
          )}
        </div>
      </div>
    </div>
  );
}

/** KN01: no in-app chat; the parties call or text each other once the plan is confirmed. */
function ContactCard({ planId, role }: { planId: string; role: Role }) {
  const { data: contact, error, reload } = useLoad<PlanContact>(`/v1/bookings/plans/${planId}/contact`);
  const { data: me, reload: reloadMe } = useLoad<Me>("/v1/identity/me");
  const [phone, setPhone] = useState("");
  const save = useAction();
  const other = role === "TUTOR" ? contact?.learnerPhone : contact?.tutorPhone;

  return (
    <Card>
      <CardHeader><CardTitle>Liên hệ</CardTitle></CardHeader>
      <CardContent className="grid gap-3 text-sm">
        <ErrorBox error={error} />
        {contact && (
          <p>
            <span className="font-medium">{role === "TUTOR" ? "SĐT người thuê" : "SĐT gia sư"}:</span>{" "}
            {other ? <a className="text-primary hover:underline" href={`tel:${other}`}>{other}</a> : <span className="text-muted-foreground">chưa cập nhật</span>}
          </p>
        )}
        {me && !me.phone && (
          <form className="grid gap-1.5" onSubmit={(e) => {
            e.preventDefault();
            void save.run(async () => { await api("/v1/identity/me/phone", { method: "PUT", body: { phone } }); await Promise.all([reloadMe(), reload()]); });
          }}>
            <Label htmlFor="my-phone">SĐT của bạn (để bên kia liên hệ)</Label>
            <div className="flex gap-2">
              <Input id="my-phone" inputMode="tel" required pattern="0[0-9]{9}" title="10 chữ số, bắt đầu bằng 0" value={phone} onChange={(e) => setPhone(e.target.value)} />
              <Button type="submit" disabled={save.pending}>Lưu</Button>
            </div>
            <ErrorBox error={save.error} />
          </form>
        )}
        <p className="text-muted-foreground">Nếu có khiếu nại, hãy lưu lại ảnh chụp tin nhắn trao đổi để làm bằng chứng.</p>
      </CardContent>
    </Card>
  );
}

/**
 * Tutor ticks the weekly cells to teach. Grid-flow plans: exactly sessionsPerWeek of the learner's cells, period fixed by the learner.
 * Older free-text plans: any cells inside the tutor's availability, tutor picks the start date (4 weeks).
 */
function ProposeForm({ detail, onDone }: { detail: PlanDetail; onDone: () => void }) {
  const { plan, preferredSlots } = detail;
  const need = plan.sessionsPerWeek;
  const [startDate, setStartDate] = useState(plan.startDate ?? addDays(isoToday(), 1));
  const weeks = plan.weeks ?? 4;
  const { data } = useLoad<WeekWindow[]>(startDate ? `/v1/bookings/availability/tutors/${plan.tutorId}/free?fromDate=${startDate}&weeks=${weeks}&mode=${plan.mode}` : null);
  const [picked, setPicked] = useState<Block[]>([]);
  const [meetLink, setMeetLink] = useState("");
  const { pending, error, run } = useAction();

  const free = data?.map(norm) ?? [];
  // Options: the learner's sessions still inside free time; older plans without them: any 2h inside free time.
  const options = need !== null ? preferredSlots.map(norm).filter((p) => free.some((z) => inside(z, p))) : free;
  // Grid plans: the learner's sessions are the plan, the tutor does not pick.
  const valid = need !== null ? options : picked.filter((p) => options.some((o) => inside(o, p)));
  const place = (day: number, minute: number) => {
    if (need !== null) return null;
    const zone = options.find((o) => o.dayOfWeek === day && toMin(o.startTime) <= minute && minute < toMin(o.endTime));
    if (!zone) return null;
    const start = Math.max(toMin(zone.startTime), Math.min(minute, toMin(zone.endTime) - SESSION));
    const b = block(day, start, start + SESSION);
    // Two sessions of the tutor on one day need the 60-minute gap.
    return valid.some((p) => clashes(p, b, GAP)) ? null : b;
  };
  const ready = need !== null ? valid.length === need : valid.length > 0;
  const endDate = need !== null && plan.endDate ? plan.endDate : addDays(startDate, weeks * 7 - 1);
  const from = startDate > isoToday() ? startDate : isoToday();

  return (
    <Card className="border-primary/40">
      <CardHeader><CardTitle>Chốt lịch học</CardTitle></CardHeader>
      <CardContent>
        <form className="grid gap-3" onSubmit={(e) => {
          e.preventDefault();
          void run(async () => {
            await api(`/v1/bookings/plans/${plan.id}/propose`, {
              method: "POST",
              body: { startDate: need !== null ? null : startDate, slots: valid.map((p) => ({ dayOfWeek: p.dayOfWeek, startTime: p.startTime })), meetLink: meetLink || null },
            });
            onDone();
          });
        }}>
          {need !== null ? (
            <p className="text-sm">Học sinh muốn <span className="font-semibold">{need} buổi/tuần</span>, {weeks} tuần, vào các buổi dưới đây. Gửi kế hoạch để nhận, hoặc từ chối kèm lý do.</p>
          ) : (
            <div className="grid gap-1.5">
              <Label htmlFor="p-start">Ngày bắt đầu (4 tuần)</Label>
              <Input id="p-start" type="date" required min={addDays(isoToday(), 1)} value={startDate} onChange={(e) => setStartDate(e.target.value)} />
            </div>
          )}
          {need !== null && options.length < need && <p className="text-sm text-destructive">Có buổi học sinh chọn đã kín. Hãy từ chối kèm lý do để học sinh chọn lại.</p>}
          <TimeGrid label="Buổi dạy hằng tuần" zones={options} blocks={valid} place={place}
            onPlace={(b) => setPicked([...valid, b])}
            onBlockClick={(b) => { if (need === null) setPicked(valid.filter((p) => p !== b)); }} blockHint={need === null ? "bấm để bỏ" : undefined} />
          <p className={ready ? "text-xs text-muted-foreground" : "text-xs font-medium text-primary"}>
            Đã chọn {valid.length}{need !== null ? `/${need}` : ""} buổi · ≈ {countSessions(valid, from, endDate)} buổi, {fmtDate(from)} → {fmtDate(endDate)}
          </p>
          {plan.mode === "ONLINE" && (
            <div className="grid gap-1.5">
              <Label htmlFor="p-link">Link học online (tùy chọn)</Label>
              <Input id="p-link" type="url" pattern="https://.*" placeholder="https://meet.google.com/..." value={meetLink} onChange={(e) => setMeetLink(e.target.value)} />
            </div>
          )}
          <ErrorBox error={error} />
          <Button type="submit" disabled={!ready || pending}>{pending ? "Đang gửi…" : "Gửi kế hoạch cho học sinh"}</Button>
          <p className="text-xs text-muted-foreground">Các buổi được giữ tối đa 24 giờ chờ học sinh xác nhận.</p>
        </form>
      </CardContent>
    </Card>
  );
}

/** Weekday and "HH:MM" of an instant in Vietnam time. */
function weekCell(iso: string): Block {
  const parts = new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Ho_Chi_Minh", weekday: "short", hour: "2-digit", minute: "2-digit", hourCycle: "h23" })
    .formatToParts(new Date(iso));
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? "";
  const day = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].indexOf(get("weekday")) + 1;
  const start = toMin(`${get("hour")}:${get("minute")}`);
  return block(day, start, start + SESSION);
}

/** The weekly sessions of a period: distinct weekday/time among its last 7 days of sessions (after any slot change). */
function weeklySlots(sessions: Session[]): Block[] {
  const live = sessions.filter((s) => s.status !== "CANCELLED" && s.status !== "RELEASED");
  if (!live.length) return [];
  const last = Math.max(...live.map((s) => new Date(s.startAt).getTime()));
  const cells = live.filter((s) => new Date(s.startAt).getTime() > last - 7 * 86_400_000).map((s) => weekCell(s.startAt));
  return cells.filter((c, i) => cells.findIndex((o) => o.dayOfWeek === c.dayOfWeek && o.startTime === c.startTime) === i);
}

const slotLabel = (day: number, time: string) => `${DAY_LABEL[day]} ${fmtTime(time)}`;

/**
 * DOI05: move one weekly slot for every remaining session from a date on. Either side asks, the other accepts or declines;
 * unanswered requests expire after 24h (or at the first affected session) and the old schedule stays.
 */
function SlotChangeCard({ detail, role, onDone }: { detail: PlanDetail; role: Role; onDone: () => void }) {
  const { plan, sessions, pendingSlotChange: change } = detail;
  const [open, setOpen] = useState(false);
  const [from, setFrom] = useState(0);
  const [effectiveFrom, setEffectiveFrom] = useState(addDays(isoToday(), 1));
  const [target, setTarget] = useState<Block | null>(null);
  const { pending, error, run } = useAction();
  const current = weeklySlots(sessions.filter((s) => s.status === "SCHEDULED"));
  const weeks = plan.endDate ? Math.max(1, Math.ceil((new Date(plan.endDate).getTime() - new Date(effectiveFrom).getTime()) / (7 * 86_400_000)) + 1) : 4;
  const { data } = useLoad<WeekWindow[]>(open ? `/v1/bookings/availability/tutors/${plan.tutorId}/free?fromDate=${effectiveFrom}&weeks=${weeks}&mode=${plan.mode}` : null);
  const free = data?.map(norm) ?? [];
  const act = (path: string, body?: unknown) => run(async () => { await api(`/v1/bookings/slot-changes/${change?.id}/${path}`, { method: "POST", body }); onDone(); });

  if (change) {
    const mine = (change.requestedBy === plan.learnerId ? "LEARNER" : "TUTOR") === role;
    return (
      <Card className="border-primary/40">
        <CardHeader><CardTitle>Đề nghị đổi giờ học</CardTitle></CardHeader>
        <CardContent className="grid gap-3 text-sm">
          <p>{slotLabel(change.fromDay, change.fromTime)} → <span className="font-semibold">{slotLabel(change.toDay, change.toTime)}</span>, từ {fmtDate(change.effectiveFrom)}.</p>
          <p className="text-muted-foreground">Hạn trả lời: {fmtDateTime(change.expiresAt)}. Quá hạn giữ lịch cũ.</p>
          <ErrorBox error={error} />
          {mine ? (
            <Button variant="outline" disabled={pending} onClick={() => act("cancel")}>Hủy đề nghị</Button>
          ) : (
            <div className="flex flex-wrap gap-2">
              <Button disabled={pending} onClick={() => act("accept")}>Đồng ý đổi</Button>
              <ReasonAction label="Từ chối" variant="destructive" placeholder="Lý do từ chối" onSubmit={(reason) => act("decline", { reason })} />
            </div>
          )}
        </CardContent>
      </Card>
    );
  }
  if (!current.length) return null;
  if (!open) return <Button variant="outline" onClick={() => setOpen(true)}>Đổi giờ học</Button>;
  const old = current[Math.min(from, current.length - 1)];
  const place = (day: number, minute: number) => {
    const zone = free.find((z) => z.dayOfWeek === day && toMin(z.startTime) <= minute && minute < toMin(z.endTime));
    if (!zone) return null;
    const start = Math.max(toMin(zone.startTime), Math.min(minute, toMin(zone.endTime) - SESSION));
    return block(day, start, start + SESSION);
  };
  const valid = target && free.some((z) => inside(z, target)) ? target : null;

  return (
    <Card className="border-primary/40">
      <CardHeader><CardTitle>Đổi giờ học</CardTitle></CardHeader>
      <CardContent>
        <form className="grid gap-3" onSubmit={(e) => {
          e.preventDefault();
          if (!valid) return;
          void run(async () => {
            await api(`/v1/bookings/plans/${plan.id}/slot-changes`, {
              method: "POST",
              body: { fromDay: old.dayOfWeek, fromTime: old.startTime, toDay: valid.dayOfWeek, toTime: valid.startTime, effectiveFrom },
            });
            setOpen(false);
            onDone();
          });
        }}>
          <div className="grid gap-1.5" role="group" aria-label="Ca muốn đổi">
            <span className="text-sm font-medium">Ca muốn đổi</span>
            <div className="flex flex-wrap gap-1.5">
              {current.map((c, i) => (
                <Button key={`${c.dayOfWeek}-${c.startTime}`} type="button" size="sm" variant={i === from ? "default" : "outline"} aria-pressed={i === from} onClick={() => setFrom(i)}>
                  {slotLabel(c.dayOfWeek, c.startTime)}
                </Button>
              ))}
            </div>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="sc-from">Áp dụng từ ngày</Label>
            <Input id="sc-from" type="date" required min={addDays(isoToday(), 1)} max={plan.endDate ?? undefined} value={effectiveFrom}
              onChange={(e) => setEffectiveFrom(e.target.value)} />
          </div>
          <TimeGrid label="Chọn giờ mới trong khoảng trống của gia sư" zones={free} blocks={valid ? [valid] : []} place={place}
            onPlace={setTarget} onBlockClick={() => setTarget(null)} blockHint="bấm để bỏ" />
          <ErrorBox error={error} />
          <div className="flex gap-2">
            <Button type="submit" disabled={!valid || pending}>Gửi đề nghị</Button>
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>Đóng</Button>
          </div>
          <p className="text-xs text-muted-foreground">Bên kia cần đồng ý trong 24 giờ. Mọi buổi còn lại của ca này từ ngày áp dụng sẽ chuyển sang giờ mới.</p>
        </form>
      </CardContent>
    </Card>
  );
}

/** P09: renewal is a new request to the same tutor, prefilled with this period's weekly sessions; it holds nothing until confirmed. */
function RenewCard({ detail }: { detail: PlanDetail }) {
  const [open, setOpen] = useState(false);
  const { data: tutor, error } = useLoad<PublicTutor>(open ? `/v1/tutors/${detail.plan.tutorId}` : null);
  if (!open) return <Button variant="outline" onClick={() => setOpen(true)}>Gia hạn kỳ học</Button>;
  if (error) return <ErrorBox error={error} />;
  if (!tutor) return <p className="text-sm text-muted-foreground">Đang tải…</p>;
  return <RequestWizard tutor={tutor} renew={{ plan: detail.plan, slots: weeklySlots(detail.sessions) }} />;
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
