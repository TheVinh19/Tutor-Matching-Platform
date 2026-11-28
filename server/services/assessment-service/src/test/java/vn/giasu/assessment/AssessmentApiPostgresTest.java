package vn.giasu.assessment;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import vn.giasu.assessment.BookingClient.PlanSummary;
import vn.giasu.common.security.JwtIssuer;

/** P16 question bank, exams, grading and gradebook on real PostgreSQL. Booking is mocked. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = AssessmentApplication.class, properties = "JWT_SECRET=test-secret-test-secret-test-secret-123")
class AssessmentApiPostgresTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired WebApplicationContext context;
    @Autowired JwtIssuer issuer;
    @Autowired JdbcClient jdbc;
    @MockitoBean BookingClient booking;
    @MockitoBean Notifier notifier;
    MockMvc mvc;
    final UUID admin = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
    }

    ResultActions call(MockHttpServletRequestBuilder req, UUID id, String role, String json) throws Exception {
        if (id != null) req.header("Authorization", "Bearer " + issuer.issue(id, role, "x@t.vn", Duration.ofHours(1)).getTokenValue());
        if (json != null) req.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(req);
    }

    static String json(String s) { return s.replace("\"", "\\\"").replace("\n", "\\n"); }

    /** A CSV with `n` questions for the subject/level, two topics, difficulty cycling 1..3. */
    static String csv(String subject, String level, int n) {
        StringBuilder sb = new StringBuilder("subject,level,topic,difficulty,content,a,b,c,d,correct,explanation,source\n");
        for (int i = 0; i < n; i++) {
            sb.append(subject).append(',').append(level).append(',').append(i % 2 == 0 ? "Đại số" : "Hình học").append(',').append(i % 3 + 1)
                    .append(",\"Câu ").append(i).append(": 2 + ").append(i).append(" = ?\",").append(i + 2).append(',').append(i + 3).append(',')
                    .append(i + 4).append(',').append(i + 5).append(",A,\"Cộng hai số, đáp án ").append(i + 2).append("\",Đề tự soạn\n");
        }
        return sb.toString();
    }

    void plan(UUID plan, UUID learner, UUID tutor, String subject, String status, UUID renewedFrom) {
        when(booking.plan(plan)).thenReturn(new PlanSummary(plan, learner, tutor, subject, "THCS", status, renewedFrom));
    }

    String exam(UUID plan, UUID tutor, String kind, int count) throws Exception {
        return JsonPath.read(call(post("/api/v1/assessments/exams"), tutor, "TUTOR", """
                {"planId":"%s","kind":"%s","title":"Bài %s","questionCount":%d,"durationMinutes":30}""".formatted(plan, kind, kind, count))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.exam.id");
    }

    /** Displayed letters of the right answers, read from the stored option order (the API never reveals them before submit). */
    List<String> rightLetters(String examId) {
        return jdbc.sql("""
                SELECT substr('ABCD', strpos(i.option_order, q.correct), 1) FROM attempt_items i JOIN attempts a ON a.id = i.attempt_id
                JOIN questions q ON q.id = i.question_id WHERE a.exam_id = ? ORDER BY i.position""").param(UUID.fromString(examId))
                .query(String.class).list();
    }

    String answers(List<String> right, int correctCount) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < right.size(); i++) {
            String choice = i < correctCount ? right.get(i) : String.valueOf("ABCD".charAt(("ABCD".indexOf(right.get(i)) + 1) % 4));
            parts.add("{\"position\":" + (i + 1) + ",\"choice\":\"" + choice + "\"}");
        }
        return "{\"answers\":[" + String.join(",", parts) + "]}";
    }

    @Test
    void importValidatesAllRowsAndAdminReviewsQuestions() throws Exception {
        String bad = "subject,level,topic,difficulty,content,a,b,c,d,correct\nTOAN,THCS,Đại số,5,\"Câu sai\",1,2,3,4,E\n";
        call(post("/api/v1/assessments/admin/questions/import"), admin, "ADMIN", "{\"csv\":\"" + json(bad) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CSV"))
                .andExpect(jsonPath("$.details[*].line", hasItem(2)));
        call(post("/api/v1/assessments/admin/questions/import"), UUID.randomUUID(), "TUTOR", "{\"csv\":\"" + json(csv("VAN", "THCS", 3)) + "\"}")
                .andExpect(status().isForbidden());
        call(post("/api/v1/assessments/admin/questions/import"), admin, "ADMIN", "{\"csv\":\"" + json(csv("VAN", "THCS", 3)) + "\"}")
                .andExpect(jsonPath("$.imported").value(3));
        call(get("/api/v1/assessments/admin/questions?subject=VAN"), admin, "ADMIN", null).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].status").value("APPROVED"));

        // A pending question (as an AI draft would be) is reviewed once.
        UUID pending = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO questions (id, subject_code, level_code, topic, difficulty, content, option_a, option_b, option_c, option_d, correct,
                    origin, status, created_by) VALUES (?, 'VAN', 'THCS', 'Từ loại', 2, 'Từ nào là danh từ?', 'chạy', 'bàn', 'đẹp', 'rất', 'B',
                    'AI', 'PENDING', ?)""").params(pending, admin).update();
        call(post("/api/v1/assessments/admin/questions/" + pending + "/reject"), admin, "ADMIN", "{\"note\":\"\"}").andExpect(status().isBadRequest());
        call(post("/api/v1/assessments/admin/questions/" + pending + "/approve"), admin, "ADMIN", null).andExpect(jsonPath("$.status").value("APPROVED"));
        call(post("/api/v1/assessments/admin/questions/" + pending + "/approve"), admin, "ADMIN", null).andExpect(status().isConflict());
    }

    @Test
    void tutorAssignsLearnerTakesOnceAndResultsArePerTopic() throws Exception {
        UUID planId = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        UUID tutor = UUID.randomUUID();
        plan(planId, learner, tutor, "TOAN", "CONFIRMED", null);
        call(post("/api/v1/assessments/admin/questions/import"), admin, "ADMIN", "{\"csv\":\"" + json(csv("TOAN", "THCS", 11)) + "\"}").andExpect(status().isOk());

        String create = """
                {"planId":"%s","kind":"PLACEMENT","title":"Đầu kỳ","questionCount":12,"durationMinutes":30}""".formatted(planId);
        call(post("/api/v1/assessments/exams"), tutor, "TUTOR", create).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_ENOUGH_QUESTIONS"));
        call(post("/api/v1/assessments/questions"), tutor, "TUTOR", """
                {"subjectCode":"TOAN","levelCode":"THCS","topic":"Hình học","difficulty":2,"content":"Tổng ba góc tam giác?",
                 "options":["90","180","270","360"],"correct":"B","explanation":"Định lý tổng ba góc"}""").andExpect(status().isCreated());
        call(post("/api/v1/assessments/exams"), UUID.randomUUID(), "TUTOR", create).andExpect(status().isForbidden());
        String examId = JsonPath.read(call(post("/api/v1/assessments/exams"), tutor, "TUTOR", create).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.exam.id");
        call(post("/api/v1/assessments/exams"), tutor, "TUTOR", create).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXAM_EXISTS"));

        call(post("/api/v1/assessments/exams/" + examId + "/start"), UUID.randomUUID(), "LEARNER", null).andExpect(status().isForbidden());
        String attempt = call(post("/api/v1/assessments/exams/" + examId + "/start"), learner, "LEARNER", null)
                .andExpect(jsonPath("$.items", hasSize(12))).andExpect(jsonPath("$.items[0].options", hasSize(4)))
                .andExpect(jsonPath("$.items[0].correct").doesNotExist()).andReturn().getResponse().getContentAsString();
        call(post("/api/v1/assessments/exams/" + examId + "/start"), learner, "LEARNER", null)
                .andExpect(jsonPath("$.attemptId").value((String) JsonPath.read(attempt, "$.attemptId")));
        call(get("/api/v1/assessments/exams/" + examId + "/result"), tutor, "TUTOR", null).andExpect(status().isConflict());

        call(post("/api/v1/assessments/exams/" + examId + "/submit"), learner, "LEARNER", answers(rightLetters(examId), 6))
                .andExpect(jsonPath("$.percent").value(50.0)).andExpect(jsonPath("$.attempt.correctCount").value(6))
                .andExpect(jsonPath("$.attempt.late").value(false)).andExpect(jsonPath("$.byTopic[*].topic", hasItem("Hình học")))
                .andExpect(jsonPath("$.items[0].correctOption").exists()).andExpect(jsonPath("$.items[0].explanation").exists());
        call(post("/api/v1/assessments/exams/" + examId + "/submit"), learner, "LEARNER", answers(rightLetters(examId), 12))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_SUBMITTED"));
        call(get("/api/v1/assessments/exams/" + examId + "/result"), tutor, "TUTOR", null).andExpect(jsonPath("$.percent").value(50.0));
        call(get("/api/v1/assessments/exams/" + examId + "/result"), UUID.randomUUID(), "SUPPORT", null).andExpect(status().isOk());
        call(get("/api/v1/assessments/exams/" + examId + "/result"), UUID.randomUUID(), "TUTOR", null).andExpect(status().isForbidden());

        // Late submission is still graded, flagged late.
        String periodic = exam(planId, tutor, "PERIODIC", 5);
        call(post("/api/v1/assessments/exams/" + periodic + "/start"), learner, "LEARNER", null).andExpect(status().isOk());
        jdbc.sql("UPDATE attempts SET deadline_at = now() - interval '1 minute' WHERE exam_id = ?").param(UUID.fromString(periodic)).update();
        call(post("/api/v1/assessments/exams/" + periodic + "/submit"), learner, "LEARNER", answers(rightLetters(periodic), 5))
                .andExpect(jsonPath("$.attempt.late").value(true)).andExpect(jsonPath("$.percent").value(100.0));
        call(get("/api/v1/assessments/exams?planId=" + planId), learner, "LEARNER", null).andExpect(jsonPath("$", hasSize(2)));

        plan(planId, learner, tutor, "TOAN", "REJECTED", null);
        call(post("/api/v1/assessments/exams"), tutor, "TUTOR", """
                {"planId":"%s","kind":"PERIODIC","title":"Giữa kỳ","questionCount":5,"durationMinutes":30}""".formatted(planId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_NOT_ACTIVE"));
    }

    @Test
    void gradebookFollowsTheRenewalChainAndSignalsFeedCare() throws Exception {
        UUID first = UUID.randomUUID();
        UUID renewal = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        UUID tutor = UUID.randomUUID();
        plan(first, learner, tutor, "ANH", "HOAN_TAT_NGHIA_VU", null);
        plan(renewal, learner, tutor, "ANH", "CONFIRMED", first);
        call(post("/api/v1/assessments/admin/questions/import"), admin, "ADMIN", "{\"csv\":\"" + json(csv("ANH", "THCS", 10)) + "\"}").andExpect(status().isOk());

        // Placement was given while the first period was running.
        plan(first, learner, tutor, "ANH", "CONFIRMED", null);
        String placement = exam(first, tutor, "PLACEMENT", 10);
        call(post("/api/v1/assessments/exams/" + placement + "/start"), learner, "LEARNER", null).andExpect(status().isOk());
        call(post("/api/v1/assessments/exams/" + placement + "/submit"), learner, "LEARNER", answers(rightLetters(placement), 4)).andExpect(status().isOk());
        plan(first, learner, tutor, "ANH", "HOAN_TAT_NGHIA_VU", null);

        String periodic = exam(renewal, tutor, "PERIODIC", 10);
        call(post("/api/v1/assessments/exams/" + periodic + "/start"), learner, "LEARNER", null).andExpect(status().isOk());
        call(post("/api/v1/assessments/exams/" + periodic + "/submit"), learner, "LEARNER", answers(rightLetters(periodic), 9)).andExpect(status().isOk());

        String book = call(get("/api/v1/assessments/plans/" + renewal + "/gradebook"), learner, "LEARNER", null)
                .andExpect(jsonPath("$.planChain", hasSize(2))).andExpect(jsonPath("$.planChain[0]").value(first.toString()))
                .andExpect(jsonPath("$.exams", hasSize(2))).andReturn().getResponse().getContentAsString();
        List<Double> changes = JsonPath.read(book, "$.progress[*].change");
        assertEquals(2, changes.size());
        call(get("/api/v1/assessments/plans/" + renewal + "/gradebook"), UUID.randomUUID(), "LEARNER", null).andExpect(status().isForbidden());

        call(get("/api/v1/assessments/internal/learners/signals"), learner, "LEARNER", null).andExpect(status().isForbidden());
        String signals = call(get("/api/v1/assessments/internal/learners/signals"), UUID.randomUUID(), "SERVICE", null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(List.of(90.0), JsonPath.read(signals, "$[?(@.learnerId == '" + learner + "')].latestPercent"));
        assertEquals(List.of(40.0), JsonPath.read(signals, "$[?(@.learnerId == '" + learner + "')].placementPercent"));
    }
}
