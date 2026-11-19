// Response shapes of the backend (see docs/api/README.md).
export type Mode = "ONLINE" | "OFFLINE" | "FLEX";
export type SubjectLevel = { subjectCode: string; levelCode: string };
export type Catalog = {
  subjects: { code: string; name: string; primarySchool: boolean }[];
  levels: { code: string; name: string }[];
  areas: { code: string; province: string; name: string }[];
};

export type ProfileStatus = "DRAFT" | "PENDING" | "APPROVED" | "REJECTED";
export type TutorProfile = {
  accountId: string; fullName: string; bio: string; experience: string; teachingMode: Mode; rank: number;
  status: ProfileStatus; rejectReason: string | null; submittedAt: string | null; reviewedAt: string | null;
  subjects: SubjectLevel[]; areaCodes: string[];
};
export type PublicTutor = Pick<TutorProfile, "accountId" | "fullName" | "bio" | "experience" | "teachingMode" | "rank" | "subjects" | "areaCodes">;
export type SearchPage = { items: PublicTutor[]; page: number; size: number; total: number };
export type Review = { id: string; planId: string; content: string; createdAt: string };
export type HistoryEntry = { action: string; reason: string | null; actorId: string; createdAt: string };

export type Slot = {
  id: string; tutorId: string; dayOfWeek: number; startTime: string; endTime: string; mode: Mode;
  effectiveFrom: string; effectiveTo: string | null;
};

export type PlanStatus = "REQUESTED" | "PROPOSED" | "CONFIRMED" | "COMPLETED" | "REJECTED" | "CANCELLED" | "EXPIRED";
export type Plan = {
  id: string; learnerId: string; tutorId: string; subjectCode: string; levelCode: string; mode: "ONLINE" | "OFFLINE";
  note: string; status: PlanStatus; paymentStatus: string | null; rejectReason: string | null;
  startDate: string | null; endDate: string | null; holdExpiresAt: string | null; meetLink: string | null;
  createdAt: string; proposedAt: string | null; confirmedAt: string | null; closedAt: string | null;
  /** Grid flow; null on older free-text requests. */
  sessionsPerWeek: number | null; weeks: number | null;
};
export type SessionStatus = "HELD" | "SCHEDULED" | "CANCEL_REPORTED" | "COMPLETED_PENDING_CONFIRM" | "CONFIRMED" | "DISPUTED" | "CANCELLED" | "RELEASED";
export type Session = {
  id: string; planId: string; tutorId: string; learnerId: string; startAt: string; endAt: string; status: SessionStatus;
  meetLink: string | null; cancelReason: string | null; cancelledBy: string | null; cancelledAt: string | null;
  completedAt: string | null; confirmDeadline: string | null; confirmedAt: string | null; autoConfirmed: boolean; disputeReason: string | null;
};
export type WeekCell = { dayOfWeek: number; startTime: string };
export type WeekWindow = { dayOfWeek: number; startTime: string; endTime: string };
export type PlanDetail = { plan: Plan; sessions: Session[]; preferredSlots: WeekCell[] };

export type Notice = { id: string; type: string; title: string; body: string; refType: string | null; refId: string | null; createdAt: string; readAt: string | null };
