"use client";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Empty, ErrorBox, NativeSelect, PageTitle, ReasonAction, StatusBadge, useAction } from "@/components/common";
import { MODE_LABEL, PROFILE_STATUS } from "@/config/labels";
import { useLoad } from "@/hooks/use-load";
import { api, fmtDateTime } from "@/lib/api";
import { useCatalog } from "@/features/tutors/catalog";
import type { HistoryEntry, ProfileStatus, TutorProfile } from "@/types/api";

const ACTION_LABEL: Record<string, string> = { SUBMIT: "Gửi duyệt", APPROVE: "Duyệt", REJECT: "Từ chối" };

export function ProfileReview() {
  const [status, setStatus] = useState<ProfileStatus>("PENDING");
  const { data, error, reload } = useLoad<TutorProfile[]>(`/v1/tutors/admin/profiles?status=${status}`);
  return (
    <>
      <PageTitle title="Duyệt hồ sơ gia sư" description="Duyệt hồ sơ, môn và cấp học. Lưu lý do, người duyệt và thời điểm.">
        <NativeSelect aria-label="Lọc trạng thái" className="w-44" value={status} onChange={(e) => setStatus(e.target.value as ProfileStatus)}>
          {(Object.keys(PROFILE_STATUS) as ProfileStatus[]).map((s) => <option key={s} value={s}>{PROFILE_STATUS[s][0]}</option>)}
        </NativeSelect>
      </PageTitle>
      <ErrorBox error={error} />
      {data?.length === 0 && <Empty>Không có hồ sơ nào ở trạng thái này.</Empty>}
      <div className="grid gap-4 lg:grid-cols-2">
        {data?.map((p) => <ProfileCard key={p.accountId} profile={p} onChange={reload} />)}
      </div>
    </>
  );
}

function ProfileCard({ profile: p, onChange }: { profile: TutorProfile; onChange: () => void }) {
  const { subjectLevel, area } = useCatalog();
  const [showHistory, setShowHistory] = useState(false);
  const { data: detail } = useLoad<{ history: HistoryEntry[] }>(showHistory ? `/v1/tutors/admin/profiles/${p.accountId}` : null);
  const { pending, error, run } = useAction();
  const decide = (path: string, body?: unknown) => run(async () => {
    await api(`/v1/tutors/admin/profiles/${p.accountId}/${path}`, { method: "POST", body });
    onChange();
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center justify-between gap-2">{p.fullName} <StatusBadge value={PROFILE_STATUS[p.status]} /></CardTitle>
        <CardDescription>{MODE_LABEL[p.teachingMode]} · Hạng {p.rank} · gửi {fmtDateTime(p.submittedAt)}</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-2 text-sm">
        <p className="whitespace-pre-line">{p.bio || "—"}</p>
        <p><span className="font-medium">Kinh nghiệm:</span> {p.experience || "—"}</p>
        <p><span className="font-medium">Môn/cấp:</span> {p.subjects.map(subjectLevel).join(", ") || "—"}</p>
        {p.areaCodes.length > 0 && <p><span className="font-medium">Địa bàn:</span> {p.areaCodes.map(area).join("; ")}</p>}
        {p.rejectReason && <p><span className="font-medium">Lý do từ chối:</span> {p.rejectReason}</p>}
        <ErrorBox error={error} />
        {p.status === "PENDING" && (
          <div className="flex flex-wrap gap-2 pt-1">
            <Button size="sm" disabled={pending} onClick={() => decide("approve")}>Duyệt</Button>
            <ReasonAction label="Từ chối" variant="destructive" placeholder="Lý do từ chối (gia sư sẽ thấy)" onSubmit={(reason) => decide("reject", { reason })} />
          </div>
        )}
        <Button size="sm" variant="link" className="w-fit px-0" onClick={() => setShowHistory(!showHistory)}>
          {showHistory ? "Ẩn lịch sử" : "Xem lịch sử duyệt"}
        </Button>
        {showHistory && (
          <ul className="grid gap-1 text-xs text-muted-foreground">
            {detail?.history.map((h, i) => (
              <li key={i}>{fmtDateTime(h.createdAt)} · {ACTION_LABEL[h.action] ?? h.action}{h.reason ? ` · ${h.reason}` : ""}</li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}
