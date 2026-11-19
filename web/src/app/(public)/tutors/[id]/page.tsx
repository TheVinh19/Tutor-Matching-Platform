import { TutorDetail } from "@/features/tutors/tutor-detail";
import { getSession } from "@/lib/server-session";

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const session = await getSession();
  return <TutorDetail id={id} role={session?.role ?? null} />;
}
