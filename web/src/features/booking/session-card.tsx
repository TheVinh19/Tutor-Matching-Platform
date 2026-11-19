"use client";
import { useState } from "react";
import { Video } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ErrorBox, ReasonAction, StatusBadge, useAction } from "@/components/common";
import { SESSION_STATUS } from "@/config/labels";
import { api, fmtClock, fmtDateTime } from "@/lib/api";
import type { Session } from "@/types/api";

type Role = "LEARNER" | "TUTOR";

/** One session with the actions its current status allows for this role. */
export function SessionCard({ session: s, role, onChange }: { session: Session; role: Role; onChange: () => void }) {
  const { pending, error, run } = useAction();
  const [editLink, setEditLink] = useState(false);
  const [link, setLink] = useState(s.meetLink ?? "");
  const post = (action: string, body?: unknown) => run(async () => {
    await api(`/v1/bookings/sessions/${s.id}/${action}`, { method: "POST", body });
    onChange();
  });
  const started = new Date(s.startAt).getTime() <= Date.now();

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
      {s.cancelReason && <p className="text-sm text-muted-foreground">Lý do báo bận/hủy: {s.cancelReason}</p>}
      {s.disputeReason && <p className="text-sm text-muted-foreground">Lý do phản đối: {s.disputeReason}</p>}
      {s.status === "COMPLETED_PENDING_CONFIRM" && s.confirmDeadline && (
        <p className="text-sm text-muted-foreground">Hạn xác nhận: {fmtDateTime(s.confirmDeadline)} (quá hạn sẽ tự xác nhận)</p>
      )}
      {s.autoConfirmed && <p className="text-xs text-muted-foreground">Tự xác nhận sau 24 giờ.</p>}
      <ErrorBox error={error} />

      <div className="flex flex-wrap gap-2">
        {role === "TUTOR" && s.status === "SCHEDULED" && (
          <Button size="sm" disabled={pending || !started} title={started ? undefined : "Chỉ báo hoàn thành sau giờ bắt đầu"} onClick={() => post("complete")}>
            Đã dạy xong
          </Button>
        )}
        {role === "LEARNER" && s.status === "COMPLETED_PENDING_CONFIRM" && (
          <>
            <Button size="sm" disabled={pending} onClick={() => post("confirm")}>Xác nhận đã học</Button>
            <ReasonAction label="Phản đối" variant="destructive" placeholder="Vì sao bạn phản đối buổi này?" onSubmit={(reason) => post("dispute", { reason })} />
          </>
        )}
        {s.status === "SCHEDULED" && (
          <ReasonAction label="Báo bận / hủy buổi" variant="destructive" placeholder="Lý do (bên kia sẽ được thông báo ngay)"
            onSubmit={(reason) => post("cancel", { reason })} />
        )}
        {role === "TUTOR" && (s.status === "SCHEDULED" || s.status === "HELD") && !editLink && (
          <Button size="sm" variant="ghost" onClick={() => setEditLink(true)}>Đổi link học</Button>
        )}
      </div>
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
