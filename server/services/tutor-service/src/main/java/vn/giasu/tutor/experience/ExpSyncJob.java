package vn.giasu.tutor.experience;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.giasu.common.web.ApiException;

/** Keeps the ledger and rank inputs current for every approved tutor; rank itself is never changed here (P09). */
@Component
public class ExpSyncJob {
    private static final Logger log = LoggerFactory.getLogger(ExpSyncJob.class);

    private final JdbcClient jdbc;
    private final ExpLedger ledger;

    ExpSyncJob(JdbcClient jdbc, ExpLedger ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    // shortcut: one booking call per approved tutor; switch to a "changed since" feed when tutors number in the thousands.
    @Scheduled(fixedDelayString = "${tutor.exp.sync-delay:600000}", initialDelayString = "${tutor.exp.sync-delay:600000}")
    public int syncAll() {
        List<UUID> tutors = jdbc.sql("SELECT account_id FROM tutor_profiles WHERE status = 'APPROVED'").query(UUID.class).list();
        int synced = 0;
        for (UUID tutor : tutors) {
            try {
                ledger.sync(tutor);
                synced++;
            } catch (ApiException e) {
                log.warn("EXP sync stopped: {}", e.getMessage()); // booking down: next run catches up
                break;
            }
        }
        return synced;
    }
}
