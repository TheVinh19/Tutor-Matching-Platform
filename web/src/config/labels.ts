import type { CaseInfo, CaseOutcome, ExpEntry, Mode, PlanStatus, ProfileStatus, RankFlag, ReviewStatus, SessionStatus, Strike } from "@/types/api";

type Tone = "default" | "secondary" | "outline" | "destructive";

export const MODE_LABEL: Record<Mode, string> = { ONLINE: "Online", OFFLINE: "Trực tiếp", FLEX: "Linh hoạt" };
export const DAY_LABEL = ["", "Thứ 2", "Thứ 3", "Thứ 4", "Thứ 5", "Thứ 6", "Thứ 7", "Chủ nhật"];

export const PROFILE_STATUS: Record<ProfileStatus, [string, Tone]> = {
  DRAFT: ["Nháp", "outline"], PENDING: ["Chờ duyệt", "secondary"], APPROVED: ["Đã duyệt", "default"], REJECTED: ["Bị từ chối", "destructive"],
};
export const PLAN_STATUS: Record<PlanStatus, [string, Tone]> = {
  REQUESTED: ["Chờ gia sư lập kế hoạch", "secondary"], PROPOSED: ["Chờ Học sinh xác nhận", "secondary"],
  CONFIRMED: ["Đang học", "default"], HOAN_TAT_NGHIA_VU: ["Hoàn tất nghĩa vụ", "outline"],
  KET_THUC_CO_HOAN_MOT_PHAN: ["Kết thúc, hoàn một phần", "outline"], HET_KY_CON_NGHIA_VU: ["Hết kỳ, còn nghĩa vụ", "destructive"],
  REJECTED: ["Gia sư từ chối", "destructive"],
  CANCELLED: ["Đã hủy", "outline"], EXPIRED: ["Hết hạn giữ lịch", "outline"],
};
export const SESSION_STATUS: Record<SessionStatus, [string, Tone]> = {
  HELD: ["Đang giữ chỗ", "secondary"], SCHEDULED: ["Đã chốt", "default"], IN_PROGRESS: ["Đang học", "default"],
  CANCEL_REPORTED: ["Báo bận/hủy – chờ xử lý", "destructive"],
  COMPLETED_PENDING_CONFIRM: ["Chờ xác nhận", "secondary"], CONFIRMED: ["Đã xác nhận", "outline"], DISPUTED: ["Bị phản đối", "destructive"],
  LEARNER_ABSENT_PENDING: ["Gia sư báo học sinh vắng", "destructive"], LEARNER_ABSENT: ["Học sinh vắng", "outline"],
  TUTOR_ABSENT_REPORTED: ["Báo gia sư vắng", "destructive"], FAILED_TUTOR: ["Thất bại do gia sư", "destructive"],
  CANCELLED: ["Đã hủy", "outline"], RELEASED: ["Đã giải phóng", "outline"],
};
export const RANK_LABEL = ["", "Hạng 1 · Cơ bản", "Hạng 2 · Kinh nghiệm", "Hạng 3 · Chuyên gia"];
export const RANK_FLAG: Record<RankFlag, [string, Tone]> = {
  OK: ["Ổn định", "outline"], ELIGIBLE_UP: ["Đủ điều kiện lên Hạng", "default"], MUST_REVIEW: ["Mất điều kiện giữ Hạng", "destructive"],
};
export const EXP_TYPE: Record<ExpEntry["type"], string> = {
  INITIAL: "Khởi tạo Hạng 2 đầu vào", SESSION_ON_TIME: "Buổi dạy đúng giờ", PERIOD_COMPLETED: "Kỳ hoàn tất nghĩa vụ",
  RENEWAL: "Gia hạn hợp lệ", PENALTY: "Phạt sau xác minh",
};
/** Rank completion rate; P12: no sample shows "chưa có dữ liệu". */
export const fmtRate = (rate: number | null | undefined) => (rate == null ? "Chưa có dữ liệu" : `${Math.round(rate * 1000) / 10}%`);
export const CASE_STATUS: Record<CaseInfo["status"], [string, Tone]> = {
  OPEN: ["Đang xử lý", "secondary"], DECIDED: ["Đã kết luận", "outline"], APPEALED: ["Đang kháng nghị", "destructive"],
};
export const CASE_KIND: Record<CaseInfo["kind"], string> = { DISPUTE: "Phản đối buổi học", TUTOR_ABSENT: "Gia sư không dạy" };
export const OUTCOME_LABEL: Record<CaseOutcome, string> = {
  TUTOR_FAULT: "Lỗi gia sư", LEARNER_WRONG: "Người thuê khiếu nại sai", NO_FAULT: "Không lỗi bên nào",
};
export const STRIKE_LABEL: Record<Strike["kind"], string> = { SELF_CANCEL: "Tự ý hủy", NO_SHOW: "Vắng không báo", FALSE_COMPLAINT: "Khiếu nại sai" };
export const REVIEW_STATUS: Record<ReviewStatus, [string, Tone]> = {
  PENDING: ["Chờ duyệt", "secondary"], APPROVED: ["Đã duyệt", "default"], REJECTED: ["Bị từ chối", "destructive"],
};
export const PARTY_LABEL = { LEARNER: "Học sinh", TUTOR: "Gia sư" } as const;
/** Periods that are over normally: contact stays visible, the learner may review within 14 days. */
export const CLOSED_PLAN: PlanStatus[] = ["HOAN_TAT_NGHIA_VU", "KET_THUC_CO_HOAN_MOT_PHAN"];
