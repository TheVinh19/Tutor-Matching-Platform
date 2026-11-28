# care-service

Chăm sóc khách hàng theo P18, vai trò `SUPPORT` (nhân viên CSKH). Cổng 8087, database `care_db`.

- Phiếu hỗ trợ của học sinh và gia sư, trả lời trong phiếu, đo thời gian phản hồi đầu tiên và giải quyết theo nhóm. Phiếu khiếu nại một buổi học chuyển thành vụ việc ở booking; admin kết luận (phương án B).
- FAQ do CSKH soạn, là nguồn trả lời của trợ lý AI; câu trợ lý chưa trả lời được về thành danh sách cần bổ sung FAQ.
- Chăm sóc 4 tầng: job 06:00 hằng ngày (giờ Việt Nam) đọc tín hiệu từ booking (hủy, vắng, tranh chấp, vụ việc mở, bị khóa, sắp hết kỳ chưa gia hạn) và assessment (điểm đầu vào, bài trước, bài gần nhất). Tầng 3–4 sinh việc cần gọi (một việc mở mỗi học sinh), tầng 2 nhận nhắc nhở tự động. Lưu lịch sử tầng, nhật ký liên hệ; xem số điện thoại chỉ với học sinh tầng 2 trở lên và có ghi nhật ký.
- Không sửa lịch, tiền, Hạng hay hồ sơ; không kết luận vụ việc.

API: xem [docs/api](../../../docs/api/README.md#care-service). Bản đồ: [domain-map](../../../docs/architecture/domain-map.md).
