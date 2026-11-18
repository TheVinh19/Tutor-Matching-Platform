package vn.giasu.common.security;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import vn.giasu.common.web.ApiExceptionHandler;

/** Every servlet service validates the HS256 token issued by identity-service with the shared JWT_SECRET. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
@Import(ApiExceptionHandler.class)
public class JwtSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SecretKey jwtSecretKey(@Value("${JWT_SECRET:}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be set and at least 32 bytes long");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jwtDecoder(SecretKey key) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(JwtIssuer.ISSUER));
        return decoder;
    }

    @Bean
    @ConditionalOnMissingBean
    JwtIssuer jwtIssuer(SecretKey key) {
        return new JwtIssuer(key);
    }

    @Bean
    @ConditionalOnMissingBean
    InternalClients internalClients(JwtIssuer issuer, @Value("${spring.application.name:service}") String serviceName) {
        return new InternalClients(issuer, serviceName);
    }

    /** Maps claim role=TUTOR to authority ROLE_TUTOR so hasRole('TUTOR') works. */
    @Bean
    @ConditionalOnMissingBean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtIssuer.ROLE_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
