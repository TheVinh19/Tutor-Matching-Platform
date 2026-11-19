import { PlanView } from "@/features/booking/plan-view";

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  return <PlanView id={(await params).id} role="TUTOR" />;
}
