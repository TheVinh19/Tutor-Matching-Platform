package vn.giasu.booking;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import vn.giasu.booking.holds.HoldJobs;
import vn.giasu.common.security.JwtIssuer;

/** Availability, plan/session lifecycle and double-booking protection on real PostgreSQL. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = BookingApplication.class, properties = {"JWT_SECRET=test-secret-test-secret-test-secret-123", "booking.jobs.delay=3600000"})
class BookingApiPostgresTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired WebApplicationContext context;
    @Autowired JwtIssuer issuer;
    @Autowired JdbcClient jdbc;
    @Autowired HoldJobs jobs;
    @MockitoBean TutorClient tutors;
    @MockitoBean Notifier notifier;
    MockMvc mvc;

    /** Monday at least a week away, so the first session is always > 12h ahead. */
    final LocalDate monday = Schedule.today().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(1);

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
        when(tutors.requireApproved(any())).thenAnswer(inv -> new TutorClient.TutorProfile(inv.getArgument(0), "GS", "APPROVED", "FLEX", 1,
                List.of(new TutorClient.SubjectLevel("TOAN", "THPT"))));
    }

    String token(UUID id, String role) { return "Bearer " + issuer.issue(id, role, "x@t.vn", Duration.ofHours(1)).getTokenValue(); }

    ResultActions call(MockHttpServletRequestBuilder req, UUID id, String role, String json) throws Exception {
        if (id != null) req.header("Authorization", token(id, role));
        if (json != null) req.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(req);
    }

    void openMondayMorning(UUID tutor) throws Exception {
        call(post("/api/v1/bookings/availability"), tutor, "TUTOR", """
                {"dayOfWeek":1,"startTime":"08:00","endTime":"12:00","mode":"FLEX","effectiveFrom":"%s"}""".formatted(Schedule.today()))
                .andExpect(status().isCreated());
    }

    UUID requestPlan(UUID learner, UUID tutor) throws Exception {
        String body = call(post("/api/v1/bookings/plans"), learner, "LEARNER", """
                {"tutorId":"%s","subjectCode":"TOAN","levelCode":"THPT","mode":"ONLINE","note":"Ôn thi"}""".formatted(tutor))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.plan.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.plan.id"));
    }

    ResultActions propose(UUID tutor, UUID plan, String startTime) throws Exception {
        return call(post("/api/v1/bookings/plans/" + plan + "/propose"), tutor, "TUTOR", """
                {"startDate":"%s","slots":[{"dayOfWeek":1,"startTime":"%s"}],"meetLink":"https://meet.google.com/abc"}""".formatted(monday, startTime));
    }

    @Test
    void availabilityRules() throws Exception {
        UUID tutor = UUID.randomUUID();
        openMondayMorning(tutor);
        String today = Schedule.today().toString();
        call(post("/api/v1/bookings/availability"), tutor, "TUTOR", """
                {"dayOfWeek":1,"startTime":"10:00","endTime":"14:00","mode":"ONLINE","effectiveFrom":"%s"}""".formatted(today))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AVAILABILITY_OVERLAP"));
        call(post("/api/v1/bookings/availability"), tutor, "TUTOR", """
                {"dayOfWeek":2,"startTime":"07:00","endTime":"10:00","mode":"ONLINE","effectiveFrom":"%s"}""".formatted(today))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
        call(post("/api/v1/bookings/availability"), tutor, "TUTOR", """
                {"dayOfWeek":2,"startTime":"18:00","endTime":"19:30","mode":"ONLINE","effectiveFrom":"%s"}""".formatted(today))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
        call(post("/api/v1/bookings/availability"), UUID.randomUUID(), "LEARNER", """
                {"dayOfWeek":2,"startTime":"18:00","endTime":"22:00","mode":"ONLINE","effectiveFrom":"%s"}""".formatted(today))
                .andExpect(status().isForbidden());

        call(get("/api/v1/bookings/availability/tutors/" + tutor), null, null, null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        String internal = "/api/v1/bookings/internal/availability/tutors?day=1&from=09:00&to=11:00&fromDate=" + monday + "&toDate=" + monday.plusWeeks(3);
        call(get(internal), UUID.randomUUID(), "LEARNER", null).andExpect(status().isForbidden());
        call(get(internal), UUID.randomUUID(), "SERVICE", null).andExpect(status().isOk()).andExpect(jsonPath("$", hasItem(tutor.toString())));

        String slotId = JsonPath.read(call(get("/api/v1/bookings/availability/me"), tutor, "TUTOR", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        call(delete("/api/v1/bookings/availability/" + slotId), UUID.randomUUID(), "TUTOR", null).andExpect(status().isNotFound());
        call(delete("/api/v1/bookings/availability/" + slotId), tutor, "TUTOR", null).andExpect(status().isNoContent());
    }

    @Test
    void calendarFlowTutorPicksFromLearnerSlots() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        String saveUrl = "/api/v1/bookings/availability/me";
        call(put(saveUrl), tutor, "TUTOR", """
                {"windows":[{"dayOfWeek":1,"startTime":"08:00","endTime":"09:30"}]}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
        call(put(saveUrl), tutor, "TUTOR", """
                {"windows":[{"dayOfWeek":1,"startTime":"08:00","endTime":"12:00"},{"dayOfWeek":1,"startTime":"11:00","endTime":"14:00"}]}""")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AVAILABILITY_OVERLAP"));
        // Monday 08-12 and Wednesday 08-13: the two examples from the spec.
        call(put(saveUrl), tutor, "TUTOR", """
                {"windows":[{"dayOfWeek":1,"startTime":"08:00","endTime":"12:00"},{"dayOfWeek":3,"startTime":"08:00","endTime":"13:00"}]}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));

        String request = """
                {"tutorId":"%s","subjectCode":"TOAN","levelCode":"THPT","mode":"ONLINE","sessionsPerWeek":2,"startDate":"%s","weeks":2,"preferredSlots":%s}""";
        String three = "[{\"dayOfWeek\":1,\"startTime\":\"09:00\"},{\"dayOfWeek\":3,\"startTime\":\"08:00\"},{\"dayOfWeek\":3,\"startTime\":\"11:00\"}]";
        call(post("/api/v1/bookings/plans"), learner, "LEARNER", request.formatted(tutor, monday, "[{\"dayOfWeek\":1,\"startTime\":\"09:00\"}]"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOO_FEW_SLOTS"));
        call(post("/api/v1/bookings/plans"), learner, "LEARNER", request.formatted(tutor, monday,
                "[{\"dayOfWeek\":1,\"startTime\":\"09:00\"},{\"dayOfWeek\":1,\"startTime\":\"10:30\"}]"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OUTSIDE_AVAILABILITY")); // 10:30-12:30 > 12:00
        call(post("/api/v1/bookings/plans"), learner, "LEARNER", """
                {"tutorId":"%s","subjectCode":"TOAN","levelCode":"THPT","mode":"ONLINE","preferredSlots":%s}""".formatted(tutor, three))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INCOMPLETE_PREFERENCES"));
        String body = call(post("/api/v1/bookings/plans"), learner, "LEARNER", request.formatted(tutor, monday, three))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.plan.sessionsPerWeek").value(2))
                .andExpect(jsonPath("$.preferredSlots", hasSize(3))).andReturn().getResponse().getContentAsString();
        UUID plan = UUID.fromString(JsonPath.read(body, "$.plan.id"));

        String proposeUrl = "/api/v1/bookings/plans/" + plan + "/propose";
        call(post(proposeUrl), tutor, "TUTOR", """
                {"slots":[{"dayOfWeek":1,"startTime":"09:00"}]}""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WRONG_SLOT_COUNT"));
        call(post(proposeUrl), tutor, "TUTOR", """
                {"slots":[{"dayOfWeek":1,"startTime":"09:00"},{"dayOfWeek":3,"startTime":"10:00"}]}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_PREFERRED_SLOT"));
        call(post(proposeUrl), tutor, "TUTOR", """
                {"slots":[{"dayOfWeek":1,"startTime":"09:00"},{"dayOfWeek":3,"startTime":"08:00"}]}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessions", hasSize(4)))
                .andExpect(jsonPath("$.plan.endDate").value(monday.plusWeeks(2).minusDays(1).toString()));

        // Mon 08-12 minus 09-11 (+60 min each side) leaves nothing >= 2h; Wed 08-13 minus 08-10 (+60) leaves 11-13.
        call(get("/api/v1/bookings/availability/tutors/" + tutor + "/free?weeks=2&fromDate=" + monday), null, null, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].dayOfWeek").value(3))
                .andExpect(jsonPath("$[0].startTime").value(org.hamcrest.Matchers.startsWith("11:00")))
                .andExpect(jsonPath("$[0].endTime").value(org.hamcrest.Matchers.startsWith("13:00")));
    }

    @Test
    void requestProposeConfirmCompleteAndPlanCloses() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        openMondayMorning(tutor);
        UUID plan = requestPlan(learner, tutor);
        verify(notifier).send(eq(tutor), eq("PLAN_REQUESTED"), anyString(), anyString(), eq("PLAN"), eq(plan));

        call(post("/api/v1/bookings/plans"), learner, "LEARNER", """
                {"tutorId":"%s","subjectCode":"VAN","levelCode":"THPT","mode":"ONLINE"}""".formatted(tutor))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SUBJECT_NOT_OFFERED"));
        propose(tutor, plan, "11:00").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OUTSIDE_AVAILABILITY"));
        propose(tutor, plan, "07:00").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SLOT"));
        propose(UUID.randomUUID(), plan, "08:00").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_PLAN_TUTOR"));

        propose(tutor, plan, "08:00").andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.status").value("PROPOSED"))
                .andExpect(jsonPath("$.plan.endDate").value(monday.plusWeeks(4).minusDays(1).toString()))
                .andExpect(jsonPath("$.sessions", hasSize(4)))
                .andExpect(jsonPath("$.sessions[*].status", everyItem(is("HELD"))));

        call(post("/api/v1/bookings/plans/" + plan + "/confirm"), UUID.randomUUID(), "LEARNER", null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_PLAN_OWNER"));
        call(post("/api/v1/bookings/plans/" + plan + "/confirm"), tutor, "TUTOR", null).andExpect(status().isForbidden());
        call(post("/api/v1/bookings/plans/" + plan + "/confirm"), learner, "LEARNER", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.plan.paymentStatus").value("PAYMENT_PENDING_MOCK"))
                .andExpect(jsonPath("$.sessions[*].status", everyItem(is("SCHEDULED"))))
                .andExpect(jsonPath("$.sessions[0].meetLink").value("https://meet.google.com/abc"));

        List<UUID> sessions = jdbc.sql("SELECT id FROM sessions WHERE plan_id = ? ORDER BY start_at").param(plan).query(UUID.class).list();
        UUID first = sessions.get(0);
        call(post("/api/v1/bookings/sessions/" + first + "/complete"), tutor, "TUTOR", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_NOT_STARTED"));
        call(get("/api/v1/bookings/sessions/" + first), UUID.randomUUID(), "LEARNER", null).andExpect(status().isForbidden());
        call(get("/api/v1/bookings/sessions/" + first), UUID.randomUUID(), "ADMIN", null).andExpect(status().isOk());

        // Move every session into the past so the tutor can mark them taught.
        jdbc.sql("""
                UPDATE sessions SET start_at = now() - (interval '1 day' * (5 - n.rn)) - interval '3 hours',
                                    end_at = now() - (interval '1 day' * (5 - n.rn)) - interval '1 hour',
                                    block_until = now() - (interval '1 day' * (5 - n.rn))
                FROM (SELECT id, row_number() OVER (ORDER BY start_at) AS rn FROM sessions WHERE plan_id = ?) n WHERE sessions.id = n.id""")
                .param(plan).update();
        for (UUID s : sessions) {
            call(post("/api/v1/bookings/sessions/" + s + "/complete"), tutor, "TUTOR", null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED_PENDING_CONFIRM"));
        }
        call(post("/api/v1/bookings/sessions/" + first + "/confirm"), UUID.randomUUID(), "ADMIN", null).andExpect(status().isForbidden());
        call(post("/api/v1/bookings/sessions/" + first + "/dispute"), learner, "LEARNER", "{\"reason\":\"\"}").andExpect(status().isBadRequest());
        for (UUID s : sessions.subList(0, 3)) {
            call(post("/api/v1/bookings/sessions/" + s + "/confirm"), learner, "LEARNER", null).andExpect(jsonPath("$.status").value("CONFIRMED"));
        }
        call(get("/api/v1/bookings/plans/" + plan), learner, "LEARNER", null).andExpect(jsonPath("$.plan.status").value("CONFIRMED"));

        // Last one is accepted by the 24h auto-confirm job.
        jdbc.sql("UPDATE sessions SET confirm_deadline = now() - interval '1 minute' WHERE id = ?").param(sessions.get(3)).update();
        assertEquals(1, jobs.autoConfirm());
        call(get("/api/v1/bookings/plans/" + plan), learner, "LEARNER", null)
                .andExpect(jsonPath("$.plan.status").value("COMPLETED"))
                .andExpect(jsonPath("$.sessions[3].autoConfirmed").value(true));
        call(get("/api/v1/bookings/internal/plans/" + plan + "/review-eligibility"), UUID.randomUUID(), "SERVICE", null)
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.learnerId").value(learner.toString()));
        verify(notifier, atLeastOnce()).send(eq(learner), eq("PLAN_COMPLETED"), anyString(), anyString(), eq("PLAN"), eq(plan));
    }

    @Test
    void overlappingOrTooCloseSessionsAreRejected() throws Exception {
        UUID tutor = UUID.randomUUID();
        openMondayMorning(tutor);
        UUID first = requestPlan(UUID.randomUUID(), tutor);
        propose(tutor, first, "08:00").andExpect(status().isOk());

        UUID second = requestPlan(UUID.randomUUID(), tutor);
        propose(tutor, second, "08:00").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SCHEDULE_CONFLICT"))
                .andExpect(jsonPath("$.details[0].side").value("TUTOR"));
        propose(tutor, second, "10:30").andExpect(status().isBadRequest()); // 10:30-12:30 is outside 08-12
        jdbc.sql("UPDATE availability_slots SET end_time = '14:00' WHERE tutor_id = ?").param(tutor).update();
        propose(tutor, second, "10:30").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SCHEDULE_CONFLICT")); // gap 30 min
        propose(tutor, second, "11:00").andExpect(status().isOk()); // gap exactly 60 min

        // Two slots of one plan on the same day must also keep 60 minutes apart.
        UUID third = requestPlan(UUID.randomUUID(), tutor);
        call(post("/api/v1/bookings/plans/" + third + "/propose"), tutor, "TUTOR", """
                {"startDate":"%s","slots":[{"dayOfWeek":3,"startTime":"08:00"},{"dayOfWeek":3,"startTime":"10:30"}]}""".formatted(monday))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SLOTS_TOO_CLOSE"));
    }

    @Test
    void concurrentProposalsForTheSameSlotOnlyOneWins() throws Exception {
        UUID tutor = UUID.randomUUID();
        openMondayMorning(tutor);
        List<UUID> planIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) planIds.add(requestPlan(UUID.randomUUID(), tutor));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(planIds.size());
        List<Future<Integer>> results = new ArrayList<>();
        for (UUID plan : planIds) {
            Callable<Integer> task = () -> {
                start.await();
                return propose(tutor, plan, "08:00").andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) statuses.add(f.get());
        pool.shutdown();
        assertEquals(1, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
        assertEquals(planIds.size() - 1, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
        assertEquals(4L, jdbc.sql("SELECT count(*) FROM sessions WHERE tutor_id = ? AND status = 'HELD'").param(tutor).query(Long.class).single());
    }

    @Test
    void expiredHoldIsReleasedAndSlotReusable() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        openMondayMorning(tutor);
        UUID plan = requestPlan(learner, tutor);
        propose(tutor, plan, "08:00").andExpect(status().isOk());
        jdbc.sql("UPDATE plans SET hold_expires_at = now() - interval '1 minute' WHERE id = ?").param(plan).update();

        call(post("/api/v1/bookings/plans/" + plan + "/confirm"), learner, "LEARNER", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
        UUID other = requestPlan(UUID.randomUUID(), tutor);
        propose(tutor, other, "08:00").andExpect(status().isOk()); // propose frees expired holds first
        call(get("/api/v1/bookings/plans/" + plan), learner, "LEARNER", null)
                .andExpect(jsonPath("$.plan.status").value("EXPIRED")).andExpect(jsonPath("$.sessions", hasSize(0)));
    }

    @Test
    void cancelReportRejectAndMeetLink() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        openMondayMorning(tutor);
        UUID plan = requestPlan(learner, tutor);
        propose(tutor, plan, "08:00").andExpect(status().isOk());
        call(post("/api/v1/bookings/plans/" + plan + "/confirm"), learner, "LEARNER", null).andExpect(status().isOk());
        UUID session = jdbc.sql("SELECT id FROM sessions WHERE plan_id = ? ORDER BY start_at LIMIT 1").param(plan).query(UUID.class).single();

        call(put("/api/v1/bookings/sessions/" + session + "/meet-link"), tutor, "TUTOR", "{\"meetLink\":\"http://insecure\"}").andExpect(status().isBadRequest());
        call(put("/api/v1/bookings/sessions/" + session + "/meet-link"), tutor, "TUTOR", "{\"meetLink\":\"https://meet.google.com/new\"}")
                .andExpect(jsonPath("$.meetLink").value("https://meet.google.com/new"));
        call(post("/api/v1/bookings/sessions/" + session + "/cancel"), UUID.randomUUID(), "LEARNER", "{\"reason\":\"Bận\"}").andExpect(status().isForbidden());
        call(post("/api/v1/bookings/sessions/" + session + "/cancel"), learner, "LEARNER", "{\"reason\":\"Con bị ốm\"}")
                .andExpect(jsonPath("$.status").value("CANCEL_REPORTED")).andExpect(jsonPath("$.cancelledBy").value(learner.toString()));
        verify(notifier).send(eq(tutor), eq("SESSION_CANCEL_REPORTED"), anyString(), anyString(), eq("SESSION"), eq(session));
        call(get("/api/v1/bookings/sessions/me"), learner, "LEARNER", null).andExpect(status().isOk());
        call(get("/api/v1/bookings/plans/me"), tutor, "TUTOR", null).andExpect(jsonPath("$[*].id", hasItem(plan.toString())));

        UUID declined = requestPlan(UUID.randomUUID(), tutor);
        call(post("/api/v1/bookings/plans/" + declined + "/reject"), tutor, "TUTOR", "{\"reason\":\"Kín lịch\"}")
                .andExpect(jsonPath("$.plan.status").value("REJECTED"));
        call(post("/api/v1/bookings/plans/" + declined + "/reject"), tutor, "TUTOR", "{\"reason\":\"Kín lịch\"}").andExpect(status().isConflict());
        call(get("/api/v1/bookings/admin/plans?status=REJECTED"), UUID.randomUUID(), "ADMIN", null)
                .andExpect(jsonPath("$[*].id", hasItem(declined.toString())));
    }
}
