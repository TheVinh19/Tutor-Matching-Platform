package vn.giasu.care.tiers;

import java.util.ArrayList;
import java.util.List;

/**
 * P18 care tiers, a pure function of a learner's last-30-day signals so the thresholds are testable without a database.
 * T4 needs action now, T3 a staff call, T2 an automatic reminder, T1 is learning steadily.
 */
final class CareRules {
    /** Below this many points under the previous test counts as a drop (T2). */
    static final double SCORE_DROP_POINTS = 10;

    record Signals(long cancels30d, long absences30d, long disputes30d, long openCases, boolean restricted, boolean endingWithoutRenewal,
                   boolean lastTwoAbsent, Double placement, Double previous, Double latest, boolean overdueTicket) {}

    record Tier(int tier, List<String> reasons) {}

    private CareRules() {}

    static Tier tier(Signals s) {
        List<String> t4 = new ArrayList<>();
        if (s.openCases() > 0) t4.add("Có vụ việc đang mở");
        if (s.restricted()) t4.add("Đang bị khóa đặt đơn");
        if (s.lastTwoAbsent() && s.endingWithoutRenewal()) t4.add("Dấu hiệu bỏ học: vắng 2 buổi gần nhất và sắp hết kỳ chưa gia hạn");
        if (!t4.isEmpty()) return new Tier(4, t4);

        long misses = s.cancels30d() + s.absences30d();
        List<String> t3 = new ArrayList<>();
        if (misses >= 2) t3.add("Hủy/vắng " + misses + " buổi trong 30 ngày");
        if (s.disputes30d() >= 1) t3.add("Có tranh chấp trong 30 ngày");
        if (s.latest() != null && s.placement() != null && s.latest() < s.placement()) t3.add("Điểm gần nhất thấp hơn bài đầu vào");
        if (s.overdueTicket()) t3.add("Có phiếu hỗ trợ quá 48 giờ chưa giải quyết");
        if (!t3.isEmpty()) return new Tier(3, t3);

        List<String> t2 = new ArrayList<>();
        if (misses == 1) t2.add("Hủy/vắng 1 buổi trong 30 ngày");
        if (s.latest() != null && s.previous() != null && s.latest() < s.previous() - SCORE_DROP_POINTS) t2.add("Điểm giảm so với bài trước");
        if (s.endingWithoutRenewal()) t2.add("Sắp hết kỳ chưa gia hạn");
        if (!t2.isEmpty()) return new Tier(2, t2);

        return new Tier(1, List.of("Học đều"));
    }
}
