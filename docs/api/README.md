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
| GET | `/api/v1/identity/me` | đã đăng nhập | `{accountId,username,email,phone,role,status}` |
| PUT | `/api/v1/identity/me/phone` | đã đăng nhập | `{phone}` 10 số, bắt đầu bằng 0; chưa xác minh OTP |
| GET | `/api/v1/identity/internal/accounts/{id}/contact` | SERVICE | `{accountId,phone}` cho booking |
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
| POST | `/api/v1/tutors/reviews` | LEARNER | `{planId,content}` 10–2000 ký tự; chỉ chủ đơn, kỳ HOAN_TAT_NGHIA_VU/KET_THUC_CO_HOAN_MOT_PHAN, trong 14 ngày, 1 lần/kỳ |
| POST | `/api/v1/tutors/reviews/{id}/reply` | TUTOR của nhận xét | `{content}` ≤2000, một lần, công khai (`409 REPLY_EXISTS`) |
| POST | `/api/v1/tutors/reviews/{id}/report` | TUTOR của nhận xét | `{reason}` báo vi phạm cho admin |
| GET | `/api/v1/tutors/me/reviews` | TUTOR | nhận xét về mình, kể cả bị ẩn |
| GET | `/api/v1/tutors/admin/reviews?filter=REPORTED\|HIDDEN\|ALL` | ADMIN | bản gốc, mặc định REPORTED |
| POST | `/api/v1/tutors/admin/reviews/{id}/hide` · `/restore` | ADMIN | `{reason}`; ẩn/khôi phục toàn bộ, lưu lịch sử (`GET .../moderation`) |
| GET | `/api/v1/tutors/me/exp` | TUTOR | `{view:{rank,exp,stats,completionRate,flag},entries,decisions}`; EXP = max(0, tổng sổ) |
| GET | `/api/v1/tutors/admin/ranks?flag=OK\|ELIGIBLE_UP\|MUST_REVIEW` | ADMIN | gia sư đã duyệt kèm cờ xét Hạng (P10); Hạng không tự đổi |
| GET | `/api/v1/tutors/admin/ranks/{id}` | ADMIN | chi tiết, đồng bộ trước khi trả |
| POST | `/api/v1/tutors/admin/ranks/sync` | ADMIN | đồng bộ sổ EXP mọi gia sư (job tự chạy mỗi 10 phút) |
| POST | `/api/v1/tutors/admin/ranks/{id}/decide` | ADMIN | `{rank,reason}`; lên đúng 1 bậc khi ELIGIBLE_UP (`409 NOT_ELIGIBLE`), hạ luôn được |
| POST | `/api/v1/tutors/admin/ranks/{id}/penalties` | ADMIN | `{points:30\|100\|200,reason,refType:SESSION\|PLAN,refId}`; một phạt/ref (`409 PENALTY_EXISTS`) |
| GET | `/api/v1/tutors/internal/profiles/{id}` | SERVICE | trạng thái duyệt, hình thức, Hạng, môn/cấp |

