package vn.giasu.common.security;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import vn.giasu.common.web.ApiError;

/** Stateless JWT API defaults; each service adds its own authorizeHttpRequests rules. */
public final class ApiSecurity {
    private ApiSecurity() {}

    public static HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((req, res, ex) -> ApiError.write(req, res, 401, "UNAUTHORIZED", "Chưa đăng nhập hoặc token không hợp lệ"))
                        .accessDeniedHandler((req, res, ex) -> ApiError.write(req, res, 403, "FORBIDDEN", "Không có quyền thực hiện thao tác này")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> ApiError.write(req, res, 401, "UNAUTHORIZED", "Chưa đăng nhập hoặc token không hợp lệ"))
                        .accessDeniedHandler((req, res, ex) -> ApiError.write(req, res, 403, "FORBIDDEN", "Không có quyền thực hiện thao tác này")));
    }
}
