"use client";
import { useState } from "react";
import { Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Empty, ErrorBox, NativeSelect, PageTitle, useAction } from "@/components/common";
import { DAY_LABEL, MODE_LABEL } from "@/config/labels";
import { useLoad } from "@/hooks/use-load";
import { api, fmtDate, fmtTime } from "@/lib/api";
import type { Mode, Slot } from "@/types/api";

const today = () => new Date().toLocaleDateString("sv-SE", { timeZone: "Asia/Ho_Chi_Minh" });

export function AvailabilityEditor() {
  const { data, error, reload } = useLoad<Slot[]>("/v1/bookings/availability/me");
  const [form, setForm] = useState({ dayOfWeek: 1, startTime: "08:00", endTime: "12:00", mode: "FLEX" as Mode, effectiveFrom: today(), effectiveTo: "" });
  const add = useAction();
  const remove = useAction();

  return (
    <>
      <PageTitle title="Lịch rảnh hằng tuần" description="Có hiệu lực ngay, không cần admin duyệt. Trong 08:00–22:00, mỗi khoảng ít nhất 120 phút. Cần hồ sơ đã duyệt." />
      <div className="grid gap-6 lg:grid-cols-[1fr_360px]">
        <Card>
          <CardHeader><CardTitle>Khoảng đã khai</CardTitle></CardHeader>
          <CardContent className="grid gap-2">
            <ErrorBox error={error ?? remove.error} />
            {data?.length === 0 && <Empty>Chưa có khoảng nào.</Empty>}
            {data?.map((s) => (
              <div key={s.id} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border p-3 text-sm">
                <span className="font-medium">{DAY_LABEL[s.dayOfWeek]} · {fmtTime(s.startTime)}–{fmtTime(s.endTime)}</span>
                <span className="text-muted-foreground">{MODE_LABEL[s.mode]} · từ {fmtDate(s.effectiveFrom)}{s.effectiveTo ? ` đến ${fmtDate(s.effectiveTo)}` : ""}</span>
                <Button size="icon-sm" variant="ghost" aria-label="Xóa khoảng này" disabled={remove.pending}
                  onClick={() => remove.run(async () => { await api(`/v1/bookings/availability/${s.id}`, { method: "DELETE" }); await reload(); })}>
                  <Trash2 />
                </Button>
              </div>
            ))}
            <p className="text-xs text-muted-foreground">Xóa khoảng không ảnh hưởng các buổi đã giữ hoặc đã chốt.</p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle>Thêm khoảng</CardTitle></CardHeader>
          <CardContent>
            <form className="grid gap-3" onSubmit={(e) => {
              e.preventDefault();
              void add.run(async () => {
                await api("/v1/bookings/availability", { method: "POST", body: { ...form, effectiveTo: form.effectiveTo || null } });
                await reload();
              });
            }}>
              <div className="grid gap-1.5">
                <Label htmlFor="a-day">Thứ</Label>
                <NativeSelect id="a-day" value={form.dayOfWeek} onChange={(e) => setForm({ ...form, dayOfWeek: Number(e.target.value) })}>
                  {DAY_LABEL.slice(1).map((d, i) => <option key={d} value={i + 1}>{d}</option>)}
                </NativeSelect>
              </div>
              <div className="grid grid-cols-2 gap-2">
                <div className="grid gap-1.5">
                  <Label htmlFor="a-start">Từ</Label>
                  <Input id="a-start" type="time" min="08:00" max="20:00" required value={form.startTime} onChange={(e) => setForm({ ...form, startTime: e.target.value })} />
                </div>
                <div className="grid gap-1.5">
                  <Label htmlFor="a-end">Đến</Label>
                  <Input id="a-end" type="time" min="10:00" max="22:00" required value={form.endTime} onChange={(e) => setForm({ ...form, endTime: e.target.value })} />
                </div>
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor="a-mode">Hình thức</Label>
                <NativeSelect id="a-mode" value={form.mode} onChange={(e) => setForm({ ...form, mode: e.target.value as Mode })}>
                  {(Object.keys(MODE_LABEL) as Mode[]).map((m) => <option key={m} value={m}>{MODE_LABEL[m]}</option>)}
                </NativeSelect>
              </div>
              <div className="grid grid-cols-2 gap-2">
                <div className="grid gap-1.5">
                  <Label htmlFor="a-from">Áp dụng từ</Label>
                  <Input id="a-from" type="date" required min={today()} value={form.effectiveFrom} onChange={(e) => setForm({ ...form, effectiveFrom: e.target.value })} />
                </div>
                <div className="grid gap-1.5">
                  <Label htmlFor="a-to">Đến (tùy chọn)</Label>
                  <Input id="a-to" type="date" min={form.effectiveFrom} value={form.effectiveTo} onChange={(e) => setForm({ ...form, effectiveTo: e.target.value })} />
                </div>
              </div>
              <ErrorBox error={add.error} />
              <Button type="submit" disabled={add.pending}>Thêm</Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </>
  );
}
