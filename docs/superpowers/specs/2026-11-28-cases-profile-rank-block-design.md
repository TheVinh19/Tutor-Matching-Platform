# Vụ việc, chế tài, hồ sơ (video/môn mới/minh chứng/nhãn mới), chặn kế hoạch khi mất điều kiện Hạng

Ngày: 2026-11-28. Nhánh: `feature/exp-rank-reviews`. Người dùng đã duyệt trong hội thoại.

## 1. Vụ việc (booking, P07/P12/P14)

- Buổi chuyển `DISPUTED` hoặc `TUTOR_ABSENT_REPORTED` tự mở vụ (`cases`, một vụ/buổi), hạn bằng chứng gia sư 72h, báo gia sư.
- Bằng chứng: link https, tối đa 5 link/lần, không xóa (`case_evidence`). Gia sư nộp trong hạn khi vụ `OPEN`, lúc nào cũng được khi `APPEALED`; người thuê nộp khi vụ còn mở.
- Admin kết luận (`OPEN`/`APPEALED` → `DECIDED`): `TUTOR_FAULT` → buổi `FAILED_TUTOR` (tính vào `tutorFaultFailed`); `LEARNER_WRONG` → buổi `CONFIRMED` + một vi phạm `FALSE_COMPLAINT`; `NO_FAULT` → buổi `CANCELLED`. Hết 72h không có bằng chứng gia sư: chi tiết vụ gợi ý `TUTOR_FAULT`, admin vẫn bấm.
- Kháng nghị: hai bên, không giới hạn số lần, `DECIDED` → `APPEALED`. Xét lại đổi kết luận thì cập nhật buổi và thu hồi vi phạm cũ của vụ.
- Sau kết luận gọi `closeIfDone`; `FAILED_TUTOR` là buổi xong, có hoàn một phần → `KET_THUC_CO_HOAN_MOT_PHAN`.
- shortcut: xét lại sau khi kỳ đã đóng chỉ đổi buổi, không mở lại kỳ.

## 2. Chế tài người thuê (booking)

`learner_strikes` (`SELF_CANCEL`, `NO_SHOW`, `FALSE_COMPLAINT`), admin ghi tay hai loại đầu. Vi phạm thứ 3 (chưa thu hồi) trong 90 ngày → `restricted_until = now + 30 ngày`; tạo kế hoạch mới bị `409 LEARNER_RESTRICTED`. Kỳ đã chốt không ảnh hưởng.

## 3–4. Hồ sơ (tutor-service)

- `tutor_videos`: tối đa 3, link youtube.com/youtu.be, admin duyệt/từ chối. Tìm kiếm và hồ sơ công khai cần ≥ 1 video `APPROVED`. Seed demo có video đã duyệt.
- `tutor_subjects.status` (`APPROVED`/`PENDING`): hồ sơ đã duyệt thêm môn → `PENDING`; chỉ môn `APPROVED` dùng cho tìm kiếm, công khai, đặt lịch; admin duyệt/từ chối môn.
- `tutor_evidence` (`DEGREE`, `EXPERIENCE`, link + ghi chú). `entryRank2` cần đủ hai nhóm (`409 EVIDENCE_REQUIRED`).
- Nhãn mới: `newcomer` = lần `APPROVE` đầu tiên trong 30 ngày.

## 6. Chặn kế hoạch

Hồ sơ nội bộ thêm `rankUnderReview` (cờ `MUST_REVIEW`). Booking từ chối tạo yêu cầu mới và propose (`409 RANK_UNDER_REVIEW`). Admin quyết định Hạng → cờ hết → tự gỡ.
