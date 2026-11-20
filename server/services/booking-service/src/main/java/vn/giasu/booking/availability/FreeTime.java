package vn.giasu.booking.availability;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.giasu.booking.Schedule;

/** Free-time arithmetic shared by plan booking (weekly) and session rescheduling (one week). */
public final class FreeTime {
    private FreeTime() {}

    /** A free stretch of a weekday, at least one session long. */
    public record Window(int dayOfWeek, LocalTime startTime, LocalTime endTime) {}
    public record Busy(UUID tutorId, OffsetDateTime startAt, OffsetDateTime endAt) {}

    /** Active sessions of the tutor or of the learner overlapping [from, to). */
    public static List<Busy> busy(JdbcClient jdbc, UUID tutorId, UUID learnerId, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                SELECT tutor_id, start_at, end_at FROM sessions
                WHERE status NOT IN ('CANCELLED', 'RELEASED') AND (tutor_id = :tutor OR learner_id = :me) AND start_at < :to AND end_at > :from""")
                .param("tutor", tutorId).param("me", learnerId).param("from", from).param("to", to)
                .query(Busy.class).list();
    }

    /** Busy minutes [from, to) per local weekday; the tutor's own sessions also block the 60-minute gap on both sides. Mutable. */
    public static Map<Integer, List<int[]>> byWeekday(List<Busy> sessions, UUID tutorId) {
        Map<Integer, List<int[]>> blocked = new HashMap<>();
        for (Busy s : sessions) {
            int gap = s.tutorId().equals(tutorId) ? (int) Schedule.GAP.toMinutes() : 0;
            LocalDateTime start = s.startAt().atZoneSameInstant(Schedule.ZONE).toLocalDateTime();
            LocalDateTime end = s.endAt().atZoneSameInstant(Schedule.ZONE).toLocalDateTime();
            blocked.computeIfAbsent(start.getDayOfWeek().getValue(), d -> new ArrayList<>())
                    .add(new int[] {minutes(start.toLocalTime()) - gap, minutes(end.toLocalTime()) + gap});
        }
        return blocked;
    }

    /** What is left of [start, end) on that weekday after the cuts, keeping only stretches >= one session. */
    public static List<Window> remaining(int day, LocalTime start, LocalTime end, List<int[]> cuts) {
        int min = (int) Schedule.SESSION_LENGTH.toMinutes();
        List<Window> free = new ArrayList<>();
        int cursor = minutes(start);
        int stop = minutes(end);
        for (int[] c : cuts.stream().sorted(Comparator.comparingInt(c -> c[0])).toList()) {
            if (c[1] <= cursor || c[0] >= stop) continue;
            if (c[0] - cursor >= min) free.add(window(day, cursor, c[0]));
            cursor = Math.max(cursor, c[1]);
        }
        if (stop - cursor >= min) free.add(window(day, cursor, stop));
        return free;
    }

    public static int minutes(LocalTime t) { return t.getHour() * 60 + t.getMinute(); }

    private static Window window(int day, int from, int to) { return new Window(day, LocalTime.of(from / 60, from % 60), LocalTime.of(to / 60, to % 60)); }
}
