package vn.giasu.identity.accounts;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/identity/admin/accounts")
@PreAuthorize("hasRole('ADMIN')")
class AdminAccountController {
    record AccountView(UUID id, String email, Account.Role role, Account.Status status) {}

    private final AccountRepository accounts;

    AdminAccountController(AccountRepository accounts) { this.accounts = accounts; }

    // shortcut: no paging, fine for demo-sized data; add Pageable when real sign-up exists.
    @GetMapping
    List<AccountView> list() {
        return accounts.findAll(Sort.by("email")).stream()
                .map(a -> new AccountView(a.getId(), a.getEmail(), a.getRole(), a.getStatus()))
                .toList();
    }
}
