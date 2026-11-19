# Kế hoạch mục 3 — Chức năng nền tảng và bảo mật API

Tuần 4–7 (26/10–22/11/2026). Nhánh: `feature/mock-dn-va-ho-so`.

**Trạng thái (18/11/2026):** Task 1–3 đã có code, `mvn clean install` xanh. Test Postgres (`*PostgresTest`) chạy với Docker: tất cả pass; dữ liệu demo đã thử trên Postgres 17. Task 4–5 chưa làm.

Nguồn: `Tai lieu/Yeu_cau_du_an_gia_su_v1.2.docx` (P01, GS01, CA01–CA05, DK, DG02), `Tai lieu/Vai_tro_va_quyen_nen_tang_gia_su_v1.2.docx`, `docs/architecture/domain-map.md`.

## Quyết định chung

- **Đăng nhập mock**: không đăng ký/xác thực email thật ở mục này. Identity seed sẵn 5 tài khoản demo, đăng nhập bằng email + mật khẩu demo, trả JWT thật. Phân quyền chạy như bình thường.
- Đăng ký + xác minh email (Mailpit) để lại sau; giữ endpoint `/register` chưa mở.
- Thanh toán/Escrow thuộc mục 4: ở mục này kế hoạch được chốt bằng nút xác nhận của Người thuê, có cờ `PAYMENT_PENDING_MOCK` để mục 4 nối vào.
- Một email một vai trò (P01). `/learner` là Người thuê, không có vai trò thứ tư.
- Lỗi chuẩn `{code,message,details,requestId}`, không lộ stack trace.

### Tài khoản demo (seed Flyway, mật khẩu BCrypt)

| Email | Vai trò | Ghi chú dữ liệu seed |
|---|---|---|
| `admin@demo.giasu.vn` | ADMIN | Duyệt hồ sơ |
| `tutor1@demo.giasu.vn` | TUTOR | Hồ sơ đã duyệt, có lịch rảnh |
| `tutor2@demo.giasu.vn` | TUTOR | Hồ sơ chờ duyệt (để demo luồng duyệt) |
| `learner1@demo.giasu.vn` | LEARNER | Có 1 kế hoạch đã chốt với tutor1 |
| `learner2@demo.giasu.vn` | LEARNER | Tài khoản trống để demo đặt lịch |

Mật khẩu demo chung đặt trong `.env.example` (`DEMO_PASSWORD`), không hard-code trong code Java.

---

## Task 1 — BE: Identity mock login + JWT + phân quyền xuyên service

**Phạm vi**: `identity-service`, `api-gateway`, `SecurityConfig` của 5 service.

- Migration `V1__accounts.sql`: bảng `accounts(id uuid, email unique, password_hash, role, status, created_at)` + seed 5 tài khoản.
- `POST /api/v1/identity/auth/login` → `{accessToken, expiresIn, role, accountId}`; `GET /api/v1/identity/me`.
- JWT HS256, secret qua env `JWT_SECRET`, claim `sub`, `role`, hạn 2 giờ. Không refresh token (mock).
- 5 service dùng `spring-boot-starter-oauth2-resource-server` kiểm tra JWT cùng secret; map claim `role` → `ROLE_*`; `@PreAuthorize` theo vai trò.
- Gateway mở route `/api/v1/{identity|tutors|bookings|notifications}/**`, chặn `/actuator/**` ngoài health.
- `@RestControllerAdvice` trả lỗi chuẩn: 400 validation, 401, 403, 404, 409.

**Xong khi**: login 5 tài khoản ra đúng role; sai mật khẩu 401; token LEARNER gọi API ADMIN bị 403; test Spring Security cho từng service pass.

## Task 2 — BE: Tutor service — hồ sơ, duyệt, danh mục, tìm kiếm, nhận xét

**Phạm vi**: `tutor-service` (`profiles`, `catalog`, `approval`, `search`, `reviews`).

- Migration: `subjects`, `levels`, `areas` (seed Toán/Văn/Anh…, cấp học, tỉnh/quận), `tutor_profiles` (accountId, giới thiệu, kinh nghiệm, hình thức ONLINE/OFFLINE/FLEX, Hạng mặc định, trạng thái DRAFT/PENDING/APPROVED/REJECTED), `tutor_subjects`, `tutor_areas`, `approval_history`, `reviews`.
- TUTOR: `GET/PUT /tutors/me/profile`, `POST /tutors/me/profile/submit` (chỉ hồ sơ của mình).
- ADMIN: `GET /tutors/admin/pending`, `POST /tutors/admin/{id}/approve|reject` (lưu lý do, người, thời điểm).
- Công khai/LEARNER: `GET /tutors/search?subjects=&level=&rank=&mode=&area=&day=&from=&to=` — nhiều môn khớp ít nhất một, mỗi gia sư một lần, chỉ APPROVED, **không lọc giá**, phân trang. `GET /tutors/{id}` hồ sơ công khai.
- Lọc theo lịch: gọi booking `GET /bookings/internal/availability/match` (REST nội bộ) hoặc tạm lọc sau ở Task 3.
- Nhận xét chữ (DG02): không sao, không sửa, một lần mỗi kỳ, trong 14 ngày từ khi kỳ đóng; điều kiện lấy từ booking qua REST nội bộ.

