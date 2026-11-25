package vn.giasu.booking.plans;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.booking.Notifier;
import vn.giasu.booking.Schedule;
import vn.giasu.booking.plans.Plans.Plan;
import vn.giasu.booking.plans.Plans.Session;
import vn.giasu.common.web.ApiException;

/**
 * DOI05: move one weekly slot of a confirmed period (e.g. Tue 18:00 -> Thu 19:00) for every remaining session from a date on.
 * Either party asks, the other accepts within 24h or before the first affected session; otherwise the old schedule stays (P12).
 * Free weekly windows for the new slot come from GET /availability/tutors/{tutorId}/free.
 */
@RestController
class SlotChangeController {
    record ChangeRequest(@NotNull @Min(1) @Max(7) Integer fromDay, @NotNull LocalTime fromTime,
                         @NotNull @Min(1) @Max(7) Integer toDay, @NotNull LocalTime toTime, @NotNull LocalDate effectiveFrom) {}
    record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}
    record SlotChange(UUID id, UUID planId, UUID requestedBy, int fromDay, LocalTime fromTime, int toDay, LocalTime toTime,
                      LocalDate effectiveFrom, String status, String declineReason, OffsetDateTime expiresAt, OffsetDateTime createdAt,
                      OffsetDateTime decidedAt) {}

    private static final String SELECT = """
            SELECT id, plan_id, requested_by, from_day, from_time, to_day, to_time, effective_from, status, decline_reason,
                   expires_at, created_at, decided_at FROM slot_changes""";

    private final JdbcClient jdbc;
    private final Plans plans;
    private final Notifier notifier;

    SlotChangeController(JdbcClient jdbc, Plans plans, Notifier notifier) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.notifier = notifier;
    }

    /** Pending request of a plan, shown on the plan detail; an expired one the job has not swept yet is hidden. */
    static SlotChange pending(JdbcClient jdbc, UUID planId) {
        return jdbc.sql(SELECT + " WHERE plan_id = ? AND status = 'PENDING' AND expires_at > now()").param(planId)
                .query(SlotChange.class).optional().orElse(null);
    }

    @PostMapping("/api/v1/bookings/plans/{id}/slot-changes")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    SlotChange request(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ChangeRequest r) {
        Plan plan = plans.getForUpdate(id);
        UUID me = Plans.caller(jwt);
        Plans.require(me.equals(plan.learnerId()) || me.equals(plan.tutorId()), "NOT_PLAN_PARTY", "Không phải người tham gia kế hoạch này");
        Plans.requireStatus("CONFIRMED".equals(plan.status()), "Chỉ đổi giờ học của kỳ đang học");
        if (pending(jdbc, id) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "SLOT_CHANGE_PENDING", "Đang có một đề nghị đổi giờ chờ trả lời");
        }
        if (!r.effectiveFrom().isAfter(Schedule.today())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "Ngày áp dụng phải từ ngày mai");
        }
        if (r.fromDay().equals(r.toDay()) && r.fromTime().equals(r.toTime())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_SLOT", "Ca mới trùng ca cũ");
        }
        PlanController.WeeklySlot target = new PlanController.WeeklySlot(r.toDay(), r.toTime());
        PlanController.checkSlotRules(List.of(target));
        List<OffsetDateTime[]> moves = moves(id, r.fromDay(), r.fromTime(), r.effectiveFrom(), r.toDay(), r.toTime());
        LocalDate lastDate = moves.get(moves.size() - 1)[1].atZoneSameInstant(Schedule.ZONE).toLocalDate();
        PlanController.checkInsideAvailability(jdbc, plan.tutorId(), plan.mode(), List.of(target), r.effectiveFrom(), lastDate);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expires = now.plus(Schedule.SLOT_CHANGE_WINDOW);
        for (OffsetDateTime[] m : moves) {
            OffsetDateTime earliest = m[0].isBefore(m[1]) ? m[0] : m[1];
            if (earliest.isBefore(expires)) expires = earliest;
        }
        if (!expires.isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "Buổi bị ảnh hưởng quá gần, hãy chọn ngày áp dụng muộn hơn");
        }
        UUID changeId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO slot_changes (id, plan_id, requested_by, from_day, from_time, to_day, to_time, effective_from, status, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)""")
                .params(changeId, id, me, r.fromDay(), r.fromTime(), r.toDay(), r.toTime(), r.effectiveFrom(), expires).update();
        notifier.send(other(plan, me), "SLOT_CHANGE_REQUESTED", "Đề nghị đổi giờ học",
                moves.size() + " buổi từ " + r.effectiveFrom() + " chuyển sang " + (r.toDay() == 7 ? "Chủ nhật" : "thứ " + (r.toDay() + 1))
                        + " " + r.toTime() + ".", "PLAN", id);
        return get(changeId);
    }

    /** All matching sessions move in one transaction; any clash rolls everything back (EXCLUDE constraints decide). */
    @PostMapping("/api/v1/bookings/slot-changes/{id}/accept")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    @Transactional
    SlotChange accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        SlotChange c = get(id);
        Plan plan = plans.getForUpdate(c.planId());
        UUID me = Plans.caller(jwt);
        requireOtherParty(plan, c, me);
        requireOpen(c);
        Plans.requireStatus("CONFIRMED".equals(plan.status()), "Kỳ học không còn đang học");
        List<OffsetDateTime[]> moves = moves(plan.id(), c.fromDay(), c.fromTime(), c.effectiveFrom(), c.toDay(), c.toTime());
        List<Session> affected = affected(plan.id(), c.fromDay(), c.fromTime(), c.effectiveFrom());
        try {
            for (int i = 0; i < affected.size(); i++) {
                OffsetDateTime start = moves.get(i)[1];
                OffsetDateTime end = start.plus(Schedule.SESSION_LENGTH);
                jdbc.sql("""
                        UPDATE sessions SET start_at = ?, end_at = ?, block_until = ?, reminded_60_at = NULL, reminded_30_at = NULL,
                            updated_at = now() WHERE id = ?""")
                        .params(start, end, end.plus(Schedule.GAP), affected.get(i).id()).update();
            }
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "SCHEDULE_CONFLICT", "Ca mới trùng lịch hoặc không đủ 60 phút với buổi khác");
        }
        LocalDate lastDate = moves.get(moves.size() - 1)[1].atZoneSameInstant(Schedule.ZONE).toLocalDate();
        jdbc.sql("UPDATE plans SET end_date = greatest(end_date, ?), updated_at = now() WHERE id = ?").params(lastDate, plan.id()).update();
        jdbc.sql("UPDATE slot_changes SET status = 'ACCEPTED', decided_at = now() WHERE id = ?").param(id).update();
        notifier.send(c.requestedBy(), "SLOT_CHANGE_ACCEPTED", "Đã đồng ý đổi giờ học", affected.size() + " buổi đã chuyển sang giờ mới.", "PLAN", plan.id());
        return get(id);
    }

    @PostMapping("/api/v1/bookings/slot-changes/{id}/decline")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    @Transactional
    SlotChange decline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        SlotChange c = get(id);
        Plan plan = plans.getForUpdate(c.planId());
        requireOtherParty(plan, c, Plans.caller(jwt));
        requireOpen(c);
        jdbc.sql("UPDATE slot_changes SET status = 'DECLINED', decline_reason = ?, decided_at = now() WHERE id = ?").params(r.reason().strip(), id).update();
        notifier.send(c.requestedBy(), "SLOT_CHANGE_DECLINED", "Đề nghị đổi giờ học bị từ chối", r.reason().strip(), "PLAN", plan.id());
        return get(id);
    }

    @PostMapping("/api/v1/bookings/slot-changes/{id}/cancel")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    @Transactional
    SlotChange cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        SlotChange c = get(id);
        plans.getForUpdate(c.planId());
        Plans.require(c.requestedBy().equals(Plans.caller(jwt)), "NOT_REQUESTER", "Chỉ người đề nghị được hủy");
        requireOpen(c);
        jdbc.sql("UPDATE slot_changes SET status = 'CANCELLED', decided_at = now() WHERE id = ?").param(id).update();
        return get(id);
    }

    /** Scheduled sessions of the old weekly slot from the date on, in start order. */
    private List<Session> affected(UUID planId, int fromDay, LocalTime fromTime, LocalDate effectiveFrom) {
        return plans.sessionsOf(planId).stream().filter(s -> {
            LocalDateTime local = s.startAt().atZoneSameInstant(Schedule.ZONE).toLocalDateTime();
            return "SCHEDULED".equals(s.status()) && local.getDayOfWeek().getValue() == fromDay && local.toLocalTime().equals(fromTime)
                    && !local.toLocalDate().isBefore(effectiveFrom);
        }).toList();
    }

    /** [old start, new start] per affected session; the new date is the target weekday of the same Monday-Sunday week. */
    private List<OffsetDateTime[]> moves(UUID planId, int fromDay, LocalTime fromTime, LocalDate effectiveFrom, int toDay, LocalTime toTime) {
        List<Session> affected = affected(planId, fromDay, fromTime, effectiveFrom);
        if (affected.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_SESSIONS", "Không có buổi nào của ca này từ ngày áp dụng");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return affected.stream().map(s -> {
            LocalDate date = s.startAt().atZoneSameInstant(Schedule.ZONE).toLocalDate().with(DayOfWeek.of(toDay));
            OffsetDateTime start = date.atTime(toTime).atZone(Schedule.ZONE).toOffsetDateTime();
            if (!start.isAfter(now)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "Giờ mới của buổi " + date + " đã qua, hãy chọn ngày áp dụng muộn hơn");
            }
            return new OffsetDateTime[] {s.startAt(), start};
        }).toList();
    }

    private SlotChange get(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(SlotChange.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SLOT_CHANGE_NOT_FOUND", "Không tìm thấy đề nghị đổi giờ"));
    }

    private static void requireOtherParty(Plan plan, SlotChange c, UUID me) {
        Plans.require(!me.equals(c.requestedBy()) && (me.equals(plan.learnerId()) || me.equals(plan.tutorId())),
                "NOT_OTHER_PARTY", "Chỉ bên còn lại được trả lời đề nghị");
    }

    private static void requireOpen(SlotChange c) {
        Plans.requireStatus("PENDING".equals(c.status()) && c.expiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC)),
                "Đề nghị đã được trả lời hoặc hết hạn");
    }

    private static UUID other(Plan plan, UUID me) { return me.equals(plan.learnerId()) ? plan.tutorId() : plan.learnerId(); }
}
