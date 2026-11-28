"use client";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Textarea } from "@/components/ui/textarea";
import { Empty, ErrorBox, NativeSelect, PageTitle, StatusBadge, useAction } from "@/components/common";
import { CASE_KIND, CASE_STATUS, STRIKE_LABEL } from "@/config/labels";
import { useLoad } from "@/hooks/use-load";
import { api, fmtDate, fmtDateTime } from "@/lib/api";
import { CaseBox } from "@/features/disputes/case-box";
import type { CaseInfo, Restriction } from "@/types/api";

/** /admin/cases: open/appealed cases first; decide with a reason; learner strikes and the 30-day block (P14). */
export function CaseReview() {
  const [status, setStatus] = useState<CaseInfo["status"] | "">("OPEN");
  const [selected, setSelected] = useState<CaseInfo | null>(null);
  const { data, error, reload } = useLoad<CaseInfo[]>(`/v1/bookings/admin/cases${status ? `?status=${status}` : ""}`);
  return (
    <>
      <PageTitle title="Vụ việc" description="Gia sư có 72 giờ nộp bằng chứng. Có bằng chứng thì đối chiếu hai phía; không có thì xử theo người thuê trong phạm vi vụ.">
        <NativeSelect aria-label="Lọc trạng thái" className="w-48" value={status} onChange={(e) => setStatus(e.target.value as CaseInfo["status"] | "")}>
          <option value="">Tất cả</option>
          {(Object.keys(CASE_STATUS) as CaseInfo["status"][]).map((s) => <option key={s} value={s}>{CASE_STATUS[s][0]}</option>)}
        </NativeSelect>
      </PageTitle>
      <ErrorBox error={error} />
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)]">
        <Card className="self-start">
          <CardContent className="pt-6">
            {data?.length === 0 ? <Empty>Không có vụ việc.</Empty> : (
              <ul className="grid gap-1.5">
                {data?.map((c) => (
                  <li key={c.id}>
                    <button type="button" onClick={() => setSelected(c)}
                      className={`grid w-full gap-1 rounded-md border p-2 text-left text-sm hover:bg-accent ${selected?.id === c.id ? "border-primary" : ""}`}>
                      <span className="flex items-center justify-between gap-2 font-medium">{CASE_KIND[c.kind]} <StatusBadge value={CASE_STATUS[c.status]} /></span>
                      <span className="line-clamp-1 text-xs text-muted-foreground">{fmtDate(c.createdAt)} · {c.reason}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>
        {selected ? (
          <div key={selected.id} className="grid content-start gap-4">
            <CaseBox path={`/v1/bookings/cases/${selected.id}`} viewer="ADMIN" onChange={reload} />
            <LearnerStrikes learnerId={selected.learnerId} />
          </div>
        ) : <Empty>Chọn một vụ việc.</Empty>}
      </div>
    </>
  );
}

function LearnerStrikes({ learnerId }: { learnerId: string }) {
  const { data, reload } = useLoad<Restriction>(`/v1/bookings/admin/learners/${learnerId}/strikes`);
  const [kind, setKind] = useState<"SELF_CANCEL" | "NO_SHOW">("NO_SHOW");
  const [reason, setReason] = useState("");
  const { pending, error, run } = useAction();
  return (
    <Card>
      <CardHeader><CardTitle>Vi phạm của người thuê</CardTitle></CardHeader>
      <CardContent className="grid gap-2 text-sm">
        {data && (
          <p>
            {data.activeStrikes} vi phạm trong 90 ngày ·{" "}
            {data.restrictedUntil ? <span className="font-medium text-destructive">khóa đặt đơn mới đến {fmtDate(data.restrictedUntil)}</span> : "chưa bị khóa"}
          </p>
        )}
        <ul className="grid gap-0.5 text-xs text-muted-foreground">
          {data?.strikes.map((s) => (
            <li key={s.id} className={s.revokedAt ? "line-through" : ""}>{fmtDateTime(s.createdAt)} · {STRIKE_LABEL[s.kind]} · {s.reason}</li>
          ))}
        </ul>
        <form className="grid gap-1.5 border-t pt-2" onSubmit={(e) => {
          e.preventDefault();
          void run(async () => { await api(`/v1/bookings/admin/learners/${learnerId}/strikes`, { method: "POST", body: { kind, reason } }); setReason(""); await reload(); });
        }}>
          <div className="flex gap-1.5" role="group" aria-label="Loại vi phạm">
            {(["NO_SHOW", "SELF_CANCEL"] as const).map((k) => (
              <Button key={k} type="button" size="sm" variant={k === kind ? "default" : "outline"} aria-pressed={k === kind} onClick={() => setKind(k)}>{STRIKE_LABEL[k]}</Button>
            ))}
          </div>
          <Textarea aria-label="Kết luận xác minh" required maxLength={1000} placeholder="Kết luận xác minh vi phạm" value={reason} onChange={(e) => setReason(e.target.value)} />
          <ErrorBox error={error} />
          <Button size="sm" type="submit" variant="destructive" className="w-fit" disabled={pending || !reason.trim()}>Ghi vi phạm</Button>
          <p className="text-xs text-muted-foreground">Lần 1–2 cảnh báo; lần 3 trong 90 ngày khóa đặt đơn mới 30 ngày. Không phạt chỉ vì phản đối.</p>
        </form>
      </CardContent>
    </Card>
  );
}