Duyệt hồ sơ `POST /api/v1/tutors/admin/profiles/{id}/approve` nhận thêm `entryRank2: true` để xếp Hạng 2 đầu vào (+1.200 EXP khởi tạo). Hồ sơ công khai `GET /api/v1/tutors/{id}` có thêm `taughtSessions`, `completionRate` (null = chưa có dữ liệu), `tutorFaultFailed`; nhận xét công khai có `reply`, `repliedAt`.

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
| GET | `/api/v1/bookings/plans/{id}` | 2 bên hoặc ADMIN | kế hoạch + buổi + `preferredSlots` + `pendingSlotChange` |
| POST | `/api/v1/bookings/plans/{id}/renew` | LEARNER chủ đơn | Gia hạn (P09): kỳ CONFIRMED/HOAN_TAT_NGHIA_VU/KET_THUC_CO_HOAN_MOT_PHAN. `{note?,sessionsPerWeek,startDate,weeks,preferredSlots}`; gia sư/môn/cấp/hình thức chép từ kỳ cũ; `startDate` sau `endDate` kỳ cũ. Tạo kế hoạch REQUESTED có `renewedFrom`, đi luồng propose/confirm. Mỗi kỳ một gia hạn → `409 RENEWAL_EXISTS`. `renewalQualifiedAt` đặt một lần khi kỳ mới CONFIRMED và kỳ cũ HOAN_TAT_NGHIA_VU |
| POST | `/api/v1/bookings/plans/{id}/slot-changes` | 2 bên | Đổi giờ học (DOI05), kỳ CONFIRMED: `{fromDay,fromTime,toDay,toTime,effectiveFrom}`; mọi buổi SCHEDULED của ca cũ từ `effectiveFrom` dời sang thứ mới trong cùng tuần. Ca mới trong lịch rảnh. Một đề nghị chờ/kỳ (`409 SLOT_CHANGE_PENDING`). Hết hạn min(24h, buổi sớm nhất bị ảnh hưởng). Khung trống lấy từ `availability/tutors/{tutorId}/free` |
| POST | `/api/v1/bookings/slot-changes/{id}/accept` | bên còn lại | dời toàn bộ trong một transaction; trùng → `409 SCHEDULE_CONFLICT`, không dời buổi nào |
| POST | `/api/v1/bookings/slot-changes/{id}/decline` | bên còn lại | `{reason}`, giữ lịch cũ |
| POST | `/api/v1/bookings/slot-changes/{id}/cancel` | người đề nghị | khi còn chờ |
| POST | `/api/v1/bookings/plans/{id}/propose` | TUTOR của kế hoạch | Kế hoạch có lưới: `slots` phải là đúng các buổi trong `preferredSlots` (giao diện gửi nguyên các buổi học sinh chọn) (`WRONG_SLOT_COUNT`, `NOT_PREFERRED_SLOT`), ngày lấy theo học sinh, buổi còn dưới 12 giờ bị bỏ. Kế hoạch cũ: `{startDate,endDate?,slots:[{dayOfWeek,startTime}],meetLink?}`; mặc định 4 tuần, ca 120 phút, cách ca ≥60 phút, trong lịch rảnh; buổi đầu >12 giờ. Sinh buổi HELD, giữ tới min(24h, buổi đầu − 12h). Trùng → `409 SCHEDULE_CONFLICT` (`details` = buổi xung đột) |
| POST | `/api/v1/bookings/plans/{id}/confirm` | LEARNER chủ đơn | PROPOSED → CONFIRMED, `paymentStatus=PAYMENT_PENDING_MOCK` (mục 4 thay bằng thanh toán). Hết hạn giữ → `409 HOLD_EXPIRED` |
| GET | `/api/v1/bookings/plans/{id}/contact` | 2 bên hoặc ADMIN | `{learnerPhone,tutorPhone}`, chỉ khi CONFIRMED/HOAN_TAT_NGHIA_VU/KET_THUC_CO_HOAN_MOT_PHAN/HET_KY_CON_NGHIA_VU, nếu không `409 INVALID_STATUS` (KN01: không có chat) |
| POST | `/api/v1/bookings/plans/{id}/reject` | TUTOR | `{reason}`, chỉ REQUESTED |
| POST | `/api/v1/bookings/plans/{id}/cancel` | LEARNER chủ đơn | REQUESTED/PROPOSED, giải phóng lịch giữ |
| GET | `/api/v1/bookings/sessions/me?from=&to=` | LEARNER/TUTOR | mặc định −7 đến +60 ngày |
| GET | `/api/v1/bookings/sessions/{id}` | 2 bên hoặc ADMIN | |
| PUT | `/api/v1/bookings/sessions/{id}/meet-link` | TUTOR | `{meetLink}` https, chỉ đổi buổi đó, báo Người thuê |
| POST | `/api/v1/bookings/sessions/{id}/cancel` | 2 bên | `{reason}`; SCHEDULED → CANCEL_REPORTED, chờ dời lịch tới `cancelDeadline` = min(24h, giờ học); quá hạn → CANCELLED |
| GET | `/api/v1/bookings/sessions/{id}/reschedule/free?fromDate=` | 2 bên | Ca chọn được trong 7 ngày từ `fromDate` (CANCEL_REPORTED). Học viên: giờ trống của gia sư. Gia sư: trong lịch học viên gửi |
| POST | `/api/v1/bookings/sessions/{id}/reschedule` | 2 bên | `{startAt}` phải nằm trong danh sách trên; buổi → SCHEDULED giờ mới; 409 `SLOT_NOT_AVAILABLE`/`SCHEDULE_CONFLICT` |
| POST | `/api/v1/bookings/sessions/{id}/reschedule/offer` | Học viên | `{fromDate, toDate, windows[]}` (tối đa 56 ngày); thay lịch đã gửi trước đó |
| POST | `/api/v1/bookings/sessions/{id}/reschedule/decline` | Gia sư | `{reason}`; xóa lịch học viên gửi, báo học viên |
| POST | `/api/v1/bookings/sessions/{id}/start` | TUTOR | SCHEDULED, từ giờ học − 15 phút (không giới hạn sau) → IN_PROGRESS; `startedAt` = giờ máy chủ, không sửa; muộn > 10 phút ghi `lateMinutes` (chưa thông báo/phạt) |
| POST | `/api/v1/bookings/sessions/{id}/complete` | TUTOR | IN_PROGRESS → COMPLETED_PENDING_CONFIRM, hạn xác nhận 24h. Trước giờ kết thúc phải gửi `{earlyEndBy: LEARNER/TUTOR, earlyEndNote}` (`400 EARLY_END_NOTE_REQUIRED`) |
| POST | `/api/v1/bookings/sessions/{id}/learner-absent` | TUTOR | `{reason}`; IN_PROGRESS, sau giờ học + 15 phút → LEARNER_ABSENT_PENDING, học sinh có 24h phản đối |
| POST | `/api/v1/bookings/sessions/{id}/tutor-absent` | LEARNER chủ đơn | `{reason}`; SCHEDULED (gia sư chưa bắt đầu), sau giờ học + 15 phút → TUTOR_ABSENT_REPORTED, chờ module vụ việc |
| POST | `/api/v1/bookings/sessions/{id}/confirm` | LEARNER chủ đơn | COMPLETED_PENDING_CONFIRM → CONFIRMED; LEARNER_ABSENT_PENDING → LEARNER_ABSENT. Sau đó xét đóng kỳ |
| POST | `/api/v1/bookings/sessions/{id}/dispute` | LEARNER chủ đơn | `{reason}` trong 24h (chờ xác nhận hoặc báo vắng) → DISPUTED |
| GET | `/api/v1/bookings/admin/plans?status=` | ADMIN | 100 kế hoạch mới nhất |
| GET | `/api/v1/bookings/internal/availability/tutors` | SERVICE | dùng cho lọc lịch khi tìm kiếm |
| GET | `/api/v1/bookings/internal/plans/{id}/review-eligibility` | SERVICE | dùng cho nhận xét |
| GET | `/api/v1/bookings/internal/tutors/{tutorId}/exp-sources` | SERVICE | sự kiện EXP (+10 buổi CONFIRMED bắt đầu đúng phút, +30 kỳ HOAN_TAT_NGHIA_VU, +50 gia hạn hợp lệ, mỗi sự kiện một `key`) và số liệu xét Hạng |

