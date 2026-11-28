"use client";
import { useState } from "react";
import { Video } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { ErrorBox, ReasonAction, StatusBadge, useAction } from "@/components/common";
import { PARTY_LABEL, SESSION_STATUS } from "@/config/labels";
import { api, fmtClock, fmtDateTime } from "@/lib/api";
import { RescheduleBox } from "@/features/booking/reschedule-box";
import { CaseBox } from "@/features/disputes/case-box";
import type { Session } from "@/types/api";

type Role = "LEARNER" | "TUTOR";
const MINUTE = 60_000;

/** One session with the actions its current status allows for this role. */
export function SessionCard({ session: s, role, onChange }: { session: Session; role: Role; onChange: () => void }) {
  const { pending, error, run } = useAction();
  const [editLink, setEditLink] = useState(false);
  const [link, setLink] = useState(s.meetLink ?? "");
  const post = (action: string, body?: unknown) => run(async () => {
    await api(`/v1/bookings/sessions/${s.id}/${action}`, { method: "POST", body });
    onChange();
  });
  const [showCase, setShowCase] = useState(false);
  const hasCase = s.status === "DISPUTED" || s.status === "TUTOR_ABSENT_REPORTED" || s.status === "FAILED_TUTOR" || s.disputeReason !== null;
  const [endEarly, setEndEarly] = useState(false);
  const [earlyBy, setEarlyBy] = useState<"LEARNER" | "TUTOR">("LEARNER");
  const [earlyNote, setEarlyNote] = useState("");
  const now = Date.now();
  const startMs = new Date(s.startAt).getTime();
  const endMs = new Date(s.endAt).getTime();
  const canStart = now >= startMs - 15 * MINUTE;
  // Absence of the other party can be reported 15 minutes after the planned start (P11).
  const waited = now >= startMs + 15 * MINUTE;

  return (
    <li className="grid gap-2 rounded-lg border p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-medium">{fmtDateTime(s.startAt)} · đến {fmtClock(s.endAt)}</span>
        <StatusBadge value={SESSION_STATUS[s.status]} />
      </div>
      {s.meetLink && (
        <a href={s.meetLink} target="_blank" rel="noreferrer" className="flex w-fit items-center gap-1.5 text-sm text-primary hover:underline">
          <Video className="size-4" aria-hidden /> Vào lớp học
        </a>
      )}
      {s.startedAt && (
        <p className="text-sm text-muted-foreground">
          Bắt đầu lúc {fmtClock(s.startedAt)}
          {s.lateMinutes && <span className="font-medium text-destructive"> · muộn {s.lateMinutes} phút</span>}
          {s.completedAt && <> · kết thúc {fmtClock(s.completedAt)}</>}
        </p>
      )}
      {s.earlyEndBy && <p className="text-sm text-muted-foreground">Kết thúc sớm ({PARTY_LABEL[s.earlyEndBy]} muốn nghỉ): {s.earlyEndNote}</p>}
      {s.absentNote && <p className="text-sm text-muted-foreground">Báo vắng: {s.absentNote}</p>}
      {s.status === "LEARNER_ABSENT_PENDING" && s.absentDeadline && (
        <p className="text-sm text-muted-foreground">Hạn phản đối: {fmtDateTime(s.absentDeadline)} (quá hạn sẽ ghi học sinh vắng)</p>
      )}
      {s.cancelReason && <p className="text-sm text-muted-foreground">Lý do báo bận/hủy: {s.cancelReason}</p>}
      {s.status === "CANCEL_REPORTED" && s.cancelDeadline && (
        <p className="text-sm text-muted-foreground">Cần xếp lịch mới trước {fmtDateTime(s.cancelDeadline)}, nếu không buổi sẽ bị hủy.</p>
      )}
      {s.disputeReason && <p className="text-sm text-muted-foreground">Lý do phản đối: {s.disputeReason}</p>}
      {s.status === "COMPLETED_PENDING_CONFIRM" && s.confirmDeadline && (
        <p className="text-sm text-muted-foreground">Hạn xác nhận: {fmtDateTime(s.confirmDeadline)} (quá hạn sẽ tự xác nhận)</p>
      )}
      {s.autoConfirmed && <p className="text-xs text-muted-foreground">Tự xác nhận sau 24 giờ.</p>}
      <ErrorBox error={error} />

      <div className="flex flex-wrap gap-2">
        {role === "TUTOR" && s.status === "SCHEDULED" && (
          <Button size="sm" disabled={pending || !canStart} title={canStart ? undefined : "Bấm được từ 15 phút trước giờ học"} onClick={() => post("start")}>
            Bắt đầu buổi học
          </Button>
        )}
        {role === "TUTOR" && s.status === "IN_PROGRESS" && !endEarly && (
          <Button size="sm" disabled={pending} onClick={() => (Date.now() < endMs ? setEndEarly(true) : post("complete"))}>Kết thúc buổi học</Button>
        )}
        {role === "TUTOR" && s.status === "IN_PROGRESS" && waited && (
          <ReasonAction label="Báo học sinh vắng" variant="destructive" placeholder="Đã chờ 15 phút, đã liên hệ thế nào?" onSubmit={(reason) => post("learner-absent", { reason })} />
        )}
        {role === "LEARNER" && s.status === "SCHEDULED" && waited && (
          <ReasonAction label="Báo gia sư không dạy" variant="destructive" placeholder="Gia sư chưa vào lớp, bạn đã liên hệ thế nào?" onSubmit={(reason) => post("tutor-absent", { reason })} />
        )}
        {role === "LEARNER" && s.status === "COMPLETED_PENDING_CONFIRM" && (
          <>
            <Button size="sm" disabled={pending} onClick={() => post("confirm")}>Xác nhận đã học</Button>
            <ReasonAction label="Phản đối" variant="destructive" placeholder="Vì sao bạn phản đối buổi này?" onSubmit={(reason) => post("dispute", { reason })} />
          </>
        )}
        {role === "LEARNER" && s.status === "LEARNER_ABSENT_PENDING" && (
          <>
            <Button size="sm" variant="outline" disabled={pending} onClick={() => post("confirm")}>Đồng ý đã vắng</Button>
            <ReasonAction label="Phản đối" variant="destructive" placeholder="Vì sao bạn không đồng ý bị ghi vắng?" onSubmit={(reason) => post("dispute", { reason })} />
          </>
        )}
        {s.status === "SCHEDULED" && now < startMs && (
          <ReasonAction label="Báo bận / hủy buổi" variant="destructive" placeholder="Lý do (bên kia sẽ được thông báo ngay)"
            onSubmit={(reason) => post("cancel", { reason })} />
        )}
        {role === "TUTOR" && (s.status === "SCHEDULED" || s.status === "HELD") && !editLink && (
          <Button size="sm" variant="ghost" onClick={() => setEditLink(true)}>Đổi link học</Button>
        )}
      </div>
      {endEarly && (
        <form className="grid gap-2 rounded-md border p-3" onSubmit={async (e) => {
          e.preventDefault();
          const ok = await post("complete", { earlyEndBy: earlyBy, earlyEndNote: earlyNote });
          if (ok) setEndEarly(false);
        }}>
          <span className="text-sm font-medium">Kết thúc trước giờ: ai muốn nghỉ sớm?</span>
          <div className="flex gap-2" role="group" aria-label="Ai muốn nghỉ sớm">
            {(["LEARNER", "TUTOR"] as const).map((p) => (
              <Button key={p} type="button" size="sm" variant={earlyBy === p ? "default" : "outline"} aria-pressed={earlyBy === p} onClick={() => setEarlyBy(p)}>
                {PARTY_LABEL[p]}
              </Button>
            ))}
          </div>
          <Textarea aria-label="Lý do kết thúc sớm" required maxLength={1000} placeholder="Lý do, VD: học sinh mệt xin nghỉ sớm"
            value={earlyNote} onChange={(e) => setEarlyNote(e.target.value)} />
          <div className="flex gap-2">
            <Button size="sm" type="submit" disabled={pending || !earlyNote.trim()}>Kết thúc</Button>
            <Button size="sm" type="button" variant="ghost" onClick={() => setEndEarly(false)}>Hủy</Button>
          </div>
        </form>
      )}
      {s.status === "CANCEL_REPORTED" && <RescheduleBox session={s} role={role} onDone={onChange} />}
      {(hasCase || s.absentNote !== null && s.status !== "LEARNER_ABSENT_PENDING" && s.status !== "LEARNER_ABSENT") && (
        <button type="button" className="w-fit text-sm text-primary hover:underline" onClick={() => setShowCase(!showCase)}>
          {showCase ? "Ẩn vụ việc" : "Xem vụ việc / nộp bằng chứng"}
        </button>
      )}
      {showCase && <CaseBox path={`/v1/bookings/sessions/${s.id}/case`} viewer={role} onChange={onChange} />}
      {editLink && (
        <form className="flex flex-wrap gap-2" onSubmit={async (e) => {
          e.preventDefault();
          const ok = await run(async () => { await api(`/v1/bookings/sessions/${s.id}/meet-link`, { method: "PUT", body: { meetLink: link } }); onChange(); });
          if (ok) setEditLink(false);
        }}>
          <Input type="url" aria-label="Link học" required pattern="https://.*" placeholder="https://meet.google.com/..." value={link}
            onChange={(e) => setLink(e.target.value)} className="min-w-64 flex-1" />
          <Button size="sm" type="submit" disabled={pending}>Lưu</Button>
          <Button size="sm" type="button" variant="ghost" onClick={() => setEditLink(false)}>Hủy</Button>
        </form>
      )}
    </li>
  );
}
