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
export type PublicTutor = Pick<TutorProfile, "accountId" | "fullName" | "bio" | "experience" | "teachingMode" | "rank" | "subjects" | "areaCodes"> & {
  /** Only on the profile endpoint, not in search results. completionRate null = no data yet. */
  taughtSessions?: number; completionRate?: number | null; tutorFaultFailed?: number;
  /** Approved YouTube links; newcomer = within 30 days of the first approval. */
  videos?: string[]; newcomer?: boolean;
};
export type SearchPage = { items: PublicTutor[]; page: number; size: number; total: number };
export type Review = { id: string; planId: string; content: string; createdAt: string; reply: string | null; repliedAt: string | null };
export type FullReview = Review & {
  tutorId: string; learnerId: string; hidden: boolean; reportReason: string | null; reportedAt: string | null;
};
export type ReviewModeration = { action: "HIDE" | "RESTORE"; reason: string; actorId: string; createdAt: string };

export type RankFlag = "OK" | "ELIGIBLE_UP" | "MUST_REVIEW";
export type ExpStats = { taughtSessions: number; tutorFaultFailed: number; distinctLearners: number; renewals: number; openIssues: number; syncedAt: string | null };
export type RankView = {
  accountId: string; fullName: string; rank: number; entryRank2: boolean; rankSince: string | null; exp: number;
  stats: ExpStats; completionRate: number | null; flag: RankFlag;
};
export type ExpEntry = {
  id: string; sourceKey: string; type: "INITIAL" | "SESSION_ON_TIME" | "PERIOD_COMPLETED" | "RENEWAL" | "PENALTY";
  points: number; refType: string | null; refId: string | null; reason: string | null; occurredAt: string; createdAt: string;
};
export type RankDecision = { fromRank: number; toRank: number; reason: string; actorId: string; createdAt: string };
export type RankDetail = { view: RankView; entries: ExpEntry[]; decisions: RankDecision[] };
export type HistoryEntry = { action: string; reason: string | null; actorId: string; createdAt: string };

export type Slot = {
  id: string; tutorId: string; dayOfWeek: number; startTime: string; endTime: string; mode: Mode;
  effectiveFrom: string; effectiveTo: string | null;
};

export type PlanStatus = "REQUESTED" | "PROPOSED" | "CONFIRMED" | "HOAN_TAT_NGHIA_VU" | "KET_THUC_CO_HOAN_MOT_PHAN" | "HET_KY_CON_NGHIA_VU"
  | "REJECTED" | "CANCELLED" | "EXPIRED";
export type Plan = {
  id: string; learnerId: string; tutorId: string; subjectCode: string; levelCode: string; mode: "ONLINE" | "OFFLINE";
  note: string; status: PlanStatus; paymentStatus: string | null; rejectReason: string | null;
  startDate: string | null; endDate: string | null; holdExpiresAt: string | null; meetLink: string | null;
  createdAt: string; proposedAt: string | null; confirmedAt: string | null; closedAt: string | null;
  /** Grid flow; null on older free-text requests. */
  sessionsPerWeek: number | null; weeks: number | null;
  /** Renewal: the period this one continues; renewalQualifiedAt is set once the old period completed and this one is paid. */
  renewedFrom: string | null; renewalQualifiedAt: string | null;
};
export type SessionStatus = "HELD" | "SCHEDULED" | "IN_PROGRESS" | "CANCEL_REPORTED" | "COMPLETED_PENDING_CONFIRM" | "CONFIRMED" | "DISPUTED"
  | "LEARNER_ABSENT_PENDING" | "LEARNER_ABSENT" | "TUTOR_ABSENT_REPORTED" | "FAILED_TUTOR" | "CANCELLED" | "RELEASED";

