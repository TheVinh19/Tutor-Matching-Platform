# Nền tảng gia sư

Khung monorepo cho toàn dự án dựa trên 5 tài liệu trong `Tai lieu/`. Hiện có ứng dụng khởi động, cấu hình build/test và hạ tầng local; các chức năng nghiệp vụ mới là vị trí thiết kế.

Xem [cây thư mục](docs/repository-layout.md), [kiến trúc](docs/architecture/overview.md), [bản đồ nghiệp vụ/giao diện](docs/architecture/domain-map.md), [quy ước](docs/conventions.md).

## Cấu trúc

- `server/api-gateway/`: cổng vào 8080.
- `server/services/`: identity (8081), tutor (8082), booking (8083), payment (8084), notification (8085), assessment (8086), care (8087), ai (8089).
- `server/libs/`: vị trí thư viện chung, chưa là Maven module.
- `web/`: Next.js, React, TypeScript; ba khu vực Người thuê, Gia sư, Admin.
- `mock/`: vị trí nhà cung cấp thanh toán giả lập, chưa có server chạy.
- `infra/`: Compose, PostgreSQL, Kafka, Redis, Mailpit, Nginx; vị trí giám sát/storage.
- `docs/`: kiến trúc, domain/route map, API, database, ADR, quy ước, mock contract.
- `Tai lieu/`: 5 DOCX gốc được giữ nguyên.

## Chạy Docker

Cần Docker Desktop đang chạy Linux containers, Compose v2. Tại thư mục gốc:

```powershell
Copy-Item .env.example .env
# Chỉ hạ tầng:
docker compose --env-file .env -f infra/compose.yaml up -d
# Hoặc toàn bộ ứng dụng và hạ tầng:
docker compose --env-file .env -f infra/compose.yaml --profile app up -d --build
```

Đưa dữ liệu về mặc định (xóa volume, mất mọi dữ liệu kể cả tài khoản tự tạo; seed demo chạy lại, `.env` giữ `SPRING_PROFILES_ACTIVE=demo`):

```powershell
docker compose --env-file .env -f infra/compose.yaml --profile app down -v
docker compose --env-file .env -f infra/compose.yaml --profile app up -d --build
```

Cập nhật code vừa sửa, giữ nguyên dữ liệu (chỉ build lại và khởi động lại service đã đổi; ví dụ web và tutor-service, đổi tên service tương ứng):

```powershell
docker compose --env-file .env -f infra/compose.yaml --profile app up -d --build web tutor-service
```

Chỉ xóa dữ liệu mới thêm, giữ dữ liệu demo (không trả lịch rảnh demo về mặc định): `cd infra; powershell -File postgres/clean-non-demo.ps1`.

Tài khoản demo đăng nhập bằng email `admin@`, `support@` (nhân viên CSKH), `tutor1@`, `tutor2@`, `learner1@`, `learner2@` + `demo.giasu.vn`, mật khẩu là `DEMO_PASSWORD` (mặc định `Demo@12345`).

Website qua Nginx: http://localhost:8088; web: http://localhost:3000; Mailpit: http://localhost:8025. Health mẫu: http://localhost:8088/api/v1/identity/health.

Volume PostgreSQL tạo trước khi có assessment/care/ai chưa có `assessment_db`, `care_db`, `ai_db` (script khởi tạo chỉ chạy với volume mới). Tạo thêm mà không mất dữ liệu cũ (script chạy lại nhiều lần vẫn an toàn):

```powershell
docker compose --env-file .env -f infra/compose.yaml up -d postgres
docker compose --env-file .env -f infra/compose.yaml exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 -f /docker-entrypoint-initdb.d/02-p16-p18.sql
```

Lần đầu cần tải image/dependency và chờ khởi động. Stack nhiều JVM cần đủ RAM; có thể chỉ bật hạ tầng và chạy service liên quan từ IDE. Các cổng local bind loopback, chưa phải cấu hình production.

## Chạy từ IDE hoặc terminal

Cần Java 25 (LTS), Maven 3.9+, Node.js 24 LTS (repo cũng chấp nhận Node 25). Bật hạ tầng trước, mỗi app một PowerShell riêng:

```powershell
$env:DB_PASSWORD = 'local_account_change_me' # Khớp ACCOUNT_DB_PASSWORD trong .env
$env:JWT_SECRET = 'local_jwt_secret_change_me_min_32_bytes' # Giống nhau ở mọi service, >= 32 byte
$env:DEMO_PASSWORD = 'Demo@12345' # Chỉ identity: tạo 6 tài khoản demo
mvn -f server/services/identity-service/pom.xml spring-boot:run
```

