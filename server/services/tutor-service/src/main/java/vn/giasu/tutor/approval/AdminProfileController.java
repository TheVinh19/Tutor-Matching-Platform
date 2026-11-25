package vn.giasu.tutor.approval;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.giasu.common.web.ApiException;
import vn.giasu.tutor.experience.ExpLedger;
import vn.giasu.tutor.profiles.ProfileStore;
import vn.giasu.tutor.profiles.ProfileStore.HistoryEntry;
import vn.giasu.tutor.profiles.ProfileStore.Profile;
import vn.giasu.tutor.profiles.ProfileStore.Status;

@RestController
@RequestMapping("/api/v1/tutors/admin/profiles")
@PreAuthorize("hasRole('ADMIN')")
class AdminProfileController {
    record RejectRequest(@NotBlank @Size(max = 1000) String reason) {}
    /** entryRank2: P09 entry exception, the admin places the tutor at rank 2 with 1,200 starting EXP (evidence checked by the admin). */
    record ApproveRequest(@Size(max = 1000) String note, Boolean entryRank2) {}
    record ProfileDetail(Profile profile, List<HistoryEntry> history) {}

    private final ProfileStore store;
    private final JdbcClient jdbc;
    private final ExpLedger ledger;

    AdminProfileController(ProfileStore store, JdbcClient jdbc, ExpLedger ledger) {
        this.store = store;
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    @GetMapping
    List<Profile> list(@RequestParam(defaultValue = "PENDING") Status status) {
        return store.findByStatus(status);
    }

    @GetMapping("/{accountId}")
    ProfileDetail detail(@PathVariable UUID accountId) {
        Profile profile = store.find(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Không tìm thấy hồ sơ"));
        return new ProfileDetail(profile, store.history(accountId));
    }

    @PostMapping("/{accountId}/approve")
    @Transactional
    ProfileDetail approve(@PathVariable UUID accountId, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody(required = false) ApproveRequest request) {
        ProfileDetail approved = decide(accountId, Status.APPROVED, jwt, request == null ? null : request.note());
        if (request != null && Boolean.TRUE.equals(request.entryRank2()) && approved.profile().rank() == 1) {
            UUID admin = UUID.fromString(jwt.getSubject());
            jdbc.sql("UPDATE tutor_profiles SET rank = 2, entry_rank2 = true, rank_since = now(), updated_at = now() WHERE account_id = ?")
                    .param(accountId).update();
            ledger.add(accountId, "initial", "INITIAL", 1200, null, null, "Hạng 2 đầu vào", admin, null);
            jdbc.sql("INSERT INTO rank_decisions (account_id, from_rank, to_rank, reason, actor_id) VALUES (?, 1, 2, ?, ?)")
                    .params(accountId, request.note() == null || request.note().isBlank() ? "Hạng 2 đầu vào" : request.note().strip(), admin).update();
            return detail(accountId);
        }
        return approved;
    }

    @PostMapping("/{accountId}/reject")
    @Transactional
    ProfileDetail reject(@PathVariable UUID accountId, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RejectRequest request) {
        return decide(accountId, Status.REJECTED, jwt, request.reason().strip());
    }

    private ProfileDetail decide(UUID accountId, Status to, Jwt jwt, String reason) {
        detail(accountId); // 404 before 409
        if (!store.transition(accountId, List.of(Status.PENDING), to, UUID.fromString(jwt.getSubject()), reason)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS", "Chỉ duyệt/từ chối được hồ sơ đang chờ duyệt");
        }
        return detail(accountId);
    }
}
