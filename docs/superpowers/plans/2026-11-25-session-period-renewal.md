# Buổi học, đổi giờ học, đóng kỳ, gia hạn — kế hoạch triển khai

> Thực hiện trực tiếp trong phiên (người dùng: "làm đi"). Checkbox theo dõi tiến độ.

**Mục tiêu:** làm đúng spec `docs/superpowers/specs/2026-11-25-session-period-renewal-design.md`.

**Kiến trúc:** một migration `V4`, mở rộng `SessionController`/`Plans`/`HoldJobs`/`PlanController`, thêm `plans/SlotChangeController`. Kỳ học vẫn là `plans`. Web sửa component có sẵn.

**Stack:** Spring Boot + JdbcClient + PostgreSQL (EXCLUDE constraint), Testcontainers; Next.js.

## Ràng buộc chung

- Giờ theo `Schedule.ZONE`; ca 120 phút, 08:00–22:00, cách 60 phút (`Schedule`).
- Mọi chuyển trạng thái buổi gọi `Plans.closeIfDone(planId)` (khóa dòng plan `FOR UPDATE`).
- Đến muộn: chỉ ghi `late_minutes`, không gửi thông báo.
- Không phân xử, không tiền.

## Điểm cần soi khi review

1. Hai xác nhận buổi đồng thời của cùng kỳ: kỳ vẫn phải đóng (khóa plan trong `closeIfDone`).
2. Đổi giờ học trùng lịch: rollback toàn bộ, không buổi nào bị dời.
3. Gia hạn bấm hai lần: chỉ một kỳ gia hạn mở (unique index).
4. Buổi cũ không có `started_at`: không tự nghiệm thu, học sinh vẫn xác nhận được.
5. Đổi giờ sang thứ sớm hơn trong tuần hiện tại: ngày mới không được ở quá khứ.

## Thay đổi so với spec

- Bỏ `GET /plans/{id}/slot-changes/free`: web dùng lại `GET /availability/tutors/{tutorId}/free` (đã trừ lịch gia sư và người gọi).
- `PlanDetail` thêm `pendingSlotChange` để web hiển thị.
- Học sinh bấm xác nhận khi `LEARNER_ABSENT_PENDING` → `LEARNER_ABSENT` ngay.

---

### Task 1: Migration V4 + vòng đời buổi + đóng kỳ
Files: `booking-service/.../db/migration/V4__session_period_renewal.sql`, `plans/Plans.java`, `sessions/SessionController.java`, `sessions/RescheduleController.java`, `holds/HoldJobs.java`, test `BookingApiPostgresTest`.
- [ ] V4: đổi `CHECK` `sessions.status` / `plans.status` (COMPLETED → HOAN_TAT_NGHIA_VU), cột mới, `slot_changes`, unique index gia hạn mở.
- [ ] `start`, `complete` (ghi chú kết thúc sớm), `learner-absent`, `tutor-absent`, `cancel` có `cancel_deadline`, `dispute`/`confirm` cho vắng.
- [ ] `closeIfDone` thay `completeIfDone`; job: tự nghiệm thu cần `started_at`, hết hạn vắng, hết hạn báo bận, nhắc 60/30.
- [ ] Test: `sessionStartLateEarlyEnd`, `learnerAbsentConfirmedByJob`, `tutorAbsentLeavesPeriodOpen`, `cancelReportExpiresToCancelled`, `periodClosesWithPartialRefund`, sửa test cũ.

### Task 2: Đổi giờ học trong kỳ
Files: `plans/SlotChangeController.java`, `plans/PlanController.java` (helper static, `PlanDetail`), `HoldJobs`.
- [ ] `POST /plans/{id}/slot-changes`, `/slot-changes/{id}/accept|decline|cancel`, job hết hạn.
- [ ] Test: `slotChangeMovesRemainingSessions`, `slotChangeConflictChangesNothing`, `slotChangeExpiresKeepsOldSchedule`.

### Task 3: Gia hạn + nhận xét
Files: `PlanController` (`renew`, `create` dùng chung), `Plans.qualifyRenewal`, `HoldJobs` nhắc gia hạn, `tutor-service/.../ReviewController.java` + test.
- [ ] Test: `renewalCreatesLinkedPlanOnce`, `renewalQualifiesOnlyAfterFullCompletion`, review nhận hai trạng thái mới.

### Task 4: Web + tài liệu API
Files: `web/src/types/api.ts`, `web/src/config/labels.ts`, `web/src/features/booking/session-card.tsx`, `plan-view.tsx`, `request-wizard.tsx`, `docs/api/README.md`.
- [ ] Nút/biểu mẫu theo spec mục 5; `npm run lint` + `tsc` sạch.
