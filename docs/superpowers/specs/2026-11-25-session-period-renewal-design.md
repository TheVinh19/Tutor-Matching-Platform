# Trạng thái buổi học, đổi giờ học, đóng kỳ, gia hạn

Ngày: 2026-11-25. Nhánh: `feature/session-period-renewal`.

## Mục đích

Hoàn thiện vòng đời buổi học, kỳ học và gia hạn theo tài liệu `Tai lieu/` (P04, P07, P09, P12, luồng "Buổi học", "Người thuê nghỉ", "Đổi lịch", "Đóng hoặc hủy kỳ", "Gia hạn") cho báo cáo DATN. Chưa có payment: mọi chỗ có tiền chỉ lưu trạng thái/nhãn, số tiền làm ở luồng payment.

## Mục tiêu

1. Buổi học ghi giờ bắt đầu thực tế (gia sư bấm "Bắt đầu"), cảnh báo đến muộn quá 10 phút, ghi chú khi kết thúc sớm, nhánh học sinh vắng, nhánh gia sư vắng, hết hạn báo bận, nhắc lịch trước 60/30 phút.
2. Tự nghiệm thu chỉ khi đủ điều kiện.
3. Đổi giờ học cố định trong tuần (ca học sang khung trống khác) khi đang học; chọn giờ khác khi gia hạn.
4. Kỳ học đóng theo các trạng thái của tài liệu thay cho `COMPLETED`.
5. Học sinh gia hạn kỳ thành kế hoạch mới nối với kỳ cũ; nhắc gia hạn trước 14 ngày.

## Không làm

- Phân xử (admin xử buổi bị phản đối / gia sư vắng): chờ module vụ việc. Các buổi đó để treo, kỳ chuyển `HET_KY_CON_NGHIA_VU`. (Người dùng chọn.)
- Mức phạt và thông báo đến muộn: bổ sung sau. Bây giờ chỉ ghi `late_minutes` qua API. (Người dùng chọn.)
- Bù giờ, báo cáo nội dung buổi, lịch sử sửa báo cáo: không làm. Chỉ ghi giờ bắt đầu và ghi chú kết thúc sớm. (Người dùng chọn.)
- Tiền hoàn, bồi hoàn 85/15, thu tiền gia hạn: chờ payment. Gia hạn xác nhận vẫn gán `PAYMENT_PENDING_MOCK` như hiện nay.
- Nhánh hủy cả kỳ giữa chừng: cần admin + payment.
- EXP: chỉ lưu dữ liệu đầu vào (giờ bắt đầu thực tế, cờ gia hạn đủ điều kiện), chưa cộng điểm.
- Email: timer tính từ lúc tạo thông báo web.

## Cách làm

Mở rộng bảng `sessions`/`plans` hiện có, thêm 1 bảng đề nghị đổi giờ, thêm việc vào `HoldJobs`. Kỳ học vẫn là bảng `plans` (schema đã ghi "A plan = one study period"), không tạo bảng `periods` riêng: bảng riêng sẽ trùng dữ liệu và phải đồng bộ hai nơi. Gia hạn dùng lại luồng tạo kế hoạch (wizard chọn ca), nên chọn giờ khác khi gia hạn không cần code riêng.

## Thiết kế

### 1. Buổi học

Trạng thái mới (thêm vào `CHECK` của `sessions.status`):

| Trạng thái | Nghĩa |
|---|---|
| `IN_PROGRESS` | gia sư đã bấm "Bắt đầu" |
| `LEARNER_ABSENT_PENDING` | gia sư báo học sinh vắng, chờ 24h |
| `LEARNER_ABSENT` | vắng đã xác nhận (bồi hoàn, không tính thực dạy, không +10 EXP) |
| `TUTOR_ABSENT_REPORTED` | học sinh báo gia sư không dạy; treo chờ vụ việc |

`CANCELLED` (đã có trong `CHECK`) dùng khi báo bận hết hạn.

Cột mới trên `sessions`: `started_at`, `late_minutes`, `early_end_by` (`LEARNER`/`TUTOR`), `early_end_note`, `absent_note`, `absent_deadline`, `reminded_60_at`, `reminded_30_at`, `cancel_deadline`. Giờ kết thúc thực tế là `completed_at` đã có.

API mới / đổi:

| API | Ai | Quy tắc |
|---|---|---|
| `POST /sessions/{id}/start` | gia sư | `SCHEDULED`, từ `start_at − 15 phút` đến `end_at`; `started_at = now()` máy chủ, một lần, không sửa. Muộn hơn `start_at + 10 phút` → ghi `late_minutes` (trả trong API, chưa gửi thông báo, chưa phạt). → `IN_PROGRESS` |
| `POST /sessions/{id}/complete` | gia sư | đổi: chỉ từ `IN_PROGRESS`. Body `{earlyEndBy?, earlyEndNote?}`. Bấm trước `end_at` thì bắt buộc `earlyEndBy` + `earlyEndNote` (ai muốn nghỉ sớm, lý do). `completed_at = now()` → `COMPLETED_PENDING_CONFIRM`, hạn 24h |
| `POST /sessions/{id}/learner-absent` | gia sư | `IN_PROGRESS`, now ≥ `start_at + 15 phút`; `{note}` ghi liên hệ → `LEARNER_ABSENT_PENDING`, hạn 24h, báo học sinh |
| `POST /sessions/{id}/dispute` | học sinh | thêm: dùng cho `LEARNER_ABSENT_PENDING` trong hạn → `DISPUTED` |
| `POST /sessions/{id}/tutor-absent` | học sinh | `SCHEDULED` (chưa bắt đầu), now ≥ `start_at + 15 phút`; `{reason}` → `TUTOR_ABSENT_REPORTED`, báo gia sư |
| `POST /sessions/{id}/cancel` | hai bên | thêm: đặt `cancel_deadline = min(now + 24h, start_at)` |

Việc định kỳ (`HoldJobs`, mỗi 60 giây):

- Tự nghiệm thu: `COMPLETED_PENDING_CONFIRM` quá hạn và có `started_at` → `CONFIRMED`, `auto_confirmed`. (Luồng mới luôn có `started_at`; buổi cũ không có thì học sinh tự xác nhận.)
- `LEARNER_ABSENT_PENDING` quá `absent_deadline` → `LEARNER_ABSENT`.
- `CANCEL_REPORTED` quá `cancel_deadline` mà chưa xếp lịch mới → `CANCELLED`, báo hai bên. `cancelled_by` cho biết bên báo.
- Nhắc lịch: buổi `SCHEDULED` bắt đầu trong ≤60 / ≤30 phút, chưa nhắc → gửi hai bên, ghi `reminded_*_at`.
- Sau mỗi thay đổi trạng thái buổi: gọi đánh giá đóng kỳ (mục 3).

Buổi `IN_PROGRESS` mà gia sư quên bấm kết thúc: để nguyên, học sinh vẫn thấy; không tự xử lý (người làm có thể thêm nhắc sau `end_at`).

### 2. Đổi giờ học trong kỳ

Đổi một ca cố định trong tuần (ví dụ "Thứ 3 18:00" → "Thứ 5 19:00") cho mọi buổi còn lại từ một ngày, khác với đổi lịch một buổi (`reschedule`) đã có. Theo DOI05: chỉ tác động các buổi được liệt kê từ ngày đã thống nhất.

Bảng mới `slot_changes`: `id, plan_id, requested_by, from_day, from_time, to_day, to_time, effective_from, status (PENDING/ACCEPTED/DECLINED/EXPIRED/CANCELLED), decline_reason, expires_at, created_at, decided_at`.

| API | Ai | Quy tắc |
|---|---|---|
| (khung trống) | hai bên | dùng lại `GET /availability/tutors/{tutorId}/free`, không thêm API mới |
| `POST /plans/{id}/slot-changes` | hai bên | kỳ `CONFIRMED`; `{fromDay, fromTime, toDay, toTime, effectiveFrom}`; ca cũ phải là ca đang có trong kỳ; ca mới 120 phút, 08:00–22:00, trong lịch rảnh gia sư; `effectiveFrom` > hôm nay. Một kỳ chỉ một đề nghị `PENDING`. Hết hạn `min(now + 24h, buổi sớm nhất bị ảnh hưởng)`. Báo bên kia |
| `POST /slot-changes/{id}/accept` | bên còn lại | trong một transaction: mọi buổi `SCHEDULED` của ca cũ có ngày ≥ `effectiveFrom` dời sang ngày tương ứng của ca mới trong cùng tuần; EXCLUDE constraint chặn trùng → `409 SCHEDULE_CONFLICT`, không đổi gì. Báo người đề nghị |
| `POST /slot-changes/{id}/decline` | bên còn lại | `{reason}`; giữ lịch cũ |
| `POST /slot-changes/{id}/cancel` | người đề nghị | khi còn `PENDING` |

Job: `PENDING` quá `expires_at` → `EXPIRED`, giữ lịch cũ (P12). Số buổi không đổi nên không cần payment. Ngày dời sang nếu rơi sau `end_date` kỳ thì cập nhật `end_date` (chỉ khi đổi thành công).

**Khi gia hạn:** wizard gia hạn cho chọn ca mới như khi tạo kế hoạch (mặc định điền sẵn ca của kỳ cũ).

### 3. Đóng kỳ

`plans.status`: bỏ `COMPLETED`, thêm `HOAN_TAT_NGHIA_VU`, `KET_THUC_CO_HOAN_MOT_PHAN`, `HET_KY_CON_NGHIA_VU`. Migration đổi dòng `COMPLETED` cũ thành `HOAN_TAT_NGHIA_VU`.

