package vn.giasu.identity.accounts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {
    /** SUPPORT: customer care staff (P18), created by an admin, never through public sign-up. */
    public enum Role { LEARNER, TUTOR, ADMIN, SUPPORT }
    public enum Status { ACTIVE, RESTRICTED }

    @Id
    private UUID id;
    private String email;
    private String username;
    private String phone;
    private String passwordHash;
    @Enumerated(EnumType.STRING)
    private Role role;
    @Enumerated(EnumType.STRING)
    private Status status;
    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    protected Account() {}

    public Account(UUID id, String email, String passwordHash, Role role) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = Status.ACTIVE;
    }

    /** Self sign-up: username only, email/phone are linked later. */
    public static Account withUsername(UUID id, String username, String passwordHash, Role role) {
        Account account = new Account(id, null, passwordHash, role);
        account.username = username;
        return account;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getUsername() { return username; }
    /** What to show and put in the token: username if set, else email. */
    public String getLoginName() { return username != null ? username : email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
