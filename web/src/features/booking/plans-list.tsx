"use client";
import Link from "next/link";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Empty, ErrorBox, StatusBadge } from "@/components/common";
import { MODE_LABEL, PLAN_STATUS } from "@/config/labels";
import { useLoad } from "@/hooks/use-load";
import { fmtDate, fmtDateTime } from "@/lib/api";
import { useCatalog } from "@/features/tutors/catalog";
import type { Plan } from "@/types/api";

export function PlansList({ base, title, emptyText }: { base: "/learner" | "/tutor"; title: string; emptyText: React.ReactNode }) {
  const { data, error } = useLoad<Plan[]>("/v1/bookings/plans/me");
  const { subject, level } = useCatalog();
  return (
    <Card>
      <CardHeader><CardTitle>{title}</CardTitle></CardHeader>
      <CardContent className="grid gap-2">
        <ErrorBox error={error} />
        {data?.length === 0 && <Empty>{emptyText}</Empty>}
        {data?.map((p) => (
          <Link key={p.id} href={`${base}/plans/${p.id}`} className="grid gap-1 rounded-lg border p-3 hover:border-primary/50 hover:bg-accent/30">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="font-medium">{subject(p.subjectCode)} {level(p.levelCode)} · {MODE_LABEL[p.mode]}</span>
              <StatusBadge value={PLAN_STATUS[p.status]} />
            </div>
            <span className="text-sm text-muted-foreground">
              {p.startDate ? `${fmtDate(p.startDate)} → ${fmtDate(p.endDate)}` : `Gửi lúc ${fmtDateTime(p.createdAt)}`}
              {p.status === "PROPOSED" && p.holdExpiresAt && ` · giữ lịch đến ${fmtDateTime(p.holdExpiresAt)}`}
            </span>
          </Link>
        ))}
      </CardContent>
    </Card>
  );
}
