package vn.giasu.identity.authentication;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.common.security.JwtIssuer;
import vn.giasu.common.web.ApiException;
import vn.giasu.identity.accounts.Account;
import vn.giasu.identity.accounts.AccountRepository;

@RestController
@RequestMapping("/api/v1/identity")
class AuthController {
    static final Duration TOKEN_TTL = Duration.ofHours(2);

    record LoginRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {}
    record LoginResponse(String accessToken, String tokenType, long expiresIn, Account.Role role, UUID accountId) {}
    record MeResponse(UUID accountId, String email, Account.Role role, Account.Status status) {}

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
        Account account = accounts.findByEmail(request.email().toLowerCase(Locale.ROOT)).orElse(null);
        // Hash even for unknown emails so response time does not reveal which emails exist.
        boolean matches = encoder.matches(request.password(), account != null ? account.getPasswordHash() : dummyHash);
        if (account == null || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email hoặc mật khẩu không đúng");
        }
        if (account.getStatus() != Account.Status.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_RESTRICTED", "Tài khoản đang bị hạn chế");
        }
        Jwt token = issuer.issue(account.getId(), account.getRole().name(), account.getEmail(), TOKEN_TTL);
        return new LoginResponse(token.getTokenValue(), "Bearer", TOKEN_TTL.toSeconds(), account.getRole(), account.getId());
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        Account account = accounts.findById(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Tài khoản không còn tồn tại"));
        return new MeResponse(account.getId(), account.getEmail(), account.getRole(), account.getStatus());
    }
}
