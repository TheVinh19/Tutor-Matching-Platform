# EXP, Hạng và nhận xét

Ngày: 2026-11-25. Nhánh: `feature/exp-rank-reviews`. Người dùng đã duyệt cách làm trong hội thoại.

## Mục đích

Làm P09/P10/P12/P13 (phần cốt lõi) cho báo cáo DATN: sổ EXP chống lặp, admin xét Hạng có căn cứ, phản hồi và kiểm duyệt nhận xét, số liệu công khai trên hồ sơ.

## Không làm

`is_test`, phát hiện gian lận nhiều tài khoản, kháng nghị Hạng, tự dừng gửi kế hoạch khi mất điều kiện duy trì (chỉ cảnh báo admin), đảo +50 khi hủy kỳ gia hạn (chưa có luồng hủy kỳ đã chốt), nhãn "Mới tham gia".

## Cách làm

Tutor-service giữ sổ `exp_ledger`, mỗi dòng có `source_key` duy nhất theo gia sư. Một job (và nút admin "Đồng bộ") gọi booking `GET /internal/tutors/{id}/exp-sources` lấy sự kiện đủ điều kiện + số liệu xét Hạng, chèn dòng còn thiếu, lưu số liệu vào `tutor_stats`. Chọn kéo thay vì booking đẩy: tutor-service tắt không mất điểm (lần sau bù), số liệu xét Hạng cũng từ booking. Kafka chưa nối, dùng sẽ nặng hơn.

## Booking

`GET /api/v1/bookings/internal/tutors/{tutorId}/exp-sources` (SERVICE):
- `events`: `SESSION_ON_TIME` +10 (buổi `CONFIRMED`, `started_at` cùng phút với `start_at`, key `session:{id}`), `PERIOD_COMPLETED` +30 (kỳ `HOAN_TAT_NGHIA_VU`, key `period:{id}`), `RENEWAL` +50 (`renewal_qualified_at` có, key `renewal:{id}`).
- `stats`: `taughtSessions` (buổi `CONFIRMED`), `tutorFaultFailed` (0 tới khi có module vụ việc), `distinctLearners` (learner khác nhau có kỳ `HOAN_TAT_NGHIA_VU`), `renewals`, `openIssues` (buổi `DISPUTED` + `TUTOR_ABSENT_REPORTED`).

## Tutor-service

Migration V3: `exp_ledger`, `tutor_stats`, `rank_decisions`, `review_moderation`; `tutor_profiles` thêm `rank_since`, `entry_rank2`; `reviews` thêm `reply`, `replied_at`, `report_reason`, `reported_at`.

EXP = max(0, tổng điểm). Tỷ lệ xét Hạng = taught / (taught + tutorFaultFailed), không có mẫu = null.

Cờ xét Hạng (tính khi đọc, không tự đổi Hạng):
- `ELIGIBLE_UP` Hạng 1→2: EXP ≥ 1.200, ≥ 3 người thuê, ≥ 2 gia hạn, tỷ lệ ≥ 92%, ≥ 90 ngày ở Hạng 1, không có vấn đề mở. Hạng 2→3: EXP ≥ 3.500, ≥ 8, ≥ 6, ≥ 96%, ≥ 90 ngày ở Hạng 2, không phạt trong 90 ngày, không có vấn đề mở.
- `MUST_REVIEW` mất điều kiện duy trì: Hạng 2 khi EXP < 1.200, hoặc (không phải đầu vào) < 3 người thuê/< 2 gia hạn, hoặc có mẫu và tỷ lệ < 92%. Hạng 3 khi EXP < 3.500, < 8/< 6, tỷ lệ < 96%, hoặc có phạt trong 90 ngày.
- còn lại `OK`.

API:

| API | Ai | Quy tắc |
|---|---|---|
| `GET /tutors/me/exp` | TUTOR | EXP, Hạng, số liệu, cờ, 100 dòng sổ mới nhất |
| `GET /tutors/admin/ranks?flag=` | ADMIN | gia sư đã duyệt + EXP + số liệu + cờ |
| `GET /tutors/admin/ranks/{id}` | ADMIN | chi tiết + sổ + lịch sử quyết định |
| `POST /tutors/admin/ranks/sync` | ADMIN | đồng bộ ngay mọi gia sư đã duyệt |
| `POST /tutors/admin/ranks/{id}/decide` | ADMIN | `{rank, reason}`; lên chỉ khi `ELIGIBLE_UP` và đúng +1 (`409 NOT_ELIGIBLE`); hạ luôn được; lưu `rank_decisions`, đặt `rank_since` |
| `POST /tutors/admin/ranks/{id}/penalties` | ADMIN | `{points: 30/100/200, reason, refType: SESSION/PLAN, refId}`; một phạt mỗi ref (`409 PENALTY_EXISTS`, P09 chỉ mức cao nhất mỗi buổi) |
| `POST /tutors/admin/profiles/{id}/approve` | ADMIN | thêm `entryRank2`: Hạng 2 đầu vào + dòng `INITIAL` 1.200 |
| `POST /tutors/reviews/{id}/reply` | TUTOR của nhận xét | `{content}` 1–2000, một lần (`409 REPLY_EXISTS`) |
| `POST /tutors/reviews/{id}/report` | TUTOR của nhận xét | `{reason}` báo vi phạm |
| `GET /tutors/me/reviews` | TUTOR | nhận xét của mình, có cờ ẩn |
| `GET /tutors/admin/reviews?filter=REPORTED/HIDDEN/ALL` | ADMIN | bản gốc |
| `POST /tutors/admin/reviews/{id}/hide` / `restore` | ADMIN | `{reason}`; lưu `review_moderation` |

Hồ sơ công khai thêm `taughtSessions`, `completionRate`, `tutorFaultFailed`; danh sách nhận xét công khai thêm `reply`, `repliedAt`. Job đồng bộ mỗi 10 phút (cấu hình `tutor.exp.sync-delay`).

## Web

- Gia sư: `/tutor/rank` (EXP, số liệu, cờ, sổ), `/tutor/reviews` (phản hồi, báo vi phạm); thêm vào menu.
- Admin: `/admin/ranks` (danh sách cờ, chi tiết, quyết định, phạt, đồng bộ), `/admin/reviews` (ẩn/khôi phục); duyệt hồ sơ có ô "Xếp Hạng 2 đầu vào".
- Hồ sơ công khai: số buổi đã dạy, tỷ lệ (hoặc "chưa có dữ liệu"), phản hồi của gia sư.

## Kiểm thử

Booking: exp-sources đúng phút / lệch phút / kỳ / gia hạn / số liệu. Tutor: đồng bộ hai lần không cộng trùng, EXP không âm, cờ lên/xuống Hạng, quyết định lên khi chưa đủ bị chặn, phạt trùng ref bị chặn, Hạng 2 đầu vào, phản hồi một lần, ẩn/khôi phục ẩn khỏi danh sách công khai.