export type CaseOutcome = "TUTOR_FAULT" | "LEARNER_WRONG" | "NO_FAULT";
export type CaseInfo = {
  id: string; sessionId: string; planId: string; learnerId: string; tutorId: string; kind: "DISPUTE" | "TUTOR_ABSENT";
  status: "OPEN" | "DECIDED" | "APPEALED"; reason: string; evidenceDeadline: string; outcome: CaseOutcome | null;
  decisionReason: string | null; decidedBy: string | null; decidedAt: string | null; createdAt: string; updatedAt: string;
};
export type CaseEvidence = { id: number; submittedBy: string; party: "LEARNER" | "TUTOR"; url: string; note: string | null; createdAt: string };
export type CaseEvent = { action: "OPENED" | "DECIDED" | "APPEALED"; actorId: string | null; note: string | null; createdAt: string };
export type CaseDetail = { caseInfo: CaseInfo; session: Session; evidence: CaseEvidence[]; events: CaseEvent[]; suggestedOutcome: CaseOutcome | null };
export type Strike = {
  id: number; kind: "SELF_CANCEL" | "NO_SHOW" | "FALSE_COMPLAINT"; caseId: string | null; sessionId: string | null; reason: string;
  restrictedUntil: string | null; revokedAt: string | null; createdAt: string;
};
export type Restriction = { activeStrikes: number; restrictedUntil: string | null; strikes: Strike[] };

export type ReviewStatus = "PENDING" | "APPROVED" | "REJECTED";
export type TutorVideo = { id: string; accountId: string; url: string; status: ReviewStatus; rejectReason: string | null; reviewedAt: string | null; createdAt: string };
export type SubjectItem = { accountId: string; subjectCode: string; levelCode: string; status: ReviewStatus; rejectReason: string | null };
export type TutorEvidence = { id: number; kind: "DEGREE" | "EXPERIENCE"; url: string; note: string | null; createdAt: string };
export type ProfileExtras = { videos: TutorVideo[]; subjectRequests: SubjectItem[]; evidence: TutorEvidence[] };
export type ReviewQueue = { videos: TutorVideo[]; subjects: SubjectItem[] };
export type Session = {
  id: string; planId: string; tutorId: string; learnerId: string; startAt: string; endAt: string; status: SessionStatus;
  meetLink: string | null; cancelReason: string | null; cancelledBy: string | null; cancelledAt: string | null;
  completedAt: string | null; confirmDeadline: string | null; confirmedAt: string | null; autoConfirmed: boolean; disputeReason: string | null;
  /** Date range of the learner's "can attend" offer on a CANCEL_REPORTED session, waiting for the tutor to pick a slot. */
  offerFrom: string | null; offerTo: string | null;
  /** Server time the tutor pressed "start"; lateMinutes only when more than 10 minutes late. */
  startedAt: string | null; lateMinutes: number | null;
  earlyEndBy: "LEARNER" | "TUTOR" | null; earlyEndNote: string | null;
  absentNote: string | null; absentDeadline: string | null; cancelDeadline: string | null;
};
export type WeekCell = { dayOfWeek: number; startTime: string };
export type WeekWindow = { dayOfWeek: number; startTime: string; endTime: string };
export type SlotChange = {
  id: string; planId: string; requestedBy: string; fromDay: number; fromTime: string; toDay: number; toTime: string;
  effectiveFrom: string; status: "PENDING" | "ACCEPTED" | "DECLINED" | "EXPIRED" | "CANCELLED"; declineReason: string | null;
  expiresAt: string; createdAt: string; decidedAt: string | null;
};
export type PlanDetail = { plan: Plan; sessions: Session[]; preferredSlots: WeekCell[]; pendingSlotChange: SlotChange | null };
export type PlanContact = { learnerPhone: string | null; tutorPhone: string | null };
export type Me = { accountId: string; username: string | null; email: string | null; phone: string | null; role: string; status: string };

export type Notice = { id: string; type: string; title: string; body: string; refType: string | null; refId: string | null; createdAt: string; readAt: string | null };
