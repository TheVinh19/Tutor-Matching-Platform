package vn.giasu.notification.inbox;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.common.web.ApiException;

@RestController
class InboxController {
    record NoticeRequest(@NotNull UUID recipientId, @NotBlank @Size(max = 64) String type, @NotBlank @Size(max = 200) String title,
                         @Size(max = 2000) String body, @Size(max = 32) String refType, UUID refId) {}
    record Notice(UUID id, String type, String title, String body, String refType, UUID refId, OffsetDateTime createdAt, OffsetDateTime readAt) {}

    private static final String SELECT = "SELECT id, type, title, body, ref_type, ref_id, created_at, read_at FROM notifications";

    private final JdbcClient jdbc;

    InboxController(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Called by business services (role SERVICE, path blocked at the gateway). */
    @PostMapping("/api/v1/notifications/internal")
    @ResponseStatus(HttpStatus.CREATED)
    void create(@Valid @RequestBody NoticeRequest r) {
        jdbc.sql("INSERT INTO notifications (id, recipient_id, type, title, body, ref_type, ref_id) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), r.recipientId(), r.type(), r.title(), r.body() == null ? "" : r.body(), r.refType(), r.refId())
                .update();
    }

    // shortcut: latest 50 only, add paging when inboxes grow.
    @GetMapping("/api/v1/notifications/me")
    List<Notice> mine(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return jdbc.sql(SELECT + " WHERE recipient_id = :me AND (NOT :unread OR read_at IS NULL) ORDER BY created_at DESC LIMIT 50")
                .param("me", UUID.fromString(jwt.getSubject())).param("unread", unreadOnly).query(Notice.class).list();
    }

    @GetMapping("/api/v1/notifications/me/unread-count")
    long unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE recipient_id = ? AND read_at IS NULL")
                .param(UUID.fromString(jwt.getSubject())).query(Long.class).single();
    }

    @PatchMapping("/api/v1/notifications/{id}/read")
    Notice markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID me = UUID.fromString(jwt.getSubject());
        int updated = jdbc.sql("UPDATE notifications SET read_at = coalesce(read_at, now()) WHERE id = ? AND recipient_id = ?").params(id, me).update();
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Không tìm thấy thông báo");
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(Notice.class).single();
    }
}
