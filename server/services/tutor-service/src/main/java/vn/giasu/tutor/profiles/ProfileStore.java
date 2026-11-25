package vn.giasu.tutor.profiles;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read/write of tutor_profiles + subjects + areas. Plain SQL: the search needs dynamic EXISTS filters anyway. */
@Repository
public class ProfileStore {
    public enum Mode { ONLINE, OFFLINE, FLEX }
    public enum Status { DRAFT, PENDING, APPROVED, REJECTED }

    public record SubjectLevel(String subjectCode, String levelCode) {}

    public record Profile(UUID accountId, String fullName, String bio, String experience, Mode teachingMode, int rank,
                          Status status, String rejectReason, Instant submittedAt, Instant reviewedAt,
                          List<SubjectLevel> subjects, List<String> areaCodes) {
        Profile withDetails(List<SubjectLevel> subjects, List<String> areaCodes) {
            return new Profile(accountId, fullName, bio, experience, teachingMode, rank, status, rejectReason,
                    submittedAt, reviewedAt, subjects, areaCodes);
        }
    }

    private static final String SELECT = """
            SELECT account_id, full_name, bio, experience, teaching_mode, rank, status, reject_reason, submitted_at, reviewed_at
            FROM tutor_profiles""";

    private final JdbcClient jdbc;

    public ProfileStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<Profile> find(UUID accountId) {
        return jdbc.sql(SELECT + " WHERE account_id = ?").param(accountId).query(this::row).optional()
                .map(p -> p.withDetails(subjects(List.of(accountId)).getOrDefault(accountId, List.of()),
                        areas(List.of(accountId)).getOrDefault(accountId, List.of())));
    }

    public List<Profile> findByStatus(Status status) {
        List<Profile> base = jdbc.sql(SELECT + " WHERE status = ? ORDER BY submitted_at NULLS LAST, account_id")
                .param(status.name()).query(this::row).list();
        return withDetails(base);
    }

    public List<Profile> withDetails(List<Profile> profiles) {
        List<UUID> ids = profiles.stream().map(Profile::accountId).toList();
        Map<UUID, List<SubjectLevel>> subjects = subjects(ids);
        Map<UUID, List<String>> areas = areas(ids);
        return profiles.stream()
                .map(p -> p.withDetails(subjects.getOrDefault(p.accountId(), List.of()), areas.getOrDefault(p.accountId(), List.of())))
                .toList();
    }

