package vn.giasu.ai.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.ai.Clients;
import vn.giasu.ai.assistant.Retriever.Hit;
import vn.giasu.common.web.ApiException;

/**
 * P17 Q&A assistant, level 2: answers about rules and how to use the platform with its source, says so when it does not know and
 * offers a support ticket. It never looks up the caller's own data and never acts for them.
 */
@RestController
class ChatController {
    static final int DAILY_LIMIT = 30;
    static final String NO_ANSWER = "Mình chưa có câu trả lời chắc chắn cho câu này nên không đoán. Bạn có thể bấm \"Chuyển CSKH\" để nhân viên hỗ trợ "
            + "trả lời trong phiếu. Trợ lý không tra cứu lịch, thanh toán hay dữ liệu riêng của bạn.";
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern PHONE = Pattern.compile("(\\+84|0)[\\d .-]{8,12}\\d");

    record ChatRequest(@NotBlank @Size(max = 1000) String question, UUID conversationId) {}
    record FeedbackRequest(@NotNull Boolean helpful) {}
    record Source(String type, String ref, String title) {}
    record Reply(UUID turnId, UUID conversationId, String answer, boolean matched, List<Source> sources, List<String> related, int remainingToday) {}
    record Turn(UUID id, UUID conversationId, UUID accountId, String accountRole, String question, String answer, boolean matched, String sourceType,
                String sourceRef, double score, Boolean helpful, UUID ticketId, OffsetDateTime createdAt) {}
    record TicketRef(UUID ticketId) {}
    record Stats(long turns, long matched, long helpful, long unhelpful, long escalated, long askers, Double matchedPercent) {}

    private static final String SELECT = """
            SELECT id, conversation_id, account_id, account_role, question, answer, matched, source_type, source_ref, score, helpful, ticket_id,
                   created_at FROM chat_turns""";

    private final JdbcClient jdbc;
    private final Knowledge knowledge;
    private final Clients clients;

    ChatController(JdbcClient jdbc, Knowledge knowledge, Clients clients) {
        this.jdbc = jdbc;
        this.knowledge = knowledge;
        this.clients = clients;
    }

    @PostMapping("/api/v1/ai/chat")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    Reply ask(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChatRequest r) {
        UUID caller = caller(jwt);
        OffsetDateTime dayStart = LocalDate.now(ZONE).atStartOfDay(ZONE).toOffsetDateTime();
        long today = jdbc.sql("SELECT count(*) FROM chat_turns WHERE account_id = ? AND created_at >= ?").params(caller, dayStart)
                .query(Long.class).single();
        if (today >= DAILY_LIMIT) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "DAILY_LIMIT", "Bạn đã hỏi đủ " + DAILY_LIMIT + " câu hôm nay, mai hỏi tiếp nhé");
        }
        // Personal details never need to be kept to answer a rules question.
        String question = PHONE.matcher(EMAIL.matcher(r.question().strip()).replaceAll("[email]")).replaceAll("[số điện thoại]");
        List<Hit> hits = knowledge.retriever().search(question, 3);
        boolean matched = !hits.isEmpty() && hits.getFirst().score() >= Retriever.THRESHOLD;
        Retriever.Entry top = matched ? hits.getFirst().entry() : null;
        String answer = matched ? top.answer() : NO_ANSWER;
        UUID id = UUID.randomUUID();
        UUID conversation = r.conversationId() == null ? UUID.randomUUID() : r.conversationId();
        jdbc.sql("""
                INSERT INTO chat_turns (id, conversation_id, account_id, account_role, question, answer, matched, source_type, source_ref, score)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, conversation, caller, role(jwt), question, answer, matched, matched ? top.type().name() : null, matched ? top.ref() : null,
                        hits.isEmpty() ? 0 : hits.getFirst().score())
                .update();
        if (!matched) clients.reportGap(question, "NO_ANSWER", caller);
        List<String> related = hits.stream().skip(matched ? 1 : 0).filter(h -> h.score() >= Retriever.THRESHOLD / 2).map(h -> h.entry().question())
                .toList();
        return new Reply(id, conversation, answer, matched, matched ? List.of(new Source(top.type().name(), top.ref(), top.question())) : List.of(),
                related, (int) (DAILY_LIMIT - today - 1));
    }

    @PostMapping("/api/v1/ai/chat/turns/{id}/feedback")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    Turn feedback(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody FeedbackRequest r) {
        Turn t = own(jwt, id);
        jdbc.sql("UPDATE chat_turns SET helpful = ? WHERE id = ?").params(r.helpful(), id).update();
        if (!r.helpful() && t.matched() && !Boolean.FALSE.equals(t.helpful())) clients.reportGap(t.question(), "UNHELPFUL", t.accountId());
        return get(id);
    }

    /** The question and the assistant's answer go to customer care as a ticket (source CHATBOT). */
    @PostMapping("/api/v1/ai/chat/turns/{id}/escalate")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    TicketRef escalate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Turn t = own(jwt, id);
        if (t.ticketId() != null) throw new ApiException(HttpStatus.CONFLICT, "ALREADY_ESCALATED", "Câu này đã được chuyển CSKH");
        String subject = "Trợ lý chưa trả lời được: " + (t.question().length() > 150 ? t.question().substring(0, 150) + "…" : t.question());
        UUID ticket = clients.openTicket(t.accountId(), t.accountRole(), subject,
                "Câu hỏi: " + t.question() + "\n\nTrợ lý đã trả lời: " + t.answer());
        jdbc.sql("UPDATE chat_turns SET ticket_id = ? WHERE id = ?").params(ticket, id).update();
        return new TicketRef(ticket);
    }

    @GetMapping("/api/v1/ai/chat/history")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    List<Turn> history(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID conversationId) {
        return jdbc.sql(SELECT + " WHERE account_id = :me AND (cast(:conv AS uuid) IS NULL OR conversation_id = cast(:conv AS uuid))"
                        + " ORDER BY created_at DESC LIMIT 50")
                .param("me", caller(jwt)).param("conv", conversationId == null ? null : conversationId.toString()).query(Turn.class).list();
    }

    /** P17 metrics: answered share, feedback and escalations over the last 30 days. */
    @GetMapping("/api/v1/ai/admin/stats")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT')")
    Stats stats() {
        return jdbc.sql("""
                SELECT count(*) AS turns, count(*) FILTER (WHERE matched) AS matched, count(*) FILTER (WHERE helpful) AS helpful,
                       count(*) FILTER (WHERE helpful = false) AS unhelpful, count(ticket_id) AS escalated, count(DISTINCT account_id) AS askers,
                       (count(*) FILTER (WHERE matched) * 100.0 / nullif(count(*), 0))::float8 AS matched_percent
                FROM chat_turns WHERE created_at > now() - interval '30 days'""").query(Stats.class).single();
    }

    private Turn own(Jwt jwt, UUID id) {
        Turn t = get(id);
        if (!t.accountId().equals(caller(jwt))) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Không phải câu hỏi của bạn");
        return t;
    }

    private Turn get(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(Turn.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TURN_NOT_FOUND", "Không tìm thấy câu hỏi"));
    }

    private static String role(Jwt jwt) { return jwt.getClaimAsString("role"); }

    private static UUID caller(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
