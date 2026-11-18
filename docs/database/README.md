# Database và migration

Mỗi service sở hữu database và role riêng. `infra/postgres/init.sql` tạo role/database khi PostgreSQL khởi tạo volume mới; chưa tạo bảng nghiệp vụ.

| Service | Database | Role | Dữ liệu thuộc phạm vi thiết kế |
|---|---|---|---|
| identity-service | account_db | account_app | Tài khoản, xác minh email, thông tin đăng nhập, phiên, vai trò và hạn chế truy cập |
| tutor-service | tutor_db | tutor_app | Hồ sơ, danh mục, video, duyệt, nhận xét, sổ EXP và Hạng |
| booking-service | learning_db | learning_app | Kết nối, trao đổi, lịch rảnh, kế hoạch/phiên bản, giữ lịch, kỳ/buổi, cam kết bù, vụ việc, bằng chứng và kháng nghị |
| payment-service | payment_db | payment_app | Bảng giá, giao dịch, sổ tiền, hoàn, thu hồi, đối soát và chi mô phỏng |
| notification-service | notification_db | notification_app | Thông báo web, email và lịch sử gửi |

`ai-service` là placeholder README, chưa có database. ERD, tên bảng và cột chưa được chốt trong khung này. Khi thiết kế, lưu ERD tại `docs/database/erd.md` và thêm migration tại `server/services/<service>/src/main/resources/db/migration`.

Không tạo foreign key xuyên database. Lưu ID tham chiếu và snapshot cần thiết, kiểm tra liên service qua contract. Thiết kế outbox, chống lặp và khóa xung đột lịch cùng domain; không thêm bảng mẫu thiếu mục đích.

Các ràng buộc phải xét khi triển khai:

- Kế hoạch có phiên bản, trên 0 buổi và tổng tiền trên 0 theo P02/P03.
- Lịch giữ/chốt đồng thời không trùng; booking sở hữu toàn bộ quyết định lịch theo P08.
- Giao dịch và sự kiện có định danh duy nhất; tiền kỳ vọng và snapshot giá có thể đối chiếu.
- Khoản thực dạy khác bồi hoàn; sổ tiền, EXP và quyết định có lịch sử điều chỉnh để truy vết.
- Bằng chứng giữ theo quyền đơn/vụ việc và không bị xóa khi vụ còn mở; đối chiếu P04/P05/P14.

Xem [domain map](../architecture/domain-map.md) để đối chiếu nguồn và các điều kiện chi tiết. Đổi tên service không đổi database/role legacy; không xóa volume để đổi tên. Thay mật khẩu trong `.env` không tự đổi mật khẩu của role đã tồn tại.
