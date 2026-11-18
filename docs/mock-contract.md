# Hợp đồng mock thanh toán dự kiến

Mock phục vụ tích hợp local khi triển khai luồng tiền. Hiện thư mục mock mới là vị trí thiết kế; chưa có server hay endpoint chạy ở cổng 9000. Payment là mô phỏng theo phạm vi dự án.

## Ranh giới

Payment-service gửi yêu cầu QR/giao dịch đến mock và nhận callback khi có contract. Booking chỉ nhận kết quả tiền đã được payment kiểm tra; mock không quyết định giữ/chốt lịch, EXP hay đóng vụ việc.

Contract cần được chốt cùng chức năng: mã yêu cầu/giao dịch duy nhất, số tiền kỳ vọng cố định, thời điểm hết hạn, số nhận thực tế, thời điểm nhận, cách xác thực callback và cách xử lý lặp. Không tin ảnh chuyển khoản hoặc số tiền do phía khách nhập. Chưa ấn định endpoint hay payload như thể đã triển khai.

| Kịch bản cần hỗ trợ | Kết quả phải kiểm tra |
|---|---|
| Đúng tiền trong hạn | Payment ghi nhận một lần; booking chốt nếu điều kiện giữ lịch còn hợp lệ |
| Giao dịch hết hạn | Không chốt dựa trên kết quả thất bại; giải phóng đúng phần giữ theo nghiệp vụ |
| Tiền đến muộn | Xử lý hoàn/đảo theo P03/P08, không tự chốt lịch đã hết hạn |
| Sai số tiền | Giữ kết quả đối soát và xử lý theo chính sách, không tin số tiền kỳ vọng là tiền đã nhận |
| Callback lặp hoặc thử lại | Không ghi tiền, chốt lịch hoặc thưởng lại |
| Đổi lịch có thu bù | Giữ lịch cũ và mới trong thanh toán; thành công mới chuyển nguyên tử, thất bại bỏ giữ mới |
| Hoàn/chi lặp | Khi triển khai, không hoàn hoặc chi mô phỏng hai lần |

Mailpit nhận SMTP ở môi trường dev nên không cần email mock riêng. Meet là link gia sư nhập/cập nhật, không cần mock Meet. Không tích hợp LMS, SSO hoặc mock khoảng thiếu hụt học tập. Chỉ thiết kế mock Gemini sau khi có đặc tả AI riêng.

Xem [API](api/README.md), [domain map](architecture/domain-map.md) và [tổng quan kiến trúc](architecture/overview.md).
