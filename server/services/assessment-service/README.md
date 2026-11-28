# assessment-service

Khảo thí mức 2 theo P16 trong `Tai lieu/Yeu_cau_du_an_gia_su.docx`. Cổng 8086, database `assessment_db`.

- Ngân hàng câu hỏi theo môn, cấp, chủ đề, độ khó (1–3). Admin thêm câu đã duyệt hoặc nhập CSV (tối đa 1000 dòng, lỗi dòng nào báo dòng đó, không nhập nửa chừng); gia sư thêm câu riêng chờ admin duyệt. Không chép đề có bản quyền; cột `source` ghi nguồn.
- Gia sư giao bài đầu vào, định kỳ, cuối kỳ cho kỳ đã chốt của mình; đề bốc ngẫu nhiên theo tỉ lệ dễ/vừa/khó 30/50/20, đảo thứ tự đáp án. Học sinh làm một lần, có giờ nộp; chấm tự động, điểm theo chủ đề, lời giải thích.
- Bảng điểm nối qua các kỳ gia hạn (tối đa 12 kỳ), so sánh đầu vào với bài gần nhất theo chủ đề.
- Đọc kỳ học từ booking qua API nội bộ; cung cấp kết quả, bảng điểm cho ai-service và tín hiệu điểm cho care-service.
- Ngoài phạm vi: lộ trình mẫu, đăng ký theo khóa học, LMS; sinh nháp câu hỏi bằng Gemini làm khi ai-service tích hợp Gemini.

API: xem [docs/api](../../../docs/api/README.md#assessment). Bản đồ: [domain-map](../../../docs/architecture/domain-map.md).
