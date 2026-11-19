"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Empty, ErrorBox, useAction } from "@/components/common";
import { addDays, block, inside, isoToday, norm, SESSION, TimeGrid, toMin, type Block } from "@/components/time-grid";
import { MODE_LABEL } from "@/config/labels";
import { useLoad } from "@/hooks/use-load";
import { api, fmtDate } from "@/lib/api";
import { useCatalog } from "@/features/tutors/catalog";
import type { Mode, PlanDetail, PublicTutor, WeekWindow } from "@/types/api";

const PER_WEEK = [1, 2, 3, 4, 5, 6, 7];
const WEEKS = [4, 8, 12];

/** Row of toggle buttons; one value at a time. */
function Choice<T extends string | number>({ label, options, value, onChange, render }: {
  label: string; options: T[]; value: T | null; onChange: (v: T) => void; render: (v: T) => string;
}) {
  return (
    <div className="grid gap-1.5" role="group" aria-label={label}>
      <span className="text-sm font-medium">{label}</span>
      <div className="flex flex-wrap gap-1.5">
        {options.map((o) => (
          <Button key={o} type="button" size="sm" variant={o === value ? "default" : "outline"} aria-pressed={o === value} onClick={() => onChange(o)}>
            {render(o)}
          </Button>
        ))}
      </div>
    </div>
  );
}

/**
 * Learner picks subject, sessions per week and period, then drops exactly that many 2h sessions (one per day) onto the tutor's free time.
 * Free time already excludes booked sessions and their 60-minute gaps, so nothing outside it can be picked.
 * The tutor then accepts these sessions as they are or rejects the request.
 */
export function RequestWizard({ tutor }: { tutor: PublicTutor }) {
  const router = useRouter();
  const { subjectLevel } = useCatalog();
  const modes: Mode[] = tutor.teachingMode === "FLEX" ? ["ONLINE", "OFFLINE"] : [tutor.teachingMode];
  const [subject, setSubject] = useState<number | null>(tutor.subjects.length === 1 ? 0 : null);
  const [mode, setMode] = useState<Mode>(modes[0]);
  const [wanted, setPerWeek] = useState(2);
  const [startDate, setStartDate] = useState(addDays(isoToday(), 1));
  const [weeks, setWeeks] = useState(4);
  const [picked, setPicked] = useState<Block[]>([]);
  const { data } = useLoad<WeekWindow[]>(startDate ? `/v1/bookings/availability/tutors/${tutor.accountId}/free?fromDate=${startDate}&weeks=${weeks}&mode=${mode}` : null);
  const { pending, error, run } = useAction();

  const free = data?.map(norm) ?? [];
  // At most one session per free weekday (free stretches are already >= 120 minutes). Unknown while loading.
  const maxPerWeek = data ? new Set(free.map((z) => z.dayOfWeek)).size : PER_WEEK.length;
  const perWeekOptions = PER_WEEK.filter((n) => n <= maxPerWeek);
  const perWeek = Math.min(wanted, Math.max(maxPerWeek, 1));
  // Picks that fell outside free time after changing date, length or mode are dropped silently.
  const valid = picked.filter((p) => free.some((z) => inside(z, p)));
  const minStart = addDays(isoToday(), 1);
  const ready = subject !== null && valid.length === perWeek && startDate >= minStart;
  // Exactly perWeek sessions, one per day: a pick on a day already used moves that day's session; when full, the oldest pick is replaced.
  const keepFor = (day: number) => {
    const others = valid.filter((p) => p.dayOfWeek !== day);
    return others.length >= perWeek ? others.slice(others.length - perWeek + 1) : others;
  };

  /** A 2h session at the clicked time, pulled up if it would run past the end of the free stretch. */
  const place = (day: number, minute: number) => {
    const zone = free.find((z) => z.dayOfWeek === day && toMin(z.startTime) <= minute && minute < toMin(z.endTime));
    if (!zone) return null;
    const start = Math.max(toMin(zone.startTime), Math.min(minute, toMin(zone.endTime) - SESSION));
    return block(day, start, start + SESSION);
  };

  async function submit() {
    if (subject === null) return;
    const s = tutor.subjects[subject];
    await run(async () => {
      const detail = await api<PlanDetail>("/v1/bookings/plans", {
        method: "POST",
        body: {
          tutorId: tutor.accountId, subjectCode: s.subjectCode, levelCode: s.levelCode, mode, sessionsPerWeek: perWeek, startDate, weeks,
          preferredSlots: valid.map((p) => ({ dayOfWeek: p.dayOfWeek, startTime: p.startTime })),
        },
      });
      router.push(`/learner/plans/${detail.plan.id}`);
    });
  }

  return (
    <Card className="border-primary/40">
      <CardHeader><CardTitle>Đặt lịch học</CardTitle></CardHeader>
      <CardContent>
        <form className="grid gap-4" onSubmit={(e) => { e.preventDefault(); void submit(); }}>
          <Choice label="1. Môn và cấp học" options={tutor.subjects.map((_, i) => i)} value={subject} onChange={setSubject}
            render={(i) => subjectLevel(tutor.subjects[i])} />
          {modes.length > 1 && <Choice label="Hình thức" options={modes} value={mode} onChange={setMode} render={(m) => MODE_LABEL[m]} />}
          <Choice label="2. Số buổi mỗi tuần" options={perWeekOptions} value={perWeek} onChange={setPerWeek} render={(n) => `${n} buổi`} />
          <div className="grid grid-cols-[1fr_auto] items-end gap-3">
            <div className="grid gap-1.5">
              <Label htmlFor="req-start">3. Bắt đầu từ</Label>
              <Input id="req-start" type="date" required min={minStart} value={startDate} onChange={(e) => setStartDate(e.target.value)} />
            </div>
            <Choice label="Học trong" options={WEEKS} value={weeks} onChange={setWeeks} render={(n) => `${n} tuần`} />
          </div>
          {startDate && (
            <p className="text-xs text-muted-foreground">
              ≈ {perWeek * weeks} buổi, từ {fmtDate(startDate)} đến {fmtDate(addDays(startDate, weeks * 7 - 1))}. Mỗi buổi 2 tiếng.
            </p>
          )}

          <div className="grid gap-1.5">
            <span className="text-sm font-medium">4. Bấm vào khoảng trống để chọn giờ học, mỗi ngày một buổi</span>
            {data?.length === 0 ? <Empty>Gia sư không còn khoảng trống trong thời gian này.</Empty> : (
              <TimeGrid label="Khoảng trống của gia sư" zones={free} blocks={valid} place={place}
                onPlace={(b) => setPicked([...keepFor(b.dayOfWeek), b])} onBlockClick={(b) => setPicked(valid.filter((p) => p !== b))} blockHint="bấm để bỏ" />
            )}
            <p className={valid.length === perWeek ? "text-xs text-muted-foreground" : "text-xs font-medium text-primary"}>
              Đã chọn {valid.length}/{perWeek} buổi. Vùng xám là giờ gia sư bận hoặc không dạy.
            </p>
          </div>

          <ErrorBox error={error} />
          <Button type="submit" disabled={!ready || pending}>{pending ? "Đang gửi…" : "Gửi yêu cầu cho gia sư"}</Button>
          <p className="text-xs text-muted-foreground">Gia sư nhận hoặc từ chối đúng các buổi bạn chọn. Bạn xác nhận trong 24 giờ để chốt lịch.</p>
        </form>
      </CardContent>
    </Card>
  );
}
