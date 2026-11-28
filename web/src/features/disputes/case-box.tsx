"use client";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { ErrorBox, ReasonAction, StatusBadge, useAction } from "@/components/common";
import { CASE_KIND, CASE_STATUS, OUTCOME_LABEL, PARTY_LABEL } from "@/config/labels";
import { useLoad } from "@/hooks/use-load";
import { api, fmtDateTime } from "@/lib/api";
import type { CaseDetail, CaseOutcome } from "@/types/api";

type Viewer = "LEARNER" | "TUTOR" | "ADMIN";
const EVENT_LABEL = { OPENED: "Mở vụ việc", DECIDED: "Kết luận", APPEALED: "Kháng nghị" } as const;

/**
 * One case (P07/P14): reason, evidence links from both sides, history. Parties add evidence and appeal; the admin decides.
 * path: `/v1/bookings/sessions/{id}/case` from a session card, or `/v1/bookings/cases/{id}` on the admin page.
 */
export function CaseBox({ path, viewer, onChange }: { path: string; viewer: Viewer; onChange?: () => void }) {
  const { data, error, reload } = useLoad<CaseDetail>(path);
  const [urls, setUrls] = useState("");
  const [note, setNote] = useState("");
  const [outcome, setOutcome] = useState<CaseOutcome | null>(null);
  const [reason, setReason] = useState("");
  const act = useAction();
  if (error) return <ErrorBox error={error} />;
  if (!data) return <p className="text-sm text-muted-foreground">Đang tải vụ việc…</p>;
  const c = data.caseInfo;
  const done = async () => { setUrls(""); setNote(""); setReason(""); await reload(); onChange?.(); };
  const post = (sub: string, body: unknown, admin = false) => act.run(async () => {
    await api(`/v1/bookings/${admin ? "admin/" : ""}cases/${c.id}/${sub}`, { method: "POST", body });
    await done();
  });
  const pastDeadline = new Date(c.evidenceDeadline).getTime() < Date.now();
  const canAddEvidence = viewer !== "ADMIN" && c.status !== "DECIDED" && !(viewer === "TUTOR" && c.status === "OPEN" && pastDeadline);
  const chosen = outcome ?? data.suggestedOutcome;

  return (
    <div className="grid gap-2 rounded-md border p-3 text-sm">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-medium">{CASE_KIND[c.kind]}</span>
        <StatusBadge value={CASE_STATUS[c.status]} />
      </div>
      <p><span className="font-medium">Nội dung:</span> {c.reason}</p>
      {c.status === "OPEN" && <p className="text-muted-foreground">Hạn gia sư nộp bằng chứng: {fmtDateTime(c.evidenceDeadline)}{pastDeadline && " (đã quá hạn)"}</p>}
      {c.outcome && <p><span className="font-medium">Kết luận:</span> {OUTCOME_LABEL[c.outcome]} · {c.decisionReason}</p>}

      <div className="grid gap-1">
        <span className="font-medium">Bằng chứng ({data.evidence.length})</span>
        {data.evidence.map((e) => (
          <a key={e.id} href={e.url} target="_blank" rel="noreferrer" className="w-fit break-all text-primary hover:underline">
            {PARTY_LABEL[e.party]} · {e.url}{e.note && <span className="text-muted-foreground"> · {e.note}</span>}
          </a>
        ))}
      </div>
      {canAddEvidence && (
        <form className="grid gap-1.5" onSubmit={(e) => {
          e.preventDefault();
          void post("evidence", { urls: urls.split(/\s+/).filter(Boolean), note: note || null });
        }}>
          <Textarea aria-label="Link bằng chứng" required placeholder="Link bằng chứng https, mỗi dòng một link (tối đa 5)" value={urls} onChange={(e) => setUrls(e.target.value)} />
          <Textarea aria-label="Ghi chú bằng chứng" maxLength={1000} placeholder="Ghi chú (tùy chọn)" value={note} onChange={(e) => setNote(e.target.value)} />
          <Button size="sm" type="submit" className="w-fit" disabled={act.pending || !urls.trim()}>Nộp bằng chứng</Button>
        </form>
      )}

      <ul className="grid gap-0.5 text-xs text-muted-foreground">
        {data.events.map((e, i) => <li key={i}>{fmtDateTime(e.createdAt)} · {EVENT_LABEL[e.action]}{e.note && ` · ${e.note}`}</li>)}
      </ul>
      <ErrorBox error={act.error} />

      {viewer !== "ADMIN" && c.status === "DECIDED" && (
        <ReasonAction label="Kháng nghị" placeholder="Căn cứ kháng nghị, có thể nộp thêm bằng chứng sau khi gửi" onSubmit={(r) => post("appeal", { reason: r })} />
      )}
      {viewer === "ADMIN" && c.status !== "DECIDED" && (
        <form className="grid gap-1.5 border-t pt-2" onSubmit={(e) => { e.preventDefault(); if (chosen) void post("decide", { outcome: chosen, reason }, true); }}>
          {data.suggestedOutcome && <p className="text-xs font-medium text-primary">Gia sư không nộp bằng chứng trong 72 giờ: gợi ý xử theo người thuê.</p>}
          <div className="flex flex-wrap gap-1.5" role="group" aria-label="Kết luận">
            {(Object.keys(OUTCOME_LABEL) as CaseOutcome[]).map((o) => (
              <Button key={o} type="button" size="sm" variant={o === chosen ? "default" : "outline"} aria-pressed={o === chosen} onClick={() => setOutcome(o)}>
                {OUTCOME_LABEL[o]}
              </Button>
            ))}
          </div>
          <Textarea aria-label="Căn cứ kết luận" required maxLength={2000} placeholder="Căn cứ: đối chiếu bằng chứng hai bên, nhật ký buổi học…" value={reason} onChange={(e) => setReason(e.target.value)} />
          <Button size="sm" type="submit" className="w-fit" disabled={act.pending || !chosen || !reason.trim()}>Lưu kết luận</Button>
        </form>
      )}
    </div>
  );
}
