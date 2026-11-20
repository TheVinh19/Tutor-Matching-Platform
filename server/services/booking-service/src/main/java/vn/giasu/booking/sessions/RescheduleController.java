package vn.giasu.booking.sessions;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.booking.Notifier;
import vn.giasu.booking.Schedule;
import vn.giasu.booking.availability.FreeTime;
import vn.giasu.booking.availability.FreeTime.Window;
import vn.giasu.booking.plans.Plans;
import vn.giasu.booking.plans.Plans.Session;
import vn.giasu.booking.sessions.SessionController.ReasonRequest;
import vn.giasu.common.web.ApiException;

/**
 * Moves a CANCEL_REPORTED session to a new time, back to SCHEDULED:
 * - the learner picks a free slot of the tutor (the tutor already agreed to those hours), or
 * - the learner sends the weekly windows they can attend within a date range, and the tutor picks one slot inside them
 *   (outside the tutor's public availability, so nobody else can take it first).
 * Both parties' other sessions (tutor with the 60-minute gap) and the reported session itself stay blocked.
 */
@RestController
class RescheduleController {
    record PickRequest(@NotNull OffsetDateTime startAt) {}
    record OfferWindow(@NotNull @Min(1) @Max(7) Integer dayOfWeek, @NotNull LocalTime startTime, @NotNull LocalTime endTime) {}
    record OfferRequest(@NotNull LocalDate fromDate, @NotNull LocalDate toDate,
                        @NotNull @Size(min = 1, max = 50) List<@Valid @NotNull OfferWindow> windows) {}
    record AvailabilityRow(int dayOfWeek, LocalTime startTime, LocalTime endTime, String mode, LocalDate effectiveFrom, LocalDate effectiveTo) {}

    static final int MAX_OFFER_DAYS = 56;

    private final JdbcClient jdbc;
    private final Plans plans;
    private final Notifier notifier;

