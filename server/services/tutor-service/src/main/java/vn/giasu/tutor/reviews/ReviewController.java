package vn.giasu.tutor.reviews;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
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
import vn.giasu.common.web.ApiException;
import vn.giasu.tutor.BookingClient;
import vn.giasu.tutor.BookingClient.PlanEligibility;

/**
 * DG02/P12: text review, no stars, no edits, one per closed period, within 14 days of closing. The tutor replies once or reports
 * a violation; admins only hide or restore the whole review after checking, keeping the original, who, why and when.
 */
@RestController
class ReviewController {
    static final Duration REVIEW_WINDOW = Duration.ofDays(14);
    static final Set<String> REVIEWABLE = Set.of("HOAN_TAT_NGHIA_VU", "KET_THUC_CO_HOAN_MOT_PHAN");

    enum Filter { REPORTED, HIDDEN, ALL }
    record ReviewRequest(@NotNull UUID planId, @NotBlank @Size(min = 10, max = 2000) String content) {}
    record TextRequest(@NotBlank @Size(max = 2000) String content) {}
    record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}
    /** Public: no learner id; the reply is shown under the review. */
    record ReviewView(UUID id, UUID planId, String content, OffsetDateTime createdAt, String reply, OffsetDateTime repliedAt) {}
    record Moderation(String action, String reason, UUID actorId, OffsetDateTime createdAt) {}
    record FullReview(UUID id, UUID planId, UUID tutorId, UUID learnerId, String content, boolean hidden, OffsetDateTime createdAt,
                      String reply, OffsetDateTime repliedAt, String reportReason, OffsetDateTime reportedAt) {}

    private static final String PUBLIC = "SELECT id, plan_id, content, created_at, reply, replied_at FROM reviews";
    private static final String FULL = """
            SELECT id, plan_id, tutor_id, learner_id, content, hidden, created_at, reply, replied_at, report_reason, reported_at FROM reviews""";

    private final JdbcClient jdbc;
    private final BookingClient booking;

    ReviewController(JdbcClient jdbc, BookingClient booking) {
        this.jdbc = jdbc;
        this.booking = booking;
    }

    @PostMapping("/api/v1/tutors/reviews")
    @PreAuthorize("hasRole('LEARNER')")
    @ResponseStatus(HttpStatus.CREATED)
    ReviewView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReviewRequest request) {
        UUID learnerId = UUID.fromString(jwt.getSubject());
        PlanEligibility plan = booking.planEligibility(request.planId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "Không tìm thấy kỳ học"));
        if (!plan.learnerId().equals(learnerId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_PLAN_OWNER", "Chỉ tài khoản đứng tên đơn được nhận xét");
        }
        // P12: only periods closed as HOAN_TAT_NGHIA_VU or KET_THUC_CO_HOAN_MOT_PHAN open reviews.
        if (!REVIEWABLE.contains(plan.status()) || plan.closedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "REVIEW_NOT_ALLOWED", "Kỳ học chưa hoàn tất nghĩa vụ");
        }
        if (Instant.now().isAfter(plan.closedAt().plus(REVIEW_WINDOW))) {
            throw new ApiException(HttpStatus.CONFLICT, "REVIEW_WINDOW_CLOSED", "Đã quá 14 ngày kể từ khi kỳ học đóng");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("INSERT INTO reviews (id, plan_id, tutor_id, learner_id, content) VALUES (?, ?, ?, ?, ?)")
                    .params(id, plan.planId(), plan.tutorId(), learnerId, request.content().strip()).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "REVIEW_EXISTS", "Kỳ học này đã có nhận xét");
        }
        return publicView(id);
    }

    // shortcut: latest 50 only, add paging when a tutor has more.
    @GetMapping("/api/v1/tutors/{tutorId}/reviews")
    List<ReviewView> list(@PathVariable UUID tutorId) {
        return jdbc.sql(PUBLIC + " WHERE tutor_id = ? AND NOT hidden ORDER BY created_at DESC LIMIT 50").param(tutorId).query(ReviewView.class).list();
    }

    @GetMapping("/api/v1/tutors/me/reviews")
    @PreAuthorize("hasRole('TUTOR')")
    List<FullReview> mine(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.sql(FULL + " WHERE tutor_id = ? ORDER BY created_at DESC LIMIT 100").param(UUID.fromString(jwt.getSubject()))
                .query(FullReview.class).list();
    }

    /** One public reply per review, not editable (like the review itself). */
    @PostMapping("/api/v1/tutors/reviews/{id}/reply")
    @PreAuthorize("hasRole('TUTOR')")
    @Transactional
    FullReview reply(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody TextRequest r) {
        FullReview review = ownReview(jwt, id);
        if (review.reply() != null) throw new ApiException(HttpStatus.CONFLICT, "REPLY_EXISTS", "Đã phản hồi nhận xét này");
        jdbc.sql("UPDATE reviews SET reply = ?, replied_at = now() WHERE id = ? AND reply IS NULL").params(r.content().strip(), id).update();
        return full(id);
    }

    @PostMapping("/api/v1/tutors/reviews/{id}/report")
    @PreAuthorize("hasRole('TUTOR')")
    @Transactional
    FullReview report(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        ownReview(jwt, id);
        jdbc.sql("UPDATE reviews SET report_reason = ?, reported_at = now() WHERE id = ?").params(r.reason().strip(), id).update();
        return full(id);
    }

    /** REPORTED: reported by the tutor and still visible, the queue to check. */
    @GetMapping("/api/v1/tutors/admin/reviews")
    @PreAuthorize("hasRole('ADMIN')")
    List<FullReview> adminList(@RequestParam(defaultValue = "REPORTED") Filter filter) {
        String where = switch (filter) {
            case REPORTED -> " WHERE reported_at IS NOT NULL AND NOT hidden";
            case HIDDEN -> " WHERE hidden";
            case ALL -> "";
        };
        return jdbc.sql(FULL + where + " ORDER BY created_at DESC LIMIT 100").query(FullReview.class).list();
    }

    @GetMapping("/api/v1/tutors/admin/reviews/{id}/moderation")
    @PreAuthorize("hasRole('ADMIN')")
    List<Moderation> moderation(@PathVariable UUID id) {
        return jdbc.sql("SELECT action, reason, actor_id, created_at FROM review_moderation WHERE review_id = ? ORDER BY id").param(id)
                .query(Moderation.class).list();
    }

    @PostMapping("/api/v1/tutors/admin/reviews/{id}/hide")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    FullReview hide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return moderate(jwt, id, true, r.reason());
    }

    @PostMapping("/api/v1/tutors/admin/reviews/{id}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    FullReview restore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return moderate(jwt, id, false, r.reason());
    }

    private FullReview moderate(Jwt jwt, UUID id, boolean hide, String reason) {
        full(id);
        int updated = jdbc.sql("UPDATE reviews SET hidden = ? WHERE id = ? AND hidden <> ?").params(hide, id, hide).update();
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS", hide ? "Nhận xét đã bị ẩn" : "Nhận xét đang hiển thị");
        }
        jdbc.sql("INSERT INTO review_moderation (review_id, action, reason, actor_id) VALUES (?, ?, ?, ?)")
                .params(id, hide ? "HIDE" : "RESTORE", reason.strip(), UUID.fromString(jwt.getSubject())).update();
        return full(id);
    }

    private FullReview ownReview(Jwt jwt, UUID id) {
        FullReview review = full(id);
        if (!review.tutorId().equals(UUID.fromString(jwt.getSubject()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_REVIEW_TUTOR", "Không phải nhận xét về bạn");
        }
        return review;
    }

    private FullReview full(UUID id) {
        return jdbc.sql(FULL + " WHERE id = ?").param(id).query(FullReview.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "Không tìm thấy nhận xét"));
    }

    private ReviewView publicView(UUID id) {
        return jdbc.sql(PUBLIC + " WHERE id = ?").param(id).query(ReviewView.class).single();
    }
}
