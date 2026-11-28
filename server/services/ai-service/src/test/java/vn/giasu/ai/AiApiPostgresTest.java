package vn.giasu.ai;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
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
import vn.giasu.ai.Clients.Exam;
import vn.giasu.ai.Clients.Faq;
import vn.giasu.ai.Clients.Gradebook;
import vn.giasu.ai.Clients.GradebookEntry;
import vn.giasu.ai.Clients.PlanSummary;
import vn.giasu.ai.Clients.Progress;
import vn.giasu.ai.Clients.Result;
import vn.giasu.ai.Clients.ResultItem;
import vn.giasu.ai.Clients.TopicScore;
import vn.giasu.common.security.JwtIssuer;

/** P17 assistant on real PostgreSQL; care, assessment and booking are mocked. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = AiApplication.class, properties = "JWT_SECRET=test-secret-test-secret-test-secret-123")
class AiApiPostgresTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    static final UUID MEET_FAQ = UUID.fromString("10000000-0000-0000-0000-000000000003");

    @Autowired WebApplicationContext context;
    @Autowired JwtIssuer issuer;
    @Autowired JdbcClient jdbc;
    @MockitoBean Clients clients;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
        when(clients.faqs()).thenReturn(List.of(new Faq(MEET_FAQ, "Tôi xem link học online ở đâu?",
                "Link Google Meet nằm ở thẻ buổi học trong Lịch học (nút Vào lớp học).", "meet, link học, online, vào lớp")));
    }

    ResultActions call(MockHttpServletRequestBuilder req, UUID id, String role, String json) throws Exception {
        if (id != null) req.header("Authorization", "Bearer " + issuer.issue(id, role, "x@t.vn", Duration.ofHours(1)).getTokenValue());
        if (json != null) req.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(req);
    }

    String ask(UUID who, String question) throws Exception {
        return call(post("/api/v1/ai/chat"), who, "LEARNER", "{\"question\":\"" + question + "\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void answersWithSourceOrSaysItDoesNotKnowAndEscalates() throws Exception {
        UUID learner = UUID.randomUUID();
        String late = ask(learner, "Gia sư vào lớp trễ 15 phút thì sao?");
        org.junit.jupiter.api.Assertions.assertEquals("POL-START", JsonPath.read(late, "$.sources[0].ref"));
        String meet = ask(learner, "Link Google Meet để học online ở đâu?");
        org.junit.jupiter.api.Assertions.assertEquals(MEET_FAQ.toString(), JsonPath.read(meet, "$.sources[0].ref"));

        String unknown = ask(learner, "Giá vàng hôm nay bao nhiêu, gọi tôi 0901234567");
        org.junit.jupiter.api.Assertions.assertEquals(false, JsonPath.read(unknown, "$.matched"));
        String turn = JsonPath.read(unknown, "$.turnId");
        verify(clients).reportGap(argThat(q -> q.contains("[số điện thoại]") && !q.contains("0901234567")), eq("NO_ANSWER"), eq(learner));
        assertFalse(jdbc.sql("SELECT question FROM chat_turns WHERE id = ?").param(UUID.fromString(turn)).query(String.class).single()
                .contains("0901234567"));

        UUID ticket = UUID.randomUUID();
        when(clients.openTicket(eq(learner), eq("LEARNER"), anyString(), anyString())).thenReturn(ticket);
        call(post("/api/v1/ai/chat/turns/" + turn + "/escalate"), UUID.randomUUID(), "LEARNER", null).andExpect(status().isForbidden());
        call(post("/api/v1/ai/chat/turns/" + turn + "/escalate"), learner, "LEARNER", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.ticketId").value(ticket.toString()));
        call(post("/api/v1/ai/chat/turns/" + turn + "/escalate"), learner, "LEARNER", null).andExpect(status().isConflict());

        String lateTurn = JsonPath.read(late, "$.turnId");
        call(post("/api/v1/ai/chat/turns/" + lateTurn + "/feedback"), learner, "LEARNER", "{\"helpful\":false}")
                .andExpect(jsonPath("$.helpful").value(false));
        verify(clients).reportGap(anyString(), eq("UNHELPFUL"), eq(learner));

        call(get("/api/v1/ai/chat/history"), learner, "LEARNER", null).andExpect(jsonPath("$.length()").value(3));
        call(get("/api/v1/ai/admin/stats"), UUID.randomUUID(), "SUPPORT", null).andExpect(jsonPath("$.escalated").value(1));
        call(get("/api/v1/ai/admin/stats"), learner, "LEARNER", null).andExpect(status().isForbidden());
    }

    @Test
    void dailyLimitStopsTheThirtyFirstQuestion() throws Exception {
        UUID learner = UUID.randomUUID();
        for (int i = 0; i < 30; i++) {
            jdbc.sql("INSERT INTO chat_turns (id, conversation_id, account_id, account_role, question, answer, matched, score) "
                    + "VALUES (?, ?, ?, 'LEARNER', 'q', 'a', false, 0)").params(UUID.randomUUID(), UUID.randomUUID(), learner).update();
        }
        call(post("/api/v1/ai/chat"), learner, "LEARNER", "{\"question\":\"Kỳ học tối đa bao nhiêu tuần?\"}")
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("DAILY_LIMIT"));
    }

    @Test
    void explainsWrongAnswersAndSummarisesThePlan() throws Exception {
        UUID learner = UUID.randomUUID();
        UUID tutor = UUID.randomUUID();
        UUID plan = UUID.randomUUID();
        UUID examId = UUID.randomUUID();
        Exam exam = new Exam(examId, plan, tutor, learner, "PERIODIC", "Bài định kỳ 1");
        when(clients.examResult(examId)).thenReturn(new Result(exam, 50,
                List.of(new TopicScore("Đại số", 1, 1, 100), new TopicScore("Hình học", 0, 1, 0)),
                List.of(new ResultItem(1, "Đại số", "2 + 2 = ?", List.of("4", "5", "6", "7"), "A", "A", true, "Cộng hai số"),
                        new ResultItem(2, "Hình học", "Tam giác có mấy cạnh?", List.of("4", "3", "5", "6"), "A", "B", false, "Tam giác có 3 cạnh"))));
        call(get("/api/v1/ai/learning/exams/" + examId + "/explain"), learner, "LEARNER", null)
                .andExpect(jsonPath("$.wrong.length()").value(1)).andExpect(jsonPath("$.wrong[0].correctText").value("3"))
                .andExpect(jsonPath("$.review[0].topic").value("Hình học"));
        call(get("/api/v1/ai/learning/exams/" + examId + "/explain"), UUID.randomUUID(), "LEARNER", null).andExpect(status().isForbidden());

        when(clients.plan(plan)).thenReturn(new PlanSummary(plan, learner, tutor, "TOAN", "THCS", "CONFIRMED"));
        when(clients.gradebook(plan)).thenReturn(new Gradebook(List.of(plan), List.of(
                new GradebookEntry(new Exam(UUID.randomUUID(), plan, tutor, learner, "PLACEMENT", "Đầu vào"), 40.0, List.of()),
                new GradebookEntry(exam, 50.0, List.of())),
                List.of(new Progress("Đại số", 50.0, 100.0, 50.0), new Progress("Hình học", 30.0, 0.0, -30.0))));
        call(get("/api/v1/ai/learning/plans/" + plan + "/summary"), tutor, "TUTOR", null)
                .andExpect(jsonPath("$.examsTaken").value(2)).andExpect(jsonPath("$.placementPercent").value(40.0))
                .andExpect(jsonPath("$.text").value(containsString("Cần ôn: Hình học")));
        call(get("/api/v1/ai/learning/plans/" + plan + "/summary"), UUID.randomUUID(), "TUTOR", null).andExpect(status().isForbidden());
    }
}