    RescheduleController(JdbcClient jdbc, Plans plans, Notifier notifier) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.notifier = notifier;
    }

    /** Slots the caller may pick in the 7 days from fromDate (one date per weekday): tutor's free time for the learner, the learner's offer for the tutor. */
    @GetMapping("/api/v1/bookings/sessions/{id}/reschedule/free")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    List<Window> free(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate) {
        Session s = reported(jwt, id, false);
        return options(s, Plans.caller(jwt).equals(s.tutorId()), fromDate);
    }

    @PostMapping("/api/v1/bookings/sessions/{id}/reschedule")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    @Transactional
    Session pick(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody PickRequest r) {
        Session s = reported(jwt, id, true);
        UUID me = Plans.caller(jwt);
        OffsetDateTime start = r.startAt();
        LocalDateTime local = start.atZoneSameInstant(Schedule.ZONE).toLocalDateTime();
        int from = FreeTime.minutes(local.toLocalTime());
        int to = from + (int) Schedule.SESSION_LENGTH.toMinutes();
        int day = local.getDayOfWeek().getValue();
        // Trust boundary: the start must lie inside what this caller was offered, recomputed now.
        boolean offered = options(s, me.equals(s.tutorId()), local.toLocalDate()).stream().anyMatch(w -> w.dayOfWeek() == day
                && FreeTime.minutes(w.startTime()) <= from && to <= FreeTime.minutes(w.endTime()));
        if (!offered) {
            throw new ApiException(HttpStatus.CONFLICT, "SLOT_NOT_AVAILABLE", "Ca này không còn trống, hãy chọn ca khác");
        }
        OffsetDateTime end = start.plus(Schedule.SESSION_LENGTH);
        try {
            jdbc.sql("""
                    UPDATE sessions SET status = 'SCHEDULED', start_at = ?, end_at = ?, block_until = ?, offer_from = NULL, offer_to = NULL,
                        cancel_reason = NULL, cancelled_by = NULL, cancelled_at = NULL, updated_at = now() WHERE id = ?""")
                    .params(start, end, end.plus(Schedule.GAP), id).update();
        } catch (DataIntegrityViolationException e) {
            // Lost a race with another booking after the check above; the EXCLUDE constraints decided.
            throw new ApiException(HttpStatus.CONFLICT, "SCHEDULE_CONFLICT", "Ca vừa được đặt, hãy chọn ca khác");
        }
        jdbc.sql("DELETE FROM session_offer_windows WHERE session_id = ?").param(id).update();
        UUID other = me.equals(s.learnerId()) ? s.tutorId() : s.learnerId();
        notifier.send(other, "SESSION_RESCHEDULED", "Buổi học đã dời lịch",
                "Buổi " + SessionController.local(s.startAt()) + " đã dời sang " + SessionController.local(start) + ".", "SESSION", id);
        return plans.session(id, false);
    }

    /** Learner: replaces any earlier offer. */
    @PostMapping("/api/v1/bookings/sessions/{id}/reschedule/offer")
    @PreAuthorize("hasRole('LEARNER')")
    @Transactional
    Session offer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody OfferRequest r) {
        Session s = reported(jwt, id, true);
        Plans.require(s.learnerId().equals(Plans.caller(jwt)), "NOT_SESSION_LEARNER", "Chỉ học viên gửi lịch có thể học");
        if (r.fromDate().isBefore(Schedule.today()) || r.toDate().isBefore(r.fromDate()) || r.toDate().isAfter(r.fromDate().plusDays(MAX_OFFER_DAYS))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "Khoảng ngày phải từ hôm nay, tối đa " + MAX_OFFER_DAYS + " ngày");
        }
        List<OfferWindow> windows = r.windows().stream()
                .sorted(Comparator.comparingInt(OfferWindow::dayOfWeek).thenComparing(OfferWindow::startTime)).toList();
        for (int i = 0; i < windows.size(); i++) {
            OfferWindow w = windows.get(i);
            if (w.startTime().isBefore(Schedule.DAY_START) || w.endTime().isAfter(Schedule.DAY_END)
                    || Duration.between(w.startTime(), w.endTime()).compareTo(Schedule.SESSION_LENGTH) < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "Khoảng có thể học phải trong 08:00-22:00 và dài ít nhất 120 phút", w);
            }
            if (i > 0 && windows.get(i - 1).dayOfWeek().equals(w.dayOfWeek()) && w.startTime().isBefore(windows.get(i - 1).endTime())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "WINDOW_OVERLAP", "Hai khoảng trong cùng ngày bị chồng nhau", w);
            }
        }
        jdbc.sql("DELETE FROM session_offer_windows WHERE session_id = ?").param(id).update();
        for (OfferWindow w : windows) {
            jdbc.sql("INSERT INTO session_offer_windows (session_id, day_of_week, start_time, end_time) VALUES (?, ?, ?, ?)")
                    .params(id, w.dayOfWeek(), w.startTime(), w.endTime()).update();
        }
        jdbc.sql("UPDATE sessions SET offer_from = ?, offer_to = ?, updated_at = now() WHERE id = ?").params(r.fromDate(), r.toDate(), id).update();
        notifier.send(s.tutorId(), "SESSION_RESCHEDULE_OFFER", "Học viên gửi lịch có thể học",
                "Chọn 1 ca để dời buổi " + SessionController.local(s.startAt()) + ".", "SESSION", id);
        return plans.session(id, false);
    }

    /** Tutor: none of the offered windows works; the learner is told why and may send another offer or pick a free slot. */
    @PostMapping("/api/v1/bookings/sessions/{id}/reschedule/decline")
    @PreAuthorize("hasRole('TUTOR')")
    @Transactional
    Session decline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        Session s = reported(jwt, id, true);
        Plans.require(s.tutorId().equals(Plans.caller(jwt)), "NOT_SESSION_TUTOR", "Không phải gia sư của buổi này");
        Plans.requireStatus(s.offerFrom() != null, "Học viên chưa gửi lịch có thể học");
        jdbc.sql("DELETE FROM session_offer_windows WHERE session_id = ?").param(id).update();
        jdbc.sql("UPDATE sessions SET offer_from = NULL, offer_to = NULL, updated_at = now() WHERE id = ?").param(id).update();
        notifier.send(s.learnerId(), "SESSION_RESCHEDULE_DECLINED", "Gia sư không xếp được lịch bạn gửi", r.reason().strip(), "SESSION", id);
        return plans.session(id, false);
    }

    private Session reported(Jwt jwt, UUID id, boolean forUpdate) {
        Session s = plans.session(id, forUpdate);
        UUID me = Plans.caller(jwt);
        Plans.require(me.equals(s.learnerId()) || me.equals(s.tutorId()), "NOT_SESSION_PARTY", "Không phải người tham gia buổi này");
        Plans.requireStatus("CANCEL_REPORTED".equals(s.status()), "Chỉ dời lịch buổi đang báo bận/hủy");
        return s;
    }

    /** Pickable windows in [fromDate, fromDate + 7 days): past dates and the elapsed part of today are dropped. */
    private List<Window> options(Session s, boolean tutorPicks, LocalDate fromDate) {
        LocalDate today = Schedule.today();
        LocalDate toDate = fromDate.plusDays(7);
        Map<Integer, List<int[]>> cuts = FreeTime.byWeekday(FreeTime.busy(jdbc, s.tutorId(), s.learnerId(),
                fromDate.atStartOfDay(Schedule.ZONE).toOffsetDateTime(), toDate.atStartOfDay(Schedule.ZONE).toOffsetDateTime()), s.tutorId());

        List<AvailabilityRow> rows;
        LocalDate first = fromDate;
        LocalDate last = toDate.minusDays(1);
        if (tutorPicks) {
            if (s.offerFrom() == null) return List.of();
            first = first.isBefore(s.offerFrom()) ? s.offerFrom() : first;
            last = last.isAfter(s.offerTo()) ? s.offerTo() : last;
            rows = jdbc.sql("SELECT day_of_week, start_time, end_time, 'FLEX' AS mode, CAST(NULL AS date) AS effective_from, CAST(NULL AS date) AS effective_to"
                    + " FROM session_offer_windows WHERE session_id = ?").param(s.id()).query(AvailabilityRow.class).list();
        } else {
            rows = jdbc.sql("SELECT day_of_week, start_time, end_time, mode, effective_from, effective_to FROM availability_slots WHERE tutor_id = ?")
                    .param(s.tutorId()).query(AvailabilityRow.class).list();
        }
        String planMode = tutorPicks ? null : plans.get(s.planId()).mode();
        List<Window> free = new ArrayList<>();
        for (AvailabilityRow w : rows) {
            LocalDate date = fromDate.plusDays(Math.floorMod(w.dayOfWeek() - fromDate.getDayOfWeek().getValue(), 7));
            if (date.isBefore(today) || date.isBefore(first) || date.isAfter(last)) continue;
            if (w.effectiveFrom() != null && w.effectiveFrom().isAfter(date)) continue;
            if (w.effectiveTo() != null && w.effectiveTo().isBefore(date)) continue;
            if (planMode != null && !"FLEX".equals(w.mode()) && !planMode.equals(w.mode())) continue;
            List<int[]> dayCuts = new ArrayList<>(cuts.getOrDefault(w.dayOfWeek(), List.of()));
            if (date.equals(today)) dayCuts.add(new int[] {0, FreeTime.minutes(LocalTime.now(Schedule.ZONE)) + 1});
            free.addAll(FreeTime.remaining(w.dayOfWeek(), w.startTime(), w.endTime(), dayCuts));
        }
        return free;
    }
}
