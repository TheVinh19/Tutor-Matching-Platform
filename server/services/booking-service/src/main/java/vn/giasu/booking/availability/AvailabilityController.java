package vn.giasu.booking.availability;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
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
