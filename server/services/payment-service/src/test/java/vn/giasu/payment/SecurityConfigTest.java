package vn.giasu.payment;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.giasu.common.security.JwtIssuer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
@SpringBootTest(classes = SecurityConfigTest.TestApp.class, properties = {
    "JWT_SECRET=" + SecurityConfigTest.SECRET,
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class SecurityConfigTest {
    static final String SECRET = "test-secret-test-secret-test-secret-123";
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(SecurityConfig.class)
    static class TestApp {}
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
    }
    static String token(String secret, Duration ttl) {
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return new JwtIssuer(key).issue(UUID.randomUUID(), "LEARNER", "a@b.vn", ttl).getTokenValue();
    }
    static String expiredToken() {
        SecretKey key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        java.time.Instant past = java.time.Instant.now().minus(Duration.ofHours(1));
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer(JwtIssuer.ISSUER).subject(UUID.randomUUID().toString())
                .issuedAt(past).expiresAt(past.plus(Duration.ofMinutes(5))).claim(JwtIssuer.ROLE_CLAIM, "LEARNER").build();
        var header = org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        return new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key))
                .encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
    @Test void healthIsPublicAndApiNeedsToken() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/private")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
    @Test void validTokenPassesSecurity() throws Exception {
        mvc.perform(get("/api/private").header("Authorization", "Bearer " + token(SECRET, Duration.ofMinutes(5))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test void forgedOrExpiredTokenIsRejected() throws Exception {
        mvc.perform(get("/api/private").header("Authorization", "Bearer " + token("other-secret-other-secret-other-secret", Duration.ofMinutes(5))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/private").header("Authorization", "Bearer " + expiredToken()))
                .andExpect(status().isUnauthorized());
    }
    @Test void nonApiPathsStayDenied() throws Exception {
        mvc.perform(get("/actuator/env").header("Authorization", "Bearer " + token(SECRET, Duration.ofMinutes(5))))
                .andExpect(status().isForbidden());
    }
}
