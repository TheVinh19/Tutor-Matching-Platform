"use client";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { ErrorBox, PageTitle, useAction } from "@/components/common";
import { addDays, addWindow, block, describeBlocks, inside, isoToday, norm, sameBlock, TimeGrid, toMin, type Block } from "@/components/time-grid";
import { useLoad } from "@/hooks/use-load";
import { api } from "@/lib/api";
import type { Session, Slot } from "@/types/api";

const ACTIVE: Session["status"][] = ["HELD", "SCHEDULED", "CANCEL_REPORTED"];
/** Weekday + Vietnam clock time of a session (UTC+7, no DST). */
function weekly(s: Session): Block {
  const vn = (iso: string) => new Date(new Date(iso).getTime() + 7 * 3600_000);
  const start = vn(s.startAt);
  const end = vn(s.endAt);
  return block(start.getUTCDay() || 7, start.getUTCHours() * 60 + start.getUTCMinutes(), end.getUTCHours() * 60 + end.getUTCMinutes());
}

/**
 * Tutor drags on the calendar to mark free time (at least 2h, any length inside 08:00-22:00) and saves once.
 * Upcoming held/booked sessions are shown locked; a window holding one cannot be removed (the server enforces it too).
 * shortcut: drawing needs a pointer; keyboard users can only remove windows. Add a time-input form if that is needed.
 */
export function AvailabilityEditor() {
  const { data, error, reload } = useLoad<Slot[]>("/v1/bookings/availability/me");
  const [windows, setWindows] = useState<Block[]>([]);
  const [saved, setSaved] = useState(false);
  const save = useAction();
  const [notice, setNotice] = useState<string | null>(null);
  const { data: sessions } = useLoad<Session[]>(`/v1/bookings/sessions/me?from=${isoToday()}&to=${addDays(isoToday(), 190)}`);
  const locked = (sessions ?? []).filter((s) => ACTIVE.includes(s.status) && new Date(s.startAt).getTime() > Date.now()).map(weekly)
    .filter((b, i, all) => all.findIndex((x) => sameBlock(x, b)) === i);
  const original = data?.map(norm) ?? [];
  const dirty = data !== null && describeBlocks(windows) !== describeBlocks(original);

  useEffect(() => { if (data) setWindows(data.map(norm)); }, [data]);
  const change = (next: Block[]) => { setWindows(next); setSaved(false); setNotice(null); };
  const remove = (w: Block) => {
    if (locked.some((l) => inside(w, l))) setNotice(`Khoảng ${w.startTime}–${w.endTime} đang có buổi học đã giữ/chốt, không xóa được.`);
    else change(windows.filter((x) => x !== w));
  };

  return (
    <>
      <PageTitle title="Lịch rảnh hằng tuần" description="Nhấn giữ và kéo trên lịch để tô khoảng rảnh (ít nhất 2 tiếng). Vẽ đè lên khoảng cũ để nối dài, bấm vào khoảng để xóa." />
      <Card>
        <CardContent className="grid gap-4 pt-6">
          <ErrorBox error={error ?? save.error} />
          <TimeGrid label="Lịch rảnh hằng tuần" blocks={windows} locked={locked} draw={(w) => change(addWindow(windows, w))}
            onBlockClick={remove} blockHint="bấm để xóa" />
          {notice && <p role="status" className="text-sm font-medium text-destructive">{notice}</p>}
          <div className="flex flex-wrap items-center gap-2">
            <Button disabled={!dirty || save.pending} onClick={() => save.run(async () => {
              await api("/v1/bookings/availability/me", { method: "PUT", body: { windows } });
              await reload();
              setSaved(true);
            })}>{save.pending ? "Đang lưu…" : "Lưu lịch rảnh"}</Button>
            <Button variant="ghost" disabled={!dirty} onClick={() => setWindows(original)}>Hoàn tác</Button>
            <span className="text-sm text-muted-foreground">
              {saved && !dirty ? "Đã lưu." : `${windows.reduce((h, w) => h + (toMin(w.endTime) - toMin(w.startTime)) / 60, 0)} giờ rảnh/tuần`}
            </span>
          </div>
          <p className="text-xs text-muted-foreground">
            Học sinh đặt các buổi 2 tiếng vào khoảng rảnh. Khi lịch được chốt, buổi học cùng 1 tiếng trước và sau bị khóa; phần còn lại ngắn hơn 2 tiếng cũng không nhận được buổi mới.
            Ô 🔒 là buổi đã giữ/chốt: khoảng rảnh chứa nó không xóa được.
          </p>
        </CardContent>
      </Card>
    </>
  );
}
