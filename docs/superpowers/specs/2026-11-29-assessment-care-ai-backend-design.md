# Backend khảo thí, chăm sóc khách hàng, trợ lý AI (P16–P18)

Ngày: 2026-11-29. Người dùng: "bổ sung BE của các service vừa bàn, luồng có sẵn để yên". Chỉ backend; web làm sau.

## Nguyên tắc

- Luồng hiện có giữ nguyên hành vi. Booking và identity chỉ thêm: vai trò `SUPPORT`, endpoint mới, cột/bảng mới.
- Mỗi service mới có database riêng, gọi service khác qua API nội bộ (`/internal/**`, role SERVICE).
- Phương án B: CSKH tiếp nhận, liên hệ, đề xuất; chỉ admin kết luận vụ việc, kháng nghị, vi phạm.

## Identity

`Role.SUPPORT`; admin tạo tài khoản CSKH `POST /identity/admin/accounts/support`; demo `support@demo.giasu.vn`.

## Booking (thêm)

- CSKH đọc danh sách/chi tiết vụ việc; ghi liên hệ (`case_contacts`); đề xuất kết luận (cột `proposed_*` trên `cases`); xem số điện thoại hai bên của vụ chưa kết luận (ghi `contact_views`).
- Đề xuất vi phạm người thuê (`strike_proposals`), admin duyệt thì gọi đúng logic vi phạm hiện có.
- Nội bộ: tóm tắt kế hoạch (cho khảo thí), tín hiệu chăm sóc theo người thuê (cho care), mở vụ việc từ phiếu hỗ trợ.

## assessment-service (:8086, `assessment_db`)

Ngân hàng câu hỏi (4 đáp án, chủ đề, độ khó 1–3, nguồn, giải thích); admin soạn/nhập CSV/duyệt; gia sư thêm câu riêng. Gia sư giao bài đầu kỳ/định kỳ/cuối kỳ cho kỳ của mình (một bài đầu kỳ, một bài cuối kỳ mỗi kỳ). Học sinh làm một lượt: bốc ngẫu nhiên theo tỷ lệ độ khó 30/50/20, xáo đáp án, hạn giờ (nộp muộn vẫn chấm, gắn cờ muộn). Kết quả theo chủ đề; bảng điểm theo chuỗi kỳ gia hạn.

## care-service (:8087, `care_db`)

Phiếu hỗ trợ + tin nhắn, FAQ, khoảng trống FAQ (từ trợ lý), tầng chăm sóc 4 tầng (job hằng ngày 06:00 và nút chạy lại), việc cần làm, nhật ký liên hệ, xem số điện thoại có ghi nhật ký. Chuyển phiếu thành vụ việc ở booking.

## ai-service (:8089, `ai_db`)

Nhà cung cấp mô hình chưa chốt (P17), nên bản này chạy chế độ truy xuất: so khớp câu hỏi với FAQ (từ care) và bộ quy định có sẵn, trả lời kèm nguồn, không khớp thì đề nghị tạo phiếu. Trợ lý học tập dùng lời giải thích trong ngân hàng câu hỏi và điểm theo chủ đề. Sinh nháp câu hỏi bằng AI để lại tới khi chốt nhà cung cấp. Giới hạn 30 câu hỏi/người/ngày; bộ câu hỏi mẫu đo độ chính xác trong test.
