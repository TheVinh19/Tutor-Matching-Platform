package vn.giasu.booking.availability;

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
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.booking.TutorClient;
import vn.giasu.booking.Schedule;
import vn.giasu.common.web.ApiException;

/** CA01-CA05: tutors manage weekly teaching windows; checked automatically, effective at once. */
@RestController
class AvailabilityController {
    enum Mode { ONLINE, OFFLINE, FLEX }

    record SlotRequest(@NotNull @Min(1) @Max(7) Integer dayOfWeek, @NotNull LocalTime startTime, @NotNull LocalTime endTime,
                       @NotNull Mode mode, @NotNull LocalDate effectiveFrom, LocalDate effectiveTo) {}
    record WindowRequest(@NotNull @Min(1) @Max(7) Integer dayOfWeek, @NotNull LocalTime startTime, @NotNull LocalTime endTime) {}
    record WindowsRequest(@NotNull @Size(max = 50) List<@Valid @NotNull WindowRequest> windows) {}
    /** A free stretch of a weekday, at least one session long. */
    record Window(int dayOfWeek, LocalTime startTime, LocalTime endTime) {}
    record SessionTime(UUID tutorId, OffsetDateTime startAt, OffsetDateTime endAt) {}
    record Slot(UUID id, UUID tutorId, int dayOfWeek, LocalTime startTime, LocalTime endTime, Mode mode,
                LocalDate effectiveFrom, LocalDate effectiveTo) {}

    private static final String SELECT = "SELECT id, tutor_id, day_of_week, start_time, end_time, mode, effective_from, effective_to FROM availability_slots";

    private final JdbcClient jdbc;
    private final TutorClient tutors;

    AvailabilityController(JdbcClient jdbc, TutorClient tutors) {
        this.jdbc = jdbc;
        this.tutors = tutors;
    }

