package vn.giasu.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Flyway V1 + DemoAccountSeeder on real PostgreSQL: all 5 demo accounts log in with the right role. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "JWT_SECRET=test-secret-test-secret-test-secret-123",
    "demo.password=Demo-pass-123"
})
class DemoAccountsPostgresTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired WebApplicationContext context;

    @Test
    void allDemoAccountsLogInWithTheirRole() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
        String[][] expected = {
            {"admin@demo.giasu.vn", "ADMIN"}, {"tutor1@demo.giasu.vn", "TUTOR"}, {"tutor2@demo.giasu.vn", "TUTOR"},
            {"learner1@demo.giasu.vn", "LEARNER"}, {"learner2@demo.giasu.vn", "LEARNER"}};
        for (String[] account : expected) {
            mvc.perform(post("/api/v1/identity/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"login\":\"" + account[0] + "\",\"password\":\"Demo-pass-123\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value(account[1]));
        }
    }

    @Test
    void registerWithUsernameThenLogInAndDuplicateIsRejected() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
        String body = "{\"username\":\"HocSinh.An\",\"password\":\"matkhau123\",\"role\":\"LEARNER\"}";
        mvc.perform(post("/api/v1/identity/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("LEARNER"));
        mvc.perform(post("/api/v1/identity/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
        mvc.perform(post("/api/v1/identity/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"hocsinh.an\",\"password\":\"matkhau123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("LEARNER"));
    }

    @Test
    void phoneIsSetByOwnerAndContactIsServiceOnly() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
        String token = com.jayway.jsonpath.JsonPath.read(mvc.perform(post("/api/v1/identity/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"learner2@demo.giasu.vn\",\"password\":\"Demo-pass-123\"}"))
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
        String bearer = "Bearer " + token;
        mvc.perform(get("/api/v1/identity/me").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phone").value("0901000005"));
        mvc.perform(put("/api/v1/identity/me/phone").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"12345\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/identity/me/phone").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"0987654321\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phone").value("0987654321"));
        // Users never read someone else's phone directly; only booking-service (role SERVICE) may.
        mvc.perform(get("/api/v1/identity/internal/accounts/00000000-0000-0000-0000-000000000002/contact").header("Authorization", bearer))
                .andExpect(status().isForbidden());
    }
}
