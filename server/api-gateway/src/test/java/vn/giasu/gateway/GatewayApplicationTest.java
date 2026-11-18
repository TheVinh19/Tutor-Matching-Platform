package vn.giasu.gateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.AfterAll;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayApplicationTest {
    static final DisposableServer upstream = HttpServer.create().host("127.0.0.1").port(0)
            .route(routes -> routes.get("/actuator/health", (req, res) ->
                    res.header("Content-Type", "application/json").sendString(Mono.just("{\"status\":\"UP\"}"))))
            .bindNow();
    @Value("${local.server.port}") int port;
    @DynamicPropertySource
    static void upstreamUrl(DynamicPropertyRegistry registry) {
        registry.add("IDENTITY_SERVICE_URL", () -> "http://127.0.0.1:" + upstream.port());
    }
    @AfterAll static void stopUpstream() { upstream.disposeNow(); }
    @Test void routesHealthAndDeniesOtherPaths() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var health = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/identity/health")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertEquals("{\"status\":\"UP\"}", health.body());
            var denied = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/private")).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(denied.statusCode() == 401 || denied.statusCode() == 403);
        }
    }
}
