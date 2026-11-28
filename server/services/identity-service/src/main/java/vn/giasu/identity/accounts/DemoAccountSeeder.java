package vn.giasu.identity.accounts;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import vn.giasu.identity.accounts.Account.Role;

/**
 * Mock login (mục 3): upserts the demo accounts (5 users + 1 CSKH) when env DEMO_PASSWORD is set; no seeding otherwise.
 * Fixed UUIDs so tutor/booking seed data can reference the same accounts.
 * shortcut: no registration/email verification yet, replace with real sign-up when that task lands.
 */
@Component
@ConditionalOnProperty("demo.password")
class DemoAccountSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoAccountSeeder.class);

    record DemoAccount(UUID id, String email, Role role, String phone) {}

    static final List<DemoAccount> ACCOUNTS = List.of(
            new DemoAccount(UUID.fromString("00000000-0000-0000-0000-000000000001"), "admin@demo.giasu.vn", Role.ADMIN, null),
            new DemoAccount(UUID.fromString("00000000-0000-0000-0000-000000000002"), "tutor1@demo.giasu.vn", Role.TUTOR, "0901000002"),
            new DemoAccount(UUID.fromString("00000000-0000-0000-0000-000000000003"), "tutor2@demo.giasu.vn", Role.TUTOR, "0901000003"),
            new DemoAccount(UUID.fromString("00000000-0000-0000-0000-000000000004"), "learner1@demo.giasu.vn", Role.LEARNER, "0901000004"),
            new DemoAccount(UUID.fromString("00000000-0000-0000-0000-000000000005"), "learner2@demo.giasu.vn", Role.LEARNER, "0901000005"),
            new DemoAccount(UUID.fromString("00000000-0000-0000-0000-000000000006"), "support@demo.giasu.vn", Role.SUPPORT, null));

    private final AccountRepository accounts;
    private final PasswordEncoder encoder;
    private final String password;

    DemoAccountSeeder(AccountRepository accounts, PasswordEncoder encoder, @Value("${demo.password}") String password) {
        if (password.length() < 8) {
            throw new IllegalStateException("DEMO_PASSWORD must be at least 8 characters");
        }
        this.accounts = accounts;
        this.encoder = encoder;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        // save() merges by id: inserts on first run, refreshes the hash if DEMO_PASSWORD changed.
        ACCOUNTS.forEach(a -> {
            Account account = new Account(a.id(), a.email(), encoder.encode(password), a.role());
            account.setPhone(a.phone());
            accounts.save(account);
        });
        log.info("Demo accounts ready: {}", ACCOUNTS.size());
    }
}
