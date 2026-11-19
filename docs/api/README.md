# Đặc tả API

Mọi API đi qua gateway `:8080` (hoặc Nginx `:8088`), prefix `/api/v1/<khu>`. Gateway chuyển nguyên đường dẫn tới service; mỗi service tự kiểm tra JWT, vai trò và quyền với dữ liệu cụ thể. `/api/v1/*/internal/**` bị gateway chặn, chỉ dùng giữa các service (role `SERVICE`). Payment chưa mở API nghiệp vụ (mục 4).

## Quy ước chung

- Header `Authorization: Bearer <accessToken>` lấy từ `POST /api/v1/identity/auth/login`. JWT HS256, hạn 2 giờ, claim `sub` (accountId), `role` (`LEARNER`/`TUTOR`/`ADMIN`), `email`.
- Lỗi: `{code,message,details,requestId}`. `401 UNAUTHORIZED`, `403 FORBIDDEN` (hoặc mã cụ thể như `NOT_PLAN_OWNER`), `400 VALIDATION_FAILED` (`details` = danh sách `{field,message}`), `404 *_NOT_FOUND`, `409` xung đột trạng thái/lịch, `503 *_UNAVAILABLE` khi service phụ thuộc không phản hồi.
- Thời điểm trả về dạng ISO-8601 có offset; lịch tính theo giờ Việt Nam.

## Health

`GET /api/v1/{identity|tutors|bookings|payments|notifications}/health` → `/actuator/health` của service.

## Identity

| Method | Path | Quyền | Ghi chú |
|---|---|---|---|
| POST | `/api/v1/identity/auth/login` | công khai | `{email,password}` → `{accessToken,tokenType,expiresIn,role,accountId}`. Sai email/mật khẩu cùng trả `401 INVALID_CREDENTIALS` |
| GET | `/api/v1/identity/me` | đã đăng nhập | `{accountId,email,role,status}` |
| GET | `/api/v1/identity/admin/accounts` | ADMIN | danh sách tài khoản |

Đăng nhập mock: chưa có đăng ký/xác minh email. Tài khoản demo xem README gốc.

## Tutor

| Method | Path | Quyền | Ghi chú |
|---|---|---|---|
| GET | `/api/v1/tutors/catalog` | công khai | môn, cấp học, địa bàn |
| GET | `/api/v1/tutors/search` | công khai | `subjects=TOAN,ANH` (khớp ≥1 môn, mỗi gia sư 1 lần), `level`, `ranks=1,2`, `mode` (ONLINE/OFFLINE/FLEX), `area`, `day`(1–7)+`from`+`to` (HH:mm) [+`fromDate`,`toDate`], lặp bộ ba tối đa 7 lần (`day=2&from=08:00&to=10:00&day=3&from=...`) để lọc gia sư rảnh trọn mọi buổi, `page`, `size`≤50. Không lọc giá, sắp theo tên |
| GET | `/api/v1/tutors/{accountId}` | công khai | chỉ hồ sơ APPROVED |
| GET | `/api/v1/tutors/{accountId}/reviews` | công khai | 50 nhận xét mới nhất, không lộ người viết |
| GET/PUT | `/api/v1/tutors/me/profile` | TUTOR | PUT chỉ khi DRAFT/REJECTED (`409 PROFILE_LOCKED`), mã danh mục sai → `400 UNKNOWN_CATALOG_CODE` |
| POST | `/api/v1/tutors/me/profile/submit` | TUTOR | DRAFT/REJECTED → PENDING; cần ≥1 môn và địa bàn nếu không chỉ online |
| GET | `/api/v1/tutors/admin/profiles?status=PENDING` | ADMIN | |
| GET | `/api/v1/tutors/admin/profiles/{id}` | ADMIN | hồ sơ + lịch sử duyệt |
| POST | `/api/v1/tutors/admin/profiles/{id}/approve` | ADMIN | body tùy chọn `{note}`; chỉ PENDING |
| POST | `/api/v1/tutors/admin/profiles/{id}/reject` | ADMIN | `{reason}` bắt buộc |
| POST | `/api/v1/tutors/reviews` | LEARNER | `{planId,content}` 10–2000 ký tự; chỉ chủ đơn, kỳ COMPLETED, trong 14 ngày, 1 lần/kỳ |
| GET | `/api/v1/tutors/internal/profiles/{id}` | SERVICE | trạng thái duyệt, hình thức, Hạng, môn/cấp |

## Booking

