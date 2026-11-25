"use client";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Empty, ErrorBox, NativeSelect, PageTitle, StatusBadge, useAction } from "@/components/common";
import { fmtRate, RANK_FLAG } from "@/config/labels";
import { useLoad } from "@/hooks/use-load";
import { api } from "@/lib/api";
import { DecisionList, ExpLedgerList, RankSummary } from "@/features/reputation/rank-panel";
import type { RankDetail, RankFlag, RankView } from "@/types/api";

/** /admin/ranks: the system flags, the admin decides with a reason (P09/P10: decide within 2 working days, never automatic). */
export function RankReview() {
  const [flag, setFlag] = useState<RankFlag | "">("");
  const [selected, setSelected] = useState<string | null>(null);
  const { data, error, reload } = useLoad<RankView[]>(`/v1/tutors/admin/ranks${flag ? `?flag=${flag}` : ""}`);
  const sync = useAction();
  return (
    <>
      <PageTitle title="Xét Hạng gia sư" description="Cờ do hệ thống tính từ EXP, tỷ lệ hoàn thành, số người thuê, gia hạn và kỷ luật. Hạng chỉ đổi khi admin quyết định.">
        <div className="flex gap-2">
          <NativeSelect aria-label="Lọc cờ" className="w-52" value={flag} onChange={(e) => setFlag(e.target.value as RankFlag | "")}>
            <option value="">Tất cả</option>
            {(Object.keys(RANK_FLAG) as RankFlag[]).map((f) => <option key={f} value={f}>{RANK_FLAG[f][0]}</option>)}
          </NativeSelect>
          <Button variant="outline" disabled={sync.pending} onClick={() => sync.run(async () => { await api("/v1/tutors/admin/ranks/sync", { method: "POST" }); await reload(); })}>
            Đồng bộ số liệu
          </Button>
        </div>
      </PageTitle>
      <ErrorBox error={error ?? sync.error} />
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
        <Card className="self-start">
          <CardContent className="pt-6">
            {data?.length === 0 ? <Empty>Không có gia sư nào.</Empty> : (
              <ul className="grid gap-1.5">
                {data?.map((v) => (
                  <li key={v.accountId}>
                    <button type="button" onClick={() => setSelected(v.accountId)}
                      className={`grid w-full gap-1 rounded-md border p-2 text-left text-sm hover:bg-accent ${selected === v.accountId ? "border-primary" : ""}`}>
                      <span className="flex items-center justify-between gap-2 font-medium">{v.fullName} <StatusBadge value={RANK_FLAG[v.flag]} /></span>
                      <span className="text-xs text-muted-foreground">Hạng {v.rank} · {v.exp.toLocaleString("vi-VN")} EXP · {fmtRate(v.completionRate)}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>
        {selected ? <RankDetailPanel key={selected} id={selected} onChange={reload} /> : <Empty>Chọn một gia sư để xem chi tiết.</Empty>}
      </div>
    </>
  );
}

function RankDetailPanel({ id, onChange }: { id: string; onChange: () => void }) {
  const { data, error, reload } = useLoad<RankDetail>(`/v1/tutors/admin/ranks/${id}`);
  const [rank, setRank] = useState(0);
  const [reason, setReason] = useState("");
  const [points, setPoints] = useState(30);
  const [refType, setRefType] = useState<"SESSION" | "PLAN">("SESSION");
  const [refId, setRefId] = useState("");
  const [penaltyReason, setPenaltyReason] = useState("");
  const decide = useAction();
  const penalize = useAction();
  if (error) return <ErrorBox error={error} />;
  if (!data) return <p className="text-muted-foreground">Đang tải…</p>;
  const v = data.view;
  // Promotion is one step only; preselect the flagged direction, otherwise the admin picks.
  const options = [1, 2, 3].filter((r) => r < v.rank || r === v.rank + 1);
  const target = rank || (v.flag === "ELIGIBLE_UP" ? v.rank + 1 : v.flag === "MUST_REVIEW" ? v.rank - 1 : 0);
  const done = async () => { await reload(); onChange(); };

  return (
    <div className="grid content-start gap-4">
      <RankSummary view={v} />
      <Card>
        <CardHeader><CardTitle>Quyết định Hạng</CardTitle></CardHeader>
        <CardContent>
          <form className="grid gap-2" onSubmit={(e) => {
            e.preventDefault();
            void decide.run(async () => { await api(`/v1/tutors/admin/ranks/${id}/decide`, { method: "POST", body: { rank: target, reason } }); setReason(""); await done(); });
          }}>
            <div className="flex gap-1.5" role="group" aria-label="Hạng mới">
              {options.map((r) => (
                <Button key={r} type="button" size="sm" variant={r === target ? "default" : "outline"} aria-pressed={r === target} onClick={() => setRank(r)}>
                  {r > v.rank ? "Lên" : "Hạ"} Hạng {r}
                </Button>
              ))}
            </div>
            <Textarea aria-label="Căn cứ quyết định" required maxLength={1000} placeholder="Căn cứ: điều kiện đã kiểm tra, minh chứng, log học…" value={reason} onChange={(e) => setReason(e.target.value)} />
            <ErrorBox error={decide.error} />
            <Button type="submit" size="sm" className="w-fit" disabled={decide.pending || !target || !reason.trim()}>Lưu quyết định</Button>
            <p className="text-xs text-muted-foreground">Lên Hạng chỉ khi hệ thống báo đủ điều kiện và lên từng bậc. Hạ Hạng về mức cao nhất còn đáp ứng.</p>
          </form>
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>Phạt EXP sau xác minh</CardTitle></CardHeader>
        <CardContent>
          <form className="grid gap-2" onSubmit={(e) => {
            e.preventDefault();
            void penalize.run(async () => {
              await api(`/v1/tutors/admin/ranks/${id}/penalties`, { method: "POST", body: { points, reason: penaltyReason, refType, refId } });
              setPenaltyReason(""); setRefId(""); await done();
            });
          }}>
            <div className="flex flex-wrap gap-1.5" role="group" aria-label="Mức phạt">
              {[[30, "Muộn/thiếu giờ −30"], [100, "Tự ý bỏ buổi −100"], [200, "Phá cam kết giữa kỳ −200"]].map(([p, label]) => (
                <Button key={p} type="button" size="sm" variant={p === points ? "default" : "outline"} aria-pressed={p === points}
                  onClick={() => { setPoints(p as number); setRefType(p === 200 ? "PLAN" : "SESSION"); }}>{label}</Button>
              ))}
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="pen-ref">Mã {refType === "PLAN" ? "kỳ học" : "buổi học"}</Label>
              <Input id="pen-ref" required pattern="[0-9a-fA-F-]{36}" title="UUID của buổi/kỳ" value={refId} onChange={(e) => setRefId(e.target.value.trim())} />
            </div>
            <Textarea aria-label="Kết luận xác minh" required maxLength={1000} placeholder="Kết luận xác minh vi phạm" value={penaltyReason} onChange={(e) => setPenaltyReason(e.target.value)} />
            <ErrorBox error={penalize.error} />
            <Button type="submit" size="sm" variant="destructive" className="w-fit" disabled={penalize.pending || !penaltyReason.trim() || !refId}>Ghi phạt</Button>
            <p className="text-xs text-muted-foreground">Mỗi buổi/kỳ chỉ một mức phạt. Phạt không tự hạ Hạng.</p>
          </form>
        </CardContent>
      </Card>
      <DecisionList decisions={data.decisions} />
      <ExpLedgerList entries={data.entries} />
    </div>
  );
}
