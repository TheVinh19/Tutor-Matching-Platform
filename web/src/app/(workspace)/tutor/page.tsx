import Link from "next/link";
import { buttonVariants } from "@/components/ui/button";
import { PageTitle } from "@/components/common";
import { PlansList } from "@/features/booking/plans-list";

export default function Page() {
  return (
    <>
      <PageTitle title="Tổng quan" description="Yêu cầu học gửi tới bạn và các kế hoạch đang chạy.">
        <div className="flex gap-2">
          <Link href="/tutor/profile" className={buttonVariants({ variant: "outline" })}>Hồ sơ</Link>
          <Link href="/tutor/availability" className={buttonVariants()}>Lịch rảnh</Link>
        </div>
      </PageTitle>
      <PlansList base="/tutor" title="Yêu cầu và kế hoạch" emptyText="Chưa có yêu cầu. Hồ sơ cần được duyệt và có lịch rảnh để Học sinh tìm thấy bạn." />
    </>
  );
}