**Xong khi**: tutor2 nộp → admin duyệt → xuất hiện trong search; hồ sơ chưa duyệt không xuất hiện; test service + repository pass.

## Task 3 — BE: Booking service — lịch rảnh, đặt lịch, buổi học, chống trùng + thông báo cơ bản

**Phạm vi**: `booking-service` (`availability`, `plans`, `holds`, `sessions`), `notification-service` (`inbox`).

- Lịch rảnh (CA01–CA05): TUTOR khai thứ, giờ bắt đầu/kết thúc, ngày áp dụng, hình thức; trong 08:00–22:00; có hiệu lực ngay, không chờ duyệt. Chỉ hồ sơ APPROVED được khai.
- Kế hoạch: LEARNER gửi yêu cầu → TUTOR lập kế hoạch (mặc định 4 tuần, ca 120 phút, cách ca khác ≥60 phút) → hệ thống sinh danh sách buổi → LEARNER đứng tên xác nhận → chốt (`PAYMENT_PENDING_MOCK`, mục 4 thay bằng thanh toán). Giữ chỗ 24 giờ.
- **Chống trùng lịch**: PostgreSQL `EXCLUDE USING gist (tutor_id WITH =, tstzrange(start_at, end_at + interval '60 min') WITH &&)` trên bảng buổi giữ/chốt + `@Transactional`; vi phạm → 409 kèm buổi xung đột. Cũng kiểm tra trùng phía Người thuê.
- Buổi học: xem lịch theo vai trò (của mình), TUTOR cập nhật link Meet, báo bận/hủy buổi (lưu lý do, thời điểm), LEARNER xác nhận hoàn thành trong 24 giờ. Trạng thái: `SCHEDULED → COMPLETED_PENDING_CONFIRM → CONFIRMED / DISPUTED / CANCELLED`.
- Quyền dữ liệu: chỉ tài khoản đứng tên đơn xác nhận; admin chỉ xem/hỗ trợ, không xác nhận thay.
- Thông báo cơ bản: booking gọi `POST notification-service /internal/notifications` (REST, chưa Kafka); `GET /notifications/me`, `PATCH /notifications/{id}/read`.
- Thời gian lưu `timestamptz`, hiển thị giờ Việt Nam.

**Xong khi**: learner2 đặt tutor1 thành công; đặt ca trùng/cách <60 phút trả 409; 2 request đồng thời chỉ 1 thành công (test Testcontainers PostgreSQL); thông báo xuất hiện cho bên còn lại.

## Task 4 — FE: Đăng nhập demo + giao diện 3 vai trò

**Phạm vi**: `web/src/app/(auth)`, `(workspace)`, `(public)/tutors`, `src/features/*`, `src/components/auth`.

- Thêm Tailwind + shadcn/ui, React Hook Form + Zod (theo `docs/conventions.md`).
- `/login`: form email/mật khẩu + 5 nút "Đăng nhập nhanh" cho tài khoản demo.
- Lưu token trong cookie httpOnly qua Route Handler Next.js (không localStorage); `middleware.ts` chặn `/learner`, `/tutor`, `/admin` sai vai trò → chuyển `/login` hoặc 403. Fetch API + Context, không Axios/Zustand.
- Công khai/LEARNER: `/tutors` (bộ lọc nhiều môn, cấp, Hạng, hình thức, địa bàn, lịch), `/tutors/[id]`, gửi yêu cầu.
- LEARNER: `/learner/plans`, `/learner/schedule`, `/learner/sessions/[id]` (xác nhận/phản đối, báo bận), thông báo.
- TUTOR: `/tutor/profile` (khai + nộp duyệt), `/tutor/availability`, `/tutor/plans`, `/tutor/schedule`.
- ADMIN: `/admin/tutors` (danh sách chờ, duyệt/từ chối có lý do).
- Hiển thị lỗi 409 trùng lịch, 403, lỗi validation rõ ràng; nút đăng xuất.

**Xong khi**: `npm run build` + `npm run typecheck` pass; cả 5 tài khoản đăng nhập và chỉ thấy khu vực đúng vai trò.

## Task 5 — Test & check toàn luồng

- Backend: `mvn -B -f server/pom.xml clean verify` pass; test đơn vị quy tắc lịch (08–22, 120 phút, cách 60 phút, 4 tuần); test phân quyền 401/403 mỗi service; Testcontainers cho chống trùng đồng thời.
- Postman collection `docs/api/postman/muc3.postman_collection.json` chạy bằng Postman CLI: login 5 user → duyệt tutor2 → search → đặt lịch → xác nhận → thông báo; kèm case lỗi 401/403/409/400.
- Kiểm tra tay trên `docker compose --profile app` qua http://localhost:8088 theo kịch bản demo ở trên.
- Cập nhật `docs/api/README.md` (endpoint mới), `docs/database/erd.md`, README (tài khoản demo, biến `JWT_SECRET`, `DEMO_PASSWORD`).
- CI xanh trước khi mở PR vào `main`.

**Xong khi**: tất cả lệnh trên pass, kịch bản demo chạy hết không lỗi.

## Thứ tự

Task 1 → (Task 2 ‖ Task 3) → Task 4 (bắt đầu được song song với mock API sau khi Task 1 chốt contract) → Task 5.
