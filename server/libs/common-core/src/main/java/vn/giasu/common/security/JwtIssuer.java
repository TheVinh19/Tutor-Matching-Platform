package vn.giasu.common.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Issues access tokens. Used by identity-service, and by tests of other services. */
public class JwtIssuer {
    public static final String ISSUER = "giasu-identity";
    public static final String ROLE_CLAIM = "role";

    private final JwtEncoder encoder;

    public JwtIssuer(SecretKey key) {
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    public Jwt issue(UUID accountId, String role, String email, Duration ttl) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(accountId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim(ROLE_CLAIM, role)
                .claim("email", email)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims));
    }
}
