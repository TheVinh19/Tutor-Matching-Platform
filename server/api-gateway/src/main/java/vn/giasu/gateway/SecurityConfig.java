package vn.giasu.gateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
@Configuration
class SecurityConfig {
    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        // Stateless JWT API: no cookies/session at the gateway, so CSRF does not apply. Services check JWT and roles.
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(auth -> auth
                .pathMatchers(HttpMethod.GET, "/actuator/health", "/api/v1/identity/health", "/api/v1/tutors/health", "/api/v1/bookings/health", "/api/v1/payments/health", "/api/v1/notifications/health").permitAll()
                .pathMatchers("/api/v1/*/internal/**").denyAll()
                .pathMatchers("/api/v1/identity/**", "/api/v1/tutors/**", "/api/v1/bookings/**", "/api/v1/notifications/**").permitAll()
                .anyExchange().denyAll()).build();
    }
}
