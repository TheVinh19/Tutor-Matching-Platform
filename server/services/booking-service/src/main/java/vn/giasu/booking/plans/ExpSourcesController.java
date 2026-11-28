package vn.giasu.booking.plans;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * For tutor-service EXP and rank review (P09/P10). Booking decides which facts qualify; tutor-service owns the ledger and
 * inserts each event once by its key, so repeated reads are harmless.
 */
@RestController
class ExpSourcesController {
    record Event(String key, String type, int points, String refType, UUID refId, OffsetDateTime occurredAt) {}
    /** tutorFaultFailed: sessions a case concluded as the tutor's fault (FAILED_TUTOR). */
    record Stats(long taughtSessions, long tutorFaultFailed, long distinctLearners, long renewals, long openIssues) {}
    record Sources(List<Event> events, Stats stats) {}

    private final JdbcClient jdbc;

    ExpSourcesController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/api/v1/bookings/internal/tutors/{tutorId}/exp-sources")
    Sources sources(@PathVariable UUID tutorId) {
        List<Event> events = new ArrayList<>();
        // +10: accepted and started in the planned minute, Vietnam time (P09). Late start still counts as taught, just no bonus.
        events.addAll(jdbc.sql("""
                SELECT 'session:' || id AS key, 'SESSION_ON_TIME' AS type, 10 AS points, 'SESSION' AS ref_type, id AS ref_id,
                       confirmed_at AS occurred_at
                FROM sessions WHERE tutor_id = ? AND status = 'CONFIRMED' AND started_at IS NOT NULL
                  AND date_trunc('minute', started_at) = date_trunc('minute', start_at)""")
                .param(tutorId).query(Event.class).list());
        events.addAll(jdbc.sql("""
                SELECT 'period:' || id AS key, 'PERIOD_COMPLETED' AS type, 30 AS points, 'PLAN' AS ref_type, id AS ref_id, closed_at AS occurred_at
                FROM plans WHERE tutor_id = ? AND status = 'HOAN_TAT_NGHIA_VU'""")
                .param(tutorId).query(Event.class).list());
        events.addAll(jdbc.sql("""
                SELECT 'renewal:' || id AS key, 'RENEWAL' AS type, 50 AS points, 'PLAN' AS ref_type, id AS ref_id,
                       renewal_qualified_at AS occurred_at
                FROM plans WHERE tutor_id = ? AND renewal_qualified_at IS NOT NULL""")
                .param(tutorId).query(Event.class).list());
        Stats stats = jdbc.sql("""
                SELECT (SELECT count(*) FROM sessions WHERE tutor_id = :t AND status = 'CONFIRMED') AS taught_sessions,
                       (SELECT count(*) FROM sessions WHERE tutor_id = :t AND status = 'FAILED_TUTOR') AS tutor_fault_failed,
                       (SELECT count(DISTINCT learner_id) FROM plans WHERE tutor_id = :t AND status = 'HOAN_TAT_NGHIA_VU') AS distinct_learners,
                       (SELECT count(*) FROM plans WHERE tutor_id = :t AND renewal_qualified_at IS NOT NULL) AS renewals,
                       (SELECT count(*) FROM sessions WHERE tutor_id = :t AND status IN ('DISPUTED', 'TUTOR_ABSENT_REPORTED')) AS open_issues""")
                .param("t", tutorId).query(Stats.class).single();
        return new Sources(events, stats);
    }
}
