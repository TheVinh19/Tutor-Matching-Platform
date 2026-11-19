"use client";
import Link from "next/link";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Empty, ErrorBox, NativeSelect, PageTitle } from "@/components/common";
import { DAY_LABEL, MODE_LABEL } from "@/config/labels";
import { DAY_END, DAY_START, SESSION, STEP, toMin, toTime } from "@/components/time-grid";
import { useLoad } from "@/hooks/use-load";
import { useCatalog } from "@/features/tutors/catalog";
import type { SearchPage } from "@/types/api";

type Slot = { day: string; from: string };
type Filters = { subjects: string[]; level: string; ranks: string[]; mode: string; area: string; slots: Slot[] };
/** 08:00 ... 20:00 every 30 minutes: every start whose 2h session ends by 22:00. */
const START_TIMES = Array.from({ length: (DAY_END - SESSION - DAY_START) / STEP + 1 }, (_, i) => toTime(DAY_START + i * STEP));
const EMPTY: Filters = { subjects: [], level: "", ranks: [], mode: "", area: "", slots: [] };

function toQuery(f: Filters, page: number) {
  const q = new URLSearchParams({ page: String(page), size: "12" });
  if (f.subjects.length) q.set("subjects", f.subjects.join(","));
  if (f.ranks.length) q.set("ranks", f.ranks.join(","));
  for (const key of ["level", "mode", "area"] as const) if (f[key]) q.set(key, f[key]);
  // One day/from/to triple per session; the tutor must be free for all of them.
  for (const s of f.slots) { q.append("day", s.day); q.append("from", s.from); q.append("to", toTime(toMin(s.from) + SESSION)); }
  return q.toString();
}

function toggle(list: string[], value: string) {
  return list.includes(value) ? list.filter((x) => x !== value) : [...list, value];
}

