# AI service — dự kiến

Trợ lý AI mức 2 theo P17 trong `Tai lieu/Yeu_cau_du_an_gia_su.docx`:

- Hỏi đáp quy định và FAQ (FAQ do CSKH duyệt ở care-service), có dẫn nguồn; không chắc thì không đoán mà tạo phiếu hỗ trợ.
- Trợ lý học tập gắn khảo thí (P16): giải thích câu sai, gợi ý chủ đề cần ôn, tóm tắt trình độ học sinh cho gia sư, sinh nháp câu hỏi cho ngân hàng (bắt buộc người duyệt).
- Không tra dữ liệu riêng của người hỏi, không thao tác thay người dùng; không gửi số điện thoại, địa chỉ, email, thanh toán hay bằng chứng sang nhà cung cấp mô hình.
- Lưu hội thoại, giới hạn lượt hỏi mỗi ngày, đo độ chính xác bằng bộ khoảng 30 câu hỏi mẫu.

Nhà cung cấp mô hình chưa chốt (Gemini đang cân nhắc). Hiện chỉ có README; chưa có Maven module, database, API key hoặc JVM. AI không sửa lịch, giao dịch, vụ việc hay Hạng; không phải thuật toán gợi ý hoặc xếp hạng gia sư.

Xem [bản đồ toàn dự án](../../../docs/architecture/domain-map.md).
