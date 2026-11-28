package vn.giasu.tutor.profiles;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.common.web.ApiException;
import vn.giasu.tutor.profiles.ProfileStore.Profile;
import vn.giasu.tutor.profiles.ProfileStore.Status;
import vn.giasu.tutor.profiles.ProfileStore.SubjectLevel;

/**
 * Parts of a tutor profile reviewed one by one: teaching videos (P12, YouTube links, at most 3, ≥1 approved to be public),
 * subjects added after approval (P09, reviewed separately), and rank 2 entry evidence links (degree + ≥1 year experience).
 */
@RestController
class ProfileExtrasController {
    enum EvidenceKind { DEGREE, EXPERIENCE }

    record VideoRequest(@NotBlank @Size(max = 300)
                        @Pattern(regexp = "https://(www\\.|m\\.)?(youtube\\.com/watch\\?v=|youtu\\.be/)[\\w-]{11}\\S*", message = "phải là link YouTube")
                        String url) {}
    record SubjectRequest(@NotBlank @Size(max = 32) String subjectCode, @NotBlank @Size(max = 32) String levelCode) {}
    record SubjectDecision(@NotNull UUID accountId, @NotBlank String subjectCode, @NotBlank String levelCode, @Size(max = 1000) String reason) {}
    record EvidenceRequest(@NotNull EvidenceKind kind, @NotBlank @Size(max = 500) @Pattern(regexp = "https://\\S+", message = "phải là link https") String url,
                           @Size(max = 1000) String note) {}
    record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}

    record Video(UUID id, UUID accountId, String url, String status, String rejectReason, OffsetDateTime reviewedAt, OffsetDateTime createdAt) {}
    record SubjectItem(UUID accountId, String subjectCode, String levelCode, String status, String rejectReason) {}
    record Evidence(long id, String kind, String url, String note, OffsetDateTime createdAt) {}
    record Extras(List<Video> videos, List<SubjectItem> subjectRequests, List<Evidence> evidence) {}
    record Queue(List<Video> videos, List<SubjectItem> subjects) {}

    static final int MAX_VIDEOS = 3;
    private static final String VIDEO = "SELECT id, account_id, url, status, reject_reason, reviewed_at, created_at FROM tutor_videos";
    private static final String SUBJECT = "SELECT account_id, subject_code, level_code, status, reject_reason FROM tutor_subjects";

    private final JdbcClient jdbc;
    private final ProfileStore store;

    ProfileExtrasController(JdbcClient jdbc, ProfileStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    @GetMapping("/api/v1/tutors/me/extras")
    @PreAuthorize("hasRole('TUTOR')")
    Extras mine(@AuthenticationPrincipal Jwt jwt) {
        return extras(profile(TutorProfileController.id(jwt)).accountId());
    }

    @PostMapping("/api/v1/tutors/me/videos")
    @PreAuthorize("hasRole('TUTOR')")
    @Transactional
    Extras addVideo(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VideoRequest r) {
        UUID me = lockedProfile(TutorProfileController.id(jwt));
        long live = jdbc.sql("SELECT count(*) FROM tutor_videos WHERE account_id = ? AND status <> 'REJECTED'").param(me).query(Long.class).single();
        if (live >= MAX_VIDEOS) throw new ApiException(HttpStatus.CONFLICT, "VIDEO_LIMIT", "Tối đa 3 video");
        jdbc.sql("INSERT INTO tutor_videos (id, account_id, url) VALUES (?, ?, ?)").params(UUID.randomUUID(), me, r.url().strip()).update();
        return extras(me);
    }

    /** Only once the profile is approved; before that, subjects are edited in the profile and approved with it. */
    @PostMapping("/api/v1/tutors/me/subjects")
    @PreAuthorize("hasRole('TUTOR')")
    @Transactional
    Extras requestSubject(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SubjectRequest r) {
        UUID me = lockedProfile(TutorProfileController.id(jwt));
        if (profile(me).status() != Status.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "PROFILE_NOT_APPROVED", "Hồ sơ chưa duyệt: thêm môn trong phần hồ sơ");
        }
        List<SubjectLevel> subject = List.of(new SubjectLevel(r.subjectCode(), r.levelCode()));
        if (!store.unknownCodes(subject, List.of()).isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_CATALOG_CODE", "Mã danh mục không tồn tại");
        if (!store.notPrimarySchool(subject).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LEVEL_NOT_ALLOWED", "Tiểu học chỉ nhận Toán, Ngữ văn, Tiếng Anh");
        }
        String current = jdbc.sql("SELECT status FROM tutor_subjects WHERE account_id = ? AND subject_code = ? AND level_code = ?")
                .params(me, r.subjectCode(), r.levelCode()).query(String.class).optional().orElse(null);
        if ("APPROVED".equals(current) || "PENDING".equals(current)) {
            throw new ApiException(HttpStatus.CONFLICT, "SUBJECT_EXISTS", "Môn/cấp này đã có hoặc đang chờ duyệt");
        }
        jdbc.sql("""
                INSERT INTO tutor_subjects (account_id, subject_code, level_code, status) VALUES (?, ?, ?, 'PENDING')
                ON CONFLICT (account_id, subject_code, level_code) DO UPDATE SET status = 'PENDING', reject_reason = NULL""")
                .params(me, r.subjectCode(), r.levelCode()).update();
        return extras(me);
    }

    @PostMapping("/api/v1/tutors/me/evidence")
    @PreAuthorize("hasRole('TUTOR')")
    @Transactional
    Extras addEvidence(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EvidenceRequest r) {
        UUID me = lockedProfile(TutorProfileController.id(jwt));
        jdbc.sql("INSERT INTO tutor_evidence (account_id, kind, url, note) VALUES (?, ?, ?, ?)")
                .params(me, r.kind().name(), r.url().strip(), r.note() == null || r.note().isBlank() ? null : r.note().strip()).update();
        return extras(me);
    }

    @GetMapping("/api/v1/tutors/admin/profiles/{accountId}/extras")
    @PreAuthorize("hasRole('ADMIN')")
    Extras ofTutor(@PathVariable UUID accountId) {
        return extras(profile(accountId).accountId());
    }

    @GetMapping("/api/v1/tutors/admin/review-queue")
    @PreAuthorize("hasRole('ADMIN')")
    Queue queue() {
        return new Queue(jdbc.sql(VIDEO + " WHERE status = 'PENDING' ORDER BY created_at").query(Video.class).list(),
                jdbc.sql(SUBJECT + " WHERE status = 'PENDING' ORDER BY account_id, subject_code").query(SubjectItem.class).list());
    }

    @PostMapping("/api/v1/tutors/admin/videos/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Extras approveVideo(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return decideVideo(jwt, id, "APPROVED", null);
    }

    @PostMapping("/api/v1/tutors/admin/videos/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Extras rejectVideo(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return decideVideo(jwt, id, "REJECTED", r.reason().strip());
    }

    @PostMapping("/api/v1/tutors/admin/subjects/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Extras approveSubject(@Valid @RequestBody SubjectDecision r) {
        return decideSubject(r, "APPROVED", null);
    }

    @PostMapping("/api/v1/tutors/admin/subjects/reject")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Extras rejectSubject(@Valid @RequestBody SubjectDecision r) {
        if (r.reason() == null || r.reason().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Cần lý do từ chối");
        return decideSubject(r, "REJECTED", r.reason().strip());
    }

    private Extras decideVideo(Jwt jwt, UUID id, String to, String reason) {
        Video v = jdbc.sql(VIDEO + " WHERE id = ? FOR UPDATE").param(id).query(Video.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VIDEO_NOT_FOUND", "Không tìm thấy video"));
        if (!"PENDING".equals(v.status())) throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS", "Video đã được xử lý");
        jdbc.sql("UPDATE tutor_videos SET status = ?, reject_reason = ?, reviewed_by = ?, reviewed_at = now() WHERE id = ?")
                .params(to, reason, UUID.fromString(jwt.getSubject()), id).update();
        return extras(v.accountId());
    }

    private Extras decideSubject(SubjectDecision r, String to, String reason) {
        int updated = jdbc.sql("UPDATE tutor_subjects SET status = ?, reject_reason = ? WHERE account_id = ? AND subject_code = ? AND level_code = ? AND status = 'PENDING'")
                .params(to, reason, r.accountId(), r.subjectCode(), r.levelCode()).update();
        if (updated == 0) throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS", "Không có môn đang chờ duyệt này");
        return extras(r.accountId());
    }

    private Extras extras(UUID accountId) {
        return new Extras(jdbc.sql(VIDEO + " WHERE account_id = ? ORDER BY created_at").param(accountId).query(Video.class).list(),
                jdbc.sql(SUBJECT + " WHERE account_id = ? AND status <> 'APPROVED' ORDER BY subject_code").param(accountId).query(SubjectItem.class).list(),
                jdbc.sql("SELECT id, kind, url, note, created_at FROM tutor_evidence WHERE account_id = ? ORDER BY id").param(accountId)
                        .query(Evidence.class).list());
    }

    private Profile profile(UUID accountId) {
        return store.find(accountId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Chưa có hồ sơ"));
    }

    /** Serializes concurrent adds for one tutor so the 3-video limit holds. */
    private UUID lockedProfile(UUID accountId) {
        return jdbc.sql("SELECT account_id FROM tutor_profiles WHERE account_id = ? FOR UPDATE").param(accountId).query(UUID.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Chưa có hồ sơ"));
    }
}
