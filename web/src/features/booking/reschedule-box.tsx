"use client";
import { useState } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Empty, ErrorBox, ReasonAction, useAction } from "@/components/common";
import { addDays, addWindow, block, isoToday, norm, SESSION, TimeGrid, toMin, type Block } from "@/components/time-grid";
import { DAY_LABEL } from "@/config/labels";
import { useLoad } from "@/hooks/use-load";
import { api, fmtDate } from "@/lib/api";
import type { Session, WeekWindow } from "@/types/api";

const mondayOf = (iso: string) => addDays(iso, -((new Date(`${iso}T00:00:00Z`).getUTCDay() + 6) % 7));
const MAX_OFFER_DAYS = 56; // booking-service RescheduleController.MAX_OFFER_DAYS

/**
 * One Monday-Sunday week of slots the caller may pick for a CANCEL_REPORTED session (the server drops past and blocked time).
 * Learner: the tutor's free time. Tutor: inside the learner's offer, limited to its date range.
 */
function PickWeek({ session: s, minDate, maxDate, onDone }: { session: Session; minDate: string; maxDate?: string; onDone: () => void }) {
  const [week, setWeek] = useState(mondayOf(minDate));
  const [picked, setPicked] = useState<Block | null>(null);
  const { data } = useLoad<WeekWindow[]>(`/v1/bookings/sessions/${s.id}/reschedule/free?fromDate=${week}`);
  const { pending, error, run } = useAction();
  const free = data?.map(norm) ?? [];
  const date = picked && addDays(week, picked.dayOfWeek - 1);
  const go = (days: number) => { setWeek(addDays(week, days)); setPicked(null); };

  /** A 2h session at the clicked time, pulled up if it would run past the end of the free stretch. */
  const place = (day: number, minute: number) => {
    const zone = free.find((z) => z.dayOfWeek === day && toMin(z.startTime) <= minute && minute < toMin(z.endTime));
    if (!zone) return null;
    const start = Math.max(toMin(zone.startTime), Math.min(minute, toMin(zone.endTime) - SESSION));
    return block(day, start, start + SESSION);
  };

  return (
    <div className="grid gap-2">
      <div className="flex items-center gap-2">
        <Button size="icon" variant="outline" aria-label="Tuần trước" disabled={week <= mondayOf(minDate)} onClick={() => go(-7)}><ChevronLeft /></Button>
        <span className="text-sm font-medium">{fmtDate(week)} – {fmtDate(addDays(week, 6))}</span>
        <Button size="icon" variant="outline" aria-label="Tuần sau" disabled={!!maxDate && addDays(week, 7) > maxDate} onClick={() => go(7)}><ChevronRight /></Button>
      </div>
      {data?.length === 0 ? <Empty>Không còn ca trống trong tuần này.</Empty> : (
        <TimeGrid label="Ca có thể chọn" zones={free} blocks={picked ? [picked] : []} place={place} onPlace={setPicked}
          onBlockClick={() => setPicked(null)} blockHint="bấm để bỏ" />
      )}
      <ErrorBox error={error} />
      <Button size="sm" className="w-fit" disabled={!picked || pending} onClick={() => run(async () => {
        await api(`/v1/bookings/sessions/${s.id}/reschedule`, { method: "POST", body: { startAt: `${date}T${picked!.startTime}:00+07:00` } });
        onDone();
      })}>
        {picked ? `Dời sang ${DAY_LABEL[picked.dayOfWeek]} ${fmtDate(date)}, ${picked.startTime}–${picked.endTime}` : "Chọn 1 ca trên lịch"}
      </Button>
    </div>
  );
}

