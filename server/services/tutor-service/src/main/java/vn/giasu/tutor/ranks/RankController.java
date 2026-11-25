package vn.giasu.tutor.ranks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.common.web.ApiException;
import vn.giasu.tutor.experience.ExpLedger;
import vn.giasu.tutor.experience.ExpSyncJob;
import vn.giasu.tutor.ranks.RankRules.Flag;

/**
 * EXP view for the tutor and rank review for admins (P09/P10). The system flags; the admin decides within 2 working days,
 * with a reason, and the decision takes effect when saved. Penalties are entered by the admin after verification.
 */
@RestController
class RankController {
    enum RefType { SESSION, PLAN }

    record RankView(UUID accountId, String fullName, int rank, boolean entryRank2, OffsetDateTime rankSince, int exp,
                    ExpLedger.Stats stats, Double completionRate, Flag flag) {}
    record Decision(int fromRank, int toRank, String reason, UUID actorId, OffsetDateTime createdAt) {}
    record RankDetail(RankView view, List<ExpLedger.Entry> entries, List<Decision> decisions) {}
    record DecideRequest(@NotNull @Min(1) @Max(3) Integer rank, @NotBlank @Size(max = 1000) String reason) {}
    record PenaltyRequest(@NotNull Integer points, @NotBlank @Size(max = 1000) String reason, @NotNull RefType refType, @NotNull UUID refId) {}
    record SyncResult(int synced) {}
    record Row(UUID accountId, String fullName, int rank, boolean entryRank2, OffsetDateTime rankSince, String status) {}

    /** P09: late/short −30, walking away from a session −100, breaking the period commitment −200. */
    static final Set<Integer> PENALTIES = Set.of(30, 100, 200);
    private static final String ROW = "SELECT account_id, full_name, rank, entry_rank2, rank_since, status FROM tutor_profiles";

    private final JdbcClient jdbc;
    private final ExpLedger ledger;
    private final ExpSyncJob syncJob;

    RankController(JdbcClient jdbc, ExpLedger ledger, ExpSyncJob syncJob) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.syncJob = syncJob;
    }

    @GetMapping("/api/v1/tutors/me/exp")
    @PreAuthorize("hasRole('TUTOR')")
    RankDetail mine(@AuthenticationPrincipal Jwt jwt) {
        return detailSynced(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/api/v1/tutors/admin/ranks")
    @PreAuthorize("hasRole('ADMIN')")
    List<RankView> list(@RequestParam(required = false) Flag flag) {
        return jdbc.sql(ROW + " WHERE status = 'APPROVED' ORDER BY full_name").query(Row.class).list().stream()
                .map(this::view).filter(v -> flag == null || v.flag() == flag).toList();
    }

    @GetMapping("/api/v1/tutors/admin/ranks/{accountId}")
    @PreAuthorize("hasRole('ADMIN')")
    RankDetail detail(@PathVariable UUID accountId) {
        return detailSynced(accountId);
    }

    @PostMapping("/api/v1/tutors/admin/ranks/sync")
    @PreAuthorize("hasRole('ADMIN')")
    SyncResult sync() {
        return new SyncResult(syncJob.syncAll());
    }

    @PostMapping("/api/v1/tutors/admin/ranks/{accountId}/decide")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    RankDetail decide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId, @Valid @RequestBody DecideRequest r) {
        Row row = approvedRowForUpdate(accountId);
        if (r.rank() == row.rank()) throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_RANK", "Gia sư đang ở Hạng này");
        if (r.rank() > row.rank()) {
            trySync(accountId);
            if (r.rank() != row.rank() + 1 || view(row).flag() != Flag.ELIGIBLE_UP) {
                throw new ApiException(HttpStatus.CONFLICT, "NOT_ELIGIBLE", "Gia sư chưa đủ điều kiện lên Hạng " + r.rank());
            }
        }
        jdbc.sql("UPDATE tutor_profiles SET rank = ?, rank_since = now(), entry_rank2 = entry_rank2 AND ? = 2, updated_at = now() WHERE account_id = ?")
                .params(r.rank(), r.rank(), accountId).update();
        jdbc.sql("INSERT INTO rank_decisions (account_id, from_rank, to_rank, reason, actor_id) VALUES (?, ?, ?, ?, ?)")
                .params(accountId, row.rank(), r.rank(), r.reason().strip(), UUID.fromString(jwt.getSubject())).update();
        return detailOf(accountId);
    }

    @PostMapping("/api/v1/tutors/admin/ranks/{accountId}/penalties")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    RankDetail penalize(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId, @Valid @RequestBody PenaltyRequest r) {
        approvedRowForUpdate(accountId);
        if (!PENALTIES.contains(r.points())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PENALTY", "Mức phạt là 30, 100 hoặc 200 EXP");
        }
        // One penalty per session/plan: P09 applies only the highest one for a session, and −200 once per plan.
        String key = "penalty:" + r.refType() + ":" + r.refId();
        if (!ledger.add(accountId, key, "PENALTY", -r.points(), r.refType().name(), r.refId(), r.reason().strip(), UUID.fromString(jwt.getSubject()), null)) {
            throw new ApiException(HttpStatus.CONFLICT, "PENALTY_EXISTS", "Buổi/kỳ này đã bị phạt");
        }
        return detailOf(accountId);
    }

    private RankDetail detailSynced(UUID accountId) {
        Row row = row(accountId);
        if ("APPROVED".equals(row.status())) trySync(accountId);
        return detailOf(accountId);
    }

    private RankDetail detailOf(UUID accountId) {
        List<Decision> decisions = jdbc.sql("SELECT from_rank, to_rank, reason, actor_id, created_at FROM rank_decisions WHERE account_id = ? ORDER BY id DESC")
                .param(accountId).query(Decision.class).list();
        return new RankDetail(view(row(accountId)), ledger.entries(accountId, 100), decisions);
    }

    private RankView view(Row row) {
        UUID id = row.accountId();
        ExpLedger.Stats stats = ledger.stats(id);
        int exp = ledger.total(id);
        long days = row.rankSince() == null ? 0 : Duration.between(row.rankSince().toInstant(), Instant.now()).toDays();
        Flag flag = "APPROVED".equals(row.status()) ? RankRules.flag(new RankRules.Input(row.rank(), exp, stats.distinctLearners(), stats.renewals(),
                stats.taughtSessions(), stats.tutorFaultFailed(), stats.openIssues(), row.entryRank2(), days,
                ledger.penalizedSince(id, Instant.now().minus(Duration.ofDays(90))))) : Flag.OK;
        return new RankView(id, row.fullName(), row.rank(), row.entryRank2(), row.rankSince(), exp, stats,
                RankRules.completionRate(stats.taughtSessions(), stats.tutorFaultFailed()), flag);
    }

    /** Booking down must not block reading or deciding a demotion; the last synced numbers are used. */
    private void trySync(UUID accountId) {
        try {
            ledger.sync(accountId);
        } catch (ApiException ignored) {
            // keep the cached stats
        }
    }

    private Row row(UUID accountId) {
        return jdbc.sql(ROW + " WHERE account_id = ?").param(accountId).query(Row.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Không tìm thấy hồ sơ"));
    }

    private Row approvedRowForUpdate(UUID accountId) {
        Row row = jdbc.sql(ROW + " WHERE account_id = ? FOR UPDATE").param(accountId).query(Row.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Không tìm thấy hồ sơ"));
        if (!"APPROVED".equals(row.status())) throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS", "Hồ sơ chưa được duyệt");
        return row;
    }
}
