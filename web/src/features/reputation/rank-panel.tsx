"use client";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Empty, ErrorBox, PageTitle, StatusBadge } from "@/components/common";
import { EXP_TYPE, fmtRate, RANK_FLAG, RANK_LABEL } from "@/config/labels";
import { useLoad } from "@/hooks/use-load";
import { fmtDate, fmtDateTime } from "@/lib/api";
import type { ExpEntry, RankDecision, RankDetail, RankView } from "@/types/api";

/** P10 thresholds shown next to the tutor's numbers; the admin decides, nothing changes automatically. */
const NEXT: Record<number, { exp: number; learners: number; renewals: number; rate: number } | undefined> = {
  1: { exp: 1200, learners: 3, renewals: 2, rate: 92 },
  2: { exp: 3500, learners: 8, renewals: 6, rate: 96 },
};

export function RankSummary({ view }: { view: RankView }) {
  const next = NEXT[view.rank];
  const s = view.stats;
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center justify-between gap-2">
          {RANK_LABEL[view.rank]} <StatusBadge value={RANK_FLAG[view.flag]} />
        </CardTitle>
      </CardHeader>
      <CardContent className="grid gap-3 text-sm">
        <p className="text-3xl font-semibold">{view.exp.toLocaleString("vi-VN")} <span className="text-base font-normal text-muted-foreground">EXP</span></p>
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1.5 sm:grid-cols-3">
          <Stat label="Buổi đã dạy" value={s.taughtSessions} />
          <Stat label="Tỷ lệ hoàn thành" value={fmtRate(view.completionRate)} hint={next && `cần ≥ ${next.rate}%`} />
          <Stat label="Người thuê hoàn tất kỳ" value={s.distinctLearners} hint={next && `cần ≥ ${next.learners}`} />
          <Stat label="Lượt gia hạn" value={s.renewals} hint={next && `cần ≥ ${next.renewals}`} />
          <Stat label="Vấn đề đang mở" value={s.openIssues} />
          <Stat label="Ở Hạng này từ" value={fmtDate(view.rankSince)} hint={next && "cần ≥ 90 ngày để lên"} />
        </dl>
        {next && <p className="text-muted-foreground">Ngưỡng lên Hạng {view.rank + 1}: {next.exp.toLocaleString("vi-VN")} EXP và các điều kiện trên; admin xét và quyết định.</p>}
        {view.entryRank2 && <p className="text-muted-foreground">Hạng 2 đầu vào: miễn điều kiện 3 người thuê / 2 gia hạn.</p>}
        <p className="text-xs text-muted-foreground">Cập nhật số liệu: {s.syncedAt ? fmtDateTime(s.syncedAt) : "chưa đồng bộ"}</p>
      </CardContent>
    </Card>
  );
}

function Stat({ label, value, hint }: { label: string; value: string | number; hint?: string }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="font-medium">{value}{hint && <span className="ml-1 text-xs font-normal text-muted-foreground">({hint})</span>}</dd>
    </div>
  );
}

export function ExpLedgerList({ entries }: { entries: ExpEntry[] }) {
  return (
    <Card>
      <CardHeader><CardTitle>Sổ EXP</CardTitle></CardHeader>
      <CardContent>
        {entries.length ? (
          <ul className="grid gap-1.5 text-sm">
            {entries.map((e) => (
              <li key={e.id} className="flex items-baseline justify-between gap-3 border-b pb-1.5 last:border-0">
                <span>
                  {EXP_TYPE[e.type]}
                  {e.reason && <span className="text-muted-foreground"> · {e.reason}</span>}
                  <span className="block text-xs text-muted-foreground">{fmtDateTime(e.occurredAt)}</span>
                </span>
                <span className={e.points < 0 ? "font-semibold text-destructive" : "font-semibold text-primary"}>{e.points > 0 ? "+" : ""}{e.points}</span>
              </li>
            ))}
          </ul>
        ) : <Empty>Chưa có EXP. Mỗi buổi dạy đúng giờ được nghiệm thu +10, kỳ hoàn tất +30, gia hạn hợp lệ +50.</Empty>}
      </CardContent>
    </Card>
  );
}

export function DecisionList({ decisions }: { decisions: RankDecision[] }) {
  if (!decisions.length) return null;
  return (
    <Card>
      <CardHeader><CardTitle>Lịch sử xét Hạng</CardTitle></CardHeader>
      <CardContent>
        <ul className="grid gap-1 text-sm">
          {decisions.map((d, i) => <li key={i}>{fmtDateTime(d.createdAt)} · Hạng {d.fromRank} → {d.toRank} · {d.reason}</li>)}
        </ul>
      </CardContent>
    </Card>
  );
}

/** /tutor/rank */
export function MyRank() {
  const { data, error } = useLoad<RankDetail>("/v1/tutors/me/exp");
  return (
    <>
      <PageTitle title="EXP & Hạng" description="Điểm chỉ cộng từ buổi dạy đúng giờ được nghiệm thu, kỳ hoàn tất và gia hạn hợp lệ." />
      <ErrorBox error={error} />
      {data && (
        <div className="grid gap-4 lg:grid-cols-[1fr_1fr]">
          <div className="grid content-start gap-4">
            <RankSummary view={data.view} />
            <DecisionList decisions={data.decisions} />
          </div>
          <ExpLedgerList entries={data.entries} />
        </div>
      )}
    </>
  );
}
