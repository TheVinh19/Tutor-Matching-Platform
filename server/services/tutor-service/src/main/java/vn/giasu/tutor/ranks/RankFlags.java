package vn.giasu.tutor.ranks;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.giasu.tutor.experience.ExpLedger;
import vn.giasu.tutor.ranks.RankRules.Flag;

/** P10 flag from the ledger and the last synced booking numbers; shared by rank review and the internal profile for booking. */
@Component
public class RankFlags {
    private final JdbcClient jdbc;
    private final ExpLedger ledger;

    RankFlags(JdbcClient jdbc, ExpLedger ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    public Flag flag(UUID id, int rank, boolean entryRank2, OffsetDateTime rankSince) {
        ExpLedger.Stats s = ledger.stats(id);
        long days = rankSince == null ? 0 : Duration.between(rankSince.toInstant(), Instant.now()).toDays();
        return RankRules.flag(new RankRules.Input(rank, ledger.total(id), s.distinctLearners(), s.renewals(), s.taughtSessions(),
                s.tutorFaultFailed(), s.openIssues(), entryRank2, days, ledger.penalizedSince(id, Instant.now().minus(Duration.ofDays(90)))));
    }

    /** P09: lost a rank condition -> no new plans until the admin decides. */
    public boolean underReview(UUID id) {
        record Row(int rank, boolean entryRank2, OffsetDateTime rankSince) {}
        return jdbc.sql("SELECT rank, entry_rank2, rank_since FROM tutor_profiles WHERE account_id = ? AND status = 'APPROVED'").param(id)
                .query(Row.class).optional().map(r -> flag(id, r.rank(), r.entryRank2(), r.rankSince()) == Flag.MUST_REVIEW).orElse(false);
    }
}
