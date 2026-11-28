package vn.giasu.assessment;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.giasu.common.security.InternalClients;
import vn.giasu.common.web.ApiException;

/** Booking owns plans; assessment only reads who teaches whom, which subject/level and the renewal link. */
@Component
public class BookingClient {
    public record PlanSummary(UUID id, UUID learnerId, UUID tutorId, String subjectCode, String levelCode, String status, UUID renewedFrom) {}

    private final RestClient client;

    BookingClient(InternalClients clients, @Value("${BOOKING_SERVICE_URL:http://localhost:8083}") String baseUrl) {
        this.client = clients.create(baseUrl);
    }

    public PlanSummary plan(UUID planId) {
        try {
            PlanSummary plan = client.get().uri("/api/v1/bookings/internal/plans/{id}/summary", planId).retrieve().body(PlanSummary.class);
            if (plan == null) throw notFound();
            return plan;
        } catch (HttpClientErrorException.NotFound e) {
            throw notFound();
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_UNAVAILABLE", "Dịch vụ lịch học tạm thời không phản hồi");
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "Không tìm thấy kỳ học");
    }
}
