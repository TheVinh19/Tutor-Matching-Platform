"use client";
import { useState } from "react";
import { Card, CardContent } from "@/components/ui/card";
import { Empty, ErrorBox, NativeSelect, PageTitle, ReasonAction, useAction } from "@/components/common";
import { useLoad } from "@/hooks/use-load";
import { api, fmtDate, fmtDateTime } from "@/lib/api";
import type { FullReview, ReviewModeration as Moderation } from "@/types/api";

type Filter = "REPORTED" | "HIDDEN" | "ALL";
const FILTER_LABEL: Record<Filter, string> = { REPORTED: "Gia sư báo vi phạm", HIDDEN: "Đang ẩn", ALL: "Tất cả" };

/** /admin/reviews: hide or restore a whole review after checking; never edit it, never hide just for being negative (P12). */
export function ReviewModeration() {
  const [filter, setFilter] = useState<Filter>("REPORTED");
  const { data, error, reload } = useLoad<FullReview[]>(`/v1/tutors/admin/reviews?filter=${filter}`);
  return (
    <>
      <PageTitle title="Kiểm duyệt nhận xét" description="Chỉ ẩn hoặc khôi phục toàn bộ nhận xét sau khi xác minh vi phạm. Bản gốc, người xử lý và lý do được lưu.">
        <NativeSelect aria-label="Lọc nhận xét" className="w-48" value={filter} onChange={(e) => setFilter(e.target.value as Filter)}>
          {(Object.keys(FILTER_LABEL) as Filter[]).map((f) => <option key={f} value={f}>{FILTER_LABEL[f]}</option>)}
        </NativeSelect>
      </PageTitle>
      <ErrorBox error={error} />
      {data?.length === 0 && <Empty>Không có nhận xét nào.</Empty>}
      <div className="grid gap-3">{data?.map((r) => <Item key={r.id} review={r} onChange={reload} />)}</div>
    </>
  );
}

function Item({ review: r, onChange }: { review: FullReview; onChange: () => void }) {
  const [showLog, setShowLog] = useState(false);
  const { data: log } = useLoad<Moderation[]>(showLog ? `/v1/tutors/admin/reviews/${r.id}/moderation` : null);
  const { error, run } = useAction();
  const act = (path: string, reason: string) => run(async () => { await api(`/v1/tutors/admin/reviews/${r.id}/${path}`, { method: "POST", body: { reason } }); onChange(); });
  return (
    <Card>
      <CardContent className="grid gap-2 pt-6 text-sm">
        <p className="whitespace-pre-line">{r.content}</p>
        <p className="text-xs text-muted-foreground">{fmtDate(r.createdAt)} · gia sư {r.tutorId.slice(0, 8)} · kỳ {r.planId.slice(0, 8)}{r.hidden && " · đang ẩn"}</p>
        {r.reportReason && <p><span className="font-medium">Gia sư báo vi phạm:</span> {r.reportReason}</p>}
        {r.reply && <p><span className="font-medium">Phản hồi:</span> {r.reply}</p>}
        <ErrorBox error={error} />
        {r.hidden
          ? <ReasonAction label="Khôi phục" placeholder="Lý do khôi phục" onSubmit={(reason) => act("restore", reason)} />
          : <ReasonAction label="Ẩn nhận xét" variant="destructive" placeholder="Kết luận xác minh vi phạm" onSubmit={(reason) => act("hide", reason)} />}
        <button type="button" className="w-fit text-xs text-primary hover:underline" onClick={() => setShowLog(!showLog)}>{showLog ? "Ẩn lịch sử" : "Lịch sử xử lý"}</button>
        {showLog && (
          <ul className="grid gap-1 text-xs text-muted-foreground">
            {log?.length ? log.map((m, i) => <li key={i}>{fmtDateTime(m.createdAt)} · {m.action === "HIDE" ? "Ẩn" : "Khôi phục"} · {m.reason}</li>) : <li>Chưa xử lý.</li>}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}
