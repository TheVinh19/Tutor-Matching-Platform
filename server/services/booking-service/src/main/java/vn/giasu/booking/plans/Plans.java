package vn.giasu.booking.plans;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
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
                       OffsetDateTime confirmedAt, OffsetDateTime closedAt, Integer sessionsPerWeek, Integer weeks) {}

    /** One weekly grid cell (weekday + start of a 120-minute session). */
    public record Cell(int dayOfWeek, LocalTime startTime) {}

    public record Session(UUID id, UUID planId, UUID tutorId, UUID learnerId, OffsetDateTime startAt, OffsetDateTime endAt,
                          String status, String meetLink, String cancelReason, UUID cancelledBy, OffsetDateTime cancelledAt,
                          OffsetDateTime completedAt, OffsetDateTime confirmDeadline, OffsetDateTime confirmedAt,
                          boolean autoConfirmed, String disputeReason, LocalDate offerFrom, LocalDate offerTo) {}

    public static final String PLAN_SELECT = """
            SELECT id, learner_id, tutor_id, subject_code, level_code, mode, note, status, payment_status, reject_reason,
                   start_date, end_date, hold_expires_at, meet_link, created_at, proposed_at, confirmed_at, closed_at,
                   sessions_per_week, weeks FROM plans""";
    public static final String SESSION_SELECT = """
            SELECT id, plan_id, tutor_id, learner_id, start_at, end_at, status, meet_link, cancel_reason, cancelled_by, cancelled_at,
                   completed_at, confirm_deadline, confirmed_at, auto_confirmed, dispute_reason, offer_from, offer_to FROM sessions""";

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

    /** A plan is COMPLETED once every remaining session is CONFIRMED (cancel reports or disputes keep it open). */
    public void completeIfDone(UUID planId) {
        int updated = jdbc.sql("""
                UPDATE plans SET status = 'COMPLETED', closed_at = now(), updated_at = now()
                WHERE id = :id AND status = 'CONFIRMED'
                  AND EXISTS (SELECT 1 FROM sessions WHERE plan_id = :id AND status = 'CONFIRMED')
                  AND NOT EXISTS (SELECT 1 FROM sessions WHERE plan_id = :id AND status NOT IN ('CONFIRMED', 'CANCELLED', 'RELEASED'))""")
                .param("id", planId).update();
        if (updated > 0) {
            Plan plan = get(planId);
            notifier.send(plan.learnerId(), "PLAN_COMPLETED", "Kỳ học đã hoàn tất",
                    "Bạn có 14 ngày để gửi nhận xét về gia sư.", "PLAN", planId);
            notifier.send(plan.tutorId(), "PLAN_COMPLETED", "Kỳ học đã hoàn tất", "Kỳ học đã hoàn tất nghĩa vụ.", "PLAN", planId);
        }
    }
}
