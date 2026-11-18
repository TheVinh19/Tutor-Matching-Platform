package vn.giasu.booking;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.giasu.common.security.InternalClients;
import vn.giasu.common.web.ApiException;

/** Reads tutor approval state from tutor-service: only APPROVED tutors may open slots or receive requests (GS01). */
@Component
public class TutorClient {
    public record SubjectLevel(String subjectCode, String levelCode) {}
    public record TutorProfile(UUID accountId, String fullName, String status, String teachingMode, int rank, List<SubjectLevel> subjects) {}

    private final RestClient client;

    TutorClient(InternalClients clients, @Value("${TUTOR_SERVICE_URL:http://localhost:8082}") String baseUrl) {
        this.client = clients.create(baseUrl);
    }

    /** Returns the tutor profile if approved, otherwise throws 409 TUTOR_NOT_APPROVED. */
    public TutorProfile requireApproved(UUID tutorId) {
        TutorProfile profile;
        try {
            profile = client.get().uri("/api/v1/tutors/internal/profiles/{id}", tutorId).retrieve().body(TutorProfile.class);
        } catch (HttpClientErrorException.NotFound e) {
            profile = null;
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TUTOR_SERVICE_UNAVAILABLE", "Dịch vụ hồ sơ gia sư tạm thời không phản hồi");
        }
        if (profile == null || !"APPROVED".equals(profile.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "TUTOR_NOT_APPROVED", "Hồ sơ gia sư chưa được duyệt");
        }
        return profile;
    }
}
