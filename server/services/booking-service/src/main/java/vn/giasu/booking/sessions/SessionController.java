package vn.giasu.booking.sessions;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
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
 * Session lifecycle: SCHEDULED -> (tutor start) IN_PROGRESS -> (tutor end) COMPLETED_PENDING_CONFIRM -> (learner, 24h) CONFIRMED | DISPUTED.
 * Absence: the tutor reports LEARNER_ABSENT_PENDING (learner may object in 24h, else LEARNER_ABSENT); the learner reports TUTOR_ABSENT_REPORTED.
 * Either party may report busy/cancel: CANCEL_REPORTED keeps the slot until the session is moved to a new time
 * (back to SCHEDULED, see RescheduleController) or the report expires (CANCELLED, see HoldJobs).
 */
@RestController
class SessionController {
    enum Party { LEARNER, TUTOR }
    record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}
    record CompleteRequest(Party earlyEndBy, @Size(max = 1000) String earlyEndNote) {}
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
        Session s = tutorSession(jwt, id);
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
        // The report expires after 24h or at the session start, whichever is first (P12); HoldJobs then cancels the session.
        OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC).plus(Schedule.CANCEL_REPORT_WINDOW);
        if (s.startAt().isBefore(deadline)) deadline = s.startAt();
        jdbc.sql("""
                UPDATE sessions SET status = 'CANCEL_REPORTED', cancel_reason = ?, cancelled_by = ?, cancelled_at = now(),
                    cancel_deadline = ?, updated_at = now() WHERE id = ?""")
                .params(r.reason().strip(), me, deadline, id).update();
        UUID other = me.equals(s.learnerId()) ? s.tutorId() : s.learnerId();
        notifier.send(other, "SESSION_CANCEL_REPORTED", "Báo bận/hủy buổi học",
                "Buổi " + local(s.startAt()) + ": " + r.reason().strip(), "SESSION", id);
        return plans.session(id, false);
    }

    /** The tutor presses "start": server time, once, never edited (P04). More than 10 minutes late is recorded; penalty comes later. */
    @PostMapping("/api/v1/bookings/sessions/{id}/start")
    @PreAuthorize("hasRole('TUTOR')")
    @Transactional
    Session start(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Session s = tutorSession(jwt, id);
        Plans.requireStatus("SCHEDULED".equals(s.status()), "Buổi không ở trạng thái đã chốt");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (now.isBefore(s.startAt().minus(Schedule.START_EARLY))) {
            throw new ApiException(HttpStatus.CONFLICT, "SESSION_NOT_STARTED", "Chỉ bắt đầu được từ 15 phút trước giờ học");
        }
        // No upper bound: starting after the planned end is still allowed, it is just recorded as late.
        long late = Duration.between(s.startAt(), now).toMinutes();
        Integer lateMinutes = late > Schedule.LATE_GRACE.toMinutes() ? (int) late : null;
        jdbc.sql("UPDATE sessions SET status = 'IN_PROGRESS', started_at = now(), late_minutes = ?, updated_at = now() WHERE id = ?")
                .params(lateMinutes, id).update();
        return plans.session(id, false);
    }

    /** Ending before the planned end needs who wanted to stop and why; the end time is the server time of the click. */
    @PostMapping("/api/v1/bookings/sessions/{id}/complete")
    @PreAuthorize("hasRole('TUTOR')")
    @Transactional
    Session complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody(required = false) CompleteRequest r) {
        Session s = tutorSession(jwt, id);
        Plans.requireStatus("IN_PROGRESS".equals(s.status()), "Cần bấm bắt đầu buổi học trước");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean early = now.isBefore(s.endAt());
        String note = r == null || r.earlyEndNote() == null ? "" : r.earlyEndNote().strip();
        if (early && (r == null || r.earlyEndBy() == null || note.isEmpty())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EARLY_END_NOTE_REQUIRED", "Kết thúc sớm cần ghi ai muốn nghỉ sớm và lý do");
        }
        jdbc.sql("""
                UPDATE sessions SET status = 'COMPLETED_PENDING_CONFIRM', completed_at = now(), confirm_deadline = ?,
                    early_end_by = ?, early_end_note = ?, updated_at = now() WHERE id = ?""")
                .params(now.plus(Schedule.CONFIRM_WINDOW), early ? r.earlyEndBy().name() : null, early ? note : null, id).update();
        notifier.send(s.learnerId(), "SESSION_COMPLETED", "Xác nhận buổi học",
                "Gia sư báo đã dạy buổi " + local(s.startAt()) + ". Bạn có 24 giờ để xác nhận hoặc phản đối.", "SESSION", id);
        return plans.session(id, false);
    }

    /** P11: the tutor started, waited 15 minutes and tried to reach the learner; the learner may object within 24h. */
    @PostMapping("/api/v1/bookings/sessions/{id}/learner-absent")
    @PreAuthorize("hasRole('TUTOR')")
    @Transactional
    Session learnerAbsent(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        Session s = tutorSession(jwt, id);
        Plans.requireStatus("IN_PROGRESS".equals(s.status()), "Cần bấm bắt đầu buổi học trước");
        requireWaited(s);
        jdbc.sql("UPDATE sessions SET status = 'LEARNER_ABSENT_PENDING', absent_note = ?, absent_deadline = ?, updated_at = now() WHERE id = ?")
                .params(r.reason().strip(), OffsetDateTime.now(ZoneOffset.UTC).plus(Schedule.CONFIRM_WINDOW), id).update();
        notifier.send(s.learnerId(), "SESSION_LEARNER_ABSENT", "Gia sư báo bạn vắng buổi học",
                "Buổi " + local(s.startAt()) + ": " + r.reason().strip() + ". Bạn có 24 giờ để phản đối.", "SESSION", id);
        return plans.session(id, false);
    }

    /** The tutor never started: the learner reports it; the session waits for the case module and is never auto-accepted. */
    @PostMapping("/api/v1/bookings/sessions/{id}/tutor-absent")
    @PreAuthorize("hasRole('LEARNER')")
    @Transactional
    Session tutorAbsent(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        Session s = plans.session(id, true);
        Plans.require(s.learnerId().equals(Plans.caller(jwt)), "NOT_PLAN_OWNER", "Chỉ tài khoản đứng tên đơn được báo");
        Plans.requireStatus("SCHEDULED".equals(s.status()), "Chỉ báo được buổi gia sư chưa bắt đầu");
        requireWaited(s);
        jdbc.sql("UPDATE sessions SET status = 'TUTOR_ABSENT_REPORTED', absent_note = ?, updated_at = now() WHERE id = ?")
                .params(r.reason().strip(), id).update();
        notifier.send(s.tutorId(), "SESSION_TUTOR_ABSENT", "Học sinh báo bạn không dạy buổi học",
                "Buổi " + local(s.startAt()) + ": " + r.reason().strip(), "SESSION", id);
        plans.closeIfDone(s.planId());
        return plans.session(id, false);
    }

    /** Accepts a taught session, or accepts the tutor's absence report before the 24h run out. */
    @PostMapping("/api/v1/bookings/sessions/{id}/confirm")
    @PreAuthorize("hasRole('LEARNER')")
    @Transactional
    Session confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Session s = learnerPending(jwt, id);
        if ("LEARNER_ABSENT_PENDING".equals(s.status())) {
            jdbc.sql("UPDATE sessions SET status = 'LEARNER_ABSENT', updated_at = now() WHERE id = ?").param(id).update();
        } else {
            jdbc.sql("UPDATE sessions SET status = 'CONFIRMED', confirmed_at = now(), updated_at = now() WHERE id = ?").param(id).update();
        }
        plans.closeIfDone(s.planId());
        return plans.session(id, false);
    }

    @PostMapping("/api/v1/bookings/sessions/{id}/dispute")
    @PreAuthorize("hasRole('LEARNER')")
    @Transactional
    Session dispute(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        Session s = learnerPending(jwt, id);
        OffsetDateTime deadline = "LEARNER_ABSENT_PENDING".equals(s.status()) ? s.absentDeadline() : s.confirmDeadline();
        if (OffsetDateTime.now(ZoneOffset.UTC).isAfter(deadline)) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFIRM_WINDOW_CLOSED", "Đã quá 24 giờ để phản đối");
        }
        jdbc.sql("UPDATE sessions SET status = 'DISPUTED', dispute_reason = ?, updated_at = now() WHERE id = ?").params(r.reason().strip(), id).update();
        notifier.send(s.tutorId(), "SESSION_DISPUTED", "Học sinh phản đối buổi học", r.reason().strip(), "SESSION", id);
        plans.closeIfDone(s.planId());
        return plans.session(id, false);
    }

    /** Only the account that owns the plan accepts or disputes; admins never confirm on its behalf (P01/P04). */
    private Session learnerPending(Jwt jwt, UUID id) {
        Session s = plans.session(id, true);
        Plans.require(s.learnerId().equals(Plans.caller(jwt)), "NOT_PLAN_OWNER", "Chỉ tài khoản đứng tên đơn được xác nhận/phản đối");
        Plans.requireStatus("COMPLETED_PENDING_CONFIRM".equals(s.status()) || "LEARNER_ABSENT_PENDING".equals(s.status()),
                "Buổi không ở trạng thái chờ xác nhận");
        return s;
    }

    private Session tutorSession(Jwt jwt, UUID id) {
        Session s = plans.session(id, true);
        Plans.require(s.tutorId().equals(Plans.caller(jwt)), "NOT_SESSION_TUTOR", "Không phải gia sư của buổi này");
        return s;
    }

    /** Absence reports open 15 minutes after the planned start (P11). */
    private static void requireWaited(Session s) {
        if (OffsetDateTime.now(ZoneOffset.UTC).isBefore(s.startAt().plus(Schedule.ABSENT_WAIT))) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_EARLY_TO_REPORT_ABSENT", "Chỉ báo vắng sau 15 phút kể từ giờ học");
        }
    }

    static String local(OffsetDateTime t) {
        return t.atZoneSameInstant(Schedule.ZONE).toLocalDateTime().toString().replace('T', ' ');
    }
}
