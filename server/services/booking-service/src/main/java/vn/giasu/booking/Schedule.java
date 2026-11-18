package vn.giasu.booking;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/** Fixed schedule rules from the requirements (CA, DK, P02/P04/P08). Times are Vietnam local; DB stores timestamptz. */
public final class Schedule {
    private Schedule() {}

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final LocalTime DAY_START = LocalTime.of(8, 0);
    public static final LocalTime DAY_END = LocalTime.of(22, 0);
    public static final Duration SESSION_LENGTH = Duration.ofMinutes(120);
    /** Minimum gap between two sessions of the same tutor. */
    public static final Duration GAP = Duration.ofMinutes(60);
    public static final Duration HOLD = Duration.ofHours(24);
    /** A proposal must be confirmed at least this long before its first session. */
    public static final Duration CUTOFF_BEFORE_FIRST = Duration.ofHours(12);
    public static final Duration CONFIRM_WINDOW = Duration.ofHours(24);
    public static final int DEFAULT_WEEKS = 4;
    public static final int MAX_WEEKS = 26;

    public static LocalDate today() { return LocalDate.now(ZONE); }
}
