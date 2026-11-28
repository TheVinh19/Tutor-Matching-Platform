package vn.giasu.booking.cases;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.giasu.booking.Notifier;
import vn.giasu.booking.plans.Plans.Session;

/** Opens cases from session code and applies learner sanctions (P07/P14). */
@Repository
public class Cases {
    /** P14: the tutor has 3 days (72h) from notification to submit evidence. */
    public static final Duration EVIDENCE_WINDOW = Duration.ofHours(72);
    static final Duration STRIKE_WINDOW = Duration.ofDays(90);
    static final Duration RESTRICTION = Duration.ofDays(30);

    private final JdbcClient jdbc;
    private final Notifier notifier;

    Cases(JdbcClient jdbc, Notifier notifier) {
        this.jdbc = jdbc;
        this.notifier = notifier;
    }

    /** One case per session; a later report on the same session joins the existing case instead of opening another. */
    public void open(Session s, String kind, String reason) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO cases (id, session_id, plan_id, learner_id, tutor_id, kind, status, reason, evidence_deadline)
                VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?) ON CONFLICT (session_id) DO NOTHING""")
                .params(id, s.id(), s.planId(), s.learnerId(), s.tutorId(), kind, reason,
                        OffsetDateTime.now(ZoneOffset.UTC).plus(EVIDENCE_WINDOW))
                .update();
        if (inserted == 0) return;
        jdbc.sql("INSERT INTO case_events (case_id, action, actor_id, note) VALUES (?, 'OPENED', ?, ?)").params(id, s.learnerId(), reason).update();
        notifier.send(s.tutorId(), "CASE_OPENED", "Có khiếu nại về buổi học", "Bạn có 72 giờ để nộp bằng chứng giải trình.", "CASE", id);
    }

    /**
     * Records a verified strike; the 3rd active one within 90 days blocks new plans for 30 days. Returns the restriction end, if any.
     */
    public OffsetDateTime strike(UUID learnerId, String kind, UUID caseId, UUID sessionId, String reason, UUID actorId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long active = jdbc.sql("SELECT count(*) FROM learner_strikes WHERE learner_id = ? AND revoked_at IS NULL AND created_at >= ?")
                .params(learnerId, now.minus(STRIKE_WINDOW)).query(Long.class).single();
        OffsetDateTime until = active + 1 >= 3 ? now.plus(RESTRICTION) : null;
        jdbc.sql("""
                INSERT INTO learner_strikes (learner_id, kind, case_id, session_id, reason, actor_id, restricted_until)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(learnerId, kind, caseId, sessionId, reason, actorId, until).update();
        notifier.send(learnerId, "LEARNER_STRIKE", until == null ? "Cảnh báo vi phạm" : "Tạm khóa đặt đơn mới 30 ngày",
                until == null ? "Lần " + (active + 1) + "/3 trong 90 ngày: " + reason : "Vi phạm lần thứ 3 trong 90 ngày. Các kỳ đã trả vẫn học bình thường.",
                "LEARNER", learnerId);
        return until;
    }

    /** Appeal changed the outcome: the strike of that case no longer counts and its block (if any) is lifted. */
    public void revokeStrikesOf(UUID caseId) {
        jdbc.sql("UPDATE learner_strikes SET revoked_at = now() WHERE case_id = ? AND revoked_at IS NULL").param(caseId).update();
    }

    public OffsetDateTime restrictedUntil(UUID learnerId) {
        return jdbc.sql("""
                SELECT max(restricted_until) FROM learner_strikes WHERE learner_id = ? AND revoked_at IS NULL AND restricted_until > now()""")
                .param(learnerId).query(OffsetDateTime.class).optional().orElse(null);
    }
}
