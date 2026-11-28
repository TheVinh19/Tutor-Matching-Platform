package vn.giasu.care;

import java.util.List;
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

/** Internal calls: booking (care signals, case from ticket), assessment (score signals), identity (phone to call). */
@Component
public class Clients {
    public record CareSignal(UUID learnerId, long cancels30d, long absences30d, long disputes30d, long openCases, boolean restricted,
                             boolean endingWithoutRenewal, boolean lastTwoAbsent) {}
    public record ScoreSignal(UUID learnerId, Double placementPercent, Double previousPercent, Double latestPercent) {}
    record TicketCaseRequest(UUID sessionId, String reason) {}
    record CaseRef(UUID caseId) {}
    record Contact(UUID accountId, String phone) {}

    private final RestClient booking;
    private final RestClient assessment;
    private final RestClient identity;

    Clients(InternalClients clients, @Value("${BOOKING_SERVICE_URL:http://localhost:8083}") String bookingUrl,
            @Value("${ASSESSMENT_SERVICE_URL:http://localhost:8086}") String assessmentUrl,
            @Value("${IDENTITY_SERVICE_URL:http://localhost:8081}") String identityUrl) {
        this.booking = clients.create(bookingUrl);
        this.assessment = clients.create(assessmentUrl);
        this.identity = clients.create(identityUrl);
    }

    public List<CareSignal> careSignals() {
        try {
            return booking.get().uri("/api/v1/bookings/internal/care/signals").retrieve().body(new ParameterizedTypeReference<List<CareSignal>>() {});
        } catch (RestClientException e) {
            throw unavailable("BOOKING_UNAVAILABLE", "Dịch vụ lịch học tạm thời không phản hồi");
        }
    }

    public UUID caseFromTicket(UUID sessionId, String reason) {
        try {
            CaseRef ref = booking.post().uri("/api/v1/bookings/internal/cases/from-ticket").body(new TicketCaseRequest(sessionId, reason))
                    .retrieve().body(CaseRef.class);
            return ref == null ? null : ref.caseId();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Không tìm thấy buổi học của phiếu");
        } catch (RestClientException e) {
            throw unavailable("BOOKING_UNAVAILABLE", "Dịch vụ lịch học tạm thời không phản hồi");
        }
    }

    /** Score signals are optional input: assessment being down must not stop the daily tiering. */
    public List<ScoreSignal> scoreSignals() {
        try {
            return assessment.get().uri("/api/v1/assessments/internal/learners/signals").retrieve()
                    .body(new ParameterizedTypeReference<List<ScoreSignal>>() {});
        } catch (RestClientException e) {
            return List.of();
        }
    }

    public String phone(UUID accountId) {
        try {
            Contact contact = identity.get().uri("/api/v1/identity/internal/accounts/{id}/contact", accountId).retrieve().body(Contact.class);
            return contact == null ? null : contact.phone();
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (RestClientException e) {
            throw unavailable("IDENTITY_SERVICE_UNAVAILABLE", "Dịch vụ tài khoản tạm thời không phản hồi");
        }
    }

    private static ApiException unavailable(String code, String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
