package vn.giasu.care;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import vn.giasu.care.Clients.CareSignal;
import vn.giasu.common.security.JwtIssuer;

/** P18 tickets, FAQ and care tiers on real PostgreSQL. Booking, assessment and identity are mocked. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = CareApplication.class, properties = {"JWT_SECRET=test-secret-test-secret-test-secret-123", "care.tier.cron=-"})
class CareApiPostgresTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired WebApplicationContext context;
    @Autowired JwtIssuer issuer;
    @Autowired JdbcClient jdbc;
    @MockitoBean Clients clients;
    @MockitoBean Notifier notifier;
    MockMvc mvc;
    final UUID support = UUID.randomUUID();

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

    @Test
    void ticketIsAnsweredTurnedIntoCaseAndResolved() throws Exception {
        UUID learner = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        String id = JsonPath.read(call(post("/api/v1/care/tickets"), learner, "LEARNER", """
                {"category":"TUTOR","subject":"Gia sư đến muộn","body":"Buổi hôm qua gia sư vào muộn 30 phút","sessionId":"%s"}""".formatted(session))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ticket.status").value("NEW"))
                .andReturn().getResponse().getContentAsString(), "$.ticket.id");

        call(get("/api/v1/care/tickets/" + id), UUID.randomUUID(), "LEARNER", null).andExpect(status().isForbidden());
        call(get("/api/v1/care/support/tickets?status=NEW"), support, "SUPPORT", null).andExpect(jsonPath("$[*].id", hasItem(id)));
        call(get("/api/v1/care/support/tickets"), learner, "LEARNER", null).andExpect(status().isForbidden());

        call(post("/api/v1/care/tickets/" + id + "/messages"), support, "SUPPORT", "{\"body\":\"CSKH đã ghi nhận, đang liên hệ gia sư\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.ticket.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.ticket.firstResponseAt").isNotEmpty()).andExpect(jsonPath("$.messages.length()").value(2));
        verify(notifier).send(eq(learner), eq("TICKET_REPLIED"), anyString(), anyString(), eq("TICKET"), eq(UUID.fromString(id)));

        when(clients.caseFromTicket(eq(session), anyString())).thenReturn(caseId);
        call(post("/api/v1/care/support/tickets/" + id + "/case"), support, "SUPPORT", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.ticket.caseId").value(caseId.toString()));
        call(post("/api/v1/care/support/tickets/" + id + "/case"), support, "SUPPORT", null).andExpect(status().isConflict());

        call(post("/api/v1/care/support/tickets/" + id + "/resolve"), support, "SUPPORT", null)
                .andExpect(jsonPath("$.ticket.status").value("RESOLVED"));
        // The author writing again reopens it.
        call(post("/api/v1/care/tickets/" + id + "/messages"), learner, "LEARNER", "{\"body\":\"Hôm nay vẫn muộn\"}")
                .andExpect(jsonPath("$.ticket.status").value("IN_PROGRESS")).andExpect(jsonPath("$.ticket.resolvedAt").isEmpty());
        call(get("/api/v1/care/support/tickets/stats"), support, "SUPPORT", null).andExpect(jsonPath("$[*].category", hasItem("TUTOR")));
    }

    @Test
    void faqFeedsTheAssistantAndGapsComeBack() throws Exception {
        call(post("/api/v1/care/support/faqs"), support, "SUPPORT", """
                {"question":"Làm sao đổi giờ học cố định?","answer":"Gửi yêu cầu đổi khung giờ trong kỳ, gia sư xác nhận.","tags":"đổi lịch"}""")
                .andExpect(status().isCreated());
        call(post("/api/v1/care/support/faqs"), UUID.randomUUID(), "LEARNER", "{\"question\":\"x\",\"answer\":\"y\"}").andExpect(status().isForbidden());
        call(get("/api/v1/care/faqs?q=đổi giờ"), UUID.randomUUID(), "LEARNER", null)
                .andExpect(jsonPath("$[*].question", hasItem("Làm sao đổi giờ học cố định?")));

        call(get("/api/v1/care/internal/faqs"), UUID.randomUUID(), "LEARNER", null).andExpect(status().isForbidden());
        call(get("/api/v1/care/internal/faqs"), UUID.randomUUID(), "SERVICE", null).andExpect(jsonPath("$.length()").value(6));

        UUID asker = UUID.randomUUID();
        call(post("/api/v1/care/internal/faq-gaps"), UUID.randomUUID(), "SERVICE", """
                {"question":"Có học thử miễn phí không?","reason":"NO_ANSWER","accountId":"%s"}""".formatted(asker)).andExpect(status().isCreated());
        call(get("/api/v1/care/support/faq-gaps"), support, "SUPPORT", null).andExpect(jsonPath("$[*].question", hasItem("Có học thử miễn phí không?")));

        String ticket = JsonPath.read(call(post("/api/v1/care/internal/tickets"), UUID.randomUUID(), "SERVICE", """
                {"authorId":"%s","authorRole":"LEARNER","subject":"Trợ lý chưa trả lời được","body":"Có học thử miễn phí không?"}""".formatted(asker))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        assertEquals("CHATBOT", jdbc.sql("SELECT source FROM tickets WHERE id = ?").param(UUID.fromString(ticket)).query(String.class).single());
    }

    @Test
    void tiersOpenOneTaskAndGatePhoneLookups() throws Exception {
        UUID steady = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        UUID openCase = UUID.randomUUID();
        when(clients.careSignals()).thenReturn(List.of(
                new CareSignal(steady, 0, 0, 0, 0, false, false, false),
                new CareSignal(missing, 1, 1, 0, 0, false, false, false),
                new CareSignal(openCase, 0, 0, 0, 1, false, false, false)));
        when(clients.scoreSignals()).thenReturn(List.of());
        when(clients.phone(missing)).thenReturn("0901234567");

        call(post("/api/v1/care/support/care/recompute"), support, "SUPPORT", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.tasksCreated").value(2));
        call(post("/api/v1/care/support/care/recompute"), support, "SUPPORT", null).andExpect(jsonPath("$.tasksCreated").value(0));
        call(get("/api/v1/care/support/care/tiers?tier=4"), support, "SUPPORT", null)
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].learnerId").value(openCase.toString()));
        assertEquals(1, jdbc.sql("SELECT count(*) FROM care_tier_history WHERE learner_id = ?").param(missing).query(Long.class).single());

        call(get("/api/v1/care/support/care/learners/" + steady + "/phone"), support, "SUPPORT", null).andExpect(status().isForbidden());
        call(get("/api/v1/care/support/care/learners/" + missing + "/phone"), support, "SUPPORT", null)
                .andExpect(jsonPath("$.phone").value("0901234567"));
        assertEquals(1, jdbc.sql("SELECT count(*) FROM contact_views WHERE account_id = ?").param(missing).query(Long.class).single());

        call(post("/api/v1/care/support/care/learners/" + missing + "/contacts"), support, "SUPPORT",
                "{\"channel\":\"PHONE\",\"reached\":true,\"note\":\"Học viên ốm, tuần sau học lại\"}").andExpect(status().isCreated());
        String task = JsonPath.read(call(get("/api/v1/care/support/care/learners/" + missing), support, "SUPPORT", null)
                .andExpect(jsonPath("$.current.tier").value(3)).andExpect(jsonPath("$.contacts.length()").value(1))
                .andReturn().getResponse().getContentAsString(), "$.tasks[0].id");
        call(post("/api/v1/care/support/care/tasks/" + task + "/done"), support, "SUPPORT", "{\"note\":\"Đã gọi\"}")
                .andExpect(jsonPath("$.status").value("DONE"));
        call(post("/api/v1/care/support/care/tasks/" + task + "/done"), support, "SUPPORT", "{\"note\":\"Đã gọi\"}").andExpect(status().isConflict());
        call(get("/api/v1/care/support/care/stats"), UUID.randomUUID(), "ADMIN", null).andExpect(jsonPath("$.openTasks").value(1));
        verify(notifier, org.mockito.Mockito.never()).send(eq(steady), any(), any(), any(), any(), any());
    }
}
