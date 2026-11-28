package vn.giasu.care.tickets;

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
import vn.giasu.care.Notifier;
import vn.giasu.common.web.ApiException;

/**
 * P18 support tickets: learners and tutors write in; customer care staff (SUPPORT) answer in the same thread, resolve, or turn a
 * complaint about a session into a booking case. The AI assistant opens tickets for questions it cannot answer.
 */
@RestController
class TicketController {
    enum Category { SCHEDULE, TUTOR, PAYMENT, TECHNICAL, FEEDBACK, OTHER }
    enum AuthorRole { LEARNER, TUTOR }

    record CreateRequest(@NotNull Category category, @NotBlank @Size(max = 200) String subject, @NotBlank @Size(max = 4000) String body,
                         UUID planId, UUID sessionId) {}
    record MessageRequest(@NotBlank @Size(max = 4000) String body) {}
    record InternalTicketRequest(@NotNull UUID authorId, @NotNull AuthorRole authorRole, @NotBlank @Size(max = 200) String subject,
                                 @NotBlank @Size(max = 4000) String body) {}
    record Ticket(UUID id, UUID authorId, String authorRole, String category, String subject, UUID planId, UUID sessionId, String status,
                  String source, UUID assigneeId, UUID caseId, OffsetDateTime createdAt, OffsetDateTime firstResponseAt, OffsetDateTime resolvedAt,
                  OffsetDateTime updatedAt) {}
    record Message(long id, UUID authorId, String authorRole, String body, OffsetDateTime createdAt) {}
    record Detail(Ticket ticket, List<Message> messages) {}
    record CategoryStats(String category, long tickets, long resolved, Double avgFirstResponseHours, Double avgResolveHours) {}
    record TicketRef(UUID id) {}

    static final String SELECT = """
            SELECT id, author_id, author_role, category, subject, plan_id, session_id, status, source, assignee_id, case_id, created_at,
                   first_response_at, resolved_at, updated_at FROM tickets""";

    private final JdbcClient jdbc;
    private final Clients clients;
    private final Notifier notifier;

