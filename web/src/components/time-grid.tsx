"use client";
import { useState, type PointerEvent as ReactPointerEvent } from "react";
import { DAY_LABEL } from "@/config/labels";
import { cn } from "@/lib/utils";

/** A weekday time range, "HH:MM". */
export type Block = { dayOfWeek: number; startTime: string; endTime: string };

/** Same rules as booking-service Schedule: 08:00-22:00, 120-minute sessions, 60-minute gap between a tutor's sessions. */
export const DAY_START = 8 * 60;
export const DAY_END = 22 * 60;
export const SESSION = 120;
export const GAP = 60;
/** Snap step of the calendar. shortcut: 30-minute snapping only; switch to 15 if quarter-hour starts are needed. */
export const STEP = 30;
const ROW = 20; // px per STEP
const HEIGHT = ((DAY_END - DAY_START) / STEP) * ROW;
const DAYS = [1, 2, 3, 4, 5, 6, 7];
const HOURS = Array.from({ length: (DAY_END - DAY_START) / 60 + 1 }, (_, i) => 8 + i);

export const toMin = (t: string) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3, 5));
export const toTime = (m: number) => `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
export const block = (dayOfWeek: number, from: number, to: number): Block => ({ dayOfWeek, startTime: toTime(from), endTime: toTime(to) });
/** API times come as "08:00:00"; also turns {dayOfWeek,startTime} session starts into 2h blocks. */
export const norm = (b: { dayOfWeek: number; startTime: string; endTime?: string }) =>
  block(b.dayOfWeek, toMin(b.startTime), b.endTime ? toMin(b.endTime) : toMin(b.startTime) + SESSION);
export const sameBlock = (a: Block, b: Block) => a.dayOfWeek === b.dayOfWeek && a.startTime === b.startTime && a.endTime === b.endTime;
/** Same day and closer than `gap` minutes (0 = plain overlap). */
export const clashes = (a: Block, b: Block, gap = 0) =>
  a.dayOfWeek === b.dayOfWeek && toMin(a.startTime) < toMin(b.endTime) + gap && toMin(b.startTime) < toMin(a.endTime) + gap;
export const inside = (outer: Block, inner: Block) =>
  outer.dayOfWeek === inner.dayOfWeek && toMin(outer.startTime) <= toMin(inner.startTime) && toMin(inner.endTime) <= toMin(outer.endTime);
/** At least one session long and inside the teaching day. */
export function fitWindow(day: number, from: number, to: number) {
  const end = Math.min(DAY_END, Math.max(to, from + SESSION));
  return block(day, Math.min(from, end - SESSION), end);
}

const shortDay = (d: number) => DAY_LABEL[d].replace("Thứ ", "T").replace("Chủ nhật", "CN");
const top = (t: string) => ((toMin(t) - DAY_START) / STEP) * ROW;
const height = (b: Block) => ((toMin(b.endTime) - toMin(b.startTime)) / STEP) * ROW;
const minuteAt = (clientY: number, column: Element) => {
  const m = DAY_START + Math.floor((clientY - column.getBoundingClientRect().top) / ROW) * STEP;
  return Math.min(DAY_END - STEP, Math.max(DAY_START, m));
};

/**
 * Week calendar, 08:00-22:00. Three layers per day:
 * - zones: open areas (free time / options); with zones, everything else is shown greyed out
 * - blocks: chosen ranges (solid), clickable via onBlockClick
 * - locked: booked sessions drawn on top, read-only
 * - preview: dashed ghost while drawing (draw) or hovering where place() would put a block
 * draw: press-drag on empty space to create a range. place: click inside a zone; place(day, minute) decides the block or null.
 */
export function TimeGrid({ label, zones, blocks = [], locked = [], draw, place, onPlace, onBlockClick, blockHint }: {
  label: string; zones?: Block[]; blocks?: Block[]; locked?: Block[];
  draw?: (b: Block) => void;
  place?: (day: number, minute: number) => Block | null; onPlace?: (b: Block) => void;
  onBlockClick?: (b: Block) => void; blockHint?: string;
}) {
  const [ghost, setGhost] = useState<Block | null>(null);
  const [drag, setDrag] = useState<{ day: number; anchor: number; m: number } | null>(null);
  const dragBlock = drag && fitWindow(drag.day, Math.min(drag.anchor, drag.m), Math.max(drag.anchor, drag.m) + STEP);
  const preview = dragBlock ?? ghost;

  const onMove = (d: number) => (e: ReactPointerEvent<HTMLDivElement>) => {
    if (drag) setDrag({ ...drag, m: minuteAt(e.clientY, e.currentTarget) });
    else if (place) setGhost(place(d, minuteAt(e.clientY, e.currentTarget)));
  };

  return (
    <div className="overflow-x-auto">
      <div role="group" aria-label={label} className="grid min-w-[520px] grid-cols-[2.75rem_repeat(7,minmax(0,1fr))] gap-x-1 select-none">
        <div />
        {DAYS.map((d) => <div key={d} className="pb-1 text-center text-xs font-medium text-muted-foreground">{shortDay(d)}</div>)}
        <div className="relative" style={{ height: HEIGHT }}>
          {HOURS.map((h) => (
            <span key={h} className="absolute right-1 -translate-y-1/2 text-[11px] text-muted-foreground" style={{ top: (h * 60 - DAY_START) / STEP * ROW }}>{h}:00</span>
          ))}
        </div>
        {DAYS.map((d) => (
          <div key={d}
            className={cn("relative rounded-md border", zones ? "bg-muted/60" : "bg-background", draw && "cursor-crosshair touch-none")}
            style={{ height: HEIGHT, backgroundImage: "linear-gradient(to bottom, var(--color-border) 1px, transparent 1px)", backgroundSize: `100% ${ROW * 2}px` }}
            onPointerDown={draw ? (e) => {
              if (e.button !== 0) return;
              e.currentTarget.setPointerCapture(e.pointerId);
              const m = minuteAt(e.clientY, e.currentTarget);
              setDrag({ day: d, anchor: m, m });
            } : undefined}
            onPointerMove={onMove(d)}
            onPointerUp={() => { if (dragBlock) draw?.(dragBlock); setDrag(null); }}
            onPointerLeave={() => setGhost(null)}>
            {zones?.filter((z) => z.dayOfWeek === d).map((z) => (
              <button key={z.startTime} type="button" disabled={!place} tabIndex={place ? 0 : -1}
                aria-label={`${DAY_LABEL[d]} ${z.startTime}–${z.endTime}, còn trống`}
                // Keyboard (detail 0) places at the start of the zone; pointer places where clicked.
                onClick={(e) => {
                  const b = place?.(d, e.detail === 0 ? toMin(z.startTime) : minuteAt(e.clientY, e.currentTarget.parentElement!));
                  if (b) onPlace?.(b);
                }}
                className={cn("absolute inset-x-0.5 rounded-md border border-primary/40 bg-accent/60 px-1 text-left align-top text-[10px] text-muted-foreground",
                  place && "cursor-pointer hover:bg-accent focus-visible:ring-3 focus-visible:ring-ring/50 outline-none")}
                style={{ top: top(z.startTime), height: height(z) }}>
                <span className="block pt-0.5">{z.startTime}–{z.endTime}</span>
              </button>
            ))}
            {blocks.filter((b) => b.dayOfWeek === d).map((b) => (
              <button key={b.startTime} type="button" disabled={!onBlockClick} title={blockHint}
                aria-label={`${DAY_LABEL[d]} ${b.startTime}–${b.endTime}${blockHint ? `, ${blockHint}` : ""}`}
                onPointerDown={(e) => e.stopPropagation()}
                onClick={() => onBlockClick?.(b)}
                className={cn("absolute inset-x-0.5 z-10 overflow-hidden rounded-md bg-primary px-1 text-left text-[11px] font-medium text-primary-foreground shadow-sm outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
                  onBlockClick && "cursor-pointer hover:bg-primary/85")}
                style={{ top: top(b.startTime), height: height(b) }}>
                <span className="block pt-0.5">{b.startTime}–{b.endTime}</span>
              </button>
            ))}
            {locked.filter((b) => b.dayOfWeek === d).map((b) => (
              <div key={`locked-${b.startTime}`} role="img" aria-label={`${DAY_LABEL[d]} ${b.startTime}–${b.endTime}, đã có buổi học`}
                className="pointer-events-none absolute inset-x-1.5 z-[15] rounded-md border border-foreground/30 bg-foreground/75 px-1 text-[11px] font-medium text-background"
                style={{ top: top(b.startTime), height: height(b) }}>
                <span className="block pt-0.5">🔒 {b.startTime}–{b.endTime}</span>
              </div>
            ))}
            {preview?.dayOfWeek === d && (
              <div aria-hidden className="pointer-events-none absolute inset-x-0.5 z-20 rounded-md border-2 border-dashed border-primary bg-primary/10 px-1 text-[11px] font-medium text-primary"
                style={{ top: top(preview.startTime), height: height(preview) }}>
                {preview.startTime}–{preview.endTime}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

/** Adds a window, merging it with windows of the same day it touches or overlaps. */
export function addWindow(windows: Block[], w: Block) {
  let from = toMin(w.startTime);
  let to = toMin(w.endTime);
  const rest = windows.filter((x) => {
    const touches = x.dayOfWeek === w.dayOfWeek && toMin(x.startTime) <= to && from <= toMin(x.endTime);
    if (touches) { from = Math.min(from, toMin(x.startTime)); to = Math.max(to, toMin(x.endTime)); }
    return !touches;
  });
  return [...rest, block(w.dayOfWeek, from, to)];
}

/** "T3 09:00–11:00, T5 17:30–19:30" for summaries. */
export function describeBlocks(blocks: Block[]) {
  return [...blocks]
    .sort((a, b) => a.dayOfWeek - b.dayOfWeek || a.startTime.localeCompare(b.startTime))
    .map((b) => `${shortDay(b.dayOfWeek)} ${b.startTime}–${b.endTime}`)
    .join(", ");
}

/** Number of dated sessions the weekly blocks give between two ISO dates (inclusive). */
export function countSessions(blocks: Block[], startDate: string, endDate: string) {
  let n = 0;
  for (let d = new Date(`${startDate}T00:00:00Z`); d <= new Date(`${endDate}T00:00:00Z`); d.setUTCDate(d.getUTCDate() + 1)) {
    const dow = d.getUTCDay() || 7;
    n += blocks.filter((b) => b.dayOfWeek === dow).length;
  }
  return n;
}

/** ISO date helpers in Vietnam time. */
export const isoToday = () => new Date().toLocaleDateString("sv-SE", { timeZone: "Asia/Ho_Chi_Minh" });
export const addDays = (iso: string, days: number) => {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
};
