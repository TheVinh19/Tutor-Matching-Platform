"use client";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Textarea } from "@/components/ui/textarea";
import { Empty, ErrorBox, PageTitle, ReasonAction, useAction } from "@/components/common";
import { useLoad } from "@/hooks/use-load";
import { api, fmtDate } from "@/lib/api";
import type { FullReview } from "@/types/api";

/** /tutor/reviews: one public reply per review, or report a violation for the admin to check (P12). */
export function MyReviews() {
  const { data, error, reload } = useLoad<FullReview[]>("/v1/tutors/me/reviews");
  return (
    <>
      <PageTitle title="Nhận xét về bạn" description="Phản hồi được hiển thị công khai dưới nhận xét và không sửa được. Admin không xóa nhận xét chỉ vì tiêu cực." />
      <ErrorBox error={error} />
      {data?.length === 0 && <Empty>Chưa có nhận xét.</Empty>}
      <div className="grid gap-3">{data?.map((r) => <ReviewItem key={r.id} review={r} onChange={reload} />)}</div>
    </>
  );
}

function ReviewItem({ review: r, onChange }: { review: FullReview; onChange: () => void }) {
  const [reply, setReply] = useState("");
  const { pending, error, run } = useAction();
  const post = (path: string, body: unknown) => run(async () => { await api(`/v1/tutors/reviews/${r.id}/${path}`, { method: "POST", body }); onChange(); });
  return (
    <Card>
      <CardContent className="grid gap-2 pt-6 text-sm">
        <p className="whitespace-pre-line">{r.content}</p>
        <p className="text-xs text-muted-foreground">
          {fmtDate(r.createdAt)}{r.hidden && " · đã bị admin ẩn"}{r.reportedAt && ` · đã báo vi phạm: ${r.reportReason}`}
        </p>
        {r.reply ? (
          <div className="rounded-md bg-muted p-2"><p className="text-xs font-medium">Phản hồi của bạn · {fmtDate(r.repliedAt)}</p><p className="whitespace-pre-line">{r.reply}</p></div>
        ) : (
          <form className="grid gap-2" onSubmit={(e) => { e.preventDefault(); void post("reply", { content: reply }); }}>
            <Textarea aria-label="Phản hồi nhận xét" maxLength={2000} placeholder="Phản hồi công khai (một lần, không sửa)" value={reply} onChange={(e) => setReply(e.target.value)} />
            <Button size="sm" className="w-fit" type="submit" disabled={pending || !reply.trim()}>Gửi phản hồi</Button>
          </form>
        )}
        <ErrorBox error={error} />
        {!r.reportedAt && !r.hidden && (
          <ReasonAction label="Báo vi phạm" variant="destructive" placeholder="Nhận xét vi phạm thế nào? Admin sẽ xác minh." onSubmit={(reason) => post("report", { reason })} />
        )}
      </CardContent>
    </Card>
  );
}