/** Learner draws the weekly windows they can attend and the date range, for the tutor to pick from. */
function OfferForm({ session: s, onDone }: { session: Session; onDone: () => void }) {
  const today = isoToday();
  const [from, setFrom] = useState(today);
  const [to, setTo] = useState(addDays(today, 13));
  const [windows, setWindows] = useState<Block[]>([]);
  const { pending, error, run } = useAction();
  const validRange = from >= today && to >= from && to <= addDays(from, MAX_OFFER_DAYS);

  return (
    <form className="grid gap-3" onSubmit={(e) => {
      e.preventDefault();
      void run(async () => { await api(`/v1/bookings/sessions/${s.id}/reschedule/offer`, { method: "POST", body: { fromDate: from, toDate: to, windows } }); onDone(); });
    }}>
      <div className="flex flex-wrap gap-3">
        <div className="grid gap-1.5">
          <Label htmlFor={`offer-from-${s.id}`}>Từ ngày</Label>
          <Input id={`offer-from-${s.id}`} type="date" required min={today} value={from} onChange={(e) => setFrom(e.target.value)} />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor={`offer-to-${s.id}`}>Đến ngày</Label>
          <Input id={`offer-to-${s.id}`} type="date" required min={from} max={addDays(from, MAX_OFFER_DAYS)} value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
      </div>
      <p className="text-sm">Nhấn giữ và kéo để tô giờ bạn học được (ít nhất 2 tiếng), bấm vào khoảng để xóa. Áp dụng cho mọi tuần trong khoảng ngày trên.</p>
      <TimeGrid label="Giờ bạn có thể học" blocks={windows} draw={(w) => setWindows(addWindow(windows, w))}
        onBlockClick={(w) => setWindows(windows.filter((x) => x !== w))} blockHint="bấm để xóa" />
      <ErrorBox error={error} />
      <Button size="sm" type="submit" className="w-fit" disabled={!windows.length || !validRange || pending}>Gửi cho gia sư</Button>
    </form>
  );
}

/**
 * Moves a CANCEL_REPORTED session. Learner: pick a free slot of the tutor, or send "can attend" windows.
 * Tutor: pick one slot inside the learner's windows, or decline with a reason.
 */
export function RescheduleBox({ session: s, role, onDone }: { session: Session; role: "LEARNER" | "TUTOR"; onDone: () => void }) {
  const [tab, setTab] = useState<"pick" | "offer" | null>(null);
  const { error, run } = useAction();
  const today = isoToday();

  if (role === "TUTOR") {
    if (!s.offerFrom || !s.offerTo) return <p className="text-sm text-muted-foreground">Chờ học viên chọn ca mới hoặc gửi lịch có thể học.</p>;
    return (
      <div className="grid gap-2 rounded-md bg-muted/40 p-2">
        <p className="text-sm">Học viên gửi lịch có thể học từ <b>{fmtDate(s.offerFrom)}</b> đến <b>{fmtDate(s.offerTo)}</b>. Chọn 1 ca để dời buổi:</p>
        <PickWeek session={s} minDate={s.offerFrom > today ? s.offerFrom : today} maxDate={s.offerTo} onDone={onDone} />
        <ErrorBox error={error} />
        <ReasonAction label="Không sắp xếp được" placeholder="Lý do (học viên sẽ gửi lại lịch khác)"
          onSubmit={(reason) => run(async () => { await api(`/v1/bookings/sessions/${s.id}/reschedule/decline`, { method: "POST", body: { reason } }); onDone(); })} />
      </div>
    );
  }

  return (
    <div className="grid gap-2">
      {s.offerFrom && (
        <p className="text-sm">Đã gửi lịch có thể học {fmtDate(s.offerFrom)} – {fmtDate(s.offerTo)}, chờ gia sư chọn ca. Gửi lại sẽ thay lịch cũ.</p>
      )}
      <div className="flex flex-wrap gap-2">
        <Button size="sm" variant={tab === "pick" ? "default" : "outline"} aria-pressed={tab === "pick"} onClick={() => setTab(tab === "pick" ? null : "pick")}>
          Chọn ca trống của gia sư
        </Button>
        <Button size="sm" variant={tab === "offer" ? "default" : "outline"} aria-pressed={tab === "offer"} onClick={() => setTab(tab === "offer" ? null : "offer")}>
          {s.offerFrom ? "Gửi lại lịch có thể học" : "Không có ca phù hợp? Gửi lịch có thể học"}
        </Button>
      </div>
      {tab === "pick" && <PickWeek session={s} minDate={today} onDone={onDone} />}
      {tab === "offer" && <OfferForm session={s} onDone={onDone} />}
    </div>
  );
}