    Map<UUID, List<SubjectLevel>> subjects(Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return jdbc.sql("SELECT account_id, subject_code, level_code FROM tutor_subjects WHERE account_id IN (:ids) ORDER BY subject_code, level_code")
                .param("ids", ids)
                .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), new SubjectLevel(rs.getString(2), rs.getString(3))))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    Map<UUID, List<String>> areas(Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return jdbc.sql("SELECT account_id, area_code FROM tutor_areas WHERE account_id IN (:ids) ORDER BY area_code")
                .param("ids", ids)
                .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    public void upsert(UUID accountId, String fullName, String bio, String experience, Mode mode,
                       List<SubjectLevel> subjects, List<String> areaCodes) {
        jdbc.sql("""
                INSERT INTO tutor_profiles (account_id, full_name, bio, experience, teaching_mode, status)
                VALUES (:id, :name, :bio, :exp, :mode, 'DRAFT')
                ON CONFLICT (account_id) DO UPDATE SET full_name = :name, bio = :bio, experience = :exp,
                    teaching_mode = :mode, status = 'DRAFT', updated_at = now()""")
                .param("id", accountId).param("name", fullName).param("bio", bio).param("exp", experience)
                .param("mode", mode.name()).update();
        jdbc.sql("DELETE FROM tutor_subjects WHERE account_id = ?").param(accountId).update();
        jdbc.sql("DELETE FROM tutor_areas WHERE account_id = ?").param(accountId).update();
        subjects.stream().distinct().forEach(s -> jdbc.sql("INSERT INTO tutor_subjects (account_id, subject_code, level_code) VALUES (?, ?, ?)")
                .params(accountId, s.subjectCode(), s.levelCode()).update());
        areaCodes.stream().distinct().forEach(a -> jdbc.sql("INSERT INTO tutor_areas (account_id, area_code) VALUES (?, ?)")
                .params(accountId, a).update());
    }

    /** Conditional status change; returns false if the profile was not in one of the expected statuses (race-safe). */
    public boolean transition(UUID accountId, Collection<Status> from, Status to, UUID actorId, String reason) {
        String timestamps = to == Status.PENDING
                ? "submitted_at = now(), reject_reason = NULL"
                : "reviewed_at = now(), reviewed_by = :actor, reject_reason = :reason"
                        // Days at the current rank count from the first approval (P10).
                        + (to == Status.APPROVED ? ", rank_since = coalesce(rank_since, now())" : "");
        int updated = jdbc.sql("UPDATE tutor_profiles SET status = :to, updated_at = now(), " + timestamps
                        + " WHERE account_id = :id AND status IN (:from)")
                .param("to", to.name()).param("id", accountId).param("actor", actorId).param("reason", reason)
                .param("from", from.stream().map(Enum::name).toList())
                .update();
        if (updated == 0) return false;
        String action = switch (to) { case PENDING -> "SUBMIT"; case APPROVED -> "APPROVE"; default -> "REJECT"; };
        jdbc.sql("INSERT INTO approval_history (account_id, action, reason, actor_id) VALUES (?, ?, ?, ?)")
                .params(accountId, action, reason, actorId).update();
        return true;
    }

    public record HistoryEntry(String action, String reason, UUID actorId, java.time.OffsetDateTime createdAt) {}

    public List<HistoryEntry> history(UUID accountId) {
        return jdbc.sql("SELECT action, reason, actor_id, created_at FROM approval_history WHERE account_id = ? ORDER BY id")
                .param(accountId).query(HistoryEntry.class).list();
    }

    /** Returns the codes in the request that do not exist in the catalog. */
    public List<String> unknownCodes(List<SubjectLevel> subjects, List<String> areaCodes) {
        List<String> unknown = new java.util.ArrayList<>();
        unknown.addAll(missing("subjects", subjects.stream().map(SubjectLevel::subjectCode).toList()));
        unknown.addAll(missing("levels", subjects.stream().map(SubjectLevel::levelCode).toList()));
        unknown.addAll(missing("areas", areaCodes));
        return unknown;
    }

    /** Returns "SUBJECT|TIEU_HOC" pairs whose subject is not allowed at primary level. */
    public List<String> notPrimarySchool(List<SubjectLevel> subjects) {
        List<String> codes = subjects.stream().filter(s -> "TIEU_HOC".equals(s.levelCode())).map(SubjectLevel::subjectCode).distinct().toList();
        if (codes.isEmpty()) return List.of();
        return jdbc.sql("SELECT code FROM subjects WHERE code IN (:codes) AND NOT primary_school").param("codes", codes)
                .query(String.class).list().stream().map(c -> c + "|TIEU_HOC").toList();
    }

    private List<String> missing(String table, List<String> codes) {
        if (codes.isEmpty()) return List.of();
        List<String> found = jdbc.sql("SELECT code FROM " + table + " WHERE code IN (:codes)").param("codes", codes).query(String.class).list();
        return codes.stream().filter(c -> !found.contains(c)).distinct().toList();
    }

    Profile row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Profile(rs.getObject("account_id", UUID.class), rs.getString("full_name"), rs.getString("bio"),
                rs.getString("experience"), Mode.valueOf(rs.getString("teaching_mode")), rs.getInt("rank"),
                Status.valueOf(rs.getString("status")), rs.getString("reject_reason"),
                instant(rs, "submitted_at"), instant(rs, "reviewed_at"), List.of(), List.of());
    }

    static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
