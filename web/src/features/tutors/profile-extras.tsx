"use client";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { ErrorBox, NativeSelect, StatusBadge, useAction } from "@/components/common";
import { REVIEW_STATUS } from "@/config/labels";
import { useLoad } from "@/hooks/use-load";
import { api, fmtDate } from "@/lib/api";
import { useCatalog } from "@/features/tutors/catalog";
import type { ProfileExtras, TutorProfile } from "@/types/api";

const EVIDENCE_LABEL = { DEGREE: "Bằng cấp / chứng chỉ", EXPERIENCE: "Kinh nghiệm ≥ 1 năm" } as const;

/** /tutor/profile, below the editor: videos (P12), subjects added after approval (P09), rank 2 entry evidence. */
export function ProfileExtrasPanel() {
  const { data: profile } = useLoad<TutorProfile>("/v1/tutors/me/profile");
  const { data, error, reload } = useLoad<ProfileExtras>(profile ? "/v1/tutors/me/extras" : null);
  const { catalog, subjectLevel } = useCatalog();
  const [video, setVideo] = useState("");
  const [subject, setSubject] = useState("");
  const [level, setLevel] = useState("");
  const [kind, setKind] = useState<"DEGREE" | "EXPERIENCE">("DEGREE");
  const [url, setUrl] = useState("");
  const [note, setNote] = useState("");
  const act = useAction();
  if (!profile) return null;
  const post = (path: string, body: unknown, reset: () => void) => act.run(async () => {
    await api(`/v1/tutors/me/${path}`, { method: "POST", body }); reset(); await reload();
  });
  const liveVideos = data?.videos.filter((v) => v.status !== "REJECTED").length ?? 0;

  return (
    <div className="mt-6 grid gap-4 lg:grid-cols-2">
      <ErrorBox error={error ?? act.error} />
      <Card>
        <CardHeader><CardTitle>Video dạy minh họa</CardTitle></CardHeader>
        <CardContent className="grid gap-2 text-sm">
          <p className="text-muted-foreground">Dán link YouTube, tối đa 3 video. Hồ sơ chỉ hiện công khai khi có ít nhất 1 video được duyệt.</p>
          <ul className="grid gap-1">
            {data?.videos.map((v) => (
              <li key={v.id} className="flex flex-wrap items-center justify-between gap-2">
                <a href={v.url} target="_blank" rel="noreferrer" className="break-all text-primary hover:underline">{v.url}</a>
                <span className="flex items-center gap-1"><StatusBadge value={REVIEW_STATUS[v.status]} />{v.rejectReason && <span className="text-xs text-muted-foreground">{v.rejectReason}</span>}</span>
              </li>
            ))}
          </ul>
          {liveVideos < 3 && (
            <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); void post("videos", { url: video }, () => setVideo("")); }}>
              <Input type="url" aria-label="Link YouTube" required placeholder="https://www.youtube.com/watch?v=..." value={video} onChange={(e) => setVideo(e.target.value)} />
              <Button type="submit" disabled={act.pending}>Gửi duyệt</Button>
            </form>
          )}
        </CardContent>
      </Card>

      {profile.status === "APPROVED" && (
        <Card>
          <CardHeader><CardTitle>Thêm môn / cấp học</CardTitle></CardHeader>
          <CardContent className="grid gap-2 text-sm">
            <p className="text-muted-foreground">Môn mới được admin duyệt riêng, chưa dùng cho tìm kiếm và đặt lịch đến khi được duyệt.</p>
            <ul className="grid gap-1">
              {data?.subjectRequests.map((s) => (
                <li key={`${s.subjectCode}-${s.levelCode}`} className="flex items-center justify-between gap-2">
                  {subjectLevel(s)} <span className="flex items-center gap-1"><StatusBadge value={REVIEW_STATUS[s.status]} />{s.rejectReason && <span className="text-xs text-muted-foreground">{s.rejectReason}</span>}</span>
                </li>
              ))}
            </ul>
            <form className="flex flex-wrap gap-2" onSubmit={(e) => {
              e.preventDefault(); void post("subjects", { subjectCode: subject, levelCode: level }, () => { setSubject(""); setLevel(""); });
            }}>
              <NativeSelect aria-label="Môn" required className="w-40" value={subject} onChange={(e) => setSubject(e.target.value)}>
                <option value="">Môn…</option>{catalog?.subjects.map((s) => <option key={s.code} value={s.code}>{s.name}</option>)}
              </NativeSelect>
              <NativeSelect aria-label="Cấp học" required className="w-40" value={level} onChange={(e) => setLevel(e.target.value)}>
                <option value="">Cấp…</option>{catalog?.levels.map((l) => <option key={l.code} value={l.code}>{l.name}</option>)}
              </NativeSelect>
              <Button type="submit" disabled={act.pending}>Gửi duyệt</Button>
            </form>
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader><CardTitle>Minh chứng xét Hạng 2 đầu vào</CardTitle></CardHeader>
        <CardContent className="grid gap-2 text-sm">
          <p className="text-muted-foreground">Cần đủ hai nhóm: bằng ĐH/CĐ đúng chuyên ngành hoặc chứng chỉ tương đương, và kinh nghiệm dạy ≥ 1 năm (hợp đồng, xác nhận trung tâm…). Gửi link Drive/ảnh.</p>
          <ul className="grid gap-1">
            {data?.evidence.map((e) => (
              <li key={e.id}><span className="font-medium">{EVIDENCE_LABEL[e.kind]}:</span>{" "}
                <a href={e.url} target="_blank" rel="noreferrer" className="break-all text-primary hover:underline">{e.url}</a>
                <span className="text-xs text-muted-foreground"> · {fmtDate(e.createdAt)}{e.note && ` · ${e.note}`}</span></li>
            ))}
          </ul>
          <form className="grid gap-2" onSubmit={(e) => { e.preventDefault(); void post("evidence", { kind, url, note: note || null }, () => { setUrl(""); setNote(""); }); }}>
            <div className="flex gap-1.5" role="group" aria-label="Nhóm minh chứng">
              {(["DEGREE", "EXPERIENCE"] as const).map((k) => (
                <Button key={k} type="button" size="sm" variant={k === kind ? "default" : "outline"} aria-pressed={k === kind} onClick={() => setKind(k)}>{EVIDENCE_LABEL[k]}</Button>
              ))}
            </div>
            <Input type="url" aria-label="Link minh chứng" required placeholder="https://drive.google.com/..." value={url} onChange={(e) => setUrl(e.target.value)} />
            <Input aria-label="Ghi chú minh chứng" maxLength={1000} placeholder="Ghi chú (tùy chọn)" value={note} onChange={(e) => setNote(e.target.value)} />
            <Button type="submit" className="w-fit" disabled={act.pending}>Thêm minh chứng</Button>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
