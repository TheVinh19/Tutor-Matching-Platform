"use client";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Empty, ErrorBox, PageTitle } from "@/components/common";
import { useLoad } from "@/hooks/use-load";
import { api, fmtDateTime } from "@/lib/api";
import { cn } from "@/lib/utils";
import type { Notice } from "@/types/api";

export function NotificationsView({ base }: { base: "/learner" | "/tutor" }) {
  const { data, error, reload } = useLoad<Notice[]>("/v1/notifications/me");
  const markRead = async (id: string) => { await api(`/v1/notifications/${id}/read`, { method: "PATCH" }); await reload(); };
  return (
    <>
      <PageTitle title="Thông báo" description="50 thông báo gần nhất." />
      <ErrorBox error={error} />
      {data?.length === 0 && <Empty>Chưa có thông báo.</Empty>}
      <ul className="grid gap-2">
        {data?.map((n) => (
          <li key={n.id} className={cn("flex flex-wrap items-start justify-between gap-3 rounded-lg border p-3", !n.readAt && "border-primary/40 bg-accent/40")}>
            <div className="grid gap-0.5">
              <span className="font-medium">{n.title}</span>
              {n.body && <span className="text-sm text-muted-foreground">{n.body}</span>}
              <span className="text-xs text-muted-foreground">{fmtDateTime(n.createdAt)}</span>
            </div>
            <div className="flex gap-2">
              {n.refType === "PLAN" && n.refId && <Link href={`${base}/plans/${n.refId}`} className="text-sm text-primary hover:underline">Xem</Link>}
              {!n.readAt && <Button size="sm" variant="ghost" onClick={() => markRead(n.id)}>Đã đọc</Button>}
            </div>
          </li>
        ))}
      </ul>
    </>
  );
}
