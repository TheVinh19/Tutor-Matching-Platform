package vn.giasu.common.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * RestClient for service-to-service calls to /api/v1/{svc}/internal/** (blocked at the gateway).
 * Each request carries a fresh 5-minute JWT with role SERVICE, signed with the shared JWT_SECRET.
 */
public class InternalClients {
    public static final String SERVICE_ROLE = "SERVICE";
    private final JwtIssuer issuer;
    private final String serviceName;

    public InternalClients(JwtIssuer issuer, String serviceName) {
        this.issuer = issuer;
        this.serviceName = serviceName;
    }

    public RestClient create(String baseUrl) {
        UUID serviceId = UUID.nameUUIDFromBytes(serviceName.getBytes(StandardCharsets.UTF_8));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    String token = issuer.issue(serviceId, SERVICE_ROLE, serviceName, Duration.ofMinutes(5)).getTokenValue();
                    request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
                    return execution.execute(request, body);
                })
                .build();
    }
}
