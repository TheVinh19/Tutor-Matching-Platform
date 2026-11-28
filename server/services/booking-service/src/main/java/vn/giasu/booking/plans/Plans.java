package vn.giasu.booking.plans;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Repository;
import vn.giasu.booking.Notifier;
import vn.giasu.common.web.ApiException;

/** Plan + session reads, ownership checks and plan completion; shared by plan, session and job code. */
@Repository
public class Plans {
    public record Plan(UUID id, UUID learnerId, UUID tutorId, String subjectCode, String levelCode, String mode, String note,
                       String status, String paymentStatus, String rejectReason, LocalDate startDate, LocalDate endDate,
                       OffsetDateTime holdExpiresAt, String meetLink, OffsetDateTime createdAt, OffsetDateTime proposedAt,
                       OffsetDateTime confirmedAt, OffsetDateTime closedAt, Integer sessionsPerWeek, Integer weeks,
                       UUID renewedFrom, OffsetDateTime renewalQualifiedAt) {}

    /** One weekly grid cell (weekday + start of a 120-minute session). */
    public record Cell(int dayOfWeek, LocalTime startTime) {}

    public record Session(UUID id, UUID planId, UUID tutorId, UUID learnerId, OffsetDateTime startAt, OffsetDateTime endAt,
                          String status, String meetLink, String cancelReason, UUID cancelledBy, OffsetDateTime cancelledAt,
                          OffsetDateTime completedAt, OffsetDateTime confirmDeadline, OffsetDateTime confirmedAt,
                          boolean autoConfirmed, String disputeReason, LocalDate offerFrom, LocalDate offerTo,
                          OffsetDateTime startedAt, Integer lateMinutes, String earlyEndBy, String earlyEndNote,
                          String absentNote, OffsetDateTime absentDeadline, OffsetDateTime cancelDeadline) {}

    public static final String PLAN_SELECT = """
            SELECT id, learner_id, tutor_id, subject_code, level_code, mode, note, status, payment_status, reject_reason,
                   start_date, end_date, hold_expires_at, meet_link, created_at, proposed_at, confirmed_at, closed_at,
                   sessions_per_week, weeks, renewed_from, renewal_qualified_at FROM plans""";
    public static final String SESSION_SELECT = """
            SELECT id, plan_id, tutor_id, learner_id, start_at, end_at, status, meet_link, cancel_reason, cancelled_by, cancelled_at,
                   completed_at, confirm_deadline, confirmed_at, auto_confirmed, dispute_reason, offer_from, offer_to,
                   started_at, late_minutes, early_end_by, early_end_note, absent_note, absent_deadline, cancel_deadline FROM sessions""";

    private final JdbcClient jdbc;
    private final Notifier notifier;

    public Plans(JdbcClient jdbc, Notifier notifier) {
        this.jdbc = jdbc;
        this.notifier = notifier;
    }

