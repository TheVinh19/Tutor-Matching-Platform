# Đặc tả API

Lưu OpenAPI và tài liệu API cùng từng task triển khai, sau đó tổ chức theo domain. Khung hiện chưa có endpoint nghiệp vụ hoặc Swagger UI tổng hợp.

| Khu API | Service | Route hiện cấu hình tại gateway |
|---|---|---|
| `/api/v1/identity` | identity-service | `GET /api/v1/identity/health` |
| `/api/v1/tutors` | tutor-service | `GET /api/v1/tutors/health` |
| `/api/v1/bookings` | booking-service | `GET /api/v1/bookings/health` |
| `/api/v1/payments` | payment-service | `GET /api/v1/payments/health` |
| `/api/v1/notifications` | notification-service | `GET /api/v1/notifications/health` |

Các route này chuyển tiếp đến `/actuator/health` của từng service. Không mở management endpoint qua wildcard. `ai-service` chỉ có README và chưa có API.

Mỗi contract nghiệp vụ cần ghi input/output, trạng thái, mã lỗi, quyền vai trò, quyền với dữ liệu cụ thể, điều kiện chống lặp và ví dụ lỗi. Các endpoint trong danh sách công việc là đề xuất; chốt hợp đồng và prefix v1 khi triển khai.

Booking sở hữu quyết định lịch và vòng đời đơn/buổi/vụ việc. Payment sở hữu giá, giao dịch và sổ tiền. Hợp đồng giữa hai bên phải mô tả hết hạn, tiền muộn, callback lặp và đối soát; không coi transaction xuyên database là có sẵn.

Springdoc OpenAPI và Postman collection bổ sung khi có chức năng cần kiểm tra. Không tạo collection rỗng hoặc ví dụ giả như endpoint đã hoạt động. Xem [domain map](../architecture/domain-map.md) và [hợp đồng mock dự kiến](../mock-contract.md).
