import Link from "next/link";
import { buttonVariants } from "@/components/ui/button";
import { PageTitle } from "@/components/common";
import { PlansList } from "@/features/booking/plans-list";
import { RestrictionBanner } from "@/features/disputes/restriction-banner";

export default function Page() {
  return (
    <>
      <PageTitle title="Tổng quan" description="Yêu cầu học và kế hoạch bạn đứng tên.">
        <Link href="/tutors" className={buttonVariants()}>Tìm gia sư</Link>
      </PageTitle>
      <RestrictionBanner />
      <PlansList base="/learner" title="Kế hoạch học của bạn" emptyText="Chưa có yêu cầu nào. Hãy tìm gia sư và gửi yêu cầu học." />
    </>
  );
}
