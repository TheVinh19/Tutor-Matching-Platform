package vn.giasu.identity;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.giasu.identity.accounts.Account;
import vn.giasu.identity.accounts.AccountRepository;

/** Login, /me, role checks and error format without a database (repository mocked). */
@SpringBootTest(classes = IdentityApplication.class, properties = {
    "JWT_SECRET=test-secret-test-secret-test-secret-123",
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class AuthApiTest {
    static final String PASSWORD = "Demo-pass-123";
    static final UUID ADMIN_ID = UUID.randomUUID();
    static final UUID LEARNER_ID = UUID.randomUUID();

    @Autowired WebApplicationContext context;
    @Autowired PasswordEncoder encoder;
    @MockitoBean AccountRepository accounts;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
        Account admin = new Account(ADMIN_ID, "admin@demo.giasu.vn", encoder.encode(PASSWORD), Account.Role.ADMIN);
        Account learner = new Account(LEARNER_ID, "learner1@demo.giasu.vn", encoder.encode(PASSWORD), Account.Role.LEARNER);
        when(accounts.findByEmail(anyString())).thenReturn(Optional.empty());
        when(accounts.findByEmail("admin@demo.giasu.vn")).thenReturn(Optional.of(admin));
        when(accounts.findByEmail("learner1@demo.giasu.vn")).thenReturn(Optional.of(learner));
        when(accounts.findById(ADMIN_ID)).thenReturn(Optional.of(admin));
        when(accounts.findById(LEARNER_ID)).thenReturn(Optional.of(learner));
        when(accounts.findAll(Sort.by("createdAt"))).thenReturn(List.of(admin, learner));
    }

    String login(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/identity/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void loginReturnsTokenAndRoleAndEmailIsCaseInsensitive() throws Exception {
        mvc.perform(post("/api/v1/identity/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"Learner1@Demo.GiaSu.vn\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(7200))
                .andExpect(jsonPath("$.role").value("LEARNER"))
                .andExpect(jsonPath("$.accountId").value(LEARNER_ID.toString()));
    }

    @Test
    void meReturnsCurrentAccount() throws Exception {
        String token = login("learner1@demo.giasu.vn", PASSWORD);
        mvc.perform(get("/api/v1/identity/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("learner1@demo.giasu.vn"))
                .andExpect(jsonPath("$.role").value("LEARNER"));
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveSame401() throws Exception {
        for (String email : new String[] {"learner1@demo.giasu.vn", "nobody@demo.giasu.vn"}) {
            mvc.perform(post("/api/v1/identity/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"login\":\"" + email + "\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.requestId").isNotEmpty());
        }
    }

    @Test
    void invalidBodyGives400WithFieldDetails() throws Exception {
        mvc.perform(post("/api/v1/identity/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details", hasSize(2)));
        mvc.perform(post("/api/v1/identity/auth/login").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void meWithoutOrWithTamperedTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/identity/me"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        String token = login("learner1@demo.giasu.vn", PASSWORD);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
        mvc.perform(get("/api/v1/identity/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpointIsForbiddenForLearnerAndOpenForAdmin() throws Exception {
        mvc.perform(get("/api/v1/identity/admin/accounts").header("Authorization", "Bearer " + login("learner1@demo.giasu.vn", PASSWORD)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/identity/admin/accounts").header("Authorization", "Bearer " + login("admin@demo.giasu.vn", PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void unknownApiPathGivesStandard404() throws Exception {
        mvc.perform(get("/api/v1/identity/nope").header("Authorization", "Bearer " + login("admin@demo.giasu.vn", PASSWORD)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void registerCreatesTutorOrLearnerButNeverAdmin() throws Exception {
        when(accounts.saveAndFlush(org.mockito.ArgumentMatchers.any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        mvc.perform(post("/api/v1/identity/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"giasu_moi\",\"password\":\"matkhau123\",\"role\":\"TUTOR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("TUTOR"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
        mvc.perform(post("/api/v1/identity/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"hacker\",\"password\":\"matkhau123\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/identity/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a b\",\"password\":\"short\",\"role\":\"LEARNER\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details", hasSize(2)));
    }

    @Test
    void registerRejectsTakenUsername() throws Exception {
        when(accounts.findByUsername("hocsinh1")).thenReturn(Optional.of(
                Account.withUsername(UUID.randomUUID(), "hocsinh1", encoder.encode(PASSWORD), Account.Role.LEARNER)));
        mvc.perform(post("/api/v1/identity/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"HocSinh1\",\"password\":\"matkhau123\",\"role\":\"LEARNER\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
    }
}
