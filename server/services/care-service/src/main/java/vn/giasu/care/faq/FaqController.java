package vn.giasu.care.faq;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.common.web.ApiException;

/** P18 FAQ kept by customer care staff; published entries are what the AI assistant answers from (P17). */
@RestController
class FaqController {
    enum Status { PUBLISHED, HIDDEN }
    enum GapReason { NO_ANSWER, UNHELPFUL }

    record FaqRequest(@NotBlank @Size(max = 500) String question, @NotBlank @Size(max = 4000) String answer, @Size(max = 300) String tags,
                      UUID sourceTicketId, Status status) {}
    record GapRequest(@NotBlank @Size(max = 2000) String question, @NotNull GapReason reason, UUID accountId) {}
    record Faq(UUID id, String question, String answer, String tags, String status, UUID sourceTicketId, OffsetDateTime createdAt,
               OffsetDateTime updatedAt) {}
    record Gap(long id, String question, String reason, UUID accountId, boolean resolved, OffsetDateTime createdAt) {}

    private static final String SELECT = "SELECT id, question, answer, tags, status, source_ticket_id, created_at, updated_at FROM faqs";

    private final JdbcClient jdbc;

    FaqController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/api/v1/care/faqs")
    List<Faq> published(@RequestParam(required = false) String q) {
        return jdbc.sql(SELECT + " WHERE status = 'PUBLISHED' AND (cast(:q AS varchar) IS NULL OR question ILIKE :like OR answer ILIKE :like"
                        + " OR tags ILIKE :like) ORDER BY question")
                .param("q", q).param("like", "%" + (q == null ? "" : q.strip()) + "%").query(Faq.class).list();
    }

    @PostMapping("/api/v1/care/support/faqs")
    @PreAuthorize("hasRole('SUPPORT')")
    @ResponseStatus(HttpStatus.CREATED)
    Faq create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FaqRequest r) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO faqs (id, question, answer, tags, status, source_ticket_id, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(id, r.question().strip(), r.answer().strip(), r.tags() == null ? "" : r.tags().strip(),
                        r.status() == null ? "PUBLISHED" : r.status().name(), r.sourceTicketId(), UUID.fromString(jwt.getSubject()))
                .update();
        return get(id);
    }

    @PutMapping("/api/v1/care/support/faqs/{id}")
    @PreAuthorize("hasRole('SUPPORT')")
    Faq update(@PathVariable UUID id, @Valid @RequestBody FaqRequest r) {
        get(id);
        jdbc.sql("UPDATE faqs SET question = ?, answer = ?, tags = ?, status = ?, updated_at = now() WHERE id = ?")
                .params(r.question().strip(), r.answer().strip(), r.tags() == null ? "" : r.tags().strip(),
                        r.status() == null ? "PUBLISHED" : r.status().name(), id)
                .update();
        return get(id);
    }

    @GetMapping("/api/v1/care/support/faq-gaps")
    @PreAuthorize("hasAnyRole('SUPPORT', 'ADMIN')")
    List<Gap> gaps(@RequestParam(defaultValue = "false") boolean resolved) {
        return jdbc.sql("SELECT id, question, reason, account_id, resolved, created_at FROM faq_gaps WHERE resolved = ? ORDER BY created_at DESC LIMIT 200")
                .param(resolved).query(Gap.class).list();
    }

    @PostMapping("/api/v1/care/support/faq-gaps/{id}/resolve")
    @PreAuthorize("hasRole('SUPPORT')")
    void resolveGap(@PathVariable long id) {
        if (jdbc.sql("UPDATE faq_gaps SET resolved = true WHERE id = ?").param(id).update() == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GAP_NOT_FOUND", "Không tìm thấy câu hỏi cần bổ sung");
        }
    }

    @GetMapping("/api/v1/care/internal/faqs")
    List<Faq> forAssistant() {
        return jdbc.sql(SELECT + " WHERE status = 'PUBLISHED'").query(Faq.class).list();
    }

    @PostMapping("/api/v1/care/internal/faq-gaps")
    @ResponseStatus(HttpStatus.CREATED)
    void gapFromAssistant(@Valid @RequestBody GapRequest r) {
        jdbc.sql("INSERT INTO faq_gaps (question, reason, account_id) VALUES (?, ?, ?)").params(r.question().strip(), r.reason().name(), r.accountId())
                .update();
    }

    private Faq get(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(Faq.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FAQ_NOT_FOUND", "Không tìm thấy câu hỏi thường gặp"));
    }
}