| Method | Path | Quyền | Ghi chú |
|---|---|---|---|
| GET | `/api/v1/bookings/availability/me` | TUTOR | |
| GET | `/api/v1/bookings/availability/tutors/{tutorId}` | công khai | |
| GET | `/api/v1/bookings/availability/tutors/{tutorId}/free?fromDate&weeks=4&mode=` | công khai | khoảng còn đặt được `[{dayOfWeek,startTime,endTime}]`: lịch rảnh trừ mọi buổi đang giữ/chốt của gia sư (kèm 60 phút trước và sau) và của người gọi, chỉ giữ đoạn ≥120 phút. Buổi ở một ngày chặn giờ đó của thứ đó cả kỳ |
| PUT | `/api/v1/bookings/availability/me` | TUTOR (hồ sơ APPROVED) | `{windows:[{dayOfWeek,startTime,endTime}]}`; thay toàn bộ lịch rảnh (FLEX, hiệu lực từ hôm nay). Mỗi khoảng trong 08:00–22:00, ≥120 phút, không chồng nhau trong ngày |
| POST | `/api/v1/bookings/availability` | TUTOR (hồ sơ APPROVED) | `{dayOfWeek,startTime,endTime,mode,effectiveFrom,effectiveTo?}`; 08:00–22:00, ≥120 phút, không trùng khoảng đã khai |
| DELETE | `/api/v1/bookings/availability/{id}` | TUTOR | không ảnh hưởng buổi đã giữ/chốt |
| POST | `/api/v1/bookings/plans` | LEARNER | `{tutorId,subjectCode,levelCode,mode,note?,sessionsPerWeek,startDate,weeks,preferredSlots:[{dayOfWeek,startTime}]}` → REQUESTED; môn/cấp và hình thức phải nằm trong hồ sơ đã duyệt. Bốn trường lưới đi cùng nhau (thiếu → `INCOMPLETE_PREFERENCES`); đúng sessionsPerWeek buổi 2 tiếng (`WRONG_SLOT_COUNT`), mỗi ngày tối đa một buổi (`ONE_SLOT_PER_DAY`), giờ bắt đầu tùy ý, nằm trong lịch rảnh. Đây là lịch học sinh muốn; gia sư nhận nguyên hoặc từ chối. Buổi đề xuất không bị giữ chỗ |
| GET | `/api/v1/bookings/plans/me?status=` | LEARNER/TUTOR | |
| GET | `/api/v1/bookings/plans/{id}` | 2 bên hoặc ADMIN | kế hoạch + buổi + `preferredSlots` |
| POST | `/api/v1/bookings/plans/{id}/propose` | TUTOR của kế hoạch | Kế hoạch có lưới: `slots` phải là đúng các buổi trong `preferredSlots` (giao diện gửi nguyên các buổi học sinh chọn) (`WRONG_SLOT_COUNT`, `NOT_PREFERRED_SLOT`), ngày lấy theo học sinh, buổi còn dưới 12 giờ bị bỏ. Kế hoạch cũ: `{startDate,endDate?,slots:[{dayOfWeek,startTime}],meetLink?}`; mặc định 4 tuần, ca 120 phút, cách ca ≥60 phút, trong lịch rảnh; buổi đầu >12 giờ. Sinh buổi HELD, giữ tới min(24h, buổi đầu − 12h). Trùng → `409 SCHEDULE_CONFLICT` (`details` = buổi xung đột) |
| POST | `/api/v1/bookings/plans/{id}/confirm` | LEARNER chủ đơn | PROPOSED → CONFIRMED, `paymentStatus=PAYMENT_PENDING_MOCK` (mục 4 thay bằng thanh toán). Hết hạn giữ → `409 HOLD_EXPIRED` |
| POST | `/api/v1/bookings/plans/{id}/reject` | TUTOR | `{reason}`, chỉ REQUESTED |
| POST | `/api/v1/bookings/plans/{id}/cancel` | LEARNER chủ đơn | REQUESTED/PROPOSED, giải phóng lịch giữ |
| GET | `/api/v1/bookings/sessions/me?from=&to=` | LEARNER/TUTOR | mặc định −7 đến +60 ngày |
| GET | `/api/v1/bookings/sessions/{id}` | 2 bên hoặc ADMIN | |
| PUT | `/api/v1/bookings/sessions/{id}/meet-link` | TUTOR | `{meetLink}` https, chỉ đổi buổi đó, báo Người thuê |
| POST | `/api/v1/bookings/sessions/{id}/cancel` | 2 bên | `{reason}`; SCHEDULED → CANCEL_REPORTED, chờ admin xử lý |
| POST | `/api/v1/bookings/sessions/{id}/complete` | TUTOR | sau giờ bắt đầu → COMPLETED_PENDING_CONFIRM, hạn xác nhận 24h |
| POST | `/api/v1/bookings/sessions/{id}/confirm` | LEARNER chủ đơn | → CONFIRMED; mọi buổi CONFIRMED thì kỳ COMPLETED |
| POST | `/api/v1/bookings/sessions/{id}/dispute` | LEARNER chủ đơn | `{reason}` trong 24h → DISPUTED |
| GET | `/api/v1/bookings/admin/plans?status=` | ADMIN | 100 kế hoạch mới nhất |
| GET | `/api/v1/bookings/internal/availability/tutors` | SERVICE | dùng cho lọc lịch khi tìm kiếm |
| GET | `/api/v1/bookings/internal/plans/{id}/review-eligibility` | SERVICE | dùng cho nhận xét |

Job mỗi 60 giây: kế hoạch PROPOSED quá hạn giữ → EXPIRED (buổi HELD → RELEASED); buổi chờ xác nhận quá 24h → CONFIRMED (`autoConfirmed`), không áp dụng cho buổi DISPUTED/CANCEL_REPORTED. Chống trùng lịch cuối cùng bằng 2 EXCLUDE constraint PostgreSQL (gia sư: gồm 60 phút đệm; Người thuê: không chồng giờ).

## Notification

| Method | Path | Quyền | Ghi chú |
|---|---|---|---|
| GET | `/api/v1/notifications/me?unreadOnly=` | đã đăng nhập | 50 thông báo mới nhất |
| GET | `/api/v1/notifications/me/unread-count` | đã đăng nhập | |
| PATCH | `/api/v1/notifications/{id}/read` | người nhận | |
| POST | `/api/v1/notifications/internal` | SERVICE | booking gửi sau khi commit; chưa có outbox (có thể mất nếu notification-service tắt) |

Springdoc OpenAPI và Postman collection bổ sung ở Task 5. Xem [domain map](../architecture/domain-map.md) và [hợp đồng mock dự kiến](../mock-contract.md).
