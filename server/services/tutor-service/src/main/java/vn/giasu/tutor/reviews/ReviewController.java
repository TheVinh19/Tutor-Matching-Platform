package vn.giasu.tutor.reviews;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.common.web.ApiException;
import vn.giasu.tutor.BookingClient;
import vn.giasu.tutor.BookingClient.PlanEligibility;

/** DG02/P12: text review, no stars, no edits, one per completed plan, within 14 days of the plan closing. */
@RestController
class ReviewController {
    static final Duration REVIEW_WINDOW = Duration.ofDays(14);

    record ReviewRequest(@NotNull UUID planId, @NotBlank @Size(min = 10, max = 2000) String content) {}
    record ReviewView(UUID id, UUID planId, String content, java.time.OffsetDateTime createdAt) {}

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
        if (!"COMPLETED".equals(plan.status()) || plan.closedAt() == null) {
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
        return jdbc.sql("SELECT id, plan_id, content, created_at FROM reviews WHERE id = ?").param(id).query(ReviewView.class).single();
    }

    // shortcut: latest 50 only, add paging when a tutor has more.
    @GetMapping("/api/v1/tutors/{tutorId}/reviews")
    List<ReviewView> list(@PathVariable UUID tutorId) {
        return jdbc.sql("SELECT id, plan_id, content, created_at FROM reviews WHERE tutor_id = ? AND NOT hidden ORDER BY created_at DESC LIMIT 50")
                .param(tutorId).query(ReviewView.class).list();
    }
}
