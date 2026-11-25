package vn.giasu.booking.holds;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.giasu.booking.Notifier;
import vn.giasu.booking.Schedule;
import vn.giasu.booking.plans.Plans;

/**
 * Time-based transitions: expired holds free their slots; taught sessions not confirmed or disputed in 24h are accepted (P04);
 * absence reports and cancel reports run out; slot change requests expire; reminders before sessions and before a period ends.
 */
@Component
public class HoldJobs {
    record Due(UUID id, UUID planId, UUID tutorId, UUID learnerId, OffsetDateTime startAt) {}

    private final JdbcClient jdbc;
    private final Plans plans;
    private final Notifier notifier;

    HoldJobs(JdbcClient jdbc, Plans plans, Notifier notifier) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.notifier = notifier;
    }

    @Scheduled(fixedDelayString = "${booking.jobs.delay:60000}", initialDelayString = "${booking.jobs.delay:60000}")
    void tick() {
        releaseExpired();
        autoConfirm();
        confirmAbsences();
        cancelExpiredReports();
        expireSlotChanges();
        remindSessions();
        remindRenewals();
    }

    /** Own transaction so callers can free expired slots before taking new ones. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int releaseExpired() {
        return jdbc.sql("""
                WITH expired AS (
                    UPDATE plans SET status = 'EXPIRED', closed_at = now(), updated_at = now()
                    WHERE status = 'PROPOSED' AND hold_expires_at <= now() RETURNING id)
                UPDATE sessions SET status = 'RELEASED', updated_at = now()
                WHERE status = 'HELD' AND plan_id IN (SELECT id FROM expired)""").update();
    }

    /** Only sessions the tutor actually started are auto-accepted; disputes and cancel reports never are. */
    @Transactional
    public int autoConfirm() {
        List<UUID> planIds = jdbc.sql("""
                UPDATE sessions SET status = 'CONFIRMED', confirmed_at = now(), auto_confirmed = true, updated_at = now()
                WHERE status = 'COMPLETED_PENDING_CONFIRM' AND confirm_deadline <= now() AND started_at IS NOT NULL RETURNING plan_id""")
                .query(UUID.class).list();
        planIds.stream().distinct().forEach(plans::closeIfDone);
        return planIds.size();
    }

    /** P11: no objection within 24h of the tutor's absence report -> learner absent (compensation, not taught time). */
    @Transactional
    public int confirmAbsences() {
        List<UUID> planIds = jdbc.sql("""
                UPDATE sessions SET status = 'LEARNER_ABSENT', updated_at = now()
                WHERE status = 'LEARNER_ABSENT_PENDING' AND absent_deadline <= now() RETURNING plan_id""")
                .query(UUID.class).list();
        planIds.stream().distinct().forEach(plans::closeIfDone);
        return planIds.size();
    }

    /** A cancel report with no new time by its deadline cancels the session; cancelled_by tells payment who reported. */
    @Transactional
    public int cancelExpiredReports() {
        List<Due> cancelled = jdbc.sql("""
                UPDATE sessions SET status = 'CANCELLED', offer_from = NULL, offer_to = NULL, updated_at = now()
                WHERE status = 'CANCEL_REPORTED' AND cancel_deadline <= now() RETURNING id, plan_id, tutor_id, learner_id, start_at""")
                .query(Due.class).list();
        for (Due d : cancelled) {
            jdbc.sql("DELETE FROM session_offer_windows WHERE session_id = ?").param(d.id()).update();
            String body = "Buổi " + local(d.startAt()) + " không xếp được lịch mới nên đã hủy.";
            notifier.send(d.learnerId(), "SESSION_CANCELLED", "Buổi học đã hủy", body, "SESSION", d.id());
            notifier.send(d.tutorId(), "SESSION_CANCELLED", "Buổi học đã hủy", body, "SESSION", d.id());
        }
        cancelled.stream().map(Due::planId).distinct().forEach(plans::closeIfDone);
        return cancelled.size();
    }

    /** Unanswered weekly slot changes expire; the old schedule stays (P12). */
    @Transactional
    public int expireSlotChanges() {
        return jdbc.sql("UPDATE slot_changes SET status = 'EXPIRED', decided_at = now() WHERE status = 'PENDING' AND expires_at <= now()").update();
    }

    /** Reminders 60 and 30 minutes before a scheduled session, to both parties, once each. */
    @Transactional
    public int remindSessions() {
        return remind("reminded_60_at", "30 minutes", "60 minutes", "60") + remind("reminded_30_at", "0 minutes", "30 minutes", "30");
    }

    private int remind(String column, String after, String upTo, String label) {
        List<Due> due = jdbc.sql("UPDATE sessions SET " + column + " = now() WHERE status = 'SCHEDULED' AND " + column + " IS NULL"
                        + " AND start_at > now() + interval '" + after + "' AND start_at <= now() + interval '" + upTo + "'"
                        + " RETURNING id, plan_id, tutor_id, learner_id, start_at")
                .query(Due.class).list();
        for (Due d : due) {
            String body = "Buổi học bắt đầu lúc " + local(d.startAt()) + ".";
            notifier.send(d.learnerId(), "SESSION_REMINDER", "Còn " + label + " phút tới buổi học", body, "SESSION", d.id());
            notifier.send(d.tutorId(), "SESSION_REMINDER", "Còn " + label + " phút tới buổi học", body, "SESSION", d.id());
        }
        return due.size();
    }

    /** P12: remind the learner 14 days before a confirmed period ends, unless a renewal is already on its way. */
    @Transactional
    public int remindRenewals() {
        List<UUID[]> due = jdbc.sql("""
                UPDATE plans p SET renewal_reminded_at = now()
                WHERE p.status = 'CONFIRMED' AND p.renewal_reminded_at IS NULL AND p.end_date <= :limit
                  AND NOT EXISTS (SELECT 1 FROM plans r WHERE r.renewed_from = p.id AND r.status IN ('REQUESTED', 'PROPOSED', 'CONFIRMED'))
                RETURNING p.id, p.learner_id""")
                .param("limit", Schedule.today().plusDays(Schedule.RENEWAL_REMINDER_DAYS))
                .query((rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}).list();
        for (UUID[] d : due) {
            notifier.send(d[1], "PLAN_RENEWAL_REMINDER", "Kỳ học sắp kết thúc", "Gia hạn để giữ việc học với gia sư.", "PLAN", d[0]);
        }
        return due.size();
    }

    private static String local(OffsetDateTime t) {
        return t.atZoneSameInstant(Schedule.ZONE).toLocalDateTime().toString().replace('T', ' ');
    }
}
