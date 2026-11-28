package vn.giasu.booking.cases;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.booking.IdentityClient;
import vn.giasu.booking.Notifier;
import vn.giasu.booking.plans.Plans;
import vn.giasu.common.web.ApiException;

/**
 * P07/P12/P14 cases. Both parties see the case and add evidence links; only the admin decides. Without tutor evidence after 72h the
 * suggested outcome follows the learner (scope of the complaint only); with evidence the admin compares both sides. Appeals reopen
 * the case with no limit on count or total duration.
 */
@RestController
class CaseController {
    enum Outcome { TUTOR_FAULT, LEARNER_WRONG, NO_FAULT }
    enum StrikeKind { SELF_CANCEL, NO_SHOW }

    record Case(UUID id, UUID sessionId, UUID planId, UUID learnerId, UUID tutorId, String kind, String status, String reason,
                OffsetDateTime evidenceDeadline, String outcome, String decisionReason, UUID decidedBy, OffsetDateTime decidedAt,
                OffsetDateTime createdAt, OffsetDateTime updatedAt,
                String proposedOutcome, String proposalReason, UUID proposedBy, OffsetDateTime proposedAt) {}
    record Evidence(long id, UUID submittedBy, String party, String url, String note, OffsetDateTime createdAt) {}
    record Event(String action, UUID actorId, String note, OffsetDateTime createdAt) {}
    record Contact(long id, UUID actorId, String party, String channel, boolean reached, String note, OffsetDateTime createdAt) {}
    record Detail(Case caseInfo, Plans.Session session, List<Evidence> evidence, List<Event> events, String suggestedOutcome,
                  List<Contact> contacts) {}
    enum Party { LEARNER, TUTOR }
    enum Channel { PHONE, MESSAGE, EMAIL, OTHER }
    record ContactRequest(@NotNull Party party, @NotNull Channel channel, @NotNull Boolean reached, @NotBlank @Size(max = 2000) String note) {}
    record PhoneView(String party, String phone) {}
    record EvidenceRequest(@NotNull @Size(min = 1, max = 5) List<@NotBlank @Size(max = 500) @Pattern(regexp = "https://\\S+", message = "phải là link https") String> urls,
                           @Size(max = 1000) String note) {}
    record DecideRequest(@NotNull Outcome outcome, @NotBlank @Size(max = 2000) String reason) {}
    record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}
    record StrikeRequest(@NotNull StrikeKind kind, @NotBlank @Size(max = 1000) String reason, UUID sessionId) {}
    record Strike(long id, String kind, UUID caseId, UUID sessionId, String reason, OffsetDateTime restrictedUntil, OffsetDateTime revokedAt,
                  OffsetDateTime createdAt) {}
    record Restriction(long activeStrikes, OffsetDateTime restrictedUntil, List<Strike> strikes) {}

    private static final String SELECT = """
            SELECT id, session_id, plan_id, learner_id, tutor_id, kind, status, reason, evidence_deadline, outcome, decision_reason,
                   decided_by, decided_at, created_at, updated_at, proposed_outcome, proposal_reason, proposed_by, proposed_at FROM cases""";

    private final JdbcClient jdbc;
    private final Plans plans;
    private final Cases cases;
    private final Notifier notifier;
    private final IdentityClient identity;

    CaseController(JdbcClient jdbc, Plans plans, Cases cases, Notifier notifier, IdentityClient identity) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.cases = cases;
        this.notifier = notifier;
        this.identity = identity;
    }

    /** P18: customer care staff read every case to take it in; they never decide it. */
    static boolean isSupport(Jwt jwt) { return "SUPPORT".equals(jwt.getClaimAsString("role")); }

    private static void requireCaseReader(Jwt jwt, Case c) {
        if (!isSupport(jwt)) Plans.requireParticipantOrAdmin(jwt, c.learnerId(), c.tutorId());
    }

    @GetMapping("/api/v1/bookings/cases/me")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    List<Case> mine(@AuthenticationPrincipal Jwt jwt) {
        String column = "TUTOR".equals(jwt.getClaimAsString("role")) ? "tutor_id" : "learner_id";
        return jdbc.sql(SELECT + " WHERE " + column + " = ? ORDER BY created_at DESC").param(Plans.caller(jwt)).query(Case.class).list();
    }

    @GetMapping("/api/v1/bookings/cases/{id}")
    Detail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Case c = get(id, false);
        requireCaseReader(jwt, c);
        return detailOf(c);
    }

    /** The case of a session, for the session card. */
    @GetMapping("/api/v1/bookings/sessions/{sessionId}/case")
    Detail ofSession(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        Case c = jdbc.sql(SELECT + " WHERE session_id = ?").param(sessionId).query(Case.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND", "Buổi này chưa có vụ việc"));
        requireCaseReader(jwt, c);
        return detailOf(c);
    }

    @PostMapping("/api/v1/bookings/cases/{id}/evidence")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    @Transactional
    Detail addEvidence(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody EvidenceRequest r) {
        Case c = get(id, true);
        UUID me = Plans.caller(jwt);
        boolean tutor = me.equals(c.tutorId());
        Plans.require(tutor || me.equals(c.learnerId()), "NOT_CASE_PARTY", "Không phải bên trong vụ việc");
        Plans.requireStatus(!"DECIDED".equals(c.status()), "Vụ việc đã có kết luận; hãy kháng nghị để bổ sung căn cứ");
        if (tutor && "OPEN".equals(c.status()) && OffsetDateTime.now(ZoneOffset.UTC).isAfter(c.evidenceDeadline())) {
            throw new ApiException(HttpStatus.CONFLICT, "EVIDENCE_WINDOW_CLOSED", "Đã quá hạn 72 giờ nộp bằng chứng");
        }
        String note = r.note() == null || r.note().isBlank() ? null : r.note().strip();
        for (String url : r.urls()) {
            jdbc.sql("INSERT INTO case_evidence (case_id, submitted_by, party, url, note) VALUES (?, ?, ?, ?, ?)")
                    .params(id, me, tutor ? "TUTOR" : "LEARNER", url.strip(), note).update();
        }
        jdbc.sql("UPDATE cases SET updated_at = now() WHERE id = ?").param(id).update();
        return detailOf(get(id, false));
    }

    @PostMapping("/api/v1/bookings/cases/{id}/appeal")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    @Transactional
    Detail appeal(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        Case c = get(id, true);
        UUID me = Plans.caller(jwt);
        Plans.require(me.equals(c.tutorId()) || me.equals(c.learnerId()), "NOT_CASE_PARTY", "Không phải bên trong vụ việc");
        Plans.requireStatus("DECIDED".equals(c.status()), "Chỉ kháng nghị vụ việc đã có kết luận");
        jdbc.sql("UPDATE cases SET status = 'APPEALED', updated_at = now() WHERE id = ?").param(id).update();
        jdbc.sql("INSERT INTO case_events (case_id, action, actor_id, note) VALUES (?, 'APPEALED', ?, ?)").params(id, me, r.reason().strip()).update();
        notifier.send(me.equals(c.tutorId()) ? c.learnerId() : c.tutorId(), "CASE_APPEALED", "Vụ việc bị kháng nghị", r.reason().strip(), "CASE", id);
        return detailOf(get(id, false));
    }

    @GetMapping("/api/v1/bookings/admin/cases")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT')")
    List<Case> adminList(@RequestParam(required = false) String status) {
        return jdbc.sql(SELECT + " WHERE cast(:status AS varchar) IS NULL OR status = :status ORDER BY updated_at DESC LIMIT 100")
                .param("status", status).query(Case.class).list();
    }

    /** P18/P11: staff log each call or message to a party (a silent tutor needs at least 2 attempts within 24h). */
    @PostMapping("/api/v1/bookings/support/cases/{id}/contacts")
    @PreAuthorize("hasRole('SUPPORT')")
    @Transactional
    Detail logContact(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ContactRequest r) {
        get(id, true);
        jdbc.sql("INSERT INTO case_contacts (case_id, actor_id, party, channel, reached, note) VALUES (?, ?, ?, ?, ?, ?)")
                .params(id, Plans.caller(jwt), r.party().name(), r.channel().name(), r.reached(), r.note().strip()).update();
        jdbc.sql("UPDATE cases SET updated_at = now() WHERE id = ?").param(id).update();
        return detailOf(get(id, false));
    }

    /** Option B: staff propose; the admin's decision (below) stays the only thing that changes the session. */
    @PostMapping("/api/v1/bookings/support/cases/{id}/proposal")
    @PreAuthorize("hasRole('SUPPORT')")
    @Transactional
    Detail propose(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody DecideRequest r) {
        Case c = get(id, true);
        Plans.requireStatus(!"DECIDED".equals(c.status()), "Vụ việc đã có kết luận");
        UUID me = Plans.caller(jwt);
        String reason = r.reason().strip();
        jdbc.sql("""
                UPDATE cases SET proposed_outcome = ?, proposal_reason = ?, proposed_by = ?, proposed_at = now(), updated_at = now()
                WHERE id = ?""").params(r.outcome().name(), reason, me, id).update();
        jdbc.sql("INSERT INTO case_events (case_id, action, actor_id, note) VALUES (?, 'PROPOSED', ?, ?)")
                .params(id, me, r.outcome().name() + ": " + reason).update();
        return detailOf(get(id, false));
    }

    /** Phone of one party of an undecided case, for staff to call; every read is logged (P18). */
    @GetMapping("/api/v1/bookings/support/cases/{id}/phone")
    @PreAuthorize("hasRole('SUPPORT')")
    @Transactional
    PhoneView phone(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam Party party) {
        Case c = get(id, false);
        Plans.requireStatus(!"DECIDED".equals(c.status()), "Chỉ xem liên hệ khi vụ việc đang xử lý");
        UUID account = party == Party.TUTOR ? c.tutorId() : c.learnerId();
        jdbc.sql("INSERT INTO contact_views (actor_id, account_id, case_id) VALUES (?, ?, ?)").params(Plans.caller(jwt), account, id).update();
        return new PhoneView(party.name(), identity.phone(account));
    }

    /** The session follows the outcome; a re-decision after appeal reverses the previous one (strike revoked, session updated). */
    @PostMapping("/api/v1/bookings/admin/cases/{id}/decide")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Detail decide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody DecideRequest r) {
        Case c = get(id, true);
        Plans.requireStatus(!"DECIDED".equals(c.status()), "Vụ việc đã có kết luận");
        UUID admin = Plans.caller(jwt);
        String reason = r.reason().strip();
        plans.session(c.sessionId(), true);
        cases.revokeStrikesOf(id);
        switch (r.outcome()) {
            case TUTOR_FAULT -> jdbc.sql("UPDATE sessions SET status = 'FAILED_TUTOR', updated_at = now() WHERE id = ?").param(c.sessionId()).update();
            case LEARNER_WRONG -> {
                jdbc.sql("UPDATE sessions SET status = 'CONFIRMED', confirmed_at = coalesce(confirmed_at, now()), updated_at = now() WHERE id = ?")
                        .param(c.sessionId()).update();
                cases.strike(c.learnerId(), "FALSE_COMPLAINT", id, c.sessionId(), "Khiếu nại sai đã xác minh: " + reason, admin);
            }
            case NO_FAULT -> jdbc.sql("UPDATE sessions SET status = 'CANCELLED', cancelled_by = NULL, updated_at = now() WHERE id = ?")
                    .param(c.sessionId()).update();
        }
        jdbc.sql("""
                UPDATE cases SET status = 'DECIDED', outcome = ?, decision_reason = ?, decided_by = ?, decided_at = now(), updated_at = now()
                WHERE id = ?""").params(r.outcome().name(), reason, admin, id).update();
        jdbc.sql("INSERT INTO case_events (case_id, action, actor_id, note) VALUES (?, 'DECIDED', ?, ?)")
                .params(id, admin, r.outcome().name() + ": " + reason).update();
        // shortcut: a re-decision after the period already closed changes the session only, the period is not reopened.
        plans.closeIfDone(c.planId());
        for (UUID party : List.of(c.learnerId(), c.tutorId())) {
            notifier.send(party, "CASE_DECIDED", "Vụ việc đã có kết luận", reason, "CASE", id);
        }
        return detailOf(get(id, false));
    }

    @PostMapping("/api/v1/bookings/admin/learners/{learnerId}/strikes")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Restriction addStrike(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID learnerId, @Valid @RequestBody StrikeRequest r) {
        cases.strike(learnerId, r.kind().name(), null, r.sessionId(), r.reason().strip(), Plans.caller(jwt));
        return restriction(learnerId);
    }

    @GetMapping("/api/v1/bookings/admin/learners/{learnerId}/strikes")
    @PreAuthorize("hasRole('ADMIN')")
    Restriction strikes(@PathVariable UUID learnerId) {
        return restriction(learnerId);
    }

    @GetMapping("/api/v1/bookings/me/restriction")
    @PreAuthorize("hasRole('LEARNER')")
    Restriction myRestriction(@AuthenticationPrincipal Jwt jwt) {
        return restriction(Plans.caller(jwt));
    }

    private Restriction restriction(UUID learnerId) {
        List<Strike> strikes = jdbc.sql("""
                SELECT id, kind, case_id, session_id, reason, restricted_until, revoked_at, created_at FROM learner_strikes
                WHERE learner_id = ? ORDER BY created_at DESC""").param(learnerId).query(Strike.class).list();
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minus(Cases.STRIKE_WINDOW);
        long active = strikes.stream().filter(s -> s.revokedAt() == null && s.createdAt().isAfter(since)).count();
        return new Restriction(active, cases.restrictedUntil(learnerId), strikes);
    }

    private Detail detailOf(Case c) {
        List<Evidence> evidence = jdbc.sql("SELECT id, submitted_by, party, url, note, created_at FROM case_evidence WHERE case_id = ? ORDER BY id")
                .param(c.id()).query(Evidence.class).list();
        List<Event> events = jdbc.sql("SELECT action, actor_id, note, created_at FROM case_events WHERE case_id = ? ORDER BY id")
                .param(c.id()).query(Event.class).list();
        boolean noTutorEvidence = evidence.stream().noneMatch(e -> "TUTOR".equals(e.party()));
        String suggested = !"DECIDED".equals(c.status()) && noTutorEvidence && OffsetDateTime.now(ZoneOffset.UTC).isAfter(c.evidenceDeadline())
                ? Outcome.TUTOR_FAULT.name() : null;
        List<Contact> contacts = jdbc.sql("SELECT id, actor_id, party, channel, reached, note, created_at FROM case_contacts WHERE case_id = ? ORDER BY id")
                .param(c.id()).query(Contact.class).list();
        return new Detail(c, plans.session(c.sessionId(), false), evidence, events, suggested, contacts);
    }

    private Case get(UUID id, boolean forUpdate) {
        return jdbc.sql(SELECT + " WHERE id = ?" + (forUpdate ? " FOR UPDATE" : "")).param(id).query(Case.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND", "Không tìm thấy vụ việc"));
    }
}
