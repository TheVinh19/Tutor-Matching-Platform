package vn.giasu.booking.holds;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.giasu.booking.plans.Plans;

/** Time-based transitions: expired holds free their slots; sessions not confirmed or disputed in 24h are accepted (P04). */
@Component
public class HoldJobs {
    private final JdbcClient jdbc;
    private final Plans plans;

    HoldJobs(JdbcClient jdbc, Plans plans) {
        this.jdbc = jdbc;
        this.plans = plans;
    }

    @Scheduled(fixedDelayString = "${booking.jobs.delay:60000}", initialDelayString = "${booking.jobs.delay:60000}")
    void tick() {
        releaseExpired();
        autoConfirm();
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

    /** Disputed sessions or sessions with a cancel report are never auto-accepted. */
    @Transactional
    public int autoConfirm() {
        List<UUID> planIds = jdbc.sql("""
                UPDATE sessions SET status = 'CONFIRMED', confirmed_at = now(), auto_confirmed = true, updated_at = now()
                WHERE status = 'COMPLETED_PENDING_CONFIRM' AND confirm_deadline <= now() RETURNING plan_id""")
                .query(UUID.class).list();
        planIds.stream().distinct().forEach(plans::completeIfDone);
        return planIds.size();
    }
}