Service khác dùng đường dẫn và mật khẩu tương ứng, cùng `JWT_SECRET`. Tutor và booking thêm `$env:SPRING_PROFILES_ACTIVE = 'demo'` để nạp dữ liệu demo (Flyway `db/demo`). DB_URL, DB_USERNAME, DB_PASSWORD, SERVER_PORT có thể ghi đè qua env. Identity giữ account_db/account_app; booking giữ learning_db/learning_app để không tác động dữ liệu cũ.

```powershell
mvn -f server/api-gateway/pom.xml spring-boot:run
```

Web trong terminal khác:

```powershell
Set-Location web
Copy-Item .env.example .env.local
npm ci
npm run dev
```

Web (Next.js + Tailwind + shadcn/ui, theme trắng cam) giữ JWT trong cookie httpOnly: `/auth/login` đăng nhập và đặt cookie, `/bff/v1/...` gắn Bearer token rồi chuyển tới gateway `/api/v1/...`. `src/proxy.ts` chặn vào `/learner`, `/tutor`, `/admin` sai vai trò (chỉ phục vụ trải nghiệm; backend vẫn kiểm tra quyền). Đặt `DEMO_PASSWORD` cho web để trang `/login` có nút đăng nhập nhanh. Gateway mở health và API nghiệp vụ identity/tutors/bookings/notifications/assessments/care/ai (xem [docs/api](docs/api/README.md)), chặn `/api/v1/*/internal/**` và API payment.

## Đăng nhập demo (mock)

Chưa có đăng ký/xác minh email. Khi đặt `DEMO_PASSWORD`, identity tạo 5 tài khoản dùng chung mật khẩu đó; `POST /api/v1/identity/auth/login` trả JWT thật và phân quyền chạy như bình thường.

| Email | Vai trò | Dữ liệu demo (profile `demo`) |
|---|---|---|
| `admin@demo.giasu.vn` | ADMIN | Duyệt hồ sơ |
| `tutor1@demo.giasu.vn` | TUTOR | Hồ sơ đã duyệt (Toán/Anh, linh hoạt), lịch rảnh T2/T4/T6/T7 08–12, T3/T5 18–22 |
| `tutor2@demo.giasu.vn` | TUTOR | Hồ sơ chờ duyệt (Văn, online) |
| `learner1@demo.giasu.vn` | LEARNER | Kế hoạch đã chốt với tutor1, T3/T5 18:00–20:00 |
| `learner2@demo.giasu.vn` | LEARNER | Trống, để demo đặt lịch |

## Kiểm tra

```powershell
mvn -B -f server/pom.xml clean verify
npm --prefix web run build
npm --prefix web run typecheck
docker compose --env-file .env.example -f infra/compose.yaml --profile app config --quiet
```

Backend: test route Gateway, test JWT/phân quyền cho 5 service và test API identity không cần database; test Postgres (`*PostgresTest`: đăng nhập demo, hồ sơ/duyệt/tìm kiếm/nhận xét, lịch rảnh/kế hoạch/buổi học/chống trùng đồng thời) dùng Testcontainers và tự bỏ qua khi máy không có Docker. CI chạy build/test server và build/typecheck web; chưa publish hoặc deploy. Chưa thay thế kiểm thử PostgreSQL, Kafka, email hay toàn bộ Docker.

## Phạm vi và dữ liệu

Module có README trong package/features/libs/mock/monitoring là vị trí thiết kế. Chưa có ERD chi tiết, OpenAPI/Swagger UI, thư viện form/UI, Postman/Playwright, outbox, giám sát hoặc triển khai VPS. Chỉ thêm khi triển khai chức năng tương ứng.

Thanh toán/hoàn/chi của đồ án chỉ mô phỏng. Không giữ tiền thật; không có LMS/đăng ký khóa học, SSO, cổng tuyển dụng, lọc giá hoặc thuật toán xếp hạng gia sư. Khảo thí, trợ lý AI và chăm sóc khách hàng (vai trò CSKH) đã chốt phạm vi mức 2 theo P16–P18 nhưng chưa triển khai. Không commit .env, bí mật hoặc bằng chứng riêng. Chỉ public asset nằm ở web/public.

Dừng mà giữ dữ liệu:

```powershell
docker compose --env-file .env -f infra/compose.yaml --profile app down
```

Không thêm `-v` trừ khi chủ động muốn xóa dữ liệu. PostgreSQL init chỉ chạy trên volume mới; đổi mật khẩu .env không tự đổi role đã tồn tại. Repo giữ project Compose giasu, database/role và volume cũ. Chưa commit/push hoặc cấu hình remote; CODEOWNERS.example chưa gán người phụ trách.
