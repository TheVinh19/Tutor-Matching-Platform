"use client";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { ErrorBox, NativeSelect, PageTitle, StatusBadge, useAction } from "@/components/common";
import { MODE_LABEL, PROFILE_STATUS } from "@/config/labels";
import { useLoad } from "@/hooks/use-load";
import { api } from "@/lib/api";
import { useCatalog } from "@/features/tutors/catalog";
import type { Mode, SubjectLevel, TutorProfile } from "@/types/api";

const key = (s: SubjectLevel) => `${s.subjectCode}|${s.levelCode}`;

export function ProfileEditor() {
  const { data: profile, error: loadError, reload } = useLoad<TutorProfile>("/v1/tutors/me/profile");
  const { catalog } = useCatalog();
  const [form, setForm] = useState({ fullName: "", bio: "", experience: "", teachingMode: "ONLINE" as Mode, subjects: [] as SubjectLevel[], areaCodes: [] as string[] });
  const save = useAction();
  const submit = useAction();

  useEffect(() => {
    if (profile) setForm({ fullName: profile.fullName, bio: profile.bio, experience: profile.experience, teachingMode: profile.teachingMode, subjects: profile.subjects, areaCodes: profile.areaCodes });
  }, [profile]);

  // 404 just means "no profile yet": start with an empty form.
  const noProfile = loadError?.code === "PROFILE_NOT_FOUND";
  const locked = profile?.status === "PENDING" || profile?.status === "APPROVED";
  const toggleSubject = (s: SubjectLevel) => setForm({ ...form, subjects: form.subjects.some((x) => key(x) === key(s)) ? form.subjects.filter((x) => key(x) !== key(s)) : [...form.subjects, s] });
  const toggleArea = (code: string) => setForm({ ...form, areaCodes: form.areaCodes.includes(code) ? form.areaCodes.filter((c) => c !== code) : [...form.areaCodes, code] });

  return (
    <>
      <PageTitle title="Hồ sơ gia sư" description="Admin duyệt hồ sơ trước khi bạn xuất hiện trong tìm kiếm và nhận học viên.">
        {profile && <StatusBadge value={PROFILE_STATUS[profile.status]} />}
      </PageTitle>
      {loadError && !noProfile && <ErrorBox error={loadError} />}
      {profile?.status === "REJECTED" && profile.rejectReason && (
        <p className="mb-4 rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-sm">Lý do từ chối: {profile.rejectReason}. Sửa hồ sơ rồi gửi duyệt lại.</p>
      )}
      {locked && (
        <p className="mb-4 rounded-lg bg-accent p-3 text-sm">
          {profile?.status === "PENDING" ? "Hồ sơ đang chờ duyệt, tạm thời không sửa được." : "Hồ sơ đã được duyệt. Việc sửa hồ sơ đã duyệt sẽ có ở bước sau."}
        </p>
      )}
      <form className="grid gap-6 lg:grid-cols-2" onSubmit={(e) => {
        e.preventDefault();
        void save.run(async () => { await api("/v1/tutors/me/profile", { method: "PUT", body: form }); await reload(); });
      }}>
        <fieldset disabled={locked} className="contents">
          <Card>
            <CardHeader><CardTitle>Thông tin</CardTitle></CardHeader>
            <CardContent className="grid gap-3">
              <div className="grid gap-1.5">
                <Label htmlFor="fullName">Họ tên</Label>
                <Input id="fullName" required maxLength={100} value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor="bio">Giới thiệu</Label>
                <Textarea id="bio" maxLength={2000} value={form.bio} onChange={(e) => setForm({ ...form, bio: e.target.value })} />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor="experience">Kinh nghiệm</Label>
                <Textarea id="experience" maxLength={2000} value={form.experience} onChange={(e) => setForm({ ...form, experience: e.target.value })} />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor="mode">Hình thức dạy</Label>
                <NativeSelect id="mode" value={form.teachingMode} onChange={(e) => setForm({ ...form, teachingMode: e.target.value as Mode })}>
                  {(Object.keys(MODE_LABEL) as Mode[]).map((m) => <option key={m} value={m}>{MODE_LABEL[m]}</option>)}
                </NativeSelect>
              </div>
            </CardContent>
          </Card>
          <Card>
            <CardHeader><CardTitle>Môn, cấp học và địa bàn</CardTitle></CardHeader>
            <CardContent className="grid gap-4">
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr><th className="py-1 text-left font-medium">Môn</th>{catalog?.levels.map((l) => <th key={l.code} className="px-1 font-medium">{l.name}</th>)}</tr>
                  </thead>
                  <tbody>
                    {catalog?.subjects.map((s) => (
                      <tr key={s.code} className="border-t">
                        <td className="py-1.5">{s.name}</td>
                        {catalog.levels.map((l) => {
                          const sl = { subjectCode: s.code, levelCode: l.code };
                          return (
                            <td key={l.code} className="text-center">
                              <input type="checkbox" aria-label={`${s.name} ${l.name}`} className="size-4 accent-primary"
                                checked={form.subjects.some((x) => key(x) === key(sl))} onChange={() => toggleSubject(sl)} />
                            </td>
                          );
                        })}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {form.teachingMode !== "ONLINE" && (
                <fieldset className="grid gap-1.5">
                  <legend className="mb-1 text-sm font-medium">Địa bàn dạy trực tiếp</legend>
                  <div className="grid grid-cols-2 gap-1.5 text-sm">
                    {catalog?.areas.map((a) => (
                      <label key={a.code} className="flex items-center gap-2">
                        <input type="checkbox" className="size-4 accent-primary" checked={form.areaCodes.includes(a.code)} onChange={() => toggleArea(a.code)} />
                        {a.name}, {a.province}
                      </label>
                    ))}
                  </div>
                </fieldset>
              )}
            </CardContent>
          </Card>
        </fieldset>
        <div className="flex flex-wrap items-center gap-3 lg:col-span-2">
          <ErrorBox error={save.error ?? submit.error} />
          <Button type="submit" variant="outline" disabled={locked || save.pending}>Lưu nháp</Button>
          {/* Save what is on screen first, so ticks not yet saved as draft are included. */}
          <Button type="button" disabled={locked || submit.pending}
            onClick={() => submit.run(async () => {
              await api("/v1/tutors/me/profile", { method: "PUT", body: form });
              await api("/v1/tutors/me/profile/submit", { method: "POST" });
              await reload();
            })}>
            Gửi duyệt
          </Button>
        </div>
      </form>
    </>
  );
}
