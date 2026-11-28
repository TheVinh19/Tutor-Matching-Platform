package vn.giasu.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.giasu.common.security.InternalClients;
import vn.giasu.common.web.ApiException;

/** Internal calls: care (FAQ, gaps, tickets), assessment (exam result, gradebook), booking (plan parties). */
@Component
public class Clients {
    private static final Logger log = LoggerFactory.getLogger(Clients.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Faq(UUID id, String question, String answer, String tags) {}
    record Gap(String question, String reason, UUID accountId) {}
    record TicketRequest(UUID authorId, String authorRole, String subject, String body) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TicketRef(UUID id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Exam(UUID id, UUID planId, UUID tutorId, UUID learnerId, String kind, String title) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TopicScore(String topic, int correct, int total, double percent) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResultItem(int position, String topic, String content, List<String> options, String chosen, String correctOption,
                             boolean correct, String explanation) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(Exam exam, double percent, List<TopicScore> byTopic, List<ResultItem> items) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GradebookEntry(Exam exam, Double percent, List<TopicScore> byTopic) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Progress(String topic, Double placementPercent, Double latestPercent, Double change) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Gradebook(List<UUID> planChain, List<GradebookEntry> exams, List<Progress> progress) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanSummary(UUID id, UUID learnerId, UUID tutorId, String subjectCode, String levelCode, String status) {}

    private final RestClient care;
    private final RestClient assessment;
    private final RestClient booking;

    Clients(InternalClients clients, @Value("${CARE_SERVICE_URL:http://localhost:8087}") String careUrl,
            @Value("${ASSESSMENT_SERVICE_URL:http://localhost:8086}") String assessmentUrl,
            @Value("${BOOKING_SERVICE_URL:http://localhost:8083}") String bookingUrl) {
        this.care = clients.create(careUrl);
        this.assessment = clients.create(assessmentUrl);
        this.booking = clients.create(bookingUrl);
    }

    /** Null when care is unreachable, so the caller can keep its last copy. */
    public List<Faq> faqs() {
        try {
            return care.get().uri("/api/v1/care/internal/faqs").retrieve().body(new ParameterizedTypeReference<List<Faq>>() {});
        } catch (RestClientException e) {
            log.warn("FAQ not refreshed: {}", e.getMessage());
            return null;
        }
    }

    /** Best effort: a lost gap report only means staff see one unanswered question fewer. */
    public void reportGap(String question, String reason, UUID accountId) {
        try {
            care.post().uri("/api/v1/care/internal/faq-gaps").body(new Gap(question, reason, accountId)).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("FAQ gap not reported: {}", e.getMessage());
        }
    }

    public UUID openTicket(UUID authorId, String authorRole, String subject, String body) {
        try {
            TicketRef ref = care.post().uri("/api/v1/care/internal/tickets").body(new TicketRequest(authorId, authorRole, subject, body))
                    .retrieve().body(TicketRef.class);
            return ref == null ? null : ref.id();
        } catch (RestClientException e) {
            throw unavailable("CARE_UNAVAILABLE", "Bộ phận hỗ trợ tạm thời không nhận phiếu, vui lòng thử lại");
        }
    }

    public Result examResult(UUID examId) {
        try {
            return assessment.get().uri("/api/v1/assessments/internal/exams/{id}/result", examId).retrieve().body(Result.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EXAM_NOT_FOUND", "Không tìm thấy bài kiểm tra");
        } catch (HttpClientErrorException.Conflict e) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_SUBMITTED", "Bài chưa nộp nên chưa có lời giải thích");
        } catch (RestClientException e) {
            throw unavailable("ASSESSMENT_UNAVAILABLE", "Dịch vụ khảo thí tạm thời không phản hồi");
        }
    }

    public Gradebook gradebook(UUID planId) {
        try {
            return assessment.get().uri("/api/v1/assessments/internal/plans/{id}/gradebook", planId).retrieve().body(Gradebook.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "Không tìm thấy kỳ học");
        } catch (RestClientException e) {
            throw unavailable("ASSESSMENT_UNAVAILABLE", "Dịch vụ khảo thí tạm thời không phản hồi");
        }
    }

    public PlanSummary plan(UUID planId) {
        try {
            return booking.get().uri("/api/v1/bookings/internal/plans/{id}/summary", planId).retrieve().body(PlanSummary.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "Không tìm thấy kỳ học");
        } catch (RestClientException e) {
            throw unavailable("BOOKING_UNAVAILABLE", "Dịch vụ lịch học tạm thời không phản hồi");
        }
    }

    private static ApiException unavailable(String code, String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
