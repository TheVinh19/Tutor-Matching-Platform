package vn.giasu.identity.authentication;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.common.security.JwtIssuer;
import vn.giasu.common.web.ApiException;
import vn.giasu.identity.accounts.Account;
import vn.giasu.identity.accounts.AccountRepository;

@RestController
@RequestMapping("/api/v1/identity")
class AuthController {
    static final Duration TOKEN_TTL = Duration.ofHours(2);

    /** login = username, or email for accounts that have one (the demo accounts). */
    record LoginRequest(@NotBlank @Size(max = 254) String login, @NotBlank @Size(max = 128) String password) {}
    record LoginResponse(String accessToken, String tokenType, long expiresIn, Account.Role role, UUID accountId) {}
    record MeResponse(UUID accountId, String username, String email, String phone, Account.Role role, Account.Status status) {}
    record PhoneRequest(@NotBlank @Pattern(regexp = "0[0-9]{9}", message = "Số điện thoại gồm 10 chữ số, bắt đầu bằng 0") String phone) {}
    /** For booking-service only: contact shown to the other party of a confirmed plan. */
    record ContactResponse(UUID accountId, String phone) {}

    enum SignUpRole { LEARNER, TUTOR }
    record RegisterRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._]{3,50}", message = "3-50 ký tự: chữ, số, dấu chấm hoặc gạch dưới") String username,
            @NotBlank @Size(min = 8, max = 128) String password,
            @NotNull SignUpRole role) {}

    private final AccountRepository accounts;
    private final PasswordEncoder encoder;
    private final JwtIssuer issuer;
    private final String dummyHash;

    AuthController(AccountRepository accounts, PasswordEncoder encoder, JwtIssuer issuer) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.issuer = issuer;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    @PostMapping("/auth/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        String login = request.login().strip().toLowerCase(Locale.ROOT);
        Optional<Account> found = login.contains("@") ? accounts.findByEmail(login) : accounts.findByUsername(login);
        Account account = found.orElse(null);
        // Hash even for unknown logins so response time does not reveal which accounts exist.
        boolean matches = encoder.matches(request.password(), account != null ? account.getPasswordHash() : dummyHash);
        if (account == null || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Tên đăng nhập hoặc mật khẩu không đúng");
        }
        if (account.getStatus() != Account.Status.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_RESTRICTED", "Tài khoản đang bị hạn chế");
        }
        return tokenFor(account);
    }

    /**
     * Self sign-up with username + password as learner or tutor (one account = one role, P01). ADMIN is never self-assigned.
     * shortcut: no email/phone verification yet; link and verify them when that task lands.
     */
    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    LoginResponse register(@Valid @RequestBody RegisterRequest request) {
        String username = request.username().toLowerCase(Locale.ROOT);
        if (accounts.findByUsername(username).isPresent()) throw usernameTaken();
        Account account;
        try {
            account = accounts.saveAndFlush(Account.withUsername(UUID.randomUUID(), username, encoder.encode(request.password()),
                    Account.Role.valueOf(request.role().name())));
        } catch (DataIntegrityViolationException e) {
            throw usernameTaken(); // lost a race with a concurrent sign-up for the same name
        }
        return tokenFor(account);
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        Account account = current(jwt);
        return new MeResponse(account.getId(), account.getUsername(), account.getEmail(), account.getPhone(), account.getRole(), account.getStatus());
    }

    // shortcut: phone is not verified (no OTP); verify it together with email when the sign-up verification task lands.
    @PutMapping("/me/phone")
    @Transactional
    MeResponse setPhone(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PhoneRequest request) {
        current(jwt).setPhone(request.phone());
        return me(jwt);
    }

    @GetMapping("/internal/accounts/{id}/contact")
    ContactResponse contact(@PathVariable UUID id) {
        Account account = accounts.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Không tìm thấy tài khoản"));
        return new ContactResponse(account.getId(), account.getPhone());
    }

    private Account current(Jwt jwt) {
        return accounts.findById(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Tài khoản không còn tồn tại"));
    }

    private static ApiException usernameTaken() {
        return new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "Tên đăng nhập đã có người dùng");
    }

    private LoginResponse tokenFor(Account account) {
        // The "email" claim carries the display/login name; username-only accounts have no email yet.
        Jwt token = issuer.issue(account.getId(), account.getRole().name(), account.getLoginName(), TOKEN_TTL);
        return new LoginResponse(token.getTokenValue(), "Bearer", TOKEN_TTL.toSeconds(), account.getRole(), account.getId());
    }
}
