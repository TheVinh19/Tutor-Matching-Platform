package vn.giasu.tutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import vn.giasu.common.security.ApiSecurity;
@Configuration
class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return ApiSecurity.stateless(http).authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/api/v1/tutors/internal/**").hasRole("SERVICE")
                // Public: catalog, search, approved profile, reviews. Roles for the rest via @PreAuthorize.
                .requestMatchers(HttpMethod.GET, "/api/v1/tutors/catalog", "/api/v1/tutors/search", "/api/v1/tutors/*", "/api/v1/tutors/*/reviews").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll()).build();
    }
}
