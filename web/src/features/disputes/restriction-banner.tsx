"use client";
import { useLoad } from "@/hooks/use-load";
import { fmtDate } from "@/lib/api";
import type { Restriction } from "@/types/api";

/** Learner home: verified strikes and the 30-day block on new plans (P14). Hidden when there is nothing to say. */
export function RestrictionBanner() {
  const { data } = useLoad<Restriction>("/v1/bookings/me/restriction");
  if (!data || data.activeStrikes === 0) return null;
  return (
    <p className={`rounded-lg p-3 text-sm ${data.restrictedUntil ? "bg-destructive/10 text-destructive" : "bg-accent"}`}>
      {data.restrictedUntil
        ? `Tài khoản bị tạm khóa đặt đơn mới đến ${fmtDate(data.restrictedUntil)} (3 vi phạm trong 90 ngày). Các kỳ đã chốt vẫn học bình thường.`
        : `Cảnh báo: ${data.activeStrikes}/3 vi phạm trong 90 ngày. Vi phạm thứ 3 sẽ khóa đặt đơn mới 30 ngày.`}
    </p>
  );
}
