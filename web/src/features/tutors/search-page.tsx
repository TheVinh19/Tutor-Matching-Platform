"use client";
import Link from "next/link";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Empty, ErrorBox, NativeSelect, PageTitle } from "@/components/common";
import { DAY_LABEL, MODE_LABEL } from "@/config/labels";
import { useLoad } from "@/hooks/use-load";
import { useCatalog } from "@/features/tutors/catalog";
import type { SearchPage } from "@/types/api";

type Filters = { subjects: string[]; level: string; ranks: string[]; mode: string; area: string; day: string; from: string; to: string };
const EMPTY: Filters = { subjects: [], level: "", ranks: [], mode: "", area: "", day: "", from: "", to: "" };

function toQuery(f: Filters, page: number) {
  const q = new URLSearchParams({ page: String(page), size: "12" });
  if (f.subjects.length) q.set("subjects", f.subjects.join(","));
  if (f.ranks.length) q.set("ranks", f.ranks.join(","));
  for (const key of ["level", "mode", "area"] as const) if (f[key]) q.set(key, f[key]);
  if (f.day && f.from && f.to) { q.set("day", f.day); q.set("from", f.from); q.set("to", f.to); }
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
  const partialSchedule = [draft.day, draft.from, draft.to].filter(Boolean).length % 3 !== 0;

  return (
    <>
      <PageTitle title="Tìm gia sư" description="Chọn nhiều môn: gia sư dạy ít nhất một môn sẽ hiện ra, mỗi người một lần. Không lọc theo giá." />
      <div className="grid gap-6 lg:grid-cols-[280px_1fr]">
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
            <legend className="mb-1 text-sm font-medium">Lịch muốn học</legend>
            <NativeSelect aria-label="Thứ" value={draft.day} onChange={(e) => set({ day: e.target.value })}>
              <option value="">Không lọc lịch</option>
              {DAY_LABEL.slice(1).map((d, i) => <option key={d} value={i + 1}>{d}</option>)}
            </NativeSelect>
            <div className="grid grid-cols-2 gap-2">
              <Input type="time" aria-label="Từ giờ" min="08:00" max="22:00" value={draft.from} onChange={(e) => set({ from: e.target.value })} />
              <Input type="time" aria-label="Đến giờ" min="08:00" max="22:00" value={draft.to} onChange={(e) => set({ to: e.target.value })} />
            </div>
            {partialSchedule && <p className="text-xs text-muted-foreground">Cần chọn đủ thứ, giờ bắt đầu và giờ kết thúc.</p>}
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
