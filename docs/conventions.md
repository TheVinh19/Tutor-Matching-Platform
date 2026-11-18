# Quy ước khung dự án

## Nguồn yêu cầu

Năm file trong `Tai lieu/` là nguồn nghiệp vụ và kiến trúc. P01–P15 tại mục 15 của `Yeu_cau_du_an_gia_su_v1.2.docx` là nguồn chính sách; tài liệu luồng, quyền và chỉ số tham chiếu các quy tắc này. Cách chia thư mục/service là lựa chọn thiết kế, không bổ sung quy tắc nghiệp vụ mới. Xem [domain map](architecture/domain-map.md).

## Backend

- Java 21, Spring Boot, Spring Cloud và Maven; parent POM tại `server/pom.xml`.
- Năm service chính là identity, tutor, booking, payment và notification. `ai-service` chỉ có README, chờ đặc tả riêng; API Gateway là ứng dụng riêng.
- Package theo feature: `vn.giasu.<service>.<feature>`. README trong package là placeholder. Chỉ thêm controller, DTO, service, repository và entity khi có logic tương ứng.
- Gateway kiểm tra truy cập đầu vào; khi triển khai, từng service vẫn phải kiểm tra JWT, vai trò và quyền đối với đơn/buổi/vụ việc.
- Không chia sẻ entity hoặc truy cập database của service khác. `server/libs` dành cho nhu cầu dùng chung thực sự, hiện chưa là Maven module.
- Prefix API dự kiến `/api/v1/<khu>`. Định dạng lỗi đề xuất là `{code,message,details,requestId}`; không trả dữ liệu nhạy cảm hoặc stack trace.
- Giữ nguyên đơn vị VND. Lịch dự kiến theo giờ Việt Nam; lưu thời điểm nhất quán, không dùng thời gian local thiếu múi giờ trong sự kiện.
- Không sửa migration đã áp dụng. Scaffold hiện chưa có bảng nghiệp vụ; thiết kế schema cùng task tương ứng.
- Booking sở hữu giữ/chốt lịch; payment sở hữu giá và tiền. Phối hợp xuyên service cần outbox, chống lặp và đối soát khi triển khai.

## Frontend

- Next.js App Router, React, TypeScript và npm; dùng lockfile.
- `(public)`, `(auth)`, `(workspace)` là route group, không xuất hiện trong URL.
- `/learner` dành cho tài khoản Người thuê đứng tên đơn, có thể là phụ huynh. Không tạo vai trò thứ tư cho người học khác tài khoản này.
- Component dùng chung ở `src/components`; code theo tính năng ở `src/features`. Dùng Fetch API và React state/Context theo kiến trúc; chưa thêm Axios/Zustand.
- Tailwind, shadcn/ui Base UI, React Hook Form, Zod và Leaflet bổ sung khi xây dựng chức năng sử dụng chúng.
- Chỉ để tài nguyên công khai trong `public`. Bằng chứng và video chưa duyệt phải qua backend có quyền.
- Route nghiệp vụ dự kiến nằm trong [domain map](architecture/domain-map.md); trang khung chưa có đầy đủ các route hoặc phân quyền đó.

## Git và kiểm tra

- Codex dùng nhánh `codex/`; nhánh đích và reviewer theo thỏa thuận nhóm.
- Cập nhật tài liệu API và kiểm tra phù hợp với thay đổi. Khi có logic lịch đồng thời hoặc tiền, kiểm tra bằng PostgreSQL thật hoặc Testcontainers.
- Không commit `.env`, cache, `target`, `node_modules`, bằng chứng riêng hoặc backup. Không ghi mật khẩu/token vào log.
- `CODEOWNERS.example` là ví dụ, chưa tự gán người phụ trách.
- CI hiện cấu hình Maven verify, web build và typecheck; chưa có release/deploy.

## Biến môi trường

| Phạm vi | Biến |
|---|---|
| Compose | `POSTGRES_PASSWORD`, `ACCOUNT_DB_PASSWORD`, `TUTOR_DB_PASSWORD`, `LEARNING_DB_PASSWORD`, `PAYMENT_DB_PASSWORD`, `NOTIFICATION_DB_PASSWORD` |
| Service local | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SERVER_PORT` |
| Gateway | `IDENTITY_SERVICE_URL`, `TUTOR_SERVICE_URL`, `BOOKING_SERVICE_URL`, `PAYMENT_SERVICE_URL`, `NOTIFICATION_SERVICE_URL` |
| Web | `API_GATEWAY_URL`; không dùng `NEXT_PUBLIC` cho bí mật |

Identity giữ `account_db`/`account_app`; booking giữ `learning_db`/`learning_app` và các biến mật khẩu legacy. Không đổi database, role hoặc volume chỉ vì đổi tên service.

Kafka từ host là `localhost:9092`, từ Compose là `kafka:19092`. Redis dùng `localhost:6379` hoặc `redis:6379`. SMTP dùng `localhost:1025` hoặc `mailpit:1025`; giao diện Mailpit ở cổng 8025. Các cấu hình producer/consumer/cache/email nghiệp vụ được thêm cùng chức năng, chưa mặc định đã tích hợp.
