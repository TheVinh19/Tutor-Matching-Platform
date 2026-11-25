package vn.giasu.booking.plans;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
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
import vn.giasu.booking.Notifier;
import vn.giasu.booking.Schedule;
import vn.giasu.booking.TutorClient;
import vn.giasu.booking.holds.HoldJobs;
import vn.giasu.booking.plans.Plans.Plan;
import vn.giasu.booking.plans.Plans.Session;
import vn.giasu.common.web.ApiException;

/**
 * Plan lifecycle: learner REQUESTED -> tutor PROPOSED (sessions HELD up to 24h) -> learner CONFIRMED (sessions SCHEDULED).
 * Payment is mục 4: confirm sets payment_status=PAYMENT_PENDING_MOCK instead of taking money.
 */
@RestController
class PlanController {
    enum PlanMode { ONLINE, OFFLINE }

    record CreateRequest(@NotNull UUID tutorId, @NotBlank @Size(max = 32) String subjectCode, @NotBlank @Size(max = 32) String levelCode,
                         @NotNull PlanMode mode, @Size(max = 1000) String note,
                         // Grid flow (all four or none): how often, from when, how long, and which weekly cells suit the learner.
                         @Min(1) @Max(7) Integer sessionsPerWeek, LocalDate startDate, @Min(1) @Max(26) Integer weeks,
                         @Size(max = 35) List<@Valid @NotNull WeeklySlot> preferredSlots) {}
    record WeeklySlot(@NotNull @Min(1) @Max(7) Integer dayOfWeek, @NotNull LocalTime startTime) {
        Plans.Cell cell() { return new Plans.Cell(dayOfWeek, startTime); }
    }
    /** startDate may be omitted when the learner already chose it (grid flow). */
    record ProposeRequest(LocalDate startDate, LocalDate endDate,
                          @NotNull @Size(min = 1, max = 14) List<@Valid @NotNull WeeklySlot> slots,
                          @Size(max = 500) @Pattern(regexp = "https://\\S+", message = "phải là link https") String meetLink) {}
    record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}
    /** Renewal: same tutor, subject, level and mode as the old period; the learner picks the new weekly slots freely. */
    record RenewRequest(@Size(max = 1000) String note, @NotNull @Min(1) @Max(7) Integer sessionsPerWeek, @NotNull LocalDate startDate,
                        @NotNull @Min(1) @Max(26) Integer weeks, @NotNull @Size(min = 1, max = 35) List<@Valid @NotNull WeeklySlot> preferredSlots) {}
    record PlanDetail(Plan plan, List<Session> sessions, List<Plans.Cell> preferredSlots, SlotChangeController.SlotChange pendingSlotChange) {}
    record Conflict(OffsetDateTime startAt, OffsetDateTime endAt, String side) {}
    record Eligibility(UUID planId, UUID learnerId, UUID tutorId, String status, OffsetDateTime closedAt) {}

    private final JdbcClient jdbc;
    private final Plans plans;
    private final TutorClient tutors;
    private final Notifier notifier;
    private final HoldJobs holds;

    PlanController(JdbcClient jdbc, Plans plans, TutorClient tutors, Notifier notifier, HoldJobs holds) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.tutors = tutors;
        this.notifier = notifier;
        this.holds = holds;
    }

    @PostMapping("/api/v1/bookings/plans")
    @PreAuthorize("hasRole('LEARNER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    PlanDetail request(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest r) {
        return create(Plans.caller(jwt), r, null);
    }

    /**
     * P09: the learner asks for the next period early; it is an ordinary request linked to the old one and holds nothing
     * until the tutor proposes and the learner confirms (no priority over other learners).
     */
    @PostMapping("/api/v1/bookings/plans/{id}/renew")
    @PreAuthorize("hasRole('LEARNER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    PlanDetail renew(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody RenewRequest r) {
        Plan old = plans.getForUpdate(id);
        Plans.require(old.learnerId().equals(Plans.caller(jwt)), "NOT_PLAN_OWNER", "Chỉ tài khoản đứng tên đơn được gia hạn");
        Plans.requireStatus(List.of("CONFIRMED", "HOAN_TAT_NGHIA_VU", "KET_THUC_CO_HOAN_MOT_PHAN").contains(old.status()),
                "Chỉ gia hạn kỳ đang học hoặc đã kết thúc bình thường");
        if (old.endDate() != null && !r.startDate().isAfter(old.endDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "Kỳ gia hạn phải bắt đầu sau ngày kết thúc kỳ cũ " + old.endDate());
        }
        // One live renewal per period: a pending one, or one already confirmed (the index guards the pending case under races).
        boolean exists = jdbc.sql("SELECT count(*) FROM plans WHERE renewed_from = ? AND status NOT IN ('REJECTED', 'CANCELLED', 'EXPIRED')")
                .param(id).query(Long.class).single() > 0;
        if (exists) throw new ApiException(HttpStatus.CONFLICT, "RENEWAL_EXISTS", "Kỳ này đã có yêu cầu gia hạn");
        CreateRequest create = new CreateRequest(old.tutorId(), old.subjectCode(), old.levelCode(), PlanMode.valueOf(old.mode()), r.note(),
                r.sessionsPerWeek(), r.startDate(), r.weeks(), r.preferredSlots());
        try {
            return create(old.learnerId(), create, id);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "RENEWAL_EXISTS", "Kỳ này đã có yêu cầu gia hạn đang chờ"); // concurrent double click
        }
    }

    private PlanDetail create(UUID learnerId, CreateRequest r, UUID renewedFrom) {
        TutorClient.TutorProfile tutor = tutors.requireApproved(r.tutorId());
        if (!tutor.subjects().contains(new TutorClient.SubjectLevel(r.subjectCode(), r.levelCode()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SUBJECT_NOT_OFFERED", "Gia sư chưa được duyệt dạy môn/cấp học này");
        }
        if (!"FLEX".equals(tutor.teachingMode()) && !tutor.teachingMode().equals(r.mode().name())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODE_NOT_OFFERED", "Gia sư không dạy theo hình thức này");
        }
        boolean grid = r.preferredSlots() != null;
        LocalDate start = r.startDate();
        LocalDate end = null;
        List<WeeklySlot> preferred = grid ? r.preferredSlots().stream().distinct().toList() : List.of();
        if (grid != (r.sessionsPerWeek() != null) || grid != (start != null) || grid != (r.weeks() != null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INCOMPLETE_PREFERENCES", "Cần chọn đủ số buổi/tuần, ngày bắt đầu, số tuần và các ca");
        }
        if (grid) {
            end = start.plusWeeks(r.weeks()).minusDays(1);
            if (!start.isAfter(Schedule.today())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "Ngày bắt đầu phải từ ngày mai");
            }
            // The learner picks the exact weekly sessions, at most one per day; the tutor accepts or rejects them as a whole.
            if (preferred.size() != r.sessionsPerWeek()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "WRONG_SLOT_COUNT", "Cần chọn đúng " + r.sessionsPerWeek() + " ca");
            }
            if (preferred.stream().map(WeeklySlot::dayOfWeek).distinct().count() != preferred.size()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ONE_SLOT_PER_DAY", "Mỗi ngày chỉ chọn một ca");
            }
            checkInsideAvailability(jdbc, r.tutorId(), r.mode().name(), preferred, start, end);
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO plans (id, learner_id, tutor_id, subject_code, level_code, mode, note, status, sessions_per_week, weeks, start_date, end_date,
                                   renewed_from)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?, ?, ?, ?)""")
                .params(id, learnerId, r.tutorId(), r.subjectCode(), r.levelCode(), r.mode().name(), r.note() == null ? "" : r.note().strip(),
                        r.sessionsPerWeek(), r.weeks(), start, end, renewedFrom)
                .update();
        for (WeeklySlot c : preferred) {
            jdbc.sql("INSERT INTO plan_preferred_slots (plan_id, day_of_week, start_time) VALUES (?, ?, ?)").params(id, c.dayOfWeek(), c.startTime()).update();
        }
        notifier.send(r.tutorId(), "PLAN_REQUESTED", "Yêu cầu học mới", "Có Học sinh gửi yêu cầu học " + r.subjectCode() + " " + r.levelCode() + ".", "PLAN", id);
        return detailOf(id);
    }

    @GetMapping("/api/v1/bookings/plans/me")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    List<Plan> mine(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String status) {
        String column = "TUTOR".equals(jwt.getClaimAsString("role")) ? "tutor_id" : "learner_id";
        return jdbc.sql(Plans.PLAN_SELECT + " WHERE " + column + " = :me AND (cast(:status AS varchar) IS NULL OR status = :status) ORDER BY created_at DESC")
                .param("me", Plans.caller(jwt)).param("status", status).query(Plan.class).list();
    }

    @GetMapping("/api/v1/bookings/plans/{id}")
    PlanDetail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Plan plan = plans.get(id);
        Plans.requireParticipantOrAdmin(jwt, plan.learnerId(), plan.tutorId());
        return detailOf(id);
    }

    @PostMapping("/api/v1/bookings/plans/{id}/propose")
    @PreAuthorize("hasRole('TUTOR')")
    @Transactional
    PlanDetail propose(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ProposeRequest r) {
        holds.releaseExpired(); // expired holds of other plans must not block this proposal
        Plan plan = plans.getForUpdate(id);
        Plans.require(plan.tutorId().equals(Plans.caller(jwt)), "NOT_PLAN_TUTOR", "Không phải gia sư của kế hoạch này");
        Plans.requireStatus("REQUESTED".equals(plan.status()), "Chỉ lập kế hoạch cho yêu cầu đang chờ");

        boolean grid = plan.sessionsPerWeek() != null;
        if (grid) {
            List<Plans.Cell> preferred = plans.preferredSlots(id);
            List<WeeklySlot> notPreferred = r.slots().stream().filter(s -> !preferred.contains(s.cell())).toList();
            if (!notPreferred.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_PREFERRED_SLOT", "Chỉ chọn trong các ca Học sinh đã chọn", notPreferred);
            }
            if (r.slots().stream().distinct().count() != plan.sessionsPerWeek()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "WRONG_SLOT_COUNT", "Cần chọn đúng " + plan.sessionsPerWeek() + " ca/tuần");
            }
        }
        LocalDate start = r.startDate() != null ? r.startDate() : plan.startDate();
        if (start == null) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "Cần ngày bắt đầu");
        LocalDate end = r.endDate() != null ? r.endDate()
                : r.startDate() == null && plan.endDate() != null ? plan.endDate() : start.plusWeeks(Schedule.DEFAULT_WEEKS).minusDays(1);
        if (grid && r.startDate() == null && start.isBefore(Schedule.today())) start = Schedule.today(); // tutor answered late: keep the end date
        if (start.isBefore(Schedule.today()) || end.isBefore(start) || end.isAfter(start.plusWeeks(Schedule.MAX_WEEKS))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "Ngày bắt đầu/kết thúc không hợp lệ (tối đa 26 tuần)");
        }
        checkSlotRules(r.slots());
        checkInsideAvailability(jdbc, plan.tutorId(), plan.mode(), r.slots(), start, end);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<OffsetDateTime[]> generated = generate(r.slots(), start, end);
        if (grid) {
            // The learner picked the start date days ago: drop sessions now too close to confirm, instead of failing the tutor.
            generated.removeIf(s -> !now.isBefore(s[0].minus(Schedule.CUTOFF_BEFORE_FIRST)));
        }
        if (generated.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_SESSIONS", "Khoảng ngày không có buổi nào khớp các thứ đã chọn");
        }
        OffsetDateTime cutoff = generated.get(0)[0].minus(Schedule.CUTOFF_BEFORE_FIRST);
        if (!now.isBefore(cutoff)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FIRST_SESSION_TOO_SOON", "Buổi đầu phải cách thời điểm hiện tại hơn 12 giờ");
        }
        List<Conflict> conflicts = conflicts(plan, generated);
        if (!conflicts.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "SCHEDULE_CONFLICT", "Trùng lịch hoặc không đủ 60 phút giữa hai ca", conflicts);
        }
        try {
            for (OffsetDateTime[] s : generated) {
                jdbc.sql("INSERT INTO sessions (id, plan_id, tutor_id, learner_id, start_at, end_at, block_until, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'HELD')")
                        .params(UUID.randomUUID(), id, plan.tutorId(), plan.learnerId(), s[0], s[1], s[1].plus(Schedule.GAP)).update();
            }
        } catch (DataIntegrityViolationException e) {
            // Lost a race with another proposal between the check above and the insert; the EXCLUDE constraint decided.
            throw new ApiException(HttpStatus.CONFLICT, "SCHEDULE_CONFLICT", "Lịch vừa được người khác giữ, vui lòng chọn lại");
        }
        OffsetDateTime hold = now.plus(Schedule.HOLD).isBefore(cutoff) ? now.plus(Schedule.HOLD) : cutoff;
        jdbc.sql("""
                UPDATE plans SET status = 'PROPOSED', start_date = ?, end_date = ?, hold_expires_at = ?, meet_link = ?,
                    proposed_at = now(), updated_at = now() WHERE id = ?""")
                .params(start, end, hold, r.meetLink(), id).update();
        notifier.send(plan.learnerId(), "PLAN_PROPOSED", "Gia sư đã gửi kế hoạch học",
                generated.size() + " buổi, cần xác nhận trước " + hold.atZoneSameInstant(Schedule.ZONE).toLocalDateTime() + ".", "PLAN", id);
        return detailOf(id);
    }

    @PostMapping("/api/v1/bookings/plans/{id}/confirm")
    @PreAuthorize("hasRole('LEARNER')")
    @Transactional
    PlanDetail confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Plan plan = plans.getForUpdate(id);
        Plans.require(plan.learnerId().equals(Plans.caller(jwt)), "NOT_PLAN_OWNER", "Chỉ tài khoản đứng tên đơn được xác nhận");
        Plans.requireStatus("PROPOSED".equals(plan.status()), "Kế hoạch không ở trạng thái chờ xác nhận");
        if (!plan.holdExpiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new ApiException(HttpStatus.CONFLICT, "HOLD_EXPIRED", "Đã hết thời gian giữ lịch, cần gia sư lập lại kế hoạch");
        }
        jdbc.sql("UPDATE sessions SET status = 'SCHEDULED', meet_link = ?, updated_at = now() WHERE plan_id = ? AND status = 'HELD'")
                .params(plan.meetLink(), id).update();
        jdbc.sql("UPDATE plans SET status = 'CONFIRMED', payment_status = 'PAYMENT_PENDING_MOCK', confirmed_at = now(), updated_at = now() WHERE id = ?")
                .param(id).update();
        notifier.send(plan.tutorId(), "PLAN_CONFIRMED", "Học sinh đã xác nhận kế hoạch", "Lịch học đã được chốt.", "PLAN", id);
        if (plan.renewedFrom() != null) plans.qualifyRenewal(plan.renewedFrom());
        return detailOf(id);
    }

    @PostMapping("/api/v1/bookings/plans/{id}/reject")
    @PreAuthorize("hasRole('TUTOR')")
    @Transactional
    PlanDetail reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        Plan plan = plans.getForUpdate(id);
        Plans.require(plan.tutorId().equals(Plans.caller(jwt)), "NOT_PLAN_TUTOR", "Không phải gia sư của kế hoạch này");
        Plans.requireStatus("REQUESTED".equals(plan.status()), "Chỉ từ chối được yêu cầu đang chờ");
        jdbc.sql("UPDATE plans SET status = 'REJECTED', reject_reason = ?, closed_at = now(), updated_at = now() WHERE id = ?")
                .params(r.reason().strip(), id).update();
        notifier.send(plan.learnerId(), "PLAN_REJECTED", "Gia sư từ chối yêu cầu", r.reason().strip(), "PLAN", id);
        return detailOf(id);
    }

    @PostMapping("/api/v1/bookings/plans/{id}/cancel")
    @PreAuthorize("hasRole('LEARNER')")
    @Transactional
    PlanDetail cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Plan plan = plans.getForUpdate(id);
        Plans.require(plan.learnerId().equals(Plans.caller(jwt)), "NOT_PLAN_OWNER", "Chỉ tài khoản đứng tên đơn được hủy");
        Plans.requireStatus("REQUESTED".equals(plan.status()) || "PROPOSED".equals(plan.status()),
                "Chỉ hủy được kế hoạch chưa chốt; buổi đã chốt dùng báo bận/hủy buổi");
        jdbc.sql("UPDATE sessions SET status = 'RELEASED', updated_at = now() WHERE plan_id = ? AND status = 'HELD'").param(id).update();
        jdbc.sql("UPDATE plans SET status = 'CANCELLED', closed_at = now(), updated_at = now() WHERE id = ?").param(id).update();
        notifier.send(plan.tutorId(), "PLAN_CANCELLED", "Học sinh đã hủy yêu cầu", "Lịch giữ (nếu có) đã được giải phóng.", "PLAN", id);
        return detailOf(id);
    }

    // shortcut: latest 100 only, add paging/filters for the admin screens later.
    @GetMapping("/api/v1/bookings/admin/plans")
    @PreAuthorize("hasRole('ADMIN')")
    List<Plan> adminList(@RequestParam(required = false) String status) {
        return jdbc.sql(Plans.PLAN_SELECT + " WHERE cast(:status AS varchar) IS NULL OR status = :status ORDER BY created_at DESC LIMIT 100")
                .param("status", status).query(Plan.class).list();
    }

    /** For tutor-service reviews: who owns the plan and whether/when it closed. */
    @GetMapping("/api/v1/bookings/internal/plans/{id}/review-eligibility")
    Eligibility eligibility(@PathVariable UUID id) {
        Plan plan = plans.get(id);
        return new Eligibility(plan.id(), plan.learnerId(), plan.tutorId(), plan.status(), plan.closedAt());
    }

    private PlanDetail detailOf(UUID id) {
        return new PlanDetail(plans.get(id), plans.sessionsOf(id), plans.preferredSlots(id), SlotChangeController.pending(jdbc, id));
    }

    static void checkSlotRules(List<WeeklySlot> slots) {
        for (WeeklySlot s : slots) {
            LocalTime endTime = s.startTime().plus(Schedule.SESSION_LENGTH);
            if (s.startTime().isBefore(Schedule.DAY_START) || endTime.isAfter(Schedule.DAY_END) || endTime.isBefore(s.startTime())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SLOT", "Ca 120 phút phải nằm trong 08:00-22:00", s);
            }
        }
        Map<Integer, List<WeeklySlot>> byDay = slots.stream().collect(Collectors.groupingBy(WeeklySlot::dayOfWeek));
        for (List<WeeklySlot> day : byDay.values()) {
            List<WeeklySlot> sorted = day.stream().sorted(Comparator.comparing(WeeklySlot::startTime)).toList();
            for (int i = 1; i < sorted.size(); i++) {
                LocalTime previousEnd = sorted.get(i - 1).startTime().plus(Schedule.SESSION_LENGTH);
                if (Duration.between(previousEnd, sorted.get(i).startTime()).compareTo(Schedule.GAP) < 0) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "SLOTS_TOO_CLOSE", "Hai ca cùng ngày phải cách nhau ít nhất 60 phút", sorted.get(i));
                }
            }
        }
    }

    static void checkInsideAvailability(JdbcClient jdbc, UUID tutorId, String mode, List<WeeklySlot> slots, LocalDate start, LocalDate end) {
        List<WeeklySlot> outside = slots.stream().filter(s -> jdbc.sql("""
                SELECT count(*) FROM availability_slots
                WHERE tutor_id = :tutor AND day_of_week = :day AND start_time <= :start AND end_time >= :end
                  AND mode IN (:mode, 'FLEX') AND effective_from <= :fromDate AND (effective_to IS NULL OR effective_to >= :toDate)""")
                .param("tutor", tutorId).param("day", s.dayOfWeek()).param("start", s.startTime())
                .param("end", s.startTime().plus(Schedule.SESSION_LENGTH)).param("mode", mode)
                .param("fromDate", start).param("toDate", end)
                .query(Long.class).single() == 0).toList();
        if (!outside.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OUTSIDE_AVAILABILITY", "Ca nằm ngoài khoảng nhận dạy (thứ, giờ, hình thức hoặc ngày áp dụng)", outside);
        }
    }

    /** All [start, end] pairs for the weekly slots between start and end dates (inclusive), sorted. */
    static List<OffsetDateTime[]> generate(List<WeeklySlot> slots, LocalDate start, LocalDate end) {
        List<OffsetDateTime[]> sessions = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            for (WeeklySlot s : slots) {
                if (s.dayOfWeek() == date.getDayOfWeek().getValue()) {
                    OffsetDateTime begin = date.atTime(s.startTime()).atZone(Schedule.ZONE).toOffsetDateTime();
                    sessions.add(new OffsetDateTime[] {begin, begin.plus(Schedule.SESSION_LENGTH)});
                }
            }
        }
        sessions.sort(Comparator.comparing(s -> s[0]));
        return sessions;
    }

    /** Overlaps with active sessions of the tutor (incl. 60-minute gap) or of the learner, for a readable 409. */
    private List<Conflict> conflicts(Plan plan, List<OffsetDateTime[]> generated) {
        OffsetDateTime from = generated.get(0)[0].minus(Schedule.GAP.plus(Schedule.SESSION_LENGTH));
        OffsetDateTime to = generated.get(generated.size() - 1)[1].plus(Schedule.GAP);
        List<Session> existing = jdbc.sql(Plans.SESSION_SELECT + " " + """
                 WHERE status NOT IN ('CANCELLED', 'RELEASED') AND (tutor_id = :tutor OR learner_id = :learner)
                   AND start_at < :to AND end_at > :from""")
                .param("tutor", plan.tutorId()).param("learner", plan.learnerId()).param("from", from).param("to", to)
                .query(Session.class).list();
        List<Conflict> conflicts = new ArrayList<>();
        for (Session e : existing) {
            for (OffsetDateTime[] s : generated) {
                boolean tutorClash = e.tutorId().equals(plan.tutorId())
                        && e.startAt().isBefore(s[1].plus(Schedule.GAP)) && s[0].isBefore(e.endAt().plus(Schedule.GAP));
                boolean learnerClash = e.learnerId().equals(plan.learnerId()) && e.startAt().isBefore(s[1]) && s[0].isBefore(e.endAt());
                if (tutorClash || learnerClash) {
                    conflicts.add(new Conflict(s[0], s[1], tutorClash ? "TUTOR" : "LEARNER"));
                }
            }
        }
        return conflicts;
    }
}