    TicketController(JdbcClient jdbc, Clients clients, Notifier notifier) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.notifier = notifier;
    }

    @PostMapping("/api/v1/care/tickets")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Detail create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest r) {
        UUID id = insert(caller(jwt), role(jwt), r.category().name(), r.subject().strip(), r.planId(), r.sessionId(), "WEB", r.body().strip());
        return detailOf(id);
    }

    @GetMapping("/api/v1/care/tickets/me")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    List<Ticket> mine(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.sql(SELECT + " WHERE author_id = ? ORDER BY updated_at DESC LIMIT 100").param(caller(jwt)).query(Ticket.class).list();
    }

    @GetMapping("/api/v1/care/tickets/{id}")
    Detail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Ticket t = get(id, false);
        requireReader(jwt, t);
        return detailOf(id);
    }

    /** Staff's first reply starts the response clock (P14/P18); a reply from the author reopens a resolved ticket. */
    @PostMapping("/api/v1/care/tickets/{id}/messages")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR', 'SUPPORT')")
    @Transactional
    Detail reply(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody MessageRequest r) {
        Ticket t = get(id, true);
        boolean staff = "SUPPORT".equals(role(jwt));
        if (!staff && !t.authorId().equals(caller(jwt))) throw forbidden();
        jdbc.sql("INSERT INTO ticket_messages (ticket_id, author_id, author_role, body) VALUES (?, ?, ?, ?)")
                .params(id, caller(jwt), role(jwt), r.body().strip()).update();
        if (staff) {
            jdbc.sql("""
                    UPDATE tickets SET first_response_at = coalesce(first_response_at, now()), assignee_id = coalesce(assignee_id, ?),
                        status = CASE WHEN status = 'NEW' THEN 'IN_PROGRESS' ELSE status END, updated_at = now() WHERE id = ?""")
                    .params(caller(jwt), id).update();
            notifier.send(t.authorId(), "TICKET_REPLIED", "CSKH đã trả lời phiếu hỗ trợ", t.subject(), "TICKET", id);
        } else {
            jdbc.sql("UPDATE tickets SET status = CASE WHEN status = 'RESOLVED' THEN 'IN_PROGRESS' ELSE status END, resolved_at = NULL, "
                    + "updated_at = now() WHERE id = ?").param(id).update();
        }
        return detailOf(id);
    }

    @GetMapping("/api/v1/care/support/tickets")
    @PreAuthorize("hasAnyRole('SUPPORT', 'ADMIN')")
    List<Ticket> queue(@RequestParam(required = false) String status) {
        return jdbc.sql(SELECT + " WHERE cast(:status AS varchar) IS NULL OR status = :status ORDER BY created_at LIMIT 200")
                .param("status", status).query(Ticket.class).list();
    }

    @PostMapping("/api/v1/care/support/tickets/{id}/resolve")
    @PreAuthorize("hasRole('SUPPORT')")
    @Transactional
    Detail resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Ticket t = get(id, true);
        if ("RESOLVED".equals(t.status())) throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS", "Phiếu đã giải quyết");
        jdbc.sql("UPDATE tickets SET status = 'RESOLVED', resolved_at = now(), assignee_id = coalesce(assignee_id, ?), updated_at = now() WHERE id = ?")
                .params(caller(jwt), id).update();
        notifier.send(t.authorId(), "TICKET_RESOLVED", "Phiếu hỗ trợ đã giải quyết", t.subject(), "TICKET", id);
        return detailOf(id);
    }

    /** P18: a ticket that is really a complaint about a session becomes that session's case in booking; the admin concludes it. */
    @PostMapping("/api/v1/care/support/tickets/{id}/case")
    @PreAuthorize("hasRole('SUPPORT')")
    @Transactional
    Detail toCase(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Ticket t = get(id, true);
        if (t.sessionId() == null) throw new ApiException(HttpStatus.CONFLICT, "NO_SESSION", "Phiếu chưa gắn buổi học nên không mở được vụ việc");
        if (t.caseId() != null) throw new ApiException(HttpStatus.CONFLICT, "ALREADY_CASE", "Phiếu đã được chuyển thành vụ việc");
        String firstMessage = jdbc.sql("SELECT body FROM ticket_messages WHERE ticket_id = ? ORDER BY id LIMIT 1").param(id).query(String.class)
                .optional().orElse("");
        UUID caseId = clients.caseFromTicket(t.sessionId(), "Từ phiếu hỗ trợ: " + t.subject() + ". " + firstMessage);
        jdbc.sql("UPDATE tickets SET case_id = ?, status = 'IN_PROGRESS', assignee_id = coalesce(assignee_id, ?), updated_at = now() WHERE id = ?")
                .params(caseId, caller(jwt), id).update();
        jdbc.sql("INSERT INTO ticket_messages (ticket_id, author_id, author_role, body) VALUES (?, ?, 'SUPPORT', ?)")
                .params(id, caller(jwt), "Đã chuyển thành vụ việc của buổi học; admin sẽ kết luận sau khi đối chiếu hai bên.").update();
        return detailOf(id);
    }

    /** Response times per category for the P18 metrics. */
    @GetMapping("/api/v1/care/support/tickets/stats")
    @PreAuthorize("hasAnyRole('SUPPORT', 'ADMIN')")
    List<CategoryStats> stats() {
        return jdbc.sql("""
                SELECT category, count(*) AS tickets, count(resolved_at) AS resolved,
                       (avg(extract(epoch FROM first_response_at - created_at)) / 3600)::float8 AS avg_first_response_hours,
                       (avg(extract(epoch FROM resolved_at - created_at)) / 3600)::float8 AS avg_resolve_hours
                FROM tickets GROUP BY category ORDER BY category""").query(CategoryStats.class).list();
    }

    /** From the AI assistant: a question it could not answer, with the conversation, becomes a ticket for staff (P17). */
    @PostMapping("/api/v1/care/internal/tickets")
    @Transactional
    TicketRef fromAssistant(@Valid @RequestBody InternalTicketRequest r) {
        return new TicketRef(insert(r.authorId(), r.authorRole().name(), "OTHER", r.subject().strip(), null, null, "CHATBOT", r.body().strip()));
    }

    private UUID insert(UUID author, String role, String category, String subject, UUID planId, UUID sessionId, String source, String body) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO tickets (id, author_id, author_role, category, subject, plan_id, session_id, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, author, role, category, subject, planId, sessionId, source).update();
        jdbc.sql("INSERT INTO ticket_messages (ticket_id, author_id, author_role, body) VALUES (?, ?, ?, ?)").params(id, author, role, body).update();
        return id;
    }

    private Detail detailOf(UUID id) {
        return new Detail(get(id, false), jdbc.sql("SELECT id, author_id, author_role, body, created_at FROM ticket_messages WHERE ticket_id = ? ORDER BY id")
                .param(id).query(Message.class).list());
    }

    private Ticket get(UUID id, boolean forUpdate) {
        return jdbc.sql(SELECT + " WHERE id = ?" + (forUpdate ? " FOR UPDATE" : "")).param(id).query(Ticket.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "Không tìm thấy phiếu"));
    }

    private static void requireReader(Jwt jwt, Ticket t) {
        String role = role(jwt);
        if (!"SUPPORT".equals(role) && !"ADMIN".equals(role) && !t.authorId().equals(caller(jwt))) throw forbidden();
    }

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Không có quyền với phiếu này");
    }

    private static String role(Jwt jwt) { return jwt.getClaimAsString("role"); }

    private static UUID caller(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
