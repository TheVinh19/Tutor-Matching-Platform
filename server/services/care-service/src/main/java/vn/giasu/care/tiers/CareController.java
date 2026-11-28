package vn.giasu.care.tiers;

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
import vn.giasu.care.Clients;
import vn.giasu.common.web.ApiException;

/** P18 care desk for staff (SUPPORT): tier lists, call tasks, contact log and the audited phone lookup. Admin can read. */
@RestController
class CareController {
    enum Channel { PHONE, MESSAGE, EMAIL, OTHER }

    record ContactRequest(@NotNull Channel channel, boolean reached, @NotBlank @Size(max = 2000) String note) {}
    record DoneRequest(@NotBlank @Size(max = 2000) String note) {}
    record TierRow(UUID learnerId, int tier, String reasons, OffsetDateTime computedAt) {}
    record HistoryRow(int tier, String reasons, OffsetDateTime createdAt) {}
    record Task(UUID id, UUID learnerId, int tier, String reason, String status, OffsetDateTime createdAt, OffsetDateTime doneAt, UUID doneBy,
                String doneNote) {}
    record Contact(long id, UUID actorId, String channel, boolean reached, String note, OffsetDateTime createdAt) {}
    record Learner(UUID learnerId, TierRow current, List<HistoryRow> history, List<Task> tasks, List<Contact> contacts) {}
    record Phone(UUID learnerId, String phone) {}
    record TierCount(int tier, long learners) {}
    record Stats(List<TierCount> tiers, long openTasks, long contacts30d, long reached30d) {}

    private static final String TASKS = "SELECT id, learner_id, tier, reason, status, created_at, done_at, done_by, done_note FROM care_tasks";

    private final JdbcClient jdbc;
    private final CareJob job;
    private final Clients clients;

    CareController(JdbcClient jdbc, CareJob job, Clients clients) {
        this.jdbc = jdbc;
        this.job = job;
        this.clients = clients;
    }

    @GetMapping("/api/v1/care/support/care/tiers")
    @PreAuthorize("hasAnyRole('SUPPORT', 'ADMIN')")
    List<TierRow> tiers(@RequestParam(required = false) Integer tier) {
        return jdbc.sql("SELECT learner_id, tier, reasons, computed_at FROM care_tiers WHERE cast(:tier AS int) IS NULL OR tier = :tier "
                + "ORDER BY tier DESC, learner_id").param("tier", tier).query(TierRow.class).list();
    }

    @GetMapping("/api/v1/care/support/care/learners/{learnerId}")
    @PreAuthorize("hasAnyRole('SUPPORT', 'ADMIN')")
    Learner learner(@PathVariable UUID learnerId) {
        return new Learner(learnerId,
                jdbc.sql("SELECT learner_id, tier, reasons, computed_at FROM care_tiers WHERE learner_id = ?").param(learnerId).query(TierRow.class)
                        .optional().orElse(null),
                jdbc.sql("SELECT tier, reasons, created_at FROM care_tier_history WHERE learner_id = ? ORDER BY id DESC LIMIT 50").param(learnerId)
                        .query(HistoryRow.class).list(),
                jdbc.sql(TASKS + " WHERE learner_id = ? ORDER BY created_at DESC").param(learnerId).query(Task.class).list(),
                jdbc.sql("SELECT id, actor_id, channel, reached, note, created_at FROM care_contacts WHERE learner_id = ? ORDER BY id DESC")
                        .param(learnerId).query(Contact.class).list());
    }

    @GetMapping("/api/v1/care/support/care/tasks")
    @PreAuthorize("hasAnyRole('SUPPORT', 'ADMIN')")
    List<Task> tasks(@RequestParam(defaultValue = "OPEN") String status) {
        return jdbc.sql(TASKS + " WHERE status = ? ORDER BY tier DESC, created_at").param(status).query(Task.class).list();
    }

    @PostMapping("/api/v1/care/support/care/tasks/{id}/done")
    @PreAuthorize("hasRole('SUPPORT')")
    Task done(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody DoneRequest r) {
        int n = jdbc.sql("UPDATE care_tasks SET status = 'DONE', done_at = now(), done_by = ?, done_note = ? WHERE id = ? AND status = 'OPEN'")
                .params(caller(jwt), r.note().strip(), id).update();
        Task task = jdbc.sql(TASKS + " WHERE id = ?").param(id).query(Task.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Không tìm thấy việc cần làm"));
        if (n == 0) throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS", "Việc này đã xong");
        return task;
    }

    @PostMapping("/api/v1/care/support/care/learners/{learnerId}/contacts")
    @PreAuthorize("hasRole('SUPPORT')")
    @ResponseStatus(HttpStatus.CREATED)
    Contact contact(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID learnerId, @Valid @RequestBody ContactRequest r) {
        long id = jdbc.sql("INSERT INTO care_contacts (learner_id, actor_id, channel, reached, note) VALUES (?, ?, ?, ?, ?) RETURNING id")
                .params(learnerId, caller(jwt), r.channel().name(), r.reached(), r.note().strip()).query(Long.class).single();
        return jdbc.sql("SELECT id, actor_id, channel, reached, note, created_at FROM care_contacts WHERE id = ?").param(id).query(Contact.class).single();
    }

    /** Phone numbers only for learners staff should reach (tier 2+ or an open task); every read is logged. */
    @GetMapping("/api/v1/care/support/care/learners/{learnerId}/phone")
    @PreAuthorize("hasRole('SUPPORT')")
    @Transactional
    Phone phone(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID learnerId) {
        boolean allowed = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM care_tiers WHERE learner_id = :id AND tier >= 2)
                    OR EXISTS (SELECT 1 FROM care_tasks WHERE learner_id = :id AND status = 'OPEN')""").param("id", learnerId).query(Boolean.class).single();
        if (!allowed) throw new ApiException(HttpStatus.FORBIDDEN, "NOT_IN_CARE", "Học viên này chưa cần liên hệ");
        jdbc.sql("INSERT INTO contact_views (actor_id, account_id) VALUES (?, ?)").params(caller(jwt), learnerId).update();
        return new Phone(learnerId, clients.phone(learnerId));
    }

    @PostMapping("/api/v1/care/support/care/recompute")
    @PreAuthorize("hasRole('SUPPORT')")
    CareJob.Run recompute() {
        return job.recompute();
    }

    @GetMapping("/api/v1/care/support/care/stats")
    @PreAuthorize("hasAnyRole('SUPPORT', 'ADMIN')")
    Stats stats() {
        return new Stats(jdbc.sql("SELECT tier, count(*) AS learners FROM care_tiers GROUP BY tier ORDER BY tier").query(TierCount.class).list(),
                jdbc.sql("SELECT count(*) FROM care_tasks WHERE status = 'OPEN'").query(Long.class).single(),
                jdbc.sql("SELECT count(*) FROM care_contacts WHERE created_at > now() - interval '30 days'").query(Long.class).single(),
                jdbc.sql("SELECT count(*) FROM care_contacts WHERE reached AND created_at > now() - interval '30 days'").query(Long.class).single());
    }

    private static UUID caller(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
