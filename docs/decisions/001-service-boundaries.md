# ADR 001: Khung service toàn dự án

Trạng thái: Đề xuất kiến trúc đã đưa vào scaffold, cần rà soát khi chốt thiết kế chi tiết.

## Căn cứ

Tài liệu kiến trúc chọn microservices nhưng chưa chốt danh sách service cuối cùng. Nghiệp vụ có các nhóm tài khoản, hồ sơ/uy tín gia sư, vòng đời đặt học, tiền và thông báo; AI cần đặc tả riêng. Nguồn và ràng buộc đầy đủ nằm trong [domain map](../architecture/domain-map.md).

## Lựa chọn

Dùng năm service chính: `identity-service`, `tutor-service`, `booking-service`, `payment-service`, `notification-service`. `ai-service` chỉ có README. API Gateway là ứng dụng riêng; web dùng một ứng dụng Next.js với ba giao diện vai trò.

Chia tính năng thành module nội bộ. Giữ lịch rảnh, giữ lịch, kế hoạch, kỳ/buổi, đổi lịch, gia hạn và vụ việc trong booking để cùng chủ dữ liệu và ranh giới transaction lịch. Payment sở hữu bảng giá và sổ tiền; booking lưu snapshot đã xác nhận. Tutor sở hữu hồ sơ, EXP/Hạng và nhận xét, nhận kết quả nghiệp vụ đủ điều kiện từ booking cùng kết quả tiền liên quan; không suy đoán từ cache.

Đổi tên account thành identity và learning thành booking để thể hiện phạm vi. Giữ nguyên database, role và biến môi trường legacy, gồm `account_db`/`account_app` và `learning_db`/`learning_app`.

## Phương án đã cân nhắc

- Giữ tên account/learning: ít đổi đường dẫn hơn, nhưng learning dễ bị hiểu thành học liệu hoặc skill-gap ngoài phạm vi. Tên mới rõ trách nhiệm hơn và không yêu cầu đổi dữ liệu.
- Tách schedule, session, dispute, EXP hoặc media thành service riêng ngay: tăng hợp đồng/sự kiện khi chưa có code nghiệp vụ. Giữ module nội bộ trước.
- Monolith: giảm công vận hành nhưng không theo lựa chọn microservices của tài liệu kiến trúc.

## Hệ quả

Booking có phạm vi rộng và cần module theo feature. Tách service sau này dựa vào nhu cầu thực tế và ranh giới dữ liệu. Không có transaction chung booking/payment; outbox, chống lặp và đối soát cần được triển khai khi thêm luồng lịch/tiền.

Không có `admin-service`: admin thao tác qua domain đang sở hữu dữ liệu và vẫn cần kiểm tra quyền/audit. Thư viện dùng chung và monitoring mới có vị trí dành sẵn, chưa mặc định là module hoặc tích hợp chạy thật.

Xem [tổng quan kiến trúc](../architecture/overview.md) để phân biệt cấu hình hiện có với phần dự kiến.