Buổi **xong**: `CONFIRMED`, `LEARNER_ABSENT`, `CANCELLED`, `RELEASED`. Buổi **còn nghĩa vụ**: `DISPUTED`, `TUTOR_ABSENT_REPORTED`. Còn lại là **chưa xong**.

`Plans.completeIfDone` thay bằng `closeIfDone` (kỳ `CONFIRMED` hoặc `HET_KY_CON_NGHIA_VU`):

- Mọi buổi xong, không buổi `CANCELLED` do gia sư báo → `HOAN_TAT_NGHIA_VU`.
- Mọi buổi xong, có buổi `CANCELLED` do gia sư báo → `KET_THUC_CO_HOAN_MOT_PHAN`.
- Không còn buổi chưa xong, nhưng có buổi còn nghĩa vụ → `HET_KY_CON_NGHIA_VU` (không mở nhận xét). Module vụ việc sau này gọi lại `closeIfDone`.

`closed_at` đặt khi vào `HOAN_TAT_NGHIA_VU` hoặc `KET_THUC_CO_HOAN_MOT_PHAN`. Nhận xét (`tutor-service/ReviewController`) nhận cả hai trạng thái này, 14 ngày từ `closed_at`.

### 4. Gia hạn

- Cột mới `plans.renewed_from uuid REFERENCES plans`, `plans.renewal_reminded_at`, `plans.renewal_qualified_at`.
- `POST /plans/{id}/renew` (học sinh chủ đơn): kỳ cũ `CONFIRMED`, `HOAN_TAT_NGHIA_VU` hoặc `KET_THUC_CO_HOAN_MOT_PHAN`. Body như tạo kế hoạch nhưng không có `tutorId/subjectCode/levelCode/mode` (chép từ kỳ cũ); ca học chọn tự do như tạo mới. `startDate` phải sau `end_date` kỳ cũ. Tạo kế hoạch `REQUESTED` có `renewed_from`, đi luồng propose/confirm như cũ. Không giữ chỗ hay ưu tiên ca.
- Mỗi kỳ cũ tối đa một gia hạn đang mở (`REQUESTED`/`PROPOSED`) → `409 RENEWAL_EXISTS`.
- Nhắc gia hạn: kỳ `CONFIRMED` còn ≤14 ngày tới `end_date`, chưa nhắc, chưa có gia hạn → báo học sinh.
- Gia hạn đủ điều kiện (đầu vào +50 EXP sau này): kỳ mới `CONFIRMED` và kỳ cũ `HOAN_TAT_NGHIA_VU` → đặt `renewal_qualified_at` trên kỳ mới một lần, dù điều kiện nào đến sau. `KET_THUC_CO_HOAN_MOT_PHAN` không đủ điều kiện (P12).

### 5. Web

- `session-card.tsx`: nút "Bắt đầu", "Kết thúc" (form ai nghỉ sớm + lý do khi trước giờ), báo vắng hai phía, hiển thị trạng thái mới, đến muộn bao nhiêu phút, hạn.
- `plan-view.tsx`: nhãn trạng thái kỳ mới; khối "Đổi giờ học" (chọn ca cũ, ca mới từ khung trống, ngày áp dụng; bên kia đồng ý/từ chối); nút "Gia hạn" mở `request-wizard` điền sẵn; liên kết kỳ cũ/kỳ gia hạn.
- `labels.ts`, `types/api.ts`: trạng thái và trường mới.

### 6. Kiểm thử

Mở rộng `BookingApiPostgresTest`: bắt đầu sai giờ, đến muộn >10 phút ghi `late_minutes`, kết thúc sớm thiếu ghi chú bị từ chối, vắng học sinh qua job, gia sư vắng, báo bận hết hạn thành `CANCELLED`, đổi giờ học (đồng ý dời đúng buổi, trùng lịch báo lỗi không đổi gì, hết hạn giữ lịch cũ), ba trạng thái đóng kỳ, gia hạn (trùng, sai ngày, đủ điều kiện một lần). Test nhận xét bên tutor-service theo trạng thái mới.

## Quyết định của Claude (cần bạn kiểm tra)

1. Báo bận quá hạn → hủy buổi (`CANCELLED`), không quay về giờ cũ, vì bên báo đã nói bận.
2. Nút "Bắt đầu" mở từ 15 phút trước giờ học; muộn tính từ phút thứ 11.
3. Buổi do gia sư báo hủy → kỳ thành `KET_THUC_CO_HOAN_MOT_PHAN`; do học sinh báo → vẫn `HOAN_TAT_NGHIA_VU`.
4. Đổi giờ học: bên nào cũng đề nghị được, bên kia đồng ý mới đổi; hết hạn 24h hoặc trước buổi đầu bị ảnh hưởng.

## Để người làm tự quyết

Tên mã lỗi cụ thể, nội dung thông báo, bố cục form trên web.
