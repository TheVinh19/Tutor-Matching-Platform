package vn.giasu.care.tiers;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.giasu.care.Clients;
import vn.giasu.care.Clients.CareSignal;
import vn.giasu.care.Clients.ScoreSignal;
import vn.giasu.care.Notifier;

/** Daily P18 tiering: pulls signals from booking and assessment, stores tiers, opens staff tasks for T3/T4, reminds T2 learners. */
@Component
class CareJob {
    private static final Logger log = LoggerFactory.getLogger(CareJob.class);

    record Run(int learners, Map<Integer, Long> byTier, int tasksCreated) {}

    private final JdbcClient jdbc;
    private final Clients clients;
    private final Notifier notifier;
    private final TransactionTemplate tx;

    CareJob(JdbcClient jdbc, Clients clients, Notifier notifier, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.notifier = notifier;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(cron = "${care.tier.cron:0 0 6 * * *}", zone = "Asia/Ho_Chi_Minh")
    void daily() {
        try {
            Run run = recompute();
            log.info("Care tiers recomputed: {} learners, {} by tier, {} new tasks", run.learners(), run.byTier(), run.tasksCreated());
        } catch (RuntimeException e) {
            log.warn("Care tiers not recomputed: {}", e.getMessage());
        }
    }

    /** One transaction, also when called from the scheduler (a self-call would skip @Transactional). */
    Run recompute() {
        return tx.execute(status -> compute());
    }

    private Run compute() {
        OffsetDateTime started = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        Map<UUID, CareSignal> care = clients.careSignals().stream().collect(Collectors.toMap(CareSignal::learnerId, Function.identity(), (a, b) -> a));
        Map<UUID, ScoreSignal> scores = clients.scoreSignals().stream()
                .collect(Collectors.toMap(ScoreSignal::learnerId, Function.identity(), (a, b) -> a));
        Set<UUID> overdue = Set.copyOf(jdbc.sql("""
                SELECT DISTINCT author_id FROM tickets WHERE author_role = 'LEARNER' AND status <> 'RESOLVED'
                AND created_at < now() - interval '48 hours'""").query(UUID.class).list());
        Map<UUID, Integer> previousTier = new HashMap<>();
        jdbc.sql("SELECT learner_id, tier FROM care_tiers").query((rs, n) -> previousTier.put(rs.getObject(1, UUID.class), rs.getInt(2))).list();

        Set<UUID> learners = new LinkedHashSet<>(care.keySet());
        learners.addAll(overdue);
        Map<Integer, Long> byTier = new TreeMap<>();
        int tasks = 0;
        for (UUID learner : learners) {
            CareSignal c = care.get(learner);
            ScoreSignal sc = scores.get(learner);
            CareRules.Tier t = CareRules.tier(new CareRules.Signals(
                    c == null ? 0 : c.cancels30d(), c == null ? 0 : c.absences30d(), c == null ? 0 : c.disputes30d(), c == null ? 0 : c.openCases(),
                    c != null && c.restricted(), c != null && c.endingWithoutRenewal(), c != null && c.lastTwoAbsent(),
                    sc == null ? null : sc.placementPercent(), sc == null ? null : sc.previousPercent(), sc == null ? null : sc.latestPercent(),
                    overdue.contains(learner)));
            String reasons = String.join("; ", t.reasons());
            byTier.merge(t.tier(), 1L, Long::sum);
            Integer before = previousTier.get(learner);
            jdbc.sql("""
                    INSERT INTO care_tiers (learner_id, tier, reasons, computed_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT (learner_id) DO UPDATE SET tier = excluded.tier, reasons = excluded.reasons, computed_at = excluded.computed_at""")
                    .params(learner, t.tier(), reasons, started).update();
            if (before == null || before != t.tier()) {
                jdbc.sql("INSERT INTO care_tier_history (learner_id, tier, reasons) VALUES (?, ?, ?)").params(learner, t.tier(), reasons).update();
            }
            if (t.tier() >= 3) {
                tasks += jdbc.sql("""
                        INSERT INTO care_tasks (id, learner_id, tier, reason) VALUES (?, ?, ?, ?)
                        ON CONFLICT (learner_id) WHERE status = 'OPEN' DO NOTHING""").params(UUID.randomUUID(), learner, t.tier(), reasons).update();
            }
            if (t.tier() == 2 && (before == null || before != 2)) {
                notifier.send(learner, "CARE_REMINDER", "Nhắc nhở học tập", reasons, "CARE", null);
            }
        }
        // Learners no longer active (no plan, no overdue ticket) leave the tier lists; their history stays.
        jdbc.sql("DELETE FROM care_tiers WHERE computed_at < ?").param(started).update();
        return new Run(learners.size(), byTier, tasks);
    }
}
