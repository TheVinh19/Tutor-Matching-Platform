package vn.giasu.booking.plans;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.booking.Schedule;
import vn.giasu.booking.cases.Cases;

/**
 * Read-only facts for assessment-service (P16) and care-service (P18), plus opening a case from a support ticket.
 * Booking stays the owner of plans, sessions and cases; callers never write its database.
 */
@RestController
class InternalServicesController {
    record PlanSummary(UUID id, UUID learnerId, UUID tutorId, String subjectCode, String levelCode, String status, UUID renewedFrom) {}
    /** Per learner who has studied: inputs of the 4 care tiers (P18). Windows are the last 30 days. */
    record CareSignal(UUID learnerId, long cancels30d, long absences30d, long disputes30d, long openCases, boolean restricted,
                      boolean endingWithoutRenewal, boolean lastTwoAbsent) {}
    record TicketCaseRequest(@NotNull UUID sessionId, @NotBlank @Size(max = 2000) String reason) {}
    record CaseRef(UUID caseId) {}

    private final JdbcClient jdbc;
    private final Plans plans;
    private final Cases cases;

    InternalServicesController(JdbcClient jdbc, Plans plans, Cases cases) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.cases = cases;
    }

    @GetMapping("/api/v1/bookings/internal/plans/{id}/summary")
    PlanSummary summary(@PathVariable UUID id) {
        Plans.Plan p = plans.get(id);
        return new PlanSummary(p.id(), p.learnerId(), p.tutorId(), p.subjectCode(), p.levelCode(), p.status(), p.renewedFrom());
    }

    // shortcut: one row per learner who ever had a confirmed period; page it when learners number in the tens of thousands.
    @GetMapping("/api/v1/bookings/internal/care/signals")
    List<CareSignal> careSignals() {
        return jdbc.sql("""
                WITH learners AS (
                    SELECT DISTINCT learner_id FROM plans
                    WHERE status IN ('CONFIRMED', 'HOAN_TAT_NGHIA_VU', 'KET_THUC_CO_HOAN_MOT_PHAN', 'HET_KY_CON_NGHIA_VU'))
                SELECT l.learner_id,
                    (SELECT count(*) FROM sessions s WHERE s.learner_id = l.learner_id AND s.cancelled_by = l.learner_id
                        AND s.cancelled_at > now() - interval '30 days') AS cancels30d,
                    (SELECT count(*) FROM sessions s WHERE s.learner_id = l.learner_id
                        AND s.status IN ('LEARNER_ABSENT', 'LEARNER_ABSENT_PENDING') AND s.start_at > now() - interval '30 days') AS absences30d,
                    (SELECT count(*) FROM cases c WHERE c.learner_id = l.learner_id AND c.kind = 'DISPUTE'
                        AND c.created_at > now() - interval '30 days') AS disputes30d,
                    (SELECT count(*) FROM cases c WHERE c.learner_id = l.learner_id AND c.status <> 'DECIDED') AS open_cases,
                    EXISTS (SELECT 1 FROM learner_strikes k WHERE k.learner_id = l.learner_id AND k.revoked_at IS NULL
                        AND k.restricted_until > now()) AS restricted,
                    EXISTS (SELECT 1 FROM plans p WHERE p.learner_id = l.learner_id AND p.status = 'CONFIRMED' AND p.end_date <= :limit
                        AND NOT EXISTS (SELECT 1 FROM plans r WHERE r.renewed_from = p.id
                            AND r.status IN ('REQUESTED', 'PROPOSED', 'CONFIRMED'))) AS ending_without_renewal,
                    coalesce((SELECT bool_and(x.status = 'LEARNER_ABSENT') AND count(*) = 2 FROM (
                        SELECT status FROM sessions s WHERE s.learner_id = l.learner_id AND s.start_at < now()
                            AND s.status IN ('CONFIRMED', 'LEARNER_ABSENT', 'FAILED_TUTOR', 'COMPLETED_PENDING_CONFIRM', 'DISPUTED')
                        ORDER BY s.start_at DESC LIMIT 2) x), false) AS last_two_absent
                FROM learners l""")
                .param("limit", Schedule.today().plusDays(Schedule.RENEWAL_REMINDER_DAYS))
                .query(CareSignal.class).list();
    }

    /** P18: a support ticket that is really a complaint about a session becomes (or joins) that session's case (P14). */
    @PostMapping("/api/v1/bookings/internal/cases/from-ticket")
    @Transactional
    CaseRef caseFromTicket(@Valid @RequestBody TicketCaseRequest r) {
        cases.open(plans.session(r.sessionId(), true), "DISPUTE", r.reason().strip());
        return new CaseRef(jdbc.sql("SELECT id FROM cases WHERE session_id = ?").param(r.sessionId()).query(UUID.class).single());
    }
}
