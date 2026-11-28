package vn.giasu.tutor.profiles;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.common.web.ApiException;
import vn.giasu.tutor.experience.ExpLedger;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.giasu.tutor.ranks.RankFlags;
import vn.giasu.tutor.ranks.RankRules;
import vn.giasu.tutor.profiles.ProfileStore.Mode;
import vn.giasu.tutor.profiles.ProfileStore.Profile;
import vn.giasu.tutor.profiles.ProfileStore.Status;
import vn.giasu.tutor.profiles.ProfileStore.SubjectLevel;

@RestController
class TutorProfileController {
    record SubjectLevelRequest(@NotBlank @Size(max = 32) String subjectCode, @NotBlank @Size(max = 32) String levelCode) {}
    record ProfileRequest(@NotBlank @Size(max = 100) String fullName,
                          @NotNull @Size(max = 2000) String bio,
                          @NotNull @Size(max = 2000) String experience,
                          @NotNull Mode teachingMode,
                          @NotNull @Size(max = 30) List<@Valid @NotNull SubjectLevelRequest> subjects,
                          @NotNull @Size(max = 20) List<@NotBlank @Size(max = 32) String> areaCodes) {}

    /**
     * What anyone may see: approved profiles with an approved video, no review notes. P12 public numbers: taught sessions, rank
     * completion rate (null = no data yet), verified tutor-fault failures; "Mới tham gia" for 30 days after the first approval.
     */
    record PublicProfile(UUID accountId, String fullName, String bio, String experience, Mode teachingMode, int rank,
                         List<SubjectLevel> subjects, List<String> areaCodes, int taughtSessions, Double completionRate, int tutorFaultFailed,
                         List<String> videos, boolean newcomer) {}

    /** For booking-service: is this tutor allowed to take learners. */
    record InternalProfile(UUID accountId, String fullName, Status status, Mode teachingMode, int rank, List<SubjectLevel> subjects,
                           boolean rankUnderReview) {}

    private final ProfileStore store;
    private final ExpLedger ledger;
    private final RankFlags flags;
    private final JdbcClient jdbc;

    TutorProfileController(ProfileStore store, ExpLedger ledger, RankFlags flags, JdbcClient jdbc) {
        this.store = store;
        this.ledger = ledger;
        this.flags = flags;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/tutors/me/profile")
    @PreAuthorize("hasRole('TUTOR')")
    Profile myProfile(@AuthenticationPrincipal Jwt jwt) {
        return store.find(id(jwt)).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Chưa có hồ sơ"));
    }

    // shortcut: approved/pending profiles are locked (409); re-review of edits on an approved profile comes later.
    @PutMapping("/api/v1/tutors/me/profile")
    @PreAuthorize("hasRole('TUTOR')")
    @Transactional
    Profile saveMyProfile(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileRequest request) {
        UUID accountId = id(jwt);
        store.find(accountId).filter(p -> p.status() == Status.PENDING || p.status() == Status.APPROVED).ifPresent(p -> {
            throw new ApiException(HttpStatus.CONFLICT, "PROFILE_LOCKED", "Hồ sơ đang chờ duyệt hoặc đã duyệt, không sửa được");
        });
        List<SubjectLevel> subjects = request.subjects().stream().map(s -> new SubjectLevel(s.subjectCode(), s.levelCode())).toList();
        List<String> unknown = store.unknownCodes(subjects, request.areaCodes());
        if (!unknown.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_CATALOG_CODE", "Mã danh mục không tồn tại", unknown);
        }
        List<String> notPrimary = store.notPrimarySchool(subjects);
        if (!notPrimary.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LEVEL_NOT_ALLOWED", "Tiểu học chỉ nhận Toán, Ngữ văn, Tiếng Anh", notPrimary);
        }
        store.upsert(accountId, request.fullName().strip(), request.bio().strip(), request.experience().strip(),
                request.teachingMode(), subjects, request.areaCodes());
        return store.find(accountId).orElseThrow();
    }

    @PostMapping("/api/v1/tutors/me/profile/submit")
    @PreAuthorize("hasRole('TUTOR')")
    @Transactional
    Profile submit(@AuthenticationPrincipal Jwt jwt) {
        UUID accountId = id(jwt);
        Profile profile = myProfile(jwt);
        if (profile.subjects().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROFILE_INCOMPLETE", "Cần chọn ít nhất một môn/cấp học");
        }
        if (!store.transition(accountId, List.of(Status.DRAFT, Status.REJECTED), Status.PENDING, accountId, null)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS", "Hồ sơ không ở trạng thái nháp hoặc bị từ chối");
        }
        return store.find(accountId).orElseThrow();
    }

    @GetMapping("/api/v1/tutors/{accountId}")
    PublicProfile publicProfile(@PathVariable UUID accountId) {
        Profile p = store.find(accountId).filter(x -> x.status() == Status.APPROVED)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TUTOR_NOT_FOUND", "Không tìm thấy gia sư"));
        List<String> videos = jdbc.sql("SELECT url FROM tutor_videos WHERE account_id = ? AND status = 'APPROVED' ORDER BY created_at")
                .param(accountId).query(String.class).list();
        if (videos.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "TUTOR_NOT_FOUND", "Không tìm thấy gia sư"); // P12: not public yet
        boolean newcomer = jdbc.sql("""
                SELECT coalesce(min(created_at) > now() - interval '30 days', false) FROM approval_history WHERE account_id = ? AND action = 'APPROVE'""")
                .param(accountId).query(Boolean.class).single();
        ExpLedger.Stats s = ledger.stats(accountId);
        return new PublicProfile(p.accountId(), p.fullName(), p.bio(), p.experience(), p.teachingMode(), p.rank(), p.subjects(), p.areaCodes(),
                s.taughtSessions(), RankRules.completionRate(s.taughtSessions(), s.tutorFaultFailed()), s.tutorFaultFailed(), videos, newcomer);
    }

    @GetMapping("/api/v1/tutors/internal/profiles/{accountId}")
    InternalProfile internalProfile(@PathVariable UUID accountId) {
        return store.find(accountId)
                .map(p -> new InternalProfile(p.accountId(), p.fullName(), p.status(), p.teachingMode(), p.rank(), p.subjects(),
                        flags.underReview(accountId)))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TUTOR_NOT_FOUND", "Không tìm thấy gia sư"));
    }

    static UUID id(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
