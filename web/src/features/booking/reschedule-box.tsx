"use client";
import { useState } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Empty, ErrorBox, ReasonAction, useAction } from "@/components/common";
import { addDays, addWindow, block, DAY_END, DAY_START, isoToday, norm, SESSION, STEP, TimeGrid, toMin, type Block } from "@/components/time-grid";
import { DAY_LABEL } from "@/config/labels";
import { useLoad } from "@/hooks/use-load";
import { api, fmtDate } from "@/lib/api";
import type { Session, WeekWindow } from "@/types/api";

const mondayOf = (iso: string) => addDays(iso, -((new Date(`${iso}T00:00:00Z`).getUTCDay() + 6) % 7));
/**
 * What is already past in the week starting `week` (a Monday), Vietnam time: earlier dates are disabled; today keeps only
 * the time from now (rounded up to the grid step), or is disabled too once a 2h session no longer fits before 22:00.
 */
function pastLimits(week: string) {
  const today = isoToday();
  const vn = new Date(Date.now() + 7 * 3600_000);
  const minute = Math.ceil((vn.getUTCHours() * 60 + vn.getUTCMinutes()) / STEP) * STEP;
  const day = vn.getUTCDay() || 7;
  const todayInWeek = addDays(week, day - 1) === today;
  const tooLate = minute > DAY_END - SESSION;
  return {
    disabledDays: [1, 2, 3, 4, 5, 6, 7].filter((d) => addDays(week, d - 1) < today || (todayInWeek && tooLate && d === day)),
    pastUntil: todayInWeek && !tooLate && minute > DAY_START ? { dayOfWeek: day, minute } : undefined,
  };
}

function WeekNav({ week, prevDisabled, nextDisabled, onGo }: { week: string; prevDisabled: boolean; nextDisabled?: boolean; onGo: (days: number) => void }) {
  return (
    <div className="flex items-center gap-2">
      <Button type="button" size="icon" variant="outline" aria-label="Tuần trước" disabled={prevDisabled} onClick={() => onGo(-7)}><ChevronLeft /></Button>
      <span className="text-sm font-medium">{fmtDate(week)} – {fmtDate(addDays(week, 6))}</span>
      <Button type="button" size="icon" variant="outline" aria-label="Tuần sau" disabled={nextDisabled} onClick={() => onGo(7)}><ChevronRight /></Button>
    </div>
  );
}

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
      <WeekNav week={week} prevDisabled={week <= mondayOf(minDate)} nextDisabled={!!maxDate && addDays(week, 7) > maxDate} onGo={go} />
      {data?.length === 0 ? <Empty>Không còn ca trống trong tuần này.</Empty> : (
        <TimeGrid label="Ca có thể chọn" zones={free} {...pastLimits(week)} blocks={picked ? [picked] : []} place={place} onPlace={setPicked}
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

/** Learner picks one Monday-Sunday week and draws the hours they can attend in it, for the tutor to pick one slot from. */
function OfferForm({ session: s, onDone }: { session: Session; onDone: () => void }) {
  const today = isoToday();
  const [week, setWeek] = useState(mondayOf(today));
  const [windows, setWindows] = useState<Block[]>([]);
  const { pending, error, run } = useAction();
  const go = (days: number) => { setWeek(addDays(week, days)); setWindows([]); };

  return (
    <form className="grid gap-3" onSubmit={(e) => {
      e.preventDefault();
      const fromDate = week < today ? today : week;
      void run(async () => {
        await api(`/v1/bookings/sessions/${s.id}/reschedule/offer`, { method: "POST", body: { fromDate, toDate: addDays(week, 6), windows } });
        onDone();
      });
    }}>
      <WeekNav week={week} prevDisabled={week <= mondayOf(today)} onGo={go} />
      <p className="text-sm">Nhấn giữ và kéo để tô giờ bạn học được trong tuần này (ít nhất 2 tiếng), bấm vào khoảng để xóa.</p>
      <TimeGrid label="Giờ bạn có thể học" blocks={windows} {...pastLimits(week)} draw={(w) => setWindows(addWindow(windows, w))}
        onBlockClick={(w) => setWindows(windows.filter((x) => x !== w))} blockHint="bấm để xóa" />
      <ErrorBox error={error} />
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" type="submit" className="w-fit" disabled={!windows.length || pending}>{pending ? "Đang gửi…" : "Gửi cho gia sư"}</Button>
        {!windows.length && <span className="text-xs font-medium text-primary">Tô ít nhất 1 khoảng giờ trên lịch để gửi.</span>}
      </div>
    </form>
  );
}

/**
 * Moves a CANCEL_REPORTED session. Learner: pick a free slot of the tutor, or send "can attend" windows.
 * Tutor: pick one slot inside the learner's windows, or decline with a reason.
 */
export function RescheduleBox({ session: s, role, onDone }: { session: Session; role: "LEARNER" | "TUTOR"; onDone: () => void }) {
  const [tab, setTab] = useState<"pick" | "offer" | null>(null);
  const [sent, setSent] = useState(false);
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
        <p role="status" className={sent ? "rounded-md border border-primary/40 bg-accent/60 p-2 text-sm font-medium" : "text-sm"}>
          {sent ? "✓ Đã gửi cho gia sư. " : ""}Lịch có thể học {fmtDate(s.offerFrom)} – {fmtDate(s.offerTo)} đang chờ gia sư chọn ca. Gửi lại sẽ thay lịch cũ.
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        <Button size="sm" variant={tab === "pick" ? "default" : "outline"} aria-pressed={tab === "pick"} onClick={() => { setTab(tab === "pick" ? null : "pick"); setSent(false); }}>
          Chọn ca trống của gia sư
        </Button>
        <Button size="sm" variant={tab === "offer" ? "default" : "outline"} aria-pressed={tab === "offer"} onClick={() => { setTab(tab === "offer" ? null : "offer"); setSent(false); }}>
          {s.offerFrom ? "Gửi lại lịch có thể học" : "Không có ca phù hợp? Gửi lịch có thể học"}
        </Button>
      </div>
      {tab === "pick" && <PickWeek session={s} minDate={today} onDone={onDone} />}
      {tab === "offer" && <OfferForm session={s} onDone={() => { setTab(null); setSent(true); onDone(); }} />}
    </div>
  );
}