    public Plan get(UUID id) {
        return jdbc.sql(PLAN_SELECT + " WHERE id = ?").param(id).query(Plan.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "Không tìm thấy kế hoạch"));
    }

    /** Locks the row so concurrent confirm/cancel/propose on one plan serialize. */
    public Plan getForUpdate(UUID id) {
        return jdbc.sql(PLAN_SELECT + " WHERE id = ? FOR UPDATE").param(id).query(Plan.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "Không tìm thấy kế hoạch"));
    }

    public List<Cell> preferredSlots(UUID planId) {
        return jdbc.sql("SELECT day_of_week, start_time FROM plan_preferred_slots WHERE plan_id = ? ORDER BY day_of_week, start_time")
                .param(planId).query(Cell.class).list();
    }

    public List<Session> sessionsOf(UUID planId) {
        return jdbc.sql(SESSION_SELECT + " WHERE plan_id = ? AND status <> 'RELEASED' ORDER BY start_at").param(planId).query(Session.class).list();
    }

    public Session session(UUID id, boolean forUpdate) {
        return jdbc.sql(SESSION_SELECT + " WHERE id = ?" + (forUpdate ? " FOR UPDATE" : "")).param(id).query(Session.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Không tìm thấy buổi học"));
    }

    public static UUID caller(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    public static boolean isAdmin(Jwt jwt) { return "ADMIN".equals(jwt.getClaimAsString("role")); }

    /** Read access: the two parties, or an admin (support scope). */
    public static void requireParticipantOrAdmin(Jwt jwt, UUID learnerId, UUID tutorId) {
        UUID me = caller(jwt);
        if (!isAdmin(jwt) && !me.equals(learnerId) && !me.equals(tutorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Không có quyền với kế hoạch/buổi học này");
        }
    }

    public static void require(boolean condition, String code, String message) {
        if (!condition) throw new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    public static void requireStatus(boolean condition, String message) {
        if (!condition) throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS", message);
    }

    /** Sessions that are settled for closing the period: taught and accepted, learner absent, cancelled, or never booked. */
    static final Set<String> DONE = Set.of("CONFIRMED", "LEARNER_ABSENT", "FAILED_TUTOR", "CANCELLED", "RELEASED");
    /** Open obligations: wait for the case module (P07); the period cannot close normally while one is open. */
    static final Set<String> OPEN = Set.of("DISPUTED", "TUTOR_ABSENT_REPORTED");

    /**
     * Closes the period once no session is still to happen (P07): HOAN_TAT_NGHIA_VU, or KET_THUC_CO_HOAN_MOT_PHAN when the tutor
     * cancelled a session (refund of that part), or HET_KY_CON_NGHIA_VU while a dispute/absence report is open.
     * Locks the plan row so two session updates committing together cannot both miss the last step.
     */
    public void closeIfDone(UUID planId) {
        Plan plan = getForUpdate(planId);
        if (!"CONFIRMED".equals(plan.status()) && !"HET_KY_CON_NGHIA_VU".equals(plan.status())) return;
        List<Session> sessions = sessionsOf(planId);
        if (sessions.isEmpty() || sessions.stream().anyMatch(s -> !DONE.contains(s.status()) && !OPEN.contains(s.status()))) return;
        String next;
        if (sessions.stream().anyMatch(s -> OPEN.contains(s.status()))) {
            next = "HET_KY_CON_NGHIA_VU";
        } else if (sessions.stream().anyMatch(s -> "FAILED_TUTOR".equals(s.status())
                || "CANCELLED".equals(s.status()) && plan.tutorId().equals(s.cancelledBy()))) {
            next = "KET_THUC_CO_HOAN_MOT_PHAN";
        } else {
            next = "HOAN_TAT_NGHIA_VU";
        }
        if (next.equals(plan.status())) return;
        boolean closed = !"HET_KY_CON_NGHIA_VU".equals(next);
        jdbc.sql("UPDATE plans SET status = ?, closed_at = CASE WHEN ? THEN now() END, updated_at = now() WHERE id = ?")
                .params(next, closed, planId).update();
        if (closed) {
            notifier.send(plan.learnerId(), "PLAN_COMPLETED", "Kỳ học đã kết thúc", "Bạn có 14 ngày để gửi nhận xét về gia sư.", "PLAN", planId);
            notifier.send(plan.tutorId(), "PLAN_COMPLETED", "Kỳ học đã kết thúc",
                    "HOAN_TAT_NGHIA_VU".equals(next) ? "Kỳ học đã hoàn tất nghĩa vụ." : "Kỳ học kết thúc, có phần hoàn tiền buổi đã hủy.", "PLAN", planId);
            qualifyRenewal(planId);
        } else {
            notifier.send(plan.learnerId(), "PLAN_OPEN_OBLIGATION", "Kỳ học còn nghĩa vụ chưa xử lý",
                    "Hết lịch học nhưng còn buổi đang phản đối/báo vắng.", "PLAN", planId);
        }
    }

    /** Renewal counts (+50 EXP input) once the old period is HOAN_TAT_NGHIA_VU and the new one is paid; idempotent. */
    public void qualifyRenewal(UUID oldPlanId) {
        jdbc.sql("""
                UPDATE plans n SET renewal_qualified_at = now(), updated_at = now() FROM plans o
                WHERE n.renewed_from = o.id AND o.id = ? AND o.status = 'HOAN_TAT_NGHIA_VU' AND n.renewal_qualified_at IS NULL
                  AND n.status IN ('CONFIRMED', 'HOAN_TAT_NGHIA_VU', 'KET_THUC_CO_HOAN_MOT_PHAN', 'HET_KY_CON_NGHIA_VU')""")
                .param(oldPlanId).update();
    }
}
