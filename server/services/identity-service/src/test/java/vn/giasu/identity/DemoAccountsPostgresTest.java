package vn.giasu.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                            .content("{\"email\":\"" + account[0] + "\",\"password\":\"Demo-pass-123\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value(account[1]));
        }
    }
}
