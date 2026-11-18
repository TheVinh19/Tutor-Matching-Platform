package vn.giasu.booking;
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
                .requestMatchers("/api/v1/bookings/internal/**").hasRole("SERVICE")
                // Public: a tutor's weekly availability, shown on the public profile.
                .requestMatchers(HttpMethod.GET, "/api/v1/bookings/availability/tutors/*").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll()).build();
    }
}
