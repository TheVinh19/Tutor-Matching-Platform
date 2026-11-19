import type { Mode, PlanStatus, ProfileStatus, SessionStatus } from "@/types/api";

type Tone = "default" | "secondary" | "outline" | "destructive";

export const MODE_LABEL: Record<Mode, string> = { ONLINE: "Online", OFFLINE: "Trực tiếp", FLEX: "Linh hoạt" };
export const DAY_LABEL = ["", "Thứ 2", "Thứ 3", "Thứ 4", "Thứ 5", "Thứ 6", "Thứ 7", "Chủ nhật"];

export const PROFILE_STATUS: Record<ProfileStatus, [string, Tone]> = {
  DRAFT: ["Nháp", "outline"], PENDING: ["Chờ duyệt", "secondary"], APPROVED: ["Đã duyệt", "default"], REJECTED: ["Bị từ chối", "destructive"],
};
export const PLAN_STATUS: Record<PlanStatus, [string, Tone]> = {
  REQUESTED: ["Chờ gia sư lập kế hoạch", "secondary"], PROPOSED: ["Chờ Học sinh xác nhận", "secondary"],
  CONFIRMED: ["Đã chốt lịch", "default"], COMPLETED: ["Hoàn tất", "outline"], REJECTED: ["Gia sư từ chối", "destructive"],
  CANCELLED: ["Đã hủy", "outline"], EXPIRED: ["Hết hạn giữ lịch", "outline"],
};
export const SESSION_STATUS: Record<SessionStatus, [string, Tone]> = {
  HELD: ["Đang giữ chỗ", "secondary"], SCHEDULED: ["Đã chốt", "default"], CANCEL_REPORTED: ["Báo bận/hủy – chờ xử lý", "destructive"],
  COMPLETED_PENDING_CONFIRM: ["Chờ xác nhận", "secondary"], CONFIRMED: ["Đã xác nhận", "outline"], DISPUTED: ["Bị phản đối", "destructive"],
  CANCELLED: ["Đã hủy", "outline"], RELEASED: ["Đã giải phóng", "outline"],
};