Job mỗi 60 giây: kế hoạch PROPOSED quá hạn giữ → EXPIRED (buổi HELD → RELEASED); buổi chờ xác nhận quá 24h và có `startedAt` → CONFIRMED (`autoConfirmed`); LEARNER_ABSENT_PENDING quá hạn → LEARNER_ABSENT; CANCEL_REPORTED quá `cancelDeadline` → CANCELLED; đề nghị đổi giờ quá hạn → EXPIRED; nhắc buổi học trước 60 và 30 phút; nhắc gia hạn khi kỳ CONFIRMED còn ≤ 14 ngày.

Đóng kỳ (sau mỗi thay đổi trạng thái buổi): mọi buổi CONFIRMED/LEARNER_ABSENT/CANCELLED → HOAN_TAT_NGHIA_VU, hoặc KET_THUC_CO_HOAN_MOT_PHAN nếu có buổi CANCELLED do gia sư báo; còn buổi DISPUTED/TUTOR_ABSENT_REPORTED khi các buổi khác đã xong → HET_KY_CON_NGHIA_VU (chưa mở nhận xét). Nhận xét mở cho hai trạng thái đóng đầu, 14 ngày từ `closedAt`. Chống trùng lịch cuối cùng bằng 2 EXCLUDE constraint PostgreSQL (gia sư: gồm 60 phút đệm; Người thuê: không chồng giờ).

## Notification

| Method | Path | Quyền | Ghi chú |
|---|---|---|---|
| GET | `/api/v1/notifications/me?unreadOnly=` | đã đăng nhập | 50 thông báo mới nhất |
| GET | `/api/v1/notifications/me/unread-count` | đã đăng nhập | |
| PATCH | `/api/v1/notifications/{id}/read` | người nhận | |
| POST | `/api/v1/notifications/internal` | SERVICE | booking gửi sau khi commit; chưa có outbox (có thể mất nếu notification-service tắt) |

Springdoc OpenAPI và Postman collection bổ sung ở Task 5. Xem [domain map](../architecture/domain-map.md) và [hợp đồng mock dự kiến](../mock-contract.md).