export function TutorSearchPage() {
  const { catalog, subjectLevel, area } = useCatalog();
  const [draft, setDraft] = useState<Filters>(EMPTY);
  const [applied, setApplied] = useState<Filters>(EMPTY);
  const [page, setPage] = useState(0);
  const { data, error, loading } = useLoad<SearchPage>(`/v1/tutors/search?${toQuery(applied, page)}`);
  const set = (patch: Partial<Filters>) => setDraft({ ...draft, ...patch });

  return (
    <>
      <PageTitle title="Tìm gia sư" description="Chọn nhiều môn: gia sư dạy ít nhất một môn sẽ hiện ra, mỗi người một lần. Không lọc theo giá." />
      <div className="grid gap-6 lg:grid-cols-[360px_1fr]">
        <form className="grid content-start gap-4 rounded-xl border p-4" onSubmit={(e) => { e.preventDefault(); setPage(0); setApplied(draft); }}>
          <fieldset className="grid gap-1.5">
            <legend className="mb-1 text-sm font-medium">Môn học</legend>
            <div className="flex flex-wrap gap-1.5">
              {catalog?.subjects.map((s) => (
                <Button key={s.code} type="button" size="sm" aria-pressed={draft.subjects.includes(s.code)}
                  variant={draft.subjects.includes(s.code) ? "default" : "outline"}
                  onClick={() => set({ subjects: toggle(draft.subjects, s.code) })}>
                  {s.name}
                </Button>
              ))}
            </div>
          </fieldset>
          <div className="grid gap-1.5">
            <Label htmlFor="level">Cấp học</Label>
            <NativeSelect id="level" value={draft.level} onChange={(e) => set({ level: e.target.value })}>
              <option value="">Tất cả</option>
              {catalog?.levels.map((l) => <option key={l.code} value={l.code}>{l.name}</option>)}
            </NativeSelect>
          </div>
          <fieldset className="grid gap-1.5">
            <legend className="mb-1 text-sm font-medium">Hạng</legend>
            <div className="flex gap-1.5">
              {["1", "2", "3"].map((r) => (
                <Button key={r} type="button" size="sm" aria-pressed={draft.ranks.includes(r)}
                  variant={draft.ranks.includes(r) ? "default" : "outline"} onClick={() => set({ ranks: toggle(draft.ranks, r) })}>
                  Hạng {r}
                </Button>
              ))}
            </div>
          </fieldset>
          <div className="grid gap-1.5">
            <Label htmlFor="mode">Hình thức</Label>
            <NativeSelect id="mode" value={draft.mode} onChange={(e) => set({ mode: e.target.value })}>
              <option value="">Tất cả</option>
              <option value="ONLINE">Online</option>
              <option value="OFFLINE">Trực tiếp</option>
            </NativeSelect>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="area">Địa bàn (học trực tiếp)</Label>
            <NativeSelect id="area" value={draft.area} onChange={(e) => set({ area: e.target.value })}>
              <option value="">Tất cả</option>
              {catalog?.areas.map((a) => <option key={a.code} value={a.code}>{a.name}, {a.province}</option>)}
            </NativeSelect>
          </div>
          <fieldset className="grid gap-1.5">
            <legend className="mb-1 text-sm font-medium">Buổi muốn học (2 tiếng)</legend>
            {draft.slots.map((s, i) => {
              const patch = (p: Partial<Slot>) => set({ slots: draft.slots.map((x, j) => (j === i ? { ...x, ...p } : x)) });
              return (
                <div key={i} className="grid grid-cols-[1fr_1fr_auto] gap-2">
                  <NativeSelect aria-label={`Thứ buổi ${i + 1}`} value={s.day} onChange={(e) => patch({ day: e.target.value })}>
                    {DAY_LABEL.slice(1).map((d, k) => <option key={d} value={k + 1}>{d}</option>)}
                  </NativeSelect>
                  <NativeSelect aria-label={`Giờ bắt đầu buổi ${i + 1}`} value={s.from} onChange={(e) => patch({ from: e.target.value })}>
                    {START_TIMES.map((t) => <option key={t} value={t}>{t}–{toTime(toMin(t) + SESSION)}</option>)}
                  </NativeSelect>
                  <Button type="button" size="sm" variant="ghost" aria-label={`Bỏ buổi ${i + 1}`}
                    onClick={() => set({ slots: draft.slots.filter((_, j) => j !== i) })}>✕</Button>
                </div>
              );
            })}
            {draft.slots.length < 7 && (
              <Button type="button" size="sm" variant="outline" className="w-fit"
                onClick={() => {
                  // Next unused day after the last pick, same start time, to make "T2, T3, T4 at 18:00" two clicks.
                  const last = draft.slots.at(-1);
                  const used = draft.slots.map((s) => s.day);
                  const day = [1, 2, 3, 4, 5, 6, 7].map((d) => ((Number(last?.day ?? 0) + d - 1) % 7) + 1).find((d) => !used.includes(String(d))) ?? 1;
                  set({ slots: [...draft.slots, { day: String(day), from: last?.from ?? "08:00" }] });
                }}>
                + Thêm buổi
              </Button>
            )}
            <p className="text-xs text-muted-foreground">Chỉ hiện gia sư có lịch rảnh trùm trọn tất cả các buổi đã chọn.</p>
          </fieldset>
          <div className="flex gap-2">
            <Button type="submit" className="flex-1">Tìm</Button>
            <Button type="button" variant="ghost" onClick={() => { setDraft(EMPTY); setApplied(EMPTY); setPage(0); }}>Xóa lọc</Button>
          </div>
        </form>

        <section aria-live="polite" className="grid content-start gap-4">
          <ErrorBox error={error} />
          {data && <p className="text-sm text-muted-foreground">{data.total} gia sư phù hợp</p>}
          {!loading && data?.items.length === 0 && <Empty>Không có gia sư phù hợp. Thử bỏ bớt bộ lọc.</Empty>}
          <div className="grid gap-4 sm:grid-cols-2">
            {data?.items.map((t) => (
              <Card key={t.accountId} className="transition-shadow hover:shadow-md">
                <CardHeader>
                  <CardTitle><Link href={`/tutors/${t.accountId}`} className="hover:text-primary">{t.fullName}</Link></CardTitle>
                  <CardDescription className="flex flex-wrap gap-1.5">
                    <Badge>Hạng {t.rank}</Badge>
                    <Badge variant="outline">{MODE_LABEL[t.teachingMode]}</Badge>
                  </CardDescription>
                </CardHeader>
                <CardContent className="grid gap-2 text-sm">
                  <p className="line-clamp-2 text-muted-foreground">{t.bio || "Chưa có giới thiệu."}</p>
                  <p><span className="font-medium">Dạy:</span> {t.subjects.map(subjectLevel).join(", ")}</p>
                  {t.areaCodes.length > 0 && <p><span className="font-medium">Khu vực:</span> {t.areaCodes.map(area).join("; ")}</p>}
                  <Link href={`/tutors/${t.accountId}`} className="mt-1 font-medium text-primary hover:underline">Xem hồ sơ →</Link>
                </CardContent>
              </Card>
            ))}
          </div>
          {data && data.total > data.size && (
            <div className="flex items-center justify-center gap-3">
              <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>Trước</Button>
              <span className="text-sm">Trang {page + 1}/{Math.ceil(data.total / data.size)}</span>
              <Button variant="outline" size="sm" disabled={(page + 1) * data.size >= data.total} onClick={() => setPage(page + 1)}>Sau</Button>
            </div>
          )}
        </section>
      </div>
    </>
  );
}
