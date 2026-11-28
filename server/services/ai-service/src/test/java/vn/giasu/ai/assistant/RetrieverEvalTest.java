package vn.giasu.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import vn.giasu.ai.assistant.Retriever.Entry;
import vn.giasu.ai.assistant.Retriever.Hit;
import vn.giasu.ai.assistant.Retriever.SourceType;

/** P17 accuracy set: sample questions against the built-in rules and care's starter FAQ; off-topic ones must not get an answer. */
class RetrieverEvalTest {
    /** Question → expected source ref, or null when the assistant should say it does not know. */
    static final String[][] CASES = {
            {"Gia sư bao lâu thì xác nhận yêu cầu đặt học của tôi?", "POL-HOLD"},
            {"Mỗi buổi học kéo dài mấy phút?", "POL-SESSION"},
            {"Buổi học có thể xếp vào khung giờ nào trong ngày?", "POL-SESSION"},
            {"Kỳ học tối đa bao nhiêu tuần?", "POL-PERIOD"},
            {"Gia sư vào lớp trễ 15 phút thì sao?", "POL-START"},
            {"Học sinh không vào lớp thì báo vắng thế nào?", "POL-ABSENT"},
            {"Tôi muốn hủy buổi học ngày mai", "POL-CANCEL"},
            {"Có đổi được giờ học cố định hằng tuần không?", "POL-SLOT"},
            {"Hết kỳ muốn học tiếp thì gia hạn sao?", "POL-RENEW"},
            {"Tại sao tài khoản tôi bị khóa đặt đơn?", "POL-STRIKE"},
            {"Vi phạm mấy lần thì bị khóa?", "POL-STRIKE"},
            {"Tôi muốn khiếu nại gia sư", "POL-CASE"},
            {"Nộp bằng chứng tranh chấp trong bao lâu?", "POL-CASE"},
            {"Làm sao để gia sư lên hạng?", "POL-RANK"},
            {"Xem bảng điểm của con ở đâu?", "POL-GRADEBOOK"},
            {"Video giới thiệu của gia sư đăng ở đâu?", "POL-VIDEO"},
            {"Chatbot có thể giúp gì cho tôi?", "POL-ASSISTANT"},
            {"Cách gửi phiếu hỗ trợ", "10000000-0000-0000-0000-000000000001"},
            {"Bộ phận CSKH làm việc mấy giờ?", "10000000-0000-0000-0000-000000000002"},
            {"Link Google Meet để học online ở đâu?", "10000000-0000-0000-0000-000000000003"},
            {"Làm bài kiểm tra ở đâu?", "10000000-0000-0000-0000-000000000004"},
            {"Đổi số điện thoại liên hệ thế nào?", "10000000-0000-0000-0000-000000000005"},
            {"Thời tiết Hà Nội hôm nay thế nào?", null},
            {"Giải giúp tôi phương trình bậc hai x^2 - 3x + 2 = 0", null},
            {"Công thức nấu phở bò", null},
            {"Giá vàng hôm nay bao nhiêu?", null},
    };

    static List<Entry> knowledge() throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (InputStream in = new ClassPathResource("ai/policies.txt").getInputStream()) {
            entries.addAll(Knowledge.parsePolicies(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
        // Mirrors care-service V2__faq_seed.sql.
        entries.add(faq("1", "Làm sao gửi yêu cầu hỗ trợ?", "Vào mục Hỗ trợ, chọn nhóm vấn đề (lịch học, gia sư, thanh toán, lỗi kỹ thuật, góp ý, khác), "
                + "ghi nội dung và gắn kỳ hoặc buổi học nếu có. Nhân viên CSKH trả lời ngay trong phiếu.", "hỗ trợ, phiếu, liên hệ, cskh"));
        entries.add(faq("2", "Giờ làm việc của bộ phận hỗ trợ?", "Từ 08:00 đến 17:00, thứ Hai đến thứ Bảy. Phiếu gửi ngoài giờ vẫn được nhận; "
                + "phản hồi ban đầu trong 1 ngày làm việc.", "giờ làm việc, hỗ trợ, thời gian phản hồi"));
        entries.add(faq("3", "Tôi xem link học online ở đâu?", "Link Google Meet nằm ở thẻ buổi học trong Lịch học (nút Vào lớp học). Gia sư có thể đổi "
                + "link từng buổi; bạn sẽ nhận thông báo khi link thay đổi.", "meet, link học, online, vào lớp"));
        entries.add(faq("4", "Làm bài kiểm tra gia sư giao ở đâu?", "Bài kiểm tra đầu kỳ, định kỳ và cuối kỳ hiện trong trang kỳ học. Bấm Bắt đầu để làm; "
                + "mỗi bài làm một lần, có giới hạn thời gian. Nộp xong xem được điểm theo chủ đề và lời giải thích.", "bài kiểm tra, khảo thí, làm bài, điểm"));
        entries.add(faq("5", "Làm sao cập nhật số điện thoại?", "Vào trang kỳ học đã chốt, ô Liên hệ có mục nhập số điện thoại của bạn (10 chữ số, bắt đầu "
                + "bằng 0). Số chỉ hiện cho bên kia sau khi kế hoạch được chốt.", "số điện thoại, liên hệ, cập nhật"));
        return entries;
    }

    static Entry faq(String n, String q, String a, String tags) {
        return new Entry(SourceType.FAQ, "10000000-0000-0000-0000-00000000000" + n, q, a, tags);
    }

    @Test
    void policiesFileParses() throws IOException {
        List<Entry> policies = knowledge().stream().filter(e -> e.type() == SourceType.POLICY).toList();
        assertEquals(14, policies.size());
        assertTrue(policies.stream().allMatch(e -> !e.answer().isBlank() && !e.tags().isBlank()));
    }

    @Test
    void answersAtLeastEightyPercentOfTheSampleSetCorrectly() throws IOException {
        Retriever retriever = new Retriever(knowledge());
        int right = 0;
        StringBuilder report = new StringBuilder();
        for (String[] c : CASES) {
            List<Hit> hits = retriever.search(c[0], 1);
            String got = hits.isEmpty() || hits.getFirst().score() < Retriever.THRESHOLD ? null : hits.getFirst().entry().ref();
            boolean ok = c[1] == null ? got == null : c[1].equals(got);
            if (ok) right++;
            report.append(ok ? "  ok  " : "  MISS ").append(c[0]).append(" -> ").append(got).append(" (")
                    .append(hits.isEmpty() ? "-" : "%s %.2f".formatted(hits.getFirst().entry().ref(), hits.getFirst().score())).append(")\n");
        }
        System.out.println(report);
        double accuracy = right * 100.0 / CASES.length;
        assertTrue(accuracy >= 80, "accuracy " + accuracy + "%\n" + report);
    }

    @Test
    void normalizeDropsVietnameseMarks() {
        assertEquals("doi lich hoc", Retriever.normalize("Đổi lịch học!"));
    }
}
