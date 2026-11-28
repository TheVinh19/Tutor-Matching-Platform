# ai-service

Trợ lý AI mức 2 theo P17. Cổng 8089, database `ai_db`.

Nhà cung cấp mô hình chưa chốt (Gemini đang cân nhắc), nên bản này chạy **chế độ truy xuất**: không gọi mô hình ngoài, không cần API key.

- Hỏi đáp: so khớp câu hỏi (bỏ dấu tiếng Việt, từ và cặp từ, trọng số theo độ hiếm) với bộ quy định có sẵn `src/main/resources/ai/policies.txt` và FAQ đã đăng của care-service (làm mới 5 phút/lần). Trả lời kèm nguồn; dưới ngưỡng thì nói chưa biết, báo khoảng trống FAQ cho CSKH và cho bấm "Chuyển CSKH" để tạo phiếu.
- Trợ lý học tập: giải thích câu sai từ lời giải thích trong ngân hàng câu hỏi, gợi ý chủ đề cần ôn; tóm tắt trình độ học sinh cho gia sư từ bảng điểm.
- Không tra dữ liệu riêng của người hỏi, không thao tác thay người dùng. Số điện thoại và email trong câu hỏi bị che trước khi lưu.
- Lưu từng lượt hỏi, giới hạn 30 câu/người/ngày, phản hồi hữu ích/không, thống kê cho admin và CSKH.
- Độ chính xác đo bằng bộ câu hỏi mẫu trong `RetrieverEvalTest` (yêu cầu ≥ 80%).

Khi chốt nhà cung cấp: giữ bộ quy định và FAQ làm nguồn dẫn chứng, thay phần so khớp bằng mô hình; thêm sinh nháp câu hỏi cho ngân hàng (bắt buộc người duyệt).

API: xem [docs/api](../../../docs/api/README.md#ai). Bản đồ: [domain-map](../../../docs/architecture/domain-map.md).
