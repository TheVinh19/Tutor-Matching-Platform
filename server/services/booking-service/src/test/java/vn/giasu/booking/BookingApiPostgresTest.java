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
    @MockitoBean IdentityClient identity;
    MockMvc mvc;

    /** Monday at least a week away, so the first session is always > 12h ahead. */
    final LocalDate monday = Schedule.today().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(1);

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
        when(tutors.requireApproved(any())).thenAnswer(inv -> new TutorClient.TutorProfile(inv.getArgument(0), "GS", "APPROVED", "FLEX", 1,
                List.of(new TutorClient.SubjectLevel("TOAN", "THPT")), false));
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
        String two = "[{\"dayOfWeek\":1,\"startTime\":\"09:00\"},{\"dayOfWeek\":3,\"startTime\":\"08:00\"}]";
        String three = "[{\"dayOfWeek\":1,\"startTime\":\"09:00\"},{\"dayOfWeek\":3,\"startTime\":\"08:00\"},{\"dayOfWeek\":3,\"startTime\":\"11:00\"}]";
        call(post("/api/v1/bookings/plans"), learner, "LEARNER", request.formatted(tutor, monday, "[{\"dayOfWeek\":1,\"startTime\":\"09:00\"}]"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WRONG_SLOT_COUNT"));
        call(post("/api/v1/bookings/plans"), learner, "LEARNER", request.formatted(tutor, monday, three))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WRONG_SLOT_COUNT")); // exactly 2, not more
        call(post("/api/v1/bookings/plans"), learner, "LEARNER", request.formatted(tutor, monday,
                "[{\"dayOfWeek\":3,\"startTime\":\"08:00\"},{\"dayOfWeek\":3,\"startTime\":\"11:00\"}]"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ONE_SLOT_PER_DAY"));
        call(post("/api/v1/bookings/plans"), learner, "LEARNER", request.formatted(tutor, monday,
                "[{\"dayOfWeek\":1,\"startTime\":\"10:30\"},{\"dayOfWeek\":3,\"startTime\":\"08:00\"}]"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OUTSIDE_AVAILABILITY")); // 10:30-12:30 > 12:00
        call(post("/api/v1/bookings/plans"), learner, "LEARNER", """
                {"tutorId":"%s","subjectCode":"TOAN","levelCode":"THPT","mode":"ONLINE","preferredSlots":%s}""".formatted(tutor, two))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INCOMPLETE_PREFERENCES"));
        String body = call(post("/api/v1/bookings/plans"), learner, "LEARNER", request.formatted(tutor, monday, two))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.plan.sessionsPerWeek").value(2))
                .andExpect(jsonPath("$.preferredSlots", hasSize(2))).andReturn().getResponse().getContentAsString();
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

        // KN01: phones stay hidden until the plan is confirmed.
        String contactUrl = "/api/v1/bookings/plans/" + plan + "/contact";
        call(get(contactUrl), learner, "LEARNER", null).andExpect(status().isConflict());
        call(post("/api/v1/bookings/plans/" + plan + "/confirm"), UUID.randomUUID(), "LEARNER", null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_PLAN_OWNER"));
        call(post("/api/v1/bookings/plans/" + plan + "/confirm"), tutor, "TUTOR", null).andExpect(status().isForbidden());
        call(post("/api/v1/bookings/plans/" + plan + "/confirm"), learner, "LEARNER", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.plan.paymentStatus").value("PAYMENT_PENDING_MOCK"))
                .andExpect(jsonPath("$.sessions[*].status", everyItem(is("SCHEDULED"))))
                .andExpect(jsonPath("$.sessions[0].meetLink").value("https://meet.google.com/abc"));
        when(identity.phone(learner)).thenReturn("0901000004");
        when(identity.phone(tutor)).thenReturn("0901000002");
        call(get(contactUrl), tutor, "TUTOR", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.learnerPhone").value("0901000004")).andExpect(jsonPath("$.tutorPhone").value("0901000002"));
        call(get(contactUrl), UUID.randomUUID(), "LEARNER", null).andExpect(status().isForbidden());

        List<UUID> sessions = jdbc.sql("SELECT id FROM sessions WHERE plan_id = ? ORDER BY start_at").param(plan).query(UUID.class).list();
        UUID first = sessions.get(0);
        call(post("/api/v1/bookings/sessions/" + first + "/start"), tutor, "TUTOR", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_NOT_STARTED"));
        call(post("/api/v1/bookings/sessions/" + first + "/complete"), tutor, "TUTOR", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        call(get("/api/v1/bookings/sessions/" + first), UUID.randomUUID(), "LEARNER", null).andExpect(status().isForbidden());
        call(get("/api/v1/bookings/sessions/" + first), UUID.randomUUID(), "ADMIN", null).andExpect(status().isOk());

        // Move every session into the past, already started on time, so the tutor can mark them taught.
        jdbc.sql("""
                UPDATE sessions SET status = 'IN_PROGRESS',
                                    start_at = now() - (interval '1 day' * (5 - n.rn)) - interval '3 hours',
                                    started_at = now() - (interval '1 day' * (5 - n.rn)) - interval '3 hours',
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
                .andExpect(jsonPath("$.plan.status").value("HOAN_TAT_NGHIA_VU"))
                .andExpect(jsonPath("$.sessions[3].autoConfirmed").value(true));
        call(get("/api/v1/bookings/internal/plans/" + plan + "/review-eligibility"), UUID.randomUUID(), "SERVICE", null)
                .andExpect(jsonPath("$.status").value("HOAN_TAT_NGHIA_VU")).andExpect(jsonPath("$.learnerId").value(learner.toString()));
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
    void availabilityEditKeepsBookedTime() throws Exception {
        UUID tutor = UUID.randomUUID();
        openMondayMorning(tutor);
        propose(tutor, requestPlan(UUID.randomUUID(), tutor), "08:00").andExpect(status().isOk()); // held Mondays 08:00-10:00
        call(put("/api/v1/bookings/availability/me"), tutor, "TUTOR", "{\"windows\":[]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKED_TIME_REMOVED"));
        call(put("/api/v1/bookings/availability/me"), tutor, "TUTOR", """
                {"windows":[{"dayOfWeek":1,"startTime":"10:00","endTime":"12:00"}]}""").andExpect(status().isConflict());
        call(put("/api/v1/bookings/availability/me"), tutor, "TUTOR", """
                {"windows":[{"dayOfWeek":1,"startTime":"08:00","endTime":"10:00"},{"dayOfWeek":3,"startTime":"14:00","endTime":"18:00"}]}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void cancelReportedSessionIsRescheduled() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        openMondayMorning(tutor);
        call(post("/api/v1/bookings/availability"), tutor, "TUTOR", """
                {"dayOfWeek":2,"startTime":"14:00","endTime":"18:00","mode":"FLEX","effectiveFrom":"%s"}""".formatted(Schedule.today()))
                .andExpect(status().isCreated());
        UUID plan = requestPlan(learner, tutor);
        propose(tutor, plan, "08:00").andExpect(status().isOk());
        call(post("/api/v1/bookings/plans/" + plan + "/confirm"), learner, "LEARNER", null).andExpect(status().isOk());
        List<UUID> sessions = jdbc.sql("SELECT id FROM sessions WHERE plan_id = ? ORDER BY start_at").param(plan).query(UUID.class).list();
        UUID first = sessions.get(0);
        UUID second = sessions.get(1);
        String week1 = monday.toString();
        String week2 = monday.plusWeeks(1).toString();

        call(post("/api/v1/bookings/sessions/" + first + "/reschedule"), learner, "LEARNER", "{\"startAt\":\"" + monday.plusDays(1) + "T14:00:00+07:00\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS")); // not reported yet
        call(post("/api/v1/bookings/sessions/" + first + "/cancel"), tutor, "TUTOR", "{\"reason\":\"Bận họp\"}").andExpect(status().isOk());

        // Learner picks from the tutor's free time: Monday is blocked (own plan sessions + gap), Tuesday 14-18 is open.
        call(get("/api/v1/bookings/sessions/" + first + "/reschedule/free?fromDate=" + week1), learner, "LEARNER", null)
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].dayOfWeek").value(2)).andExpect(jsonPath("$[0].startTime").value("14:00:00"));
        call(post("/api/v1/bookings/sessions/" + first + "/reschedule"), learner, "LEARNER", "{\"startAt\":\"" + monday.plusDays(1) + "T09:00:00+07:00\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_NOT_AVAILABLE"));
        call(post("/api/v1/bookings/sessions/" + first + "/reschedule"), learner, "LEARNER", "{\"startAt\":\"" + monday.plusDays(1) + "T15:00:00+07:00\"}")
                .andExpect(jsonPath("$.status").value("SCHEDULED")).andExpect(jsonPath("$.cancelReason").value(org.hamcrest.Matchers.nullValue()));
        verify(notifier).send(eq(tutor), eq("SESSION_RESCHEDULED"), anyString(), anyString(), eq("SESSION"), eq(first));

        // Learner sends Wednesday evening of week 2; tutor picks inside it, outside their public availability.
        call(post("/api/v1/bookings/sessions/" + second + "/cancel"), learner, "LEARNER", "{\"reason\":\"Ốm\"}").andExpect(status().isOk());
        call(get("/api/v1/bookings/sessions/" + second + "/reschedule/free?fromDate=" + week2), tutor, "TUTOR", null).andExpect(jsonPath("$", hasSize(0)));
        String offer = """
                {"fromDate":"%s","toDate":"%s","windows":[{"dayOfWeek":3,"startTime":"18:00","endTime":"22:00"}]}""";
        call(post("/api/v1/bookings/sessions/" + second + "/reschedule/offer"), tutor, "TUTOR", offer.formatted(week2, monday.plusDays(13)))
                .andExpect(status().isForbidden());
        call(post("/api/v1/bookings/sessions/" + second + "/reschedule/offer"), learner, "LEARNER", offer.formatted(week2, monday.plusDays(100)))
                .andExpect(status().isBadRequest());
        call(post("/api/v1/bookings/sessions/" + second + "/reschedule/offer"), learner, "LEARNER", offer.formatted(week2, monday.plusDays(13)))
                .andExpect(jsonPath("$.offerFrom").value(week2));
        verify(notifier).send(eq(tutor), eq("SESSION_RESCHEDULE_OFFER"), anyString(), anyString(), eq("SESSION"), eq(second));
        call(get("/api/v1/bookings/sessions/" + second + "/reschedule/free?fromDate=" + week1), tutor, "TUTOR", null).andExpect(jsonPath("$", hasSize(0)));
        call(get("/api/v1/bookings/sessions/" + second + "/reschedule/free?fromDate=" + week2), tutor, "TUTOR", null)
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].dayOfWeek").value(3));
        call(post("/api/v1/bookings/sessions/" + second + "/reschedule"), tutor, "TUTOR", "{\"startAt\":\"" + monday.plusDays(9) + "T19:00:00+07:00\"}")
                .andExpect(jsonPath("$.status").value("SCHEDULED")).andExpect(jsonPath("$.offerFrom").value(org.hamcrest.Matchers.nullValue()));
        assertEquals(monday.plusDays(9).atTime(19, 0).atZone(Schedule.ZONE).toInstant(),
                jdbc.sql("SELECT start_at FROM sessions WHERE id = ?").param(second).query(java.time.OffsetDateTime.class).single().toInstant());
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM session_offer_windows").query(Long.class).single());

        // Decline clears the offer and tells the learner.
        UUID third = sessions.get(2);
        call(post("/api/v1/bookings/sessions/" + third + "/cancel"), learner, "LEARNER", "{\"reason\":\"Thi\"}").andExpect(status().isOk());
        call(post("/api/v1/bookings/sessions/" + third + "/reschedule/decline"), tutor, "TUTOR", "{\"reason\":\"Kín\"}").andExpect(status().isConflict());
        call(post("/api/v1/bookings/sessions/" + third + "/reschedule/offer"), learner, "LEARNER", offer.formatted(week2, monday.plusDays(13)))
                .andExpect(status().isOk());
        call(post("/api/v1/bookings/sessions/" + third + "/reschedule/decline"), tutor, "TUTOR", "{\"reason\":\"Kín\"}")
                .andExpect(jsonPath("$.offerFrom").value(org.hamcrest.Matchers.nullValue()));
        verify(notifier).send(eq(learner), eq("SESSION_RESCHEDULE_DECLINED"), anyString(), eq("Kín"), eq("SESSION"), eq(third));
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

    // ---- session lifecycle, period closing, slot changes, renewals ----

    /** Confirmed plan with 4 Monday 08:00 sessions; returns the session ids in start order (plan id via planOf). */
    List<UUID> confirmedSessions(UUID learner, UUID tutor) throws Exception {
        openMondayMorning(tutor);
        UUID plan = requestPlan(learner, tutor);
        propose(tutor, plan, "08:00").andExpect(status().isOk());
        call(post("/api/v1/bookings/plans/" + plan + "/confirm"), learner, "LEARNER", null).andExpect(status().isOk());
        return jdbc.sql("SELECT id FROM sessions WHERE plan_id = ? ORDER BY start_at").param(plan).query(UUID.class).list();
    }

    UUID planOf(UUID session) { return jdbc.sql("SELECT plan_id FROM sessions WHERE id = ?").param(session).query(UUID.class).single(); }

    /** Moves a session so it starts at now + offset (a PostgreSQL interval such as '-20 minutes'). */
    void startsIn(UUID session, String offset) {
        jdbc.sql("""
                UPDATE sessions SET start_at = now() + cast(:o AS interval), end_at = now() + cast(:o AS interval) + interval '2 hours',
                    block_until = now() + cast(:o AS interval) + interval '3 hours' WHERE id = :id""")
                .param("o", offset).param("id", session).update();
    }

    /** Puts a session `daysAgo` days in the past, started on time, then the tutor ends it and the learner accepts. */
    void teachAndConfirm(UUID session, int daysAgo, UUID tutor, UUID learner) throws Exception {
        jdbc.sql("""
                UPDATE sessions SET status = 'IN_PROGRESS', start_at = now() - make_interval(days => :d) - interval '3 hours',
                    started_at = now() - make_interval(days => :d) - interval '3 hours', end_at = now() - make_interval(days => :d) - interval '1 hour',
                    block_until = now() - make_interval(days => :d) WHERE id = :id""")
                .param("d", daysAgo).param("id", session).update();
        call(post("/api/v1/bookings/sessions/" + session + "/complete"), tutor, "TUTOR", null).andExpect(status().isOk());
        call(post("/api/v1/bookings/sessions/" + session + "/confirm"), learner, "LEARNER", null).andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    String planStatus(UUID plan) { return jdbc.sql("SELECT status FROM plans WHERE id = ?").param(plan).query(String.class).single(); }

    @Test
    void startLateEarlyEndAndAutoConfirmNeedsStart() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        List<UUID> s = confirmedSessions(learner, tutor);
        String url = "/api/v1/bookings/sessions/" + s.get(0);

        startsIn(s.get(0), "30 minutes");
        call(post(url + "/start"), tutor, "TUTOR", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_NOT_STARTED"));
        startsIn(s.get(0), "-20 minutes");
        call(post(url + "/start"), UUID.randomUUID(), "TUTOR", null).andExpect(status().isForbidden());
        call(post(url + "/start"), tutor, "TUTOR", null).andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.startedAt").isNotEmpty()).andExpect(jsonPath("$.lateMinutes", org.hamcrest.Matchers.greaterThan(10)));
        call(post(url + "/start"), tutor, "TUTOR", null).andExpect(status().isConflict()); // once only
        // Even after the planned end the tutor can still start; it is only recorded as late.
        startsIn(s.get(2), "-1 day");
        call(post("/api/v1/bookings/sessions/" + s.get(2) + "/start"), tutor, "TUTOR", null)
                .andExpect(jsonPath("$.status").value("IN_PROGRESS")).andExpect(jsonPath("$.lateMinutes", org.hamcrest.Matchers.greaterThan(1000)));

        call(post(url + "/complete"), tutor, "TUTOR", null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EARLY_END_NOTE_REQUIRED"));
        call(post(url + "/complete"), tutor, "TUTOR", "{\"earlyEndBy\":\"LEARNER\",\"earlyEndNote\":\"  \"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EARLY_END_NOTE_REQUIRED"));
        call(post(url + "/complete"), tutor, "TUTOR", "{\"earlyEndBy\":\"LEARNER\",\"earlyEndNote\":\"Con mệt, xin nghỉ sớm\"}")
                .andExpect(jsonPath("$.status").value("COMPLETED_PENDING_CONFIRM"))
                .andExpect(jsonPath("$.earlyEndBy").value("LEARNER")).andExpect(jsonPath("$.earlyEndNote").value("Con mệt, xin nghỉ sớm"));

        // A session never started (old data) is not auto-accepted; a started one is.
        jdbc.sql("UPDATE sessions SET confirm_deadline = now() - interval '1 minute' WHERE id = ?").param(s.get(0)).update();
        jdbc.sql("""
                UPDATE sessions SET status = 'COMPLETED_PENDING_CONFIRM', completed_at = now(), confirm_deadline = now() - interval '1 minute'
                WHERE id = ?""").param(s.get(1)).update();
        jobs.autoConfirm();
        call(get(url), learner, "LEARNER", null).andExpect(jsonPath("$.status").value("CONFIRMED")).andExpect(jsonPath("$.autoConfirmed").value(true));
        call(get("/api/v1/bookings/sessions/" + s.get(1)), learner, "LEARNER", null).andExpect(jsonPath("$.status").value("COMPLETED_PENDING_CONFIRM"));
        call(post("/api/v1/bookings/sessions/" + s.get(1) + "/confirm"), learner, "LEARNER", null).andExpect(jsonPath("$.status").value("CONFIRMED"));

        // Late start and no start: both taught, neither earns the +10.
        call(get("/api/v1/bookings/internal/tutors/" + tutor + "/exp-sources"), UUID.randomUUID(), "SERVICE", null)
                .andExpect(jsonPath("$.events", hasSize(0))).andExpect(jsonPath("$.stats.taughtSessions").value(2));
    }

    @Test
    void absencesLeavePeriodWithOpenObligation() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        List<UUID> s = confirmedSessions(learner, tutor);
        UUID plan = planOf(s.get(0));
        String first = "/api/v1/bookings/sessions/" + s.get(0);

        startsIn(s.get(0), "-5 minutes");
        call(post(first + "/start"), tutor, "TUTOR", null).andExpect(jsonPath("$.lateMinutes").value(org.hamcrest.Matchers.nullValue()));
        call(post(first + "/learner-absent"), tutor, "TUTOR", "{\"reason\":\"Gọi 2 lần\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOO_EARLY_TO_REPORT_ABSENT"));
        startsIn(s.get(0), "-20 minutes");
        call(post(first + "/learner-absent"), tutor, "TUTOR", "{\"reason\":\"Gọi 2 lần không nghe\"}")
                .andExpect(jsonPath("$.status").value("LEARNER_ABSENT_PENDING")).andExpect(jsonPath("$.absentDeadline").isNotEmpty());
        verify(notifier).send(eq(learner), eq("SESSION_LEARNER_ABSENT"), anyString(), anyString(), eq("SESSION"), eq(s.get(0)));
        jdbc.sql("UPDATE sessions SET absent_deadline = now() - interval '1 minute' WHERE id = ?").param(s.get(0)).update();
        jobs.confirmAbsences();
        call(get(first), learner, "LEARNER", null).andExpect(jsonPath("$.status").value("LEARNER_ABSENT"));

        // Tutor never pressed start: the learner reports it after 15 minutes.
        String second = "/api/v1/bookings/sessions/" + s.get(1);
        startsIn(s.get(1), "-1 day -20 minutes");
        call(post(second + "/tutor-absent"), UUID.randomUUID(), "LEARNER", "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        call(post(second + "/tutor-absent"), learner, "LEARNER", "{\"reason\":\"Gia sư không vào lớp\"}")
                .andExpect(jsonPath("$.status").value("TUTOR_ABSENT_REPORTED"));

        teachAndConfirm(s.get(2), 3, tutor, learner);
        assertEquals("CONFIRMED", planStatus(plan));
        // Learner cancels the last one and nobody reschedules: cancelled, not the tutor's fault.
        call(post("/api/v1/bookings/sessions/" + s.get(3) + "/cancel"), learner, "LEARNER", "{\"reason\":\"Đi du lịch\"}")
                .andExpect(jsonPath("$.cancelDeadline").isNotEmpty());
        jdbc.sql("UPDATE sessions SET cancel_deadline = now() - interval '1 minute' WHERE id = ?").param(s.get(3)).update();
        jobs.cancelExpiredReports();
        assertEquals("HET_KY_CON_NGHIA_VU", planStatus(plan));
        call(get("/api/v1/bookings/plans/" + plan), learner, "LEARNER", null).andExpect(jsonPath("$.plan.closedAt").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void tutorCancellationEndsPeriodWithPartialRefund() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        List<UUID> s = confirmedSessions(learner, tutor);
        UUID plan = planOf(s.get(0));
        call(post("/api/v1/bookings/sessions/" + s.get(0) + "/cancel"), tutor, "TUTOR", "{\"reason\":\"Ốm\"}").andExpect(status().isOk());
        jdbc.sql("UPDATE sessions SET cancel_deadline = now() - interval '1 minute' WHERE id = ?").param(s.get(0)).update();
        jobs.cancelExpiredReports();
        call(get("/api/v1/bookings/sessions/" + s.get(0)), learner, "LEARNER", null).andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(notifier).send(eq(learner), eq("SESSION_CANCELLED"), anyString(), anyString(), eq("SESSION"), eq(s.get(0)));

        for (int i = 1; i < 4; i++) teachAndConfirm(s.get(i), 5 - i, tutor, learner);
        call(get("/api/v1/bookings/plans/" + plan), learner, "LEARNER", null)
                .andExpect(jsonPath("$.plan.status").value("KET_THUC_CO_HOAN_MOT_PHAN")).andExpect(jsonPath("$.plan.closedAt").isNotEmpty());
    }

    @Test
    void remindersAreSentOnce() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        List<UUID> s = confirmedSessions(learner, tutor);
        startsIn(s.get(0), "45 minutes");
        jobs.remindSessions();
        jobs.remindSessions();
        verify(notifier, org.mockito.Mockito.times(1)).send(eq(learner), eq("SESSION_REMINDER"), anyString(), anyString(), eq("SESSION"), eq(s.get(0)));
        startsIn(s.get(0), "20 minutes");
        jobs.remindSessions();
        verify(notifier, org.mockito.Mockito.times(2)).send(eq(tutor), eq("SESSION_REMINDER"), anyString(), anyString(), eq("SESSION"), eq(s.get(0)));
    }

    void openTuesdayAfternoon(UUID tutor) throws Exception {
        call(post("/api/v1/bookings/availability"), tutor, "TUTOR", """
                {"dayOfWeek":2,"startTime":"14:00","endTime":"18:00","mode":"FLEX","effectiveFrom":"%s"}""".formatted(Schedule.today()))
                .andExpect(status().isCreated());
    }

    String moveMondayToTuesday(LocalDate from) {
        return """
                {"fromDay":1,"fromTime":"08:00","toDay":2,"toTime":"15:00","effectiveFrom":"%s"}""".formatted(from);
    }

    @Test
    void slotChangeMovesRemainingSessions() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        List<UUID> s = confirmedSessions(learner, tutor);
        UUID plan = planOf(s.get(0));
        openTuesdayAfternoon(tutor);
        String url = "/api/v1/bookings/plans/" + plan + "/slot-changes";

        call(post(url), learner, "LEARNER", """
                {"fromDay":1,"fromTime":"08:00","toDay":3,"toTime":"15:00","effectiveFrom":"%s"}""".formatted(monday))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OUTSIDE_AVAILABILITY"));
        call(post(url), UUID.randomUUID(), "LEARNER", moveMondayToTuesday(monday)).andExpect(status().isForbidden());
        String body = call(post(url), learner, "LEARNER", moveMondayToTuesday(monday.plusWeeks(1)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String change = JsonPath.read(body, "$.id");
        verify(notifier).send(eq(tutor), eq("SLOT_CHANGE_REQUESTED"), anyString(), anyString(), eq("PLAN"), eq(plan));
        call(post(url), tutor, "TUTOR", moveMondayToTuesday(monday.plusWeeks(1)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_CHANGE_PENDING"));
        call(get("/api/v1/bookings/plans/" + plan), learner, "LEARNER", null).andExpect(jsonPath("$.pendingSlotChange.id").value(change));

        call(post("/api/v1/bookings/slot-changes/" + change + "/accept"), learner, "LEARNER", null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_OTHER_PARTY"));
        call(post("/api/v1/bookings/slot-changes/" + change + "/accept"), tutor, "TUTOR", null).andExpect(jsonPath("$.status").value("ACCEPTED"));
        List<java.time.OffsetDateTime> starts = jdbc.sql("SELECT start_at FROM sessions WHERE plan_id = ? ORDER BY start_at")
                .param(plan).query(java.time.OffsetDateTime.class).list();
        assertEquals(monday.atTime(8, 0).atZone(Schedule.ZONE).toInstant(), starts.get(0).toInstant()); // before effectiveFrom: unchanged
        for (int week = 1; week < 4; week++) {
            assertEquals(monday.plusWeeks(week).plusDays(1).atTime(15, 0).atZone(Schedule.ZONE).toInstant(), starts.get(week).toInstant());
        }
        call(get("/api/v1/bookings/plans/" + plan), learner, "LEARNER", null)
                .andExpect(jsonPath("$.pendingSlotChange").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.plan.endDate").value(monday.plusWeeks(4).minusDays(1).toString())); // moved dates stay inside the period
    }

    @Test
    void slotChangeConflictChangesNothingAndRequestsExpire() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        List<UUID> s = confirmedSessions(learner, tutor);
        UUID plan = planOf(s.get(0));
        openTuesdayAfternoon(tutor);
        // Another learner already has the tutor on Tuesday of week 3 at 15:00.
        UUID otherPlan = UUID.randomUUID();
        jdbc.sql("INSERT INTO plans (id, learner_id, tutor_id, subject_code, level_code, mode, status) VALUES (?, ?, ?, 'TOAN', 'THPT', 'ONLINE', 'CONFIRMED')")
                .params(otherPlan, UUID.randomUUID(), tutor).update();
        java.time.OffsetDateTime busy = monday.plusWeeks(2).plusDays(1).atTime(15, 0).atZone(Schedule.ZONE).toOffsetDateTime();
        jdbc.sql("INSERT INTO sessions (id, plan_id, tutor_id, learner_id, start_at, end_at, block_until, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'SCHEDULED')")
                .params(UUID.randomUUID(), otherPlan, tutor, UUID.randomUUID(), busy, busy.plusHours(2), busy.plusHours(3)).update();

        String url = "/api/v1/bookings/plans/" + plan + "/slot-changes";
        String change = JsonPath.read(call(post(url), tutor, "TUTOR", moveMondayToTuesday(monday.plusWeeks(1)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        call(post("/api/v1/bookings/slot-changes/" + change + "/accept"), learner, "LEARNER", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SCHEDULE_CONFLICT"));
        assertEquals(4L, jdbc.sql("SELECT count(*) FROM sessions WHERE plan_id = ? AND extract(isodow FROM start_at AT TIME ZONE 'Asia/Ho_Chi_Minh') = 1")
                .param(plan).query(Long.class).single());
        call(post("/api/v1/bookings/slot-changes/" + change + "/decline"), learner, "LEARNER", "{\"reason\":\"Trùng lịch\"}")
                .andExpect(jsonPath("$.status").value("DECLINED"));

        String expiring = JsonPath.read(call(post(url), tutor, "TUTOR", moveMondayToTuesday(monday.plusWeeks(1)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.sql("UPDATE slot_changes SET expires_at = now() - interval '1 minute' WHERE id = ?").param(UUID.fromString(expiring)).update();
        jobs.expireSlotChanges();
        call(post("/api/v1/bookings/slot-changes/" + expiring + "/accept"), learner, "LEARNER", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        assertEquals("EXPIRED", jdbc.sql("SELECT status FROM slot_changes WHERE id = ?").param(UUID.fromString(expiring)).query(String.class).single());
    }

    @Test
    void renewalIsLinkedOnceAndQualifiesAfterFullCompletion() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        List<UUID> s = confirmedSessions(learner, tutor);
        UUID old = planOf(s.get(0));
        String url = "/api/v1/bookings/plans/" + old + "/renew";
        String renew = """
                {"sessionsPerWeek":1,"startDate":"%s","weeks":2,"preferredSlots":[{"dayOfWeek":1,"startTime":"08:00"}]}""";

        call(post(url), learner, "LEARNER", renew.formatted(monday.plusWeeks(4).minusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        call(post(url), UUID.randomUUID(), "LEARNER", renew.formatted(monday.plusWeeks(4))).andExpect(status().isForbidden());
        String body = call(post(url), learner, "LEARNER", renew.formatted(monday.plusWeeks(4)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.plan.status").value("REQUESTED"))
                .andExpect(jsonPath("$.plan.renewedFrom").value(old.toString())).andExpect(jsonPath("$.plan.subjectCode").value("TOAN"))
                .andReturn().getResponse().getContentAsString();
        UUID next = UUID.fromString(JsonPath.read(body, "$.plan.id"));
        call(post(url), learner, "LEARNER", renew.formatted(monday.plusWeeks(5)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RENEWAL_EXISTS"));

        call(post("/api/v1/bookings/plans/" + next + "/propose"), tutor, "TUTOR", "{\"slots\":[{\"dayOfWeek\":1,\"startTime\":\"08:00\"}]}")
                .andExpect(jsonPath("$.sessions", hasSize(2)));
        call(post("/api/v1/bookings/plans/" + next + "/confirm"), learner, "LEARNER", null)
                .andExpect(jsonPath("$.plan.renewalQualifiedAt").value(org.hamcrest.Matchers.nullValue())); // old period still running

        for (int i = 0; i < 4; i++) teachAndConfirm(s.get(i), 5 - i, tutor, learner);
        assertEquals("HOAN_TAT_NGHIA_VU", planStatus(old));
        call(get("/api/v1/bookings/plans/" + next), learner, "LEARNER", null).andExpect(jsonPath("$.plan.renewalQualifiedAt").isNotEmpty());

        // EXP sources for tutor-service: 4 on-time sessions, the completed period, the qualified renewal.
        String sources = "/api/v1/bookings/internal/tutors/" + tutor + "/exp-sources";
        call(get(sources), tutor, "TUTOR", null).andExpect(status().isForbidden());
        call(get(sources), UUID.randomUUID(), "SERVICE", null)
                .andExpect(jsonPath("$.events", hasSize(6)))
                .andExpect(jsonPath("$.events[?(@.type == 'SESSION_ON_TIME')]", hasSize(4)))
                .andExpect(jsonPath("$.events[?(@.type == 'PERIOD_COMPLETED')].key", hasItem("period:" + old)))
                .andExpect(jsonPath("$.events[?(@.type == 'RENEWAL')].key", hasItem("renewal:" + next)))
                .andExpect(jsonPath("$.stats.taughtSessions").value(4)).andExpect(jsonPath("$.stats.distinctLearners").value(1))
                .andExpect(jsonPath("$.stats.renewals").value(1)).andExpect(jsonPath("$.stats.openIssues").value(0));
    }

    @Test
    void renewalReminderBeforePeriodEnds() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        UUID plan = planOf(confirmedSessions(learner, tutor).get(0));
        jobs.remindRenewals();
        verify(notifier, org.mockito.Mockito.never()).send(eq(learner), eq("PLAN_RENEWAL_REMINDER"), anyString(), anyString(), eq("PLAN"), eq(plan));
        jdbc.sql("UPDATE plans SET start_date = ?, end_date = ? WHERE id = ?").params(Schedule.today(), Schedule.today().plusDays(10), plan).update();
        jobs.remindRenewals();
        jobs.remindRenewals();
        verify(notifier, org.mockito.Mockito.times(1)).send(eq(learner), eq("PLAN_RENEWAL_REMINDER"), anyString(), anyString(), eq("PLAN"), eq(plan));
    }

    // ---- cases, learner strikes, rank review block ----

    String caseIdOf(UUID session) {
        return jdbc.sql("SELECT id FROM cases WHERE session_id = ?").param(session).query(UUID.class).single().toString();
    }

    @Test
    void casesDecideAppealAndClosePeriod() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        List<UUID> s = confirmedSessions(learner, tutor);
        UUID plan = planOf(s.get(0));

        // Dispute a taught session: a case opens, the tutor is told and has 72h.
        jdbc.sql("""
                UPDATE sessions SET status = 'COMPLETED_PENDING_CONFIRM', start_at = now() - interval '2 days 3 hours',
                    started_at = now() - interval '2 days 3 hours', end_at = now() - interval '2 days 1 hour', block_until = now() - interval '2 days',
                    completed_at = now() - interval '2 days', confirm_deadline = now() + interval '1 hour' WHERE id = ?""").param(s.get(0)).update();
        call(post("/api/v1/bookings/sessions/" + s.get(0) + "/dispute"), learner, "LEARNER", "{\"reason\":\"Gia sư dạy 30 phút\"}").andExpect(status().isOk());
        String c1 = caseIdOf(s.get(0));
        verify(notifier).send(eq(tutor), eq("CASE_OPENED"), anyString(), anyString(), eq("CASE"), eq(UUID.fromString(c1)));
        call(get("/api/v1/bookings/sessions/" + s.get(0) + "/case"), learner, "LEARNER", null)
                .andExpect(jsonPath("$.caseInfo.status").value("OPEN")).andExpect(jsonPath("$.caseInfo.kind").value("DISPUTE"));
        call(get("/api/v1/bookings/cases/" + c1), UUID.randomUUID(), "LEARNER", null).andExpect(status().isForbidden());

        String evidence = "/api/v1/bookings/cases/" + c1 + "/evidence";
        call(post(evidence), tutor, "TUTOR", "{\"urls\":[\"http://x\"]}").andExpect(status().isBadRequest());
        call(post(evidence), UUID.randomUUID(), "TUTOR", "{\"urls\":[\"https://drive.google.com/a\"]}").andExpect(status().isForbidden());
        call(post(evidence), tutor, "TUTOR", "{\"urls\":[\"https://drive.google.com/a\",\"https://drive.google.com/b\"],\"note\":\"Log Meet\"}")
                .andExpect(jsonPath("$.evidence", hasSize(2))).andExpect(jsonPath("$.evidence[0].party").value("TUTOR"));

        String decide = "/api/v1/bookings/admin/cases/" + c1 + "/decide";
        call(post(decide), tutor, "TUTOR", "{\"outcome\":\"TUTOR_FAULT\",\"reason\":\"x\"}").andExpect(status().isForbidden());
        call(post(decide), admin, "ADMIN", "{\"outcome\":\"TUTOR_FAULT\",\"reason\":\"Log Meet chỉ 30 phút\"}")
                .andExpect(jsonPath("$.caseInfo.status").value("DECIDED")).andExpect(jsonPath("$.session.status").value("FAILED_TUTOR"));
        call(post(decide), admin, "ADMIN", "{\"outcome\":\"NO_FAULT\",\"reason\":\"x\"}").andExpect(status().isConflict());
        call(post(evidence), tutor, "TUTOR", "{\"urls\":[\"https://drive.google.com/c\"]}").andExpect(status().isConflict());

        // Tutor appeals with new evidence (no 72h limit on appeal); the admin reverses: learner was wrong, a strike is recorded.
        call(post("/api/v1/bookings/cases/" + c1 + "/appeal"), tutor, "TUTOR", "{\"reason\":\"Có bản ghi đủ 2 giờ\"}")
                .andExpect(jsonPath("$.caseInfo.status").value("APPEALED"));
        jdbc.sql("UPDATE cases SET evidence_deadline = now() - interval '1 hour' WHERE id = ?").param(UUID.fromString(c1)).update();
        call(post(evidence), tutor, "TUTOR", "{\"urls\":[\"https://drive.google.com/record\"]}").andExpect(status().isOk());
        call(post(decide), admin, "ADMIN", "{\"outcome\":\"LEARNER_WRONG\",\"reason\":\"Bản ghi đủ 2 giờ\"}")
                .andExpect(jsonPath("$.session.status").value("CONFIRMED")).andExpect(jsonPath("$.events", hasSize(4)));
        call(get("/api/v1/bookings/admin/learners/" + learner + "/strikes"), admin, "ADMIN", null)
                .andExpect(jsonPath("$.activeStrikes").value(1)).andExpect(jsonPath("$.strikes[0].kind").value("FALSE_COMPLAINT"));

        // Tutor absent with no evidence after 72h: the suggested outcome follows the learner; the admin decides.
        startsIn(s.get(1), "-1 day -20 minutes");
        call(post("/api/v1/bookings/sessions/" + s.get(1) + "/tutor-absent"), learner, "LEARNER", "{\"reason\":\"Không vào lớp\"}").andExpect(status().isOk());
        String c2 = caseIdOf(s.get(1));
        jdbc.sql("UPDATE cases SET evidence_deadline = now() - interval '1 minute' WHERE id = ?").param(UUID.fromString(c2)).update();
        call(post("/api/v1/bookings/cases/" + c2 + "/evidence"), tutor, "TUTOR", "{\"urls\":[\"https://drive.google.com/late\"]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVIDENCE_WINDOW_CLOSED"));
        call(get("/api/v1/bookings/admin/cases?status=OPEN"), admin, "ADMIN", null).andExpect(jsonPath("$[*].id", hasItem(c2)));
        call(get("/api/v1/bookings/cases/" + c2), admin, "ADMIN", null).andExpect(jsonPath("$.suggestedOutcome").value("TUTOR_FAULT"));
        call(post("/api/v1/bookings/admin/cases/" + c2 + "/decide"), admin, "ADMIN", "{\"outcome\":\"TUTOR_FAULT\",\"reason\":\"Không có giải trình\"}")
                .andExpect(jsonPath("$.session.status").value("FAILED_TUTOR"));

        teachAndConfirm(s.get(2), 4, tutor, learner);
        teachAndConfirm(s.get(3), 3, tutor, learner);
        assertEquals("KET_THUC_CO_HOAN_MOT_PHAN", planStatus(plan));
        call(get("/api/v1/bookings/internal/tutors/" + tutor + "/exp-sources"), UUID.randomUUID(), "SERVICE", null)
                .andExpect(jsonPath("$.stats.tutorFaultFailed").value(1)).andExpect(jsonPath("$.stats.openIssues").value(0));
    }

    @Test
    void thirdStrikeIn90DaysBlocksNewPlans() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        openMondayMorning(tutor);
        String strikes = "/api/v1/bookings/admin/learners/" + learner + "/strikes";
        call(post(strikes), admin, "ADMIN", "{\"kind\":\"SELF_CANCEL\",\"reason\":\"Tự hủy\"}").andExpect(jsonPath("$.restrictedUntil").value(org.hamcrest.Matchers.nullValue()));
        call(post(strikes), admin, "ADMIN", "{\"kind\":\"NO_SHOW\",\"reason\":\"Vắng không báo\"}").andExpect(jsonPath("$.activeStrikes").value(2));
        requestPlan(learner, tutor); // two warnings do not block
        call(post(strikes), learner, "LEARNER", "{\"kind\":\"NO_SHOW\",\"reason\":\"x\"}").andExpect(status().isForbidden());
        call(post(strikes), admin, "ADMIN", "{\"kind\":\"NO_SHOW\",\"reason\":\"Vắng lần 2\"}").andExpect(jsonPath("$.restrictedUntil").isNotEmpty());
        call(post("/api/v1/bookings/plans"), learner, "LEARNER", """
                {"tutorId":"%s","subjectCode":"TOAN","levelCode":"THPT","mode":"ONLINE"}""".formatted(tutor))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LEARNER_RESTRICTED"));
        call(get("/api/v1/bookings/me/restriction"), learner, "LEARNER", null).andExpect(jsonPath("$.activeStrikes").value(3));
    }

    @Test
    void tutorUnderRankReviewTakesNoNewPlans() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        openMondayMorning(tutor);
        UUID pending = requestPlan(learner, tutor);
        when(tutors.requireApproved(eq(tutor))).thenReturn(new TutorClient.TutorProfile(tutor, "GS", "APPROVED", "FLEX", 2,
                List.of(new TutorClient.SubjectLevel("TOAN", "THPT")), true));
        call(post("/api/v1/bookings/plans"), learner, "LEARNER", """
                {"tutorId":"%s","subjectCode":"TOAN","levelCode":"THPT","mode":"ONLINE"}""".formatted(tutor))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RANK_UNDER_REVIEW"));
        propose(tutor, pending, "08:00").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RANK_UNDER_REVIEW"));
    }

    // ---- P18 customer care staff (SUPPORT) and internal facts for the new services ----

    @Test
    void supportTakesInCasesAndProposesButOnlyAdminDecides() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        UUID support = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        List<UUID> s = confirmedSessions(learner, tutor);
        startsIn(s.get(1), "-1 day -20 minutes");
        call(post("/api/v1/bookings/sessions/" + s.get(1) + "/tutor-absent"), learner, "LEARNER", "{\"reason\":\"Không vào lớp\"}").andExpect(status().isOk());
        String c = caseIdOf(s.get(1));

        call(get("/api/v1/bookings/admin/cases?status=OPEN"), support, "SUPPORT", null).andExpect(jsonPath("$[*].id", hasItem(c)));
        call(get("/api/v1/bookings/cases/" + c), support, "SUPPORT", null).andExpect(jsonPath("$.caseInfo.kind").value("TUTOR_ABSENT"));
        String contacts = "/api/v1/bookings/support/cases/" + c + "/contacts";
        String call1 = "{\"party\":\"TUTOR\",\"channel\":\"PHONE\",\"reached\":false,\"note\":\"Gọi lần 1 không nghe\"}";
        call(post(contacts), learner, "LEARNER", call1).andExpect(status().isForbidden());
        call(post(contacts), support, "SUPPORT", call1).andExpect(status().isOk());
        call(post(contacts), support, "SUPPORT", "{\"party\":\"TUTOR\",\"channel\":\"MESSAGE\",\"reached\":true,\"note\":\"Gia sư báo ốm\"}")
                .andExpect(jsonPath("$.contacts", hasSize(2))).andExpect(jsonPath("$.contacts[1].reached").value(true));

        when(identity.phone(tutor)).thenReturn("0901000002");
        call(get("/api/v1/bookings/support/cases/" + c + "/phone?party=TUTOR"), support, "SUPPORT", null).andExpect(jsonPath("$.phone").value("0901000002"));
        call(get("/api/v1/bookings/support/cases/" + c + "/phone?party=TUTOR"), learner, "LEARNER", null).andExpect(status().isForbidden());
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM contact_views WHERE case_id = ?").param(UUID.fromString(c)).query(Long.class).single());

        String proposal = "{\"outcome\":\"TUTOR_FAULT\",\"reason\":\"Gia sư thừa nhận nghỉ không báo\"}";
        call(post("/api/v1/bookings/support/cases/" + c + "/proposal"), support, "SUPPORT", proposal)
                .andExpect(jsonPath("$.caseInfo.status").value("OPEN")).andExpect(jsonPath("$.caseInfo.proposedOutcome").value("TUTOR_FAULT"))
                .andExpect(jsonPath("$.events[*].action", hasItem("PROPOSED")));
        call(post("/api/v1/bookings/admin/cases/" + c + "/decide"), support, "SUPPORT", proposal).andExpect(status().isForbidden());
        call(get("/api/v1/bookings/sessions/" + s.get(1)), learner, "LEARNER", null).andExpect(jsonPath("$.status").value("TUTOR_ABSENT_REPORTED"));
        call(post("/api/v1/bookings/admin/cases/" + c + "/decide"), admin, "ADMIN", proposal).andExpect(jsonPath("$.session.status").value("FAILED_TUTOR"));
        call(get("/api/v1/bookings/support/cases/" + c + "/phone?party=TUTOR"), support, "SUPPORT", null).andExpect(status().isConflict());
        call(post("/api/v1/bookings/support/cases/" + c + "/proposal"), support, "SUPPORT", proposal).andExpect(status().isConflict());
    }

    @Test
    void supportProposesStrikesThatCountOnlyAfterAdminApproval() throws Exception {
        UUID learner = UUID.randomUUID();
        UUID support = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        String body = "{\"learnerId\":\"" + learner + "\",\"kind\":\"NO_SHOW\",\"reason\":\"Vắng không báo, đã gọi 2 lần không nghe\"}";
        call(post("/api/v1/bookings/support/strike-proposals"), admin, "ADMIN", body).andExpect(status().isForbidden());
        long id = ((Number) JsonPath.read(call(post("/api/v1/bookings/support/strike-proposals"), support, "SUPPORT", body)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        String strikes = "/api/v1/bookings/admin/learners/" + learner + "/strikes";
        call(get(strikes), admin, "ADMIN", null).andExpect(jsonPath("$.activeStrikes").value(0));
        call(get("/api/v1/bookings/admin/strike-proposals"), support, "SUPPORT", null).andExpect(jsonPath("$[*].id", hasItem((int) id)));
        call(post("/api/v1/bookings/admin/strike-proposals/" + id + "/approve"), support, "SUPPORT", null).andExpect(status().isForbidden());
        call(post("/api/v1/bookings/admin/strike-proposals/" + id + "/approve"), admin, "ADMIN", null).andExpect(jsonPath("$.status").value("APPROVED"));
        call(get(strikes), admin, "ADMIN", null).andExpect(jsonPath("$.activeStrikes").value(1));
        call(post("/api/v1/bookings/admin/strike-proposals/" + id + "/approve"), admin, "ADMIN", null).andExpect(status().isConflict());

        long second = ((Number) JsonPath.read(call(post("/api/v1/bookings/support/strike-proposals"), support, "SUPPORT", body)
                .andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        call(post("/api/v1/bookings/admin/strike-proposals/" + second + "/reject"), admin, "ADMIN", "{\"note\":\"\"}").andExpect(status().isBadRequest());
        call(post("/api/v1/bookings/admin/strike-proposals/" + second + "/reject"), admin, "ADMIN", "{\"note\":\"Người thuê đã báo trước qua điện thoại\"}")
                .andExpect(jsonPath("$.status").value("REJECTED"));
        call(get(strikes), admin, "ADMIN", null).andExpect(jsonPath("$.activeStrikes").value(1));
    }

    @Test
    void internalFactsForAssessmentAndCare() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        UUID service = UUID.randomUUID();
        List<UUID> s = confirmedSessions(learner, tutor);
        UUID plan = planOf(s.get(0));
        call(get("/api/v1/bookings/internal/plans/" + plan + "/summary"), learner, "LEARNER", null).andExpect(status().isForbidden());
        call(get("/api/v1/bookings/internal/plans/" + plan + "/summary"), service, "SERVICE", null)
                .andExpect(jsonPath("$.learnerId").value(learner.toString())).andExpect(jsonPath("$.tutorId").value(tutor.toString()))
                .andExpect(jsonPath("$.subjectCode").value("TOAN")).andExpect(jsonPath("$.status").value("CONFIRMED"));

        call(post("/api/v1/bookings/sessions/" + s.get(3) + "/cancel"), learner, "LEARNER", "{\"reason\":\"Bận thi\"}").andExpect(status().isOk());
        String signals = call(get("/api/v1/bookings/internal/care/signals"), service, "SERVICE", null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Integer> cancels = JsonPath.read(signals, "$[?(@.learnerId == '" + learner + "')].cancels30d");
        assertEquals(List.of(1), cancels);

        String ticket = "{\"sessionId\":\"" + s.get(2) + "\",\"reason\":\"Phiếu hỗ trợ: gia sư dạy thiếu giờ\"}";
        String caseId = JsonPath.read(call(post("/api/v1/bookings/internal/cases/from-ticket"), service, "SERVICE", ticket)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.caseId");
        call(post("/api/v1/bookings/internal/cases/from-ticket"), service, "SERVICE", ticket).andExpect(jsonPath("$.caseId").value(caseId));
        assertEquals(caseId, caseIdOf(s.get(2)));
    }
}
