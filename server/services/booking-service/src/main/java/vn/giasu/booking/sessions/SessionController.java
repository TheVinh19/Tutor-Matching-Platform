package vn.giasu.booking.sessions;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.booking.Notifier;
import vn.giasu.booking.Schedule;
import vn.giasu.booking.plans.Plans;
import vn.giasu.booking.plans.Plans.Session;
import vn.giasu.common.web.ApiException;

/**
 * Session lifecycle: SCHEDULED -> (tutor) COMPLETED_PENDING_CONFIRM -> (learner, 24h) CONFIRMED | DISPUTED.
 * Either party may report busy/cancel: CANCEL_REPORTED keeps the slot and waits for admin handling (HUY02).
 */
@RestController
class SessionController {
    record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}
    record MeetLinkRequest(@NotBlank @Size(max = 500) @Pattern(regexp = "https://\\S+", message = "phải là link https") String meetLink) {}

    private final JdbcClient jdbc;
    private final Plans plans;
    private final Notifier notifier;

    SessionController(JdbcClient jdbc, Plans plans, Notifier notifier) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.notifier = notifier;
    }

    @GetMapping("/api/v1/bookings/sessions/me")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    List<Session> mine(@AuthenticationPrincipal Jwt jwt,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate start = from != null ? from : Schedule.today().minusDays(7);
        LocalDate end = to != null ? to : Schedule.today().plusDays(60);
        String column = "TUTOR".equals(jwt.getClaimAsString("role")) ? "tutor_id" : "learner_id";
        return jdbc.sql(Plans.SESSION_SELECT + " WHERE " + column + " = :me AND status NOT IN ('HELD', 'RELEASED')"
                        + " AND start_at >= :from AND start_at < :to ORDER BY start_at")
                .param("me", Plans.caller(jwt))
                .param("from", start.atStartOfDay(Schedule.ZONE).toOffsetDateTime())
                .param("to", end.plusDays(1).atStartOfDay(Schedule.ZONE).toOffsetDateTime())
                .query(Session.class).list();
    }

    @GetMapping("/api/v1/bookings/sessions/{id}")
    Session detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Session session = plans.session(id, false);
        Plans.requireParticipantOrAdmin(jwt, session.learnerId(), session.tutorId());
        return session;
    }

    /** ON06: changing one session's link updates only that session and tells the learner. */
    @PutMapping("/api/v1/bookings/sessions/{id}/meet-link")
    @PreAuthorize("hasRole('TUTOR')")
    @Transactional
    Session meetLink(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody MeetLinkRequest r) {
        Session s = plans.session(id, true);
        Plans.require(s.tutorId().equals(Plans.caller(jwt)), "NOT_SESSION_TUTOR", "Không phải gia sư của buổi này");
        Plans.requireStatus("HELD".equals(s.status()) || "SCHEDULED".equals(s.status()), "Chỉ đổi link cho buổi chưa diễn ra");
        jdbc.sql("UPDATE sessions SET meet_link = ?, updated_at = now() WHERE id = ?").params(r.meetLink(), id).update();
        notifier.send(s.learnerId(), "MEET_LINK_CHANGED", "Link học đã thay đổi", "Buổi " + local(s.startAt()) + " có link mới.", "SESSION", id);
        return plans.session(id, false);
    }

    @PostMapping("/api/v1/bookings/sessions/{id}/cancel")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    @Transactional
    Session reportCancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        Session s = plans.session(id, true);
        UUID me = Plans.caller(jwt);
        Plans.require(me.equals(s.learnerId()) || me.equals(s.tutorId()), "NOT_SESSION_PARTY", "Không phải người tham gia buổi này");
        Plans.requireStatus("SCHEDULED".equals(s.status()), "Chỉ báo bận/hủy được buổi đã chốt chưa diễn ra");
        jdbc.sql("UPDATE sessions SET status = 'CANCEL_REPORTED', cancel_reason = ?, cancelled_by = ?, cancelled_at = now(), updated_at = now() WHERE id = ?")
                .params(r.reason().strip(), me, id).update();
        UUID other = me.equals(s.learnerId()) ? s.tutorId() : s.learnerId();
        notifier.send(other, "SESSION_CANCEL_REPORTED", "Báo bận/hủy buổi học",
                "Buổi " + local(s.startAt()) + ": " + r.reason().strip(), "SESSION", id);
        return plans.session(id, false);
    }

    @PostMapping("/api/v1/bookings/sessions/{id}/complete")
    @PreAuthorize("hasRole('TUTOR')")
    @Transactional
    Session complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Session s = plans.session(id, true);
        Plans.require(s.tutorId().equals(Plans.caller(jwt)), "NOT_SESSION_TUTOR", "Không phải gia sư của buổi này");
        Plans.requireStatus("SCHEDULED".equals(s.status()), "Buổi không ở trạng thái đã chốt");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (now.isBefore(s.startAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "SESSION_NOT_STARTED", "Buổi học chưa bắt đầu");
        }
        jdbc.sql("UPDATE sessions SET status = 'COMPLETED_PENDING_CONFIRM', completed_at = now(), confirm_deadline = ?, updated_at = now() WHERE id = ?")
                .params(now.plus(Schedule.CONFIRM_WINDOW), id).update();
        notifier.send(s.learnerId(), "SESSION_COMPLETED", "Xác nhận buổi học",
                "Gia sư báo đã dạy buổi " + local(s.startAt()) + ". Bạn có 24 giờ để xác nhận hoặc phản đối.", "SESSION", id);
        return plans.session(id, false);
    }

    @PostMapping("/api/v1/bookings/sessions/{id}/confirm")
    @PreAuthorize("hasRole('LEARNER')")
    @Transactional
    Session confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Session s = learnerPending(jwt, id);
        jdbc.sql("UPDATE sessions SET status = 'CONFIRMED', confirmed_at = now(), updated_at = now() WHERE id = ?").param(id).update();
        plans.completeIfDone(s.planId());
        return plans.session(id, false);
    }

    @PostMapping("/api/v1/bookings/sessions/{id}/dispute")
    @PreAuthorize("hasRole('LEARNER')")
    @Transactional
    Session dispute(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        Session s = learnerPending(jwt, id);
        if (OffsetDateTime.now(ZoneOffset.UTC).isAfter(s.confirmDeadline())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFIRM_WINDOW_CLOSED", "Đã quá 24 giờ để phản đối");
        }
        jdbc.sql("UPDATE sessions SET status = 'DISPUTED', dispute_reason = ?, updated_at = now() WHERE id = ?").params(r.reason().strip(), id).update();
        notifier.send(s.tutorId(), "SESSION_DISPUTED", "Người thuê phản đối buổi học", r.reason().strip(), "SESSION", id);
        return plans.session(id, false);
    }

    /** Only the account that owns the plan accepts or disputes; admins never confirm on its behalf (P01/P04). */
    private Session learnerPending(Jwt jwt, UUID id) {
        Session s = plans.session(id, true);
        Plans.require(s.learnerId().equals(Plans.caller(jwt)), "NOT_PLAN_OWNER", "Chỉ tài khoản đứng tên đơn được xác nhận/phản đối");
        Plans.requireStatus("COMPLETED_PENDING_CONFIRM".equals(s.status()), "Buổi không ở trạng thái chờ xác nhận");
        return s;
    }

    private static String local(OffsetDateTime t) {
        return t.atZoneSameInstant(Schedule.ZONE).toLocalDateTime().toString().replace('T', ' ');
    }
}
