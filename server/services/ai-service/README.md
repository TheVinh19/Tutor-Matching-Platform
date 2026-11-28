# ai-service

Trợ lý AI mức 2 theo P17. Cổng 8089, database `ai_db`.

Mô hình đã chốt là **Google Gemini** (29/11/2026), nhưng bản này mới chạy **chế độ truy xuất**: chưa gọi Gemini, chưa cần API key.

- Hỏi đáp: so khớp câu hỏi (bỏ dấu tiếng Việt, từ và cặp từ, trọng số theo độ hiếm) với bộ quy định có sẵn `src/main/resources/ai/policies.txt` và FAQ đã đăng của care-service (làm mới 5 phút/lần). Trả lời kèm nguồn; dưới ngưỡng thì nói chưa biết, báo khoảng trống FAQ cho CSKH và cho bấm "Chuyển CSKH" để tạo phiếu.
- Trợ lý học tập: giải thích câu sai từ lời giải thích trong ngân hàng câu hỏi, gợi ý chủ đề cần ôn; tóm tắt trình độ học sinh cho gia sư từ bảng điểm.
- Không tra dữ liệu riêng của người hỏi, không thao tác thay người dùng. Số điện thoại và email trong câu hỏi bị che trước khi lưu.
- Lưu từng lượt hỏi, giới hạn 30 câu/người/ngày, phản hồi hữu ích/không, thống kê cho admin và CSKH.
- Độ chính xác đo bằng bộ câu hỏi mẫu trong `RetrieverEvalTest` (yêu cầu ≥ 80%).

Khi tích hợp Gemini: giữ bộ quy định và FAQ làm nguồn dẫn chứng, Gemini chỉ nhận câu hỏi đã che số điện thoại/email cùng các mục truy xuất được; giữ chế độ truy xuất làm dự phòng khi Gemini lỗi; API key chỉ ở biến môi trường của ai-service; thêm sinh nháp câu hỏi cho ngân hàng (bắt buộc người duyệt).

API: xem [docs/api](../../../docs/api/README.md#ai). Bản đồ: [domain-map](../../../docs/architecture/domain-map.md).
