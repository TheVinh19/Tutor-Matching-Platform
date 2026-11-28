package vn.giasu.tutor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.giasu.common.security.InternalClients;
import vn.giasu.common.web.ApiException;

/** Calls booking-service internal API. Booking owns schedules and plans; tutor only reads. */
@Component
public class BookingClient {
    public record PlanEligibility(UUID planId, UUID learnerId, UUID tutorId, String status, Instant closedAt) {}
    /** One qualifying fact for EXP; key is unique per tutor (session:/period:/renewal: + id). */
    public record ExpEvent(String key, String type, int points, String refType, UUID refId, Instant occurredAt) {}
    public record ExpStats(int taughtSessions, int tutorFaultFailed, int distinctLearners, int renewals, int openIssues) {}
    public record ExpSources(List<ExpEvent> events, ExpStats stats) {}

    public ExpSources expSources(UUID tutorId) {
        try {
            return client.get().uri("/api/v1/bookings/internal/tutors/{id}/exp-sources", tutorId).retrieve().body(ExpSources.class);
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    private final RestClient client;

    BookingClient(InternalClients clients, @Value("${BOOKING_SERVICE_URL:http://localhost:8083}") String baseUrl) {
        this.client = clients.create(baseUrl);
    }

    /** Tutors whose weekly availability covers the weekday/time window for the whole date range. */
    public List<UUID> tutorsAvailable(int dayOfWeek, LocalTime from, LocalTime to, LocalDate fromDate, LocalDate toDate) {
        try {
            return client.get().uri(u -> u.path("/api/v1/bookings/internal/availability/tutors")
                            .queryParam("day", dayOfWeek).queryParam("from", from).queryParam("to", to)
                            .queryParam("fromDate", fromDate).queryParam("toDate", toDate).build())
                    .retrieve().body(new ParameterizedTypeReference<List<UUID>>() {});
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    public Optional<PlanEligibility> planEligibility(UUID planId) {
        try {
            return Optional.ofNullable(client.get().uri("/api/v1/bookings/internal/plans/{id}/review-eligibility", planId)
                    .retrieve().body(PlanEligibility.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_UNAVAILABLE", "Dịch vụ lịch học tạm thời không phản hồi");
    }
}
