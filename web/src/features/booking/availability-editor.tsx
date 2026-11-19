"use client";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { ErrorBox, PageTitle, useAction } from "@/components/common";
import { block, describeBlocks, norm, TimeGrid, toMin, type Block } from "@/components/time-grid";
import { useLoad } from "@/hooks/use-load";
import { api } from "@/lib/api";
import type { Slot } from "@/types/api";

/** Adds a window, merging it with windows of the same day it touches or overlaps. */
function addWindow(windows: Block[], w: Block) {
  let from = toMin(w.startTime);
  let to = toMin(w.endTime);
  const rest = windows.filter((x) => {
    const touches = x.dayOfWeek === w.dayOfWeek && toMin(x.startTime) <= to && from <= toMin(x.endTime);
    if (touches) { from = Math.min(from, toMin(x.startTime)); to = Math.max(to, toMin(x.endTime)); }
    return !touches;
  });
  return [...rest, block(w.dayOfWeek, from, to)];
}

/**
 * Tutor drags on the calendar to mark free time (at least 2h, any length inside 08:00-22:00) and saves once.
 * shortcut: drawing needs a pointer; keyboard users can only remove windows. Add a time-input form if that is needed.
 */
export function AvailabilityEditor() {
  const { data, error, reload } = useLoad<Slot[]>("/v1/bookings/availability/me");
  const [windows, setWindows] = useState<Block[]>([]);
  const [saved, setSaved] = useState(false);
  const save = useAction();
  const original = data?.map(norm) ?? [];
  const dirty = data !== null && describeBlocks(windows) !== describeBlocks(original);

  useEffect(() => { if (data) setWindows(data.map(norm)); }, [data]);
  const change = (next: Block[]) => { setWindows(next); setSaved(false); };

  return (
    <>
      <PageTitle title="Lịch rảnh hằng tuần" description="Nhấn giữ và kéo trên lịch để tô khoảng rảnh (ít nhất 2 tiếng). Vẽ đè lên khoảng cũ để nối dài, bấm vào khoảng để xóa." />
      <Card>
        <CardContent className="grid gap-4 pt-6">
          <ErrorBox error={error ?? save.error} />
          <TimeGrid label="Lịch rảnh hằng tuần" blocks={windows} draw={(w) => change(addWindow(windows, w))}
            onBlockClick={(w) => change(windows.filter((x) => x !== w))} blockHint="bấm để xóa" />
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
            Xóa khoảng rảnh không ảnh hưởng buổi đã giữ hoặc đã chốt.
          </p>
        </CardContent>
      </Card>
    </>
  );
}
