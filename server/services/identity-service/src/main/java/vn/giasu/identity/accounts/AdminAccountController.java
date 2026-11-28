package vn.giasu.identity.accounts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.common.web.ApiException;

@RestController
@RequestMapping("/api/v1/identity/admin/accounts")
@PreAuthorize("hasRole('ADMIN')")
class AdminAccountController {
    record AccountView(UUID id, String username, String email, Account.Role role, Account.Status status) {}
    record SupportRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._]{3,50}", message = "3-50 ký tự: chữ, số, dấu chấm hoặc gạch dưới") String username,
            @NotBlank @Size(min = 8, max = 128) String password) {}

    private final AccountRepository accounts;
    private final PasswordEncoder encoder;

    AdminAccountController(AccountRepository accounts, PasswordEncoder encoder) {
        this.accounts = accounts;
        this.encoder = encoder;
    }

    // shortcut: no paging, fine for demo-sized data; add Pageable when real sign-up exists.
    @GetMapping
    List<AccountView> list() {
        return accounts.findAll(Sort.by("createdAt")).stream().map(AdminAccountController::view).toList();
    }

    /** P18: customer care staff accounts are created by an admin (role SUPPORT is never self-assigned). */
    @PostMapping("/support")
    @ResponseStatus(HttpStatus.CREATED)
    AccountView createSupport(@Valid @RequestBody SupportRequest request) {
        String username = request.username().toLowerCase(Locale.ROOT);
        if (accounts.findByUsername(username).isPresent()) throw taken();
        try {
            return view(accounts.saveAndFlush(Account.withUsername(UUID.randomUUID(), username, encoder.encode(request.password()),
                    Account.Role.SUPPORT)));
        } catch (DataIntegrityViolationException e) {
            throw taken(); // lost a race with a concurrent sign-up for the same name
        }
    }

    private static AccountView view(Account a) {
        return new AccountView(a.getId(), a.getUsername(), a.getEmail(), a.getRole(), a.getStatus());
    }

    private static ApiException taken() {
        return new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "Tên đăng nhập đã được dùng");
    }
}
