package vn.giasu.tutor.search;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.common.web.ApiException;
import vn.giasu.tutor.BookingClient;
import vn.giasu.tutor.profiles.ProfileStore;
import vn.giasu.tutor.profiles.ProfileStore.Mode;
import vn.giasu.tutor.profiles.ProfileStore.Profile;

/**
 * P01 search: many subjects (match at least one, each tutor once), level, ranks, mode, area, weekly schedule.
 * No price filter and no ranking algorithm: results are ordered by name.
 */
@RestController
class SearchController {
    record Item(UUID accountId, String fullName, String bio, Mode teachingMode, int rank,
                List<ProfileStore.SubjectLevel> subjects, List<String> areaCodes) {}
    record Page(List<Item> items, int page, int size, long total) {}

    private final JdbcClient jdbc;
    private final ProfileStore store;
    private final BookingClient booking;

    SearchController(JdbcClient jdbc, ProfileStore store, BookingClient booking) {
        this.jdbc = jdbc;
        this.store = store;
        this.booking = booking;
    }

    @GetMapping("/api/v1/tutors/search")
    Page search(@RequestParam(required = false) List<String> subjects,
                @RequestParam(required = false) String level,
                @RequestParam(required = false) List<Integer> ranks,
                @RequestParam(required = false) Mode mode,
                @RequestParam(required = false) String area,
                @RequestParam(required = false) @Min(1) @Max(7) Integer day,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime from,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime to,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
                @RequestParam(defaultValue = "0") @Min(0) int page,
                @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        StringBuilder where = new StringBuilder(" WHERE p.status = 'APPROVED'");
        List<Object[]> params = new ArrayList<>();

        if (subjects != null && !subjects.isEmpty()) {
            where.append(" AND EXISTS (SELECT 1 FROM tutor_subjects s WHERE s.account_id = p.account_id AND s.subject_code IN (:subjects)")
                    .append(level != null ? " AND s.level_code = :level)" : ")");
            params.add(new Object[] {"subjects", subjects});
        } else if (level != null) {
            where.append(" AND EXISTS (SELECT 1 FROM tutor_subjects s WHERE s.account_id = p.account_id AND s.level_code = :level)");
        }
        if (level != null) params.add(new Object[] {"level", level});
        if (ranks != null && !ranks.isEmpty()) {
            where.append(" AND p.rank IN (:ranks)");
            params.add(new Object[] {"ranks", ranks});
        }
        // Learner wants ONLINE -> ONLINE or FLEX tutors; OFFLINE -> OFFLINE or FLEX; FLEX -> anyone.
        if (mode != null && mode != Mode.FLEX) {
            where.append(" AND p.teaching_mode IN (:modes)");
            params.add(new Object[] {"modes", List.of(mode.name(), Mode.FLEX.name())});
        }
        // Area matters only for in-person teaching, so online-only tutors are not excluded by it.
        if (area != null) {
            where.append(" AND (p.teaching_mode = 'ONLINE' OR EXISTS (SELECT 1 FROM tutor_areas a WHERE a.account_id = p.account_id AND a.area_code = :area))");
            params.add(new Object[] {"area", area});
        }
        if (day != null || from != null || to != null) {
            if (day == null || from == null || to == null || !from.isBefore(to)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SCHEDULE_FILTER", "Lọc theo lịch cần đủ thứ, giờ bắt đầu < giờ kết thúc");
            }
            LocalDate start = fromDate != null ? fromDate : LocalDate.now();
            LocalDate end = toDate != null ? toDate : start;
            if (end.isBefore(start)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SCHEDULE_FILTER", "Ngày kết thúc trước ngày bắt đầu");
            }
            List<UUID> available = booking.tutorsAvailable(day, from, to, start, end);
            if (available.isEmpty()) return new Page(List.of(), page, size, 0);
            where.append(" AND p.account_id IN (:available)");
            params.add(new Object[] {"available", available});
        }

        JdbcClient.StatementSpec count = jdbc.sql("SELECT count(*) FROM tutor_profiles p" + where);
        JdbcClient.StatementSpec select = jdbc.sql("SELECT p.account_id, p.full_name, p.bio, p.experience, p.teaching_mode, p.rank, p.status,"
                + " p.reject_reason, p.submitted_at, p.reviewed_at FROM tutor_profiles p" + where
                + " ORDER BY p.full_name, p.account_id LIMIT :limit OFFSET :offset");
        for (Object[] param : params) {
            count = count.param((String) param[0], param[1]);
            select = select.param((String) param[0], param[1]);
        }
        long total = count.query(Long.class).single();
        List<Profile> rows = select.param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new Profile(rs.getObject("account_id", UUID.class), rs.getString("full_name"), rs.getString("bio"),
                        rs.getString("experience"), Mode.valueOf(rs.getString("teaching_mode")), rs.getInt("rank"),
                        ProfileStore.Status.APPROVED, null, null, null, List.of(), List.of()))
                .list();
        List<Item> items = store.withDetails(rows).stream()
                .map(p -> new Item(p.accountId(), p.fullName(), p.bio(), p.teachingMode(), p.rank(), p.subjects(), p.areaCodes()))
                .toList();
        return new Page(items, page, size, total);
    }
}