    @GetMapping("/api/v1/bookings/availability/me")
    @PreAuthorize("hasRole('TUTOR')")
    List<Slot> mine(@AuthenticationPrincipal Jwt jwt) {
        return forTutor(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/api/v1/bookings/availability/tutors/{tutorId}")
    List<Slot> forTutor(@PathVariable UUID tutorId) {
        return jdbc.sql(SELECT + " WHERE tutor_id = ? AND (effective_to IS NULL OR effective_to >= ?) ORDER BY day_of_week, start_time")
                .params(tutorId, Schedule.today()).query(Slot.class).list();
    }

    /** Calendar editor: replaces all of the tutor's windows (any length >= 120 min inside 08:00-22:00) as FLEX, effective today. */
    @PutMapping("/api/v1/bookings/availability/me")
    @PreAuthorize("hasRole('TUTOR')")
    @Transactional
    List<Slot> replace(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WindowsRequest r) {
        UUID tutorId = UUID.fromString(jwt.getSubject());
        List<WindowRequest> windows = r.windows().stream()
                .sorted(Comparator.comparingInt(WindowRequest::dayOfWeek).thenComparing(WindowRequest::startTime)).toList();
        for (int i = 0; i < windows.size(); i++) {
            WindowRequest w = windows.get(i);
            if (w.startTime().isBefore(Schedule.DAY_START) || w.endTime().isAfter(Schedule.DAY_END)
                    || Duration.between(w.startTime(), w.endTime()).compareTo(Schedule.SESSION_LENGTH) < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "Khoảng nhận dạy phải trong 08:00-22:00 và dài ít nhất 120 phút", w);
            }
            if (i > 0 && windows.get(i - 1).dayOfWeek().equals(w.dayOfWeek()) && w.startTime().isBefore(windows.get(i - 1).endTime())) {
                throw new ApiException(HttpStatus.CONFLICT, "AVAILABILITY_OVERLAP", "Hai khoảng rảnh trong cùng ngày bị chồng nhau", w);
            }
        }
        tutors.requireApproved(tutorId);
        jdbc.sql("DELETE FROM availability_slots WHERE tutor_id = ?").param(tutorId).update();
        for (WindowRequest w : windows) {
            jdbc.sql("INSERT INTO availability_slots (id, tutor_id, day_of_week, start_time, end_time, mode, effective_from) VALUES (?, ?, ?, ?, ?, 'FLEX', ?)")
                    .params(UUID.randomUUID(), tutorId, w.dayOfWeek(), w.startTime(), w.endTime(), Schedule.today()).update();
        }
        return forTutor(tutorId);
    }

    /**
     * What is still bookable in [fromDate, fromDate + weeks): the tutor's windows minus every active session of the tutor
     * (with the 60-minute gap on both sides) and of the signed-in caller, keeping only stretches >= 120 minutes.
     * Plans repeat weekly, so a session on one date blocks that weekday time for the whole period.
     */
    @GetMapping("/api/v1/bookings/availability/tutors/{tutorId}/free")
    List<Window> free(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tutorId,
                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                      @RequestParam(defaultValue = "4") @Min(1) @Max(26) int weeks,
                      @RequestParam(required = false) Mode mode) {
        UUID me = jwt == null ? tutorId : UUID.fromString(jwt.getSubject());
        LocalDate toDate = fromDate.plusWeeks(weeks);
        List<SessionTime> sessions = jdbc.sql("""
                SELECT tutor_id, start_at, end_at FROM sessions
                WHERE status NOT IN ('CANCELLED', 'RELEASED') AND (tutor_id = :tutor OR learner_id = :me) AND start_at < :to AND end_at > :from""")
                .param("tutor", tutorId).param("me", me)
                .param("from", fromDate.atStartOfDay(Schedule.ZONE).toOffsetDateTime())
                .param("to", toDate.atStartOfDay(Schedule.ZONE).toOffsetDateTime())
                .query(SessionTime.class).list();
        Map<Integer, List<int[]>> blocked = new HashMap<>();
        for (SessionTime s : sessions) {
            int gap = s.tutorId().equals(tutorId) ? (int) Schedule.GAP.toMinutes() : 0;
            LocalDateTime start = s.startAt().atZoneSameInstant(Schedule.ZONE).toLocalDateTime();
            LocalDateTime end = s.endAt().atZoneSameInstant(Schedule.ZONE).toLocalDateTime();
            blocked.computeIfAbsent(start.getDayOfWeek().getValue(), d -> new ArrayList<>())
                    .add(new int[] {minutes(start.toLocalTime()) - gap, minutes(end.toLocalTime()) + gap});
        }
        int min = (int) Schedule.SESSION_LENGTH.toMinutes();
        List<Window> free = new ArrayList<>();
        for (Slot w : forTutor(tutorId)) {
            if (mode != null && w.mode() != Mode.FLEX && w.mode() != mode) continue;
            if (w.effectiveFrom().isAfter(fromDate) || (w.effectiveTo() != null && w.effectiveTo().isBefore(toDate.minusDays(1)))) continue;
            List<int[]> cuts = blocked.getOrDefault(w.dayOfWeek(), List.of()).stream().sorted(Comparator.comparingInt(c -> c[0])).toList();
            int cursor = minutes(w.startTime());
            int end = minutes(w.endTime());
            for (int[] c : cuts) {
                if (c[1] <= cursor || c[0] >= end) continue;
                if (c[0] - cursor >= min) free.add(window(w.dayOfWeek(), cursor, c[0]));
                cursor = Math.max(cursor, c[1]);
            }
            if (end - cursor >= min) free.add(window(w.dayOfWeek(), cursor, end));
        }
        return free;
    }

    private static int minutes(LocalTime t) { return t.getHour() * 60 + t.getMinute(); }

    private static Window window(int day, int from, int to) { return new Window(day, LocalTime.of(from / 60, from % 60), LocalTime.of(to / 60, to % 60)); }

    @PostMapping("/api/v1/bookings/availability")
    @PreAuthorize("hasRole('TUTOR')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Slot create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SlotRequest r) {
        UUID tutorId = UUID.fromString(jwt.getSubject());
        if (r.startTime().isBefore(Schedule.DAY_START) || r.endTime().isAfter(Schedule.DAY_END)
                || Duration.between(r.startTime(), r.endTime()).compareTo(Schedule.SESSION_LENGTH) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "Khoảng nhận dạy phải trong 08:00-22:00 và dài ít nhất 120 phút");
        }
        if (r.effectiveFrom().isBefore(Schedule.today()) || (r.effectiveTo() != null && r.effectiveTo().isBefore(r.effectiveFrom()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "Ngày áp dụng không hợp lệ");
        }
        tutors.requireApproved(tutorId);
        LocalDate farFuture = LocalDate.of(9999, 12, 31);
        List<Slot> overlapping = jdbc.sql(SELECT + " " + """
                 WHERE tutor_id = :tutor AND day_of_week = :day AND start_time < :end AND end_time > :start
                   AND effective_from <= :to AND coalesce(effective_to, :far) >= :from""")
                .param("tutor", tutorId).param("day", r.dayOfWeek()).param("start", r.startTime()).param("end", r.endTime())
                .param("from", r.effectiveFrom()).param("to", r.effectiveTo() != null ? r.effectiveTo() : farFuture).param("far", farFuture)
                .query(Slot.class).list();
        if (!overlapping.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "AVAILABILITY_OVERLAP", "Trùng với khoảng nhận dạy đã khai", overlapping);
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO availability_slots (id, tutor_id, day_of_week, start_time, end_time, mode, effective_from, effective_to) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, tutorId, r.dayOfWeek(), r.startTime(), r.endTime(), r.mode().name(), r.effectiveFrom(), r.effectiveTo()).update();
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(Slot.class).single();
    }

    /** Closing a window does not touch sessions already held or booked. */
    @DeleteMapping("/api/v1/bookings/availability/{id}")
    @PreAuthorize("hasRole('TUTOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        int deleted = jdbc.sql("DELETE FROM availability_slots WHERE id = ? AND tutor_id = ?").params(id, UUID.fromString(jwt.getSubject())).update();
        if (deleted == 0) throw new ApiException(HttpStatus.NOT_FOUND, "AVAILABILITY_NOT_FOUND", "Không tìm thấy khoảng nhận dạy");
    }

    /**
     * For tutor-service search: tutors whose window covers [from, to] on that weekday for the whole date range.
     * Information at query time only; booking re-checks when the plan is proposed.
     */
    @GetMapping("/api/v1/bookings/internal/availability/tutors")
    List<UUID> matching(@RequestParam @Min(1) @Max(7) int day,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime from,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime to,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return jdbc.sql("""
                SELECT DISTINCT tutor_id FROM availability_slots
                WHERE day_of_week = :day AND start_time <= :from AND end_time >= :to
                  AND effective_from <= :fromDate AND (effective_to IS NULL OR effective_to >= :toDate)""")
                .param("day", day).param("from", from).param("to", to).param("fromDate", fromDate).param("toDate", toDate)
                .query(UUID.class).list();
    }
}
