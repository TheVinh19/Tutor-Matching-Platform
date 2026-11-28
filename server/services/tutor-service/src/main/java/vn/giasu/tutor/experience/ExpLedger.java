package vn.giasu.tutor.experience;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.giasu.tutor.BookingClient;
import vn.giasu.tutor.BookingClient.ExpEvent;
import vn.giasu.tutor.BookingClient.ExpSources;

/** P09/P13 EXP ledger: append-only rows, one per source key, EXP = max(0, sum). */
@Repository
public class ExpLedger {
    public record Entry(UUID id, String sourceKey, String type, int points, String refType, UUID refId, String reason,
                        OffsetDateTime occurredAt, OffsetDateTime createdAt) {}
    public record Stats(int taughtSessions, int tutorFaultFailed, int distinctLearners, int renewals, int openIssues, OffsetDateTime syncedAt) {
        static final Stats EMPTY = new Stats(0, 0, 0, 0, 0, null);
    }

    private final JdbcClient jdbc;
    private final BookingClient booking;

    ExpLedger(JdbcClient jdbc, BookingClient booking) {
        this.jdbc = jdbc;
        this.booking = booking;
    }

    public int total(UUID tutorId) {
        return jdbc.sql("SELECT greatest(0, coalesce(sum(points), 0)) FROM exp_ledger WHERE tutor_id = ?").param(tutorId).query(Integer.class).single();
    }

    public List<Entry> entries(UUID tutorId, int limit) {
        return jdbc.sql("""
                SELECT id, source_key, type, points, ref_type, ref_id, reason, occurred_at, created_at FROM exp_ledger
                WHERE tutor_id = ? ORDER BY occurred_at DESC, created_at DESC LIMIT ?""")
                .params(tutorId, limit).query(Entry.class).list();
    }

    /** Inserts once per (tutor, source key); returns false when that key is already in the ledger. */
    public boolean add(UUID tutorId, String sourceKey, String type, int points, String refType, UUID refId, String reason, UUID actorId,
                       Instant occurredAt) {
        return jdbc.sql("""
                INSERT INTO exp_ledger (id, tutor_id, source_key, type, points, ref_type, ref_id, reason, actor_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tutor_id, source_key) DO NOTHING""")
                .params(UUID.randomUUID(), tutorId, sourceKey, type, points, refType, refId, reason, actorId,
                        (occurredAt != null ? occurredAt : Instant.now()).atOffset(ZoneOffset.UTC))
                .update() > 0;
    }

    /**
     * Pulls qualifying events and rank inputs from booking; safe to repeat. Returns how many new rows were written.
     * Not @Transactional on purpose: a booking failure must not mark a caller's transaction rollback-only; each insert is idempotent.
     */
    public int sync(UUID tutorId) {
        ExpSources sources = booking.expSources(tutorId);
        int added = 0;
        for (ExpEvent e : sources.events()) {
            if (add(tutorId, e.key(), e.type(), e.points(), e.refType(), e.refId(), null, null, e.occurredAt())) added++;
        }
        BookingClient.ExpStats s = sources.stats();
        jdbc.sql("""
                INSERT INTO tutor_stats (account_id, taught_sessions, tutor_fault_failed, distinct_learners, renewals, open_issues, synced_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (account_id) DO UPDATE SET taught_sessions = excluded.taught_sessions, tutor_fault_failed = excluded.tutor_fault_failed,
                    distinct_learners = excluded.distinct_learners, renewals = excluded.renewals, open_issues = excluded.open_issues, synced_at = now()""")
                .params(tutorId, s.taughtSessions(), s.tutorFaultFailed(), s.distinctLearners(), s.renewals(), s.openIssues()).update();
        return added;
    }

    public Stats stats(UUID tutorId) {
        return jdbc.sql("""
                SELECT taught_sessions, tutor_fault_failed, distinct_learners, renewals, open_issues, synced_at FROM tutor_stats WHERE account_id = ?""")
                .param(tutorId).query(Stats.class).optional().orElse(Stats.EMPTY);
    }

    /** Verified discipline in the last 90 days (P10, rank 3). */
    public boolean penalizedSince(UUID tutorId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM exp_ledger WHERE tutor_id = ? AND type = 'PENALTY' AND created_at >= ?")
                .params(tutorId, since.atOffset(ZoneOffset.UTC)).query(Long.class).single() > 0;
    }

    public Optional<Integer> pointsOf(UUID tutorId, String sourceKey) {
        return jdbc.sql("SELECT points FROM exp_ledger WHERE tutor_id = ? AND source_key = ?").params(tutorId, sourceKey).query(Integer.class).optional();
    }
}
