# Khung repo toàn dự án

Khung này được thiết kế từ 5 tài liệu, không chỉ dành cho mốc GS-01–GS-08. Module có README mới là vị trí thiết kế, không phải chức năng đã triển khai.

```text
gia-su-platform/                              # Tên minh họa, không đổi workspace
├── server/                                  # Spring Boot / Java 21 / Maven
│   ├── api-gateway/                         # :8080, /api/v1/<khu>/**; hiện chỉ health
│   ├── services/
│   │   ├── identity-service/                # :8081; account_db
│   │   ├── tutor-service/                   # :8082; tutor_db
│   │   ├── booking-service/                 # :8083; learning_db
│   │   ├── payment-service/                 # :8084; payment_db, tiền mô phỏng
│   │   ├── notification-service/            # :8085; notification_db
│   │   └── ai-service/                      # README, chờ đặc tả Gemini
│   ├── libs/
│   │   ├── common-core/                     # README; contract/helper khi cần
│   │   └── common-observability/            # README; metrics/request ID khi cần
│   ├── Dockerfile                          # Dùng chung, MODULE chọn app
│   └── pom.xml                             # Parent POM, 6 ứng dụng Maven
├── web/                                     # Next.js / React / TypeScript
│   ├── src/
│   │   ├── app/
│   │   │   ├── (public)/
│   │   │   │   ├── page.tsx                 # Trang / đã có
│   │   │   │   └── tutors/                  # README: danh sách/chi tiết gia sư
│   │   │   ├── (auth)/                      # README: login/register/OTP/reset
│   │   │   ├── (workspace)/
│   │   │   │   ├── learner/                 # Trang khung Người thuê đứng đơn
│   │   │   │   ├── tutor/                   # Trang khung Gia sư
│   │   │   │   └── admin/                   # Trang khung Admin
│   │   │   ├── layout.tsx
│   │   │   └── globals.css
│   │   ├── components/{auth,layout,ui}/     # Vị trí component dùng chung
│   │   ├── features/
│   │   │   ├── identity/                   # Tài khoản và người học
│   │   │   ├── tutors/                     # Hồ sơ, duyệt, tìm kiếm
│   │   │   ├── connections/                # Kết nối/trao đổi
│   │   │   ├── booking/                    # Kế hoạch, lịch, đổi/gia hạn
│   │   │   ├── classroom/                  # Buổi học, báo cáo, bù
│   │   │   ├── payments/                   # Giao dịch, thu nhập, đối soát
│   │   │   ├── disputes/                   # Vụ việc, bằng chứng, kháng nghị
│   │   │   ├── reputation/                 # EXP, Hạng, nhận xét
│   │   │   ├── notifications/              # Hộp thông báo
│   │   │   ├── admin/                      # Quản trị liên domain
│   │   │   └── ai/                         # Chờ đặc tả riêng
│   │   ├── config/                         # Env, roles, menu khi triển khai
│   │   ├── hooks/                          # React state/Context
│   │   ├── types/                          # Kiểu dữ liệu frontend
│   │   ├── schemas/                        # Validation form khi triển khai
│   │   ├── services/                       # Fetch API client khi triển khai
│   │   └── lib/                            # Helper khi có nhu cầu
│   ├── public/                             # Chỉ public asset, không bằng chứng riêng
│   ├── tests/e2e/                          # README: Playwright khi có nghiệp vụ
│   ├── Dockerfile
│   ├── next.config.ts
│   ├── tsconfig.json
│   ├── .env.example
│   ├── package-lock.json
│   └── package.json
├── mock/
│   ├── payment-provider/                   # README-only, callback thanh toán giả
│   └── README.md
├── infra/
│   ├── compose.yaml                        # Stack local, profile app
│   ├── postgres/init.sql                   # Database/role riêng mỗi service
│   ├── docker/                             # Hướng dẫn Dockerfile chung
│   ├── nginx/default.conf                  # HTTP local cho web và API
│   ├── monitoring/                         # README: Prometheus/Grafana dự kiến
│   └── storage/                            # README: media/bằng chứng riêng
├── docs/
│   ├── architecture/{overview,domain-map}.md
│   ├── api/README.md                       # Quy ước OpenAPI/task
│   ├── database/README.md                  # Quy ước migration, chưa ERD chi tiết
│   ├── decisions/001-service-boundaries.md
│   ├── conventions.md
│   ├── mock-contract.md
│   └── repository-layout.md
├── Tai lieu/                                # 5 DOCX gốc, giữ nguyên
├── .github/
│   ├── workflows/ci.yml                    # Build/test, chưa deploy
│   └── CODEOWNERS.example                  # Chưa gán người phụ trách
├── scripts/README.md
├── .env.example
├── .editorconfig
├── .gitignore
└── README.md
```

## Bên trong mỗi service

Mỗi app đang chạy có pom.xml, application entrypoint, SecurityConfig, application.yaml và test. Service nghiệp vụ có migration directory; chưa có bảng domain. Package theo `server/services/<service>/src/main/java/vn/giasu/<service>/`:

| Service | Module dự kiến đã có vị trí |
|---|---|
| identity | registration, verification, authentication, accounts, restrictions |
| tutor | profiles, catalog, approval, media, search, reviews, experience, ranks |
| booking | connections, conversations, availability, plans, holds, periods, sessions, rescheduling, renewals, reports, makeup, cases, evidence, appeals |
| payment | pricing, requests, callbacks, ledger, refunds, reconciliation, payouts, recoveries |
| notification | inbox, email, templates, delivery, consumers |

Thêm controller/service/repository/DTO/entity trong feature khi triển khai, không tạo lớp rỗng cho đủ tầng. Booking quản lý trọn vòng đời kỳ/buổi/vụ việc để giữ transaction lịch. Payment sở hữu giá/sổ tiền, tutor sở hữu uy tín từ kết quả nghiệp vụ hợp lệ.

Route groups không thay URL. Giữ `/learner` cho tài khoản Người thuê, có thể là phụ huynh; không tạo vai trò người học thứ tư. [Bản đồ giao diện](architecture/domain-map.md) mô tả đầy đủ các trang dự kiến.

Tailwind, shadcn/ui Base UI, React Hook Form, Zod, Leaflet và Playwright bổ sung khi có chức năng sử dụng. AI/common libraries chưa là Maven module; monitoring chưa hoạt động; payment mock chưa chạy. Mailpit xử lý email local, Meet là link nhập thủ công.

Nguồn yêu cầu và ràng buộc xem [bản đồ nghiệp vụ](architecture/domain-map.md); lệnh chạy/test xem [README](../README.md).
