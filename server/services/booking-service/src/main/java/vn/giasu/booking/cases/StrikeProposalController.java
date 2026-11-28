package vn.giasu.booking.cases;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
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
import vn.giasu.booking.plans.Plans;
import vn.giasu.common.web.ApiException;

/**
 * P18 option B for learner sanctions: staff propose a strike (self-cancel / no-show) with their contact history;
 * it counts only when an admin approves it, through the same rule as a direct admin strike (P14).
 */
@RestController
class StrikeProposalController {
    enum Kind { SELF_CANCEL, NO_SHOW }
    record ProposalRequest(@NotNull UUID learnerId, @NotNull Kind kind, UUID sessionId, @NotBlank @Size(max = 2000) String reason) {}
    record NoteRequest(@Size(max = 1000) String note) {}
    record Proposal(long id, UUID learnerId, String kind, UUID sessionId, String reason, UUID proposedBy, String status, UUID decidedBy,
                    String decisionNote, OffsetDateTime createdAt, OffsetDateTime decidedAt) {}

    private static final String SELECT = """
            SELECT id, learner_id, kind, session_id, reason, proposed_by, status, decided_by, decision_note, created_at, decided_at
            FROM strike_proposals""";

    private final JdbcClient jdbc;
    private final Cases cases;

    StrikeProposalController(JdbcClient jdbc, Cases cases) {
        this.jdbc = jdbc;
        this.cases = cases;
    }

    @PostMapping("/api/v1/bookings/support/strike-proposals")
    @PreAuthorize("hasRole('SUPPORT')")
    @ResponseStatus(HttpStatus.CREATED)
    Proposal propose(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProposalRequest r) {
        long id = jdbc.sql("INSERT INTO strike_proposals (learner_id, kind, session_id, reason, proposed_by) VALUES (?, ?, ?, ?, ?) RETURNING id")
                .params(r.learnerId(), r.kind().name(), r.sessionId(), r.reason().strip(), Plans.caller(jwt)).query(Long.class).single();
        return get(id, false);
    }

    @GetMapping("/api/v1/bookings/admin/strike-proposals")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT')")
    List<Proposal> list(@RequestParam(defaultValue = "PENDING") String status) {
        return jdbc.sql(SELECT + " WHERE status = ? ORDER BY created_at DESC LIMIT 100").param(status).query(Proposal.class).list();
    }

    @PostMapping("/api/v1/bookings/admin/strike-proposals/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Proposal approve(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody(required = false) NoteRequest r) {
        Proposal p = pending(id);
        UUID admin = Plans.caller(jwt);
        cases.strike(p.learnerId(), p.kind(), null, p.sessionId(), p.reason(), admin);
        decide(id, "APPROVED", admin, r == null ? null : r.note());
        return get(id, false);
    }

    @PostMapping("/api/v1/bookings/admin/strike-proposals/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Proposal reject(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody NoteRequest r) {
        pending(id);
        if (r.note() == null || r.note().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Cần lý do từ chối");
        decide(id, "REJECTED", Plans.caller(jwt), r.note());
        return get(id, false);
    }

    private Proposal pending(long id) {
        Proposal p = get(id, true);
        Plans.requireStatus("PENDING".equals(p.status()), "Đề xuất đã được xử lý");
        return p;
    }

    private void decide(long id, String status, UUID admin, String note) {
        jdbc.sql("UPDATE strike_proposals SET status = ?, decided_by = ?, decision_note = ?, decided_at = now() WHERE id = ?")
                .params(status, admin, note == null || note.isBlank() ? null : note.strip(), id).update();
    }

    private Proposal get(long id, boolean forUpdate) {
        return jdbc.sql(SELECT + " WHERE id = ?" + (forUpdate ? " FOR UPDATE" : "")).param(id).query(Proposal.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROPOSAL_NOT_FOUND", "Không tìm thấy đề xuất"));
    }
}
