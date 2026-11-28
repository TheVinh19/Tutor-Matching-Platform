package vn.giasu.tutor;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import vn.giasu.common.security.JwtIssuer;

/** Profiles, approval, search filters and reviews against real PostgreSQL. booking-service is mocked. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = TutorApplication.class, properties = {"JWT_SECRET=test-secret-test-secret-test-secret-123", "tutor.exp.sync-delay=3600000"})
class TutorApiPostgresTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired WebApplicationContext context;
    @Autowired JwtIssuer issuer;
    @MockitoBean BookingClient booking;
    MockMvc mvc;
    final UUID admin = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
        when(booking.expSources(any())).thenReturn(sources(List.of(), 0, 0, 0, 0, 0));
    }

    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;

    static BookingClient.ExpSources sources(List<BookingClient.ExpEvent> events, int taught, int failed, int learners, int renewals, int open) {
        return new BookingClient.ExpSources(events, new BookingClient.ExpStats(taught, failed, learners, renewals, open));
    }

    static BookingClient.ExpEvent event(String key, String type, int points) {
        return new BookingClient.ExpEvent(key, type, points, "SESSION", UUID.randomUUID(), Instant.now());
    }

    String token(UUID id, String role) { return "Bearer " + issuer.issue(id, role, role.toLowerCase() + "@t.vn", Duration.ofHours(1)).getTokenValue(); }

    ResultActions call(MockHttpServletRequestBuilder req, UUID id, String role, String json) throws Exception {
        if (id != null) req.header("Authorization", token(id, role));
        if (json != null) req.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(req);
    }

    static String profileJson(String mode, String subjects, String areas) {
        return """
                {"fullName":"Gia sư Test","bio":"Giới thiệu","experience":"2 năm","teachingMode":"%s",
                 "subjects":[%s],"areaCodes":[%s]}""".formatted(mode, subjects, areas);
    }

    /** Creates a tutor with an approved profile and an approved video (public, P12) and returns its id. */
    UUID approvedTutor(String mode, String subjects, String areas) throws Exception {
        UUID tutor = UUID.randomUUID();
        call(put("/api/v1/tutors/me/profile"), tutor, "TUTOR", profileJson(mode, subjects, areas)).andExpect(status().isOk());
        call(post("/api/v1/tutors/me/profile/submit"), tutor, "TUTOR", null).andExpect(status().isOk());
        call(post("/api/v1/tutors/admin/profiles/" + tutor + "/approve"), admin, "ADMIN", null).andExpect(status().isOk());
        String video = JsonPath.read(call(post("/api/v1/tutors/me/videos"), tutor, "TUTOR", "{\"url\":\"https://youtu.be/aqz-KE-bpKQ\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.videos[0].id");
        call(post("/api/v1/tutors/admin/videos/" + video + "/approve"), admin, "ADMIN", null).andExpect(status().isOk());
        return tutor;
    }

    @Test
    void catalogIsPublic() throws Exception {
        call(get("/api/v1/tutors/catalog"), null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjects[*].code", hasItem("TOAN")))
                .andExpect(jsonPath("$.areas[*].code", hasItem("HN-CG")));
    }

    @Test
    void submitApproveThenSearchableOnceAcrossManySubjects() throws Exception {
        UUID tutor = UUID.randomUUID();
        String subjects = "{\"subjectCode\":\"TOAN\",\"levelCode\":\"THPT\"},{\"subjectCode\":\"ANH\",\"levelCode\":\"THPT\"}";
        call(put("/api/v1/tutors/me/profile"), tutor, "TUTOR", profileJson("OFFLINE", subjects, "\"HN-CG\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        call(get("/api/v1/tutors/search?subjects=TOAN"), null, null, null)
                .andExpect(jsonPath("$.items[*].accountId", not(hasItem(tutor.toString()))));

        call(post("/api/v1/tutors/me/profile/submit"), tutor, "TUTOR", null).andExpect(jsonPath("$.status").value("PENDING"));
        call(put("/api/v1/tutors/me/profile"), tutor, "TUTOR", profileJson("OFFLINE", subjects, "\"HN-CG\""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROFILE_LOCKED"));
        call(get("/api/v1/tutors/" + tutor), null, null, null).andExpect(status().isNotFound());
        call(get("/api/v1/tutors/admin/profiles"), admin, "ADMIN", null)
                .andExpect(jsonPath("$[*].accountId", hasItem(tutor.toString())));

        call(post("/api/v1/tutors/admin/profiles/" + tutor + "/approve"), UUID.randomUUID(), "LEARNER", null).andExpect(status().isForbidden());
        call(post("/api/v1/tutors/admin/profiles/" + tutor + "/approve"), admin, "ADMIN", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.profile.status").value("APPROVED"))
                .andExpect(jsonPath("$.history[*].action", hasItem("APPROVE")));
        call(post("/api/v1/tutors/admin/profiles/" + tutor + "/approve"), admin, "ADMIN", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        // P12: approved but no approved video yet -> still hidden.
        call(get("/api/v1/tutors/" + tutor), null, null, null).andExpect(status().isNotFound());
        String video = JsonPath.read(call(post("/api/v1/tutors/me/videos"), tutor, "TUTOR", "{\"url\":\"https://youtu.be/aqz-KE-bpKQ\"}")
                .andReturn().getResponse().getContentAsString(), "$.videos[0].id");
        call(post("/api/v1/tutors/admin/videos/" + video + "/approve"), admin, "ADMIN", null).andExpect(status().isOk());

        String body = call(get("/api/v1/tutors/search?subjects=TOAN,ANH&size=50"), null, null, null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.items[*].accountId");
        org.junit.jupiter.api.Assertions.assertEquals(1, ids.stream().filter(tutor.toString()::equals).count(), "each tutor appears once");

        call(get("/api/v1/tutors/" + tutor), null, null, null).andExpect(status().isOk()).andExpect(jsonPath("$.rank").value(1));
    }

    @Test
    void searchFiltersByModeAreaLevelAndRank() throws Exception {
        UUID offline = approvedTutor("OFFLINE", "{\"subjectCode\":\"HOA\",\"levelCode\":\"THCS\"}", "\"HN-CG\"");
        UUID online = approvedTutor("ONLINE", "{\"subjectCode\":\"HOA\",\"levelCode\":\"THPT\"}", "");
        String base = "/api/v1/tutors/search?size=50&subjects=HOA";
        call(get(base + "&mode=ONLINE"), null, null, null)
                .andExpect(jsonPath("$.items[*].accountId", hasItem(online.toString())))
                .andExpect(jsonPath("$.items[*].accountId", not(hasItem(offline.toString()))));
        call(get(base + "&area=HN-DD"), null, null, null) // online tutors are not excluded by area
                .andExpect(jsonPath("$.items[*].accountId", hasItem(online.toString())))
                .andExpect(jsonPath("$.items[*].accountId", not(hasItem(offline.toString()))));
        call(get(base + "&area=HN-CG&mode=OFFLINE"), null, null, null)
                .andExpect(jsonPath("$.items[*].accountId", hasItem(offline.toString())));
        call(get(base + "&level=THCS"), null, null, null)
                .andExpect(jsonPath("$.items[*].accountId", hasItem(offline.toString())))
                .andExpect(jsonPath("$.items[*].accountId", not(hasItem(online.toString()))));
        call(get(base + "&ranks=2,3"), null, null, null)
                .andExpect(jsonPath("$.items[*].accountId", not(hasItem(offline.toString()))));
        call(get(base + "&mode=WHATEVER"), null, null, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void scheduleFilterUsesBookingAvailability() throws Exception {
        UUID tutor = approvedTutor("ONLINE", "{\"subjectCode\":\"SINH\",\"levelCode\":\"THPT\"}", "");
        when(booking.tutorsAvailable(anyInt(), any(), any(), any(), any())).thenReturn(List.of(tutor));
        call(get("/api/v1/tutors/search?subjects=SINH&day=2&from=18:00&to=20:00"), null, null, null)
                .andExpect(jsonPath("$.items[*].accountId", hasItem(tutor.toString())));
        when(booking.tutorsAvailable(anyInt(), any(), any(), any(), any())).thenReturn(List.of());
        call(get("/api/v1/tutors/search?subjects=SINH&day=2&from=18:00&to=20:00"), null, null, null)
                .andExpect(jsonPath("$.total").value(0));
        call(get("/api/v1/tutors/search?day=2&from=18:00"), null, null, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SCHEDULE_FILTER"));

        // Several sessions: only tutors free for every one of them.
        UUID other = approvedTutor("ONLINE", "{\"subjectCode\":\"SINH\",\"levelCode\":\"THPT\"}", "");
        when(booking.tutorsAvailable(eq(2), any(), any(), any(), any())).thenReturn(List.of(tutor, other));
        when(booking.tutorsAvailable(eq(3), any(), any(), any(), any())).thenReturn(List.of(tutor));
        call(get("/api/v1/tutors/search?subjects=SINH&day=2&from=08:00&to=10:00&day=3&from=08:00&to=10:00"), null, null, null)
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].accountId").value(tutor.toString()));
        call(get("/api/v1/tutors/search?day=2&from=08:00&to=10:00&day=3&from=08:00"), null, null, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SCHEDULE_FILTER"));
    }

    @Test
    void invalidProfilesAndRejectFlow() throws Exception {
        UUID tutor = UUID.randomUUID();
        call(put("/api/v1/tutors/me/profile"), tutor, "TUTOR", profileJson("ONLINE", "{\"subjectCode\":\"XYZ\",\"levelCode\":\"THPT\"}", ""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_CATALOG_CODE"))
                .andExpect(jsonPath("$.details[0]").value("XYZ"));
        call(put("/api/v1/tutors/me/profile"), tutor, "TUTOR", profileJson("ONLINE", "{\"subjectCode\":\"SU\",\"levelCode\":\"TIEU_HOC\"}", ""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LEVEL_NOT_ALLOWED"))
                .andExpect(jsonPath("$.details[0]").value("SU|TIEU_HOC"));
        call(put("/api/v1/tutors/me/profile"), tutor, "TUTOR", profileJson("OFFLINE", "{\"subjectCode\":\"LY\",\"levelCode\":\"THPT\"}", ""))
                .andExpect(status().isOk());
        call(put("/api/v1/tutors/me/profile"), tutor, "TUTOR", profileJson("OFFLINE", "", ""))
                .andExpect(status().isOk());
        call(post("/api/v1/tutors/me/profile/submit"), tutor, "TUTOR", null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROFILE_INCOMPLETE"));
        call(put("/api/v1/tutors/me/profile"), tutor, "TUTOR", profileJson("OFFLINE", "{\"subjectCode\":\"LY\",\"levelCode\":\"THPT\"}", ""))
                .andExpect(status().isOk());
        call(post("/api/v1/tutors/me/profile/submit"), tutor, "TUTOR", null).andExpect(status().isOk());
        call(post("/api/v1/tutors/admin/profiles/" + tutor + "/reject"), admin, "ADMIN", "{\"reason\":\"\"}")
                .andExpect(status().isBadRequest());
        call(post("/api/v1/tutors/admin/profiles/" + tutor + "/reject"), admin, "ADMIN", "{\"reason\":\"Thiếu minh chứng\"}")
                .andExpect(jsonPath("$.profile.status").value("REJECTED")).andExpect(jsonPath("$.profile.rejectReason").value("Thiếu minh chứng"));
        call(put("/api/v1/tutors/me/profile"), tutor, "TUTOR", profileJson("OFFLINE", "{\"subjectCode\":\"LY\",\"levelCode\":\"THPT\"}", "\"HCM-Q1\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        call(get("/api/v1/tutors/me/profile"), UUID.randomUUID(), "LEARNER", null).andExpect(status().isForbidden());
    }

    @Test
    void reviewRules() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        UUID plan = UUID.randomUUID();
        String json = "{\"planId\":\"" + plan + "\",\"content\":\"Gia sư dạy dễ hiểu, đúng giờ.\"}";

        when(booking.planEligibility(plan)).thenReturn(Optional.of(new BookingClient.PlanEligibility(plan, learner, tutor, "CONFIRMED", null)));
        call(post("/api/v1/tutors/reviews"), learner, "LEARNER", json).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVIEW_NOT_ALLOWED"));

        // Still owing obligations (open dispute): no review yet.
        when(booking.planEligibility(plan)).thenReturn(Optional.of(new BookingClient.PlanEligibility(plan, learner, tutor, "HET_KY_CON_NGHIA_VU", null)));
        call(post("/api/v1/tutors/reviews"), learner, "LEARNER", json).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVIEW_NOT_ALLOWED"));

        when(booking.planEligibility(plan)).thenReturn(Optional.of(new BookingClient.PlanEligibility(plan, learner, tutor, "HOAN_TAT_NGHIA_VU", Instant.now().minus(Duration.ofDays(15)))));
        call(post("/api/v1/tutors/reviews"), learner, "LEARNER", json).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVIEW_WINDOW_CLOSED"));

        when(booking.planEligibility(plan)).thenReturn(Optional.of(new BookingClient.PlanEligibility(plan, learner, tutor, "KET_THUC_CO_HOAN_MOT_PHAN", Instant.now().minus(Duration.ofDays(1)))));
        call(post("/api/v1/tutors/reviews"), UUID.randomUUID(), "LEARNER", json).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_PLAN_OWNER"));
        call(post("/api/v1/tutors/reviews"), tutor, "TUTOR", json).andExpect(status().isForbidden());
        call(post("/api/v1/tutors/reviews"), learner, "LEARNER", json).andExpect(status().isCreated());
        call(post("/api/v1/tutors/reviews"), learner, "LEARNER", json).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVIEW_EXISTS"));
        call(post("/api/v1/tutors/reviews"), learner, "LEARNER", "{\"planId\":\"" + plan + "\",\"content\":\"ngắn\"}").andExpect(status().isBadRequest());

        call(get("/api/v1/tutors/" + tutor + "/reviews"), null, null, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].content").value("Gia sư dạy dễ hiểu, đúng giờ."));
    }

    @Test
    void internalProfileNeedsServiceRole() throws Exception {
        UUID tutor = approvedTutor("FLEX", "{\"subjectCode\":\"TOAN\",\"levelCode\":\"THCS\"}", "\"HN-CG\"");
        call(get("/api/v1/tutors/internal/profiles/" + tutor), UUID.randomUUID(), "ADMIN", null).andExpect(status().isForbidden());
        call(get("/api/v1/tutors/internal/profiles/" + tutor), UUID.randomUUID(), "SERVICE", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.subjects[0].subjectCode").value("TOAN"));
    }

    static final String TOAN = "{\"subjectCode\":\"TOAN\",\"levelCode\":\"THCS\"}";

    @Test
    void expSyncIsIdempotentAndAdminDecidesRank() throws Exception {
        UUID tutor = approvedTutor("ONLINE", TOAN, "");
        when(booking.expSources(tutor)).thenReturn(sources(List.of(event("session:a", "SESSION_ON_TIME", 10), event("session:b", "SESSION_ON_TIME", 10),
                event("period:p", "PERIOD_COMPLETED", 30), event("renewal:r", "RENEWAL", 50)), 100, 0, 3, 2, 0));
        call(get("/api/v1/tutors/me/exp"), tutor, "TUTOR", null).andExpect(jsonPath("$.view.exp").value(100));
        call(get("/api/v1/tutors/me/exp"), tutor, "TUTOR", null).andExpect(jsonPath("$.view.exp").value(100))
                .andExpect(jsonPath("$.entries", org.hamcrest.Matchers.hasSize(4))).andExpect(jsonPath("$.view.flag").value("OK"))
                .andExpect(jsonPath("$.view.completionRate").value(1.0));
        call(get("/api/v1/tutors/me/exp"), UUID.randomUUID(), "LEARNER", null).andExpect(status().isForbidden());

        // Enough EXP and 90 days at rank 1: flagged; the admin promotes one step only.
        jdbc.sql("INSERT INTO exp_ledger (id, tutor_id, source_key, type, points) VALUES (?, ?, 'test:bulk', 'SESSION_ON_TIME', 1100)")
                .params(UUID.randomUUID(), tutor).update();
        jdbc.sql("UPDATE tutor_profiles SET rank_since = now() - interval '100 days' WHERE account_id = ?").param(tutor).update();
        call(get("/api/v1/tutors/admin/ranks?flag=ELIGIBLE_UP"), admin, "ADMIN", null).andExpect(jsonPath("$[*].accountId", hasItem(tutor.toString())));
        String decide = "/api/v1/tutors/admin/ranks/" + tutor + "/decide";
        call(post(decide), admin, "ADMIN", "{\"rank\":3,\"reason\":\"Thử\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_ELIGIBLE"));
        call(post(decide), tutor, "TUTOR", "{\"rank\":2,\"reason\":\"Đủ\"}").andExpect(status().isForbidden());
        call(post(decide), admin, "ADMIN", "{\"rank\":2,\"reason\":\"Đủ điều kiện P10\"}")
                .andExpect(jsonPath("$.view.rank").value(2)).andExpect(jsonPath("$.decisions[0].toRank").value(2));

        // Verified penalty: once per session; dropping under 1,200 flags the tutor, rank is not changed automatically.
        String penalty = "/api/v1/tutors/admin/ranks/" + tutor + "/penalties";
        String ref = "\"refType\":\"SESSION\",\"refId\":\"" + UUID.randomUUID() + "\"";
        call(post(penalty), admin, "ADMIN", "{\"points\":50,\"reason\":\"x\"," + ref + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PENALTY"));
        call(post(penalty), admin, "ADMIN", "{\"points\":30,\"reason\":\"Đến muộn 40 phút, đã xác minh\"," + ref + "}")
                .andExpect(jsonPath("$.view.exp").value(1170)).andExpect(jsonPath("$.view.rank").value(2)).andExpect(jsonPath("$.view.flag").value("MUST_REVIEW"));
        call(post(penalty), admin, "ADMIN", "{\"points\":100,\"reason\":\"x\"," + ref + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PENALTY_EXISTS"));
        call(post(decide), admin, "ADMIN", "{\"rank\":1,\"reason\":\"EXP dưới 1.200\"}").andExpect(jsonPath("$.view.rank").value(1));

        // EXP never shows below zero.
        jdbc.sql("INSERT INTO exp_ledger (id, tutor_id, source_key, type, points) VALUES (?, ?, 'test:neg', 'PENALTY', -5000)")
                .params(UUID.randomUUID(), tutor).update();
        call(get("/api/v1/tutors/me/exp"), tutor, "TUTOR", null).andExpect(jsonPath("$.view.exp").value(0));
    }

    @Test
    void entryRank2AndPublicNumbers() throws Exception {
        UUID tutor = UUID.randomUUID();
        call(put("/api/v1/tutors/me/profile"), tutor, "TUTOR", profileJson("ONLINE", TOAN, "")).andExpect(status().isOk());
        call(post("/api/v1/tutors/me/profile/submit"), tutor, "TUTOR", null).andExpect(status().isOk());
        String approve = "/api/v1/tutors/admin/profiles/" + tutor + "/approve";
        String entry = "{\"note\":\"Bằng ĐH + 2 năm dạy\",\"entryRank2\":true}";
        call(post("/api/v1/tutors/me/evidence"), tutor, "TUTOR", "{\"kind\":\"DEGREE\",\"url\":\"https://drive.google.com/bang\"}").andExpect(status().isOk());
        call(post(approve), admin, "ADMIN", entry).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVIDENCE_REQUIRED"));
        call(post("/api/v1/tutors/me/evidence"), tutor, "TUTOR", "{\"kind\":\"EXPERIENCE\",\"url\":\"https://drive.google.com/hd\",\"note\":\"HĐ trung tâm 2023-2025\"}")
                .andExpect(jsonPath("$.evidence", org.hamcrest.Matchers.hasSize(2)));
        call(post(approve), admin, "ADMIN", entry).andExpect(jsonPath("$.profile.rank").value(2));
        call(get("/api/v1/tutors/me/exp"), tutor, "TUTOR", null).andExpect(jsonPath("$.view.exp").value(1200))
                .andExpect(jsonPath("$.view.entryRank2").value(true)).andExpect(jsonPath("$.view.flag").value("OK"));

        // P12: not public until a video is approved; then shown with the 30-day newcomer label.
        call(get("/api/v1/tutors/" + tutor), null, null, null).andExpect(status().isNotFound());
        call(post("/api/v1/tutors/me/videos"), tutor, "TUTOR", "{\"url\":\"https://vimeo.com/1\"}").andExpect(status().isBadRequest());
        for (int i = 0; i < 3; i++) {
            call(post("/api/v1/tutors/me/videos"), tutor, "TUTOR", "{\"url\":\"https://www.youtube.com/watch?v=aqz-KE-bpK" + i + "\"}").andExpect(status().isOk());
        }
        String extras = call(post("/api/v1/tutors/me/videos"), tutor, "TUTOR", "{\"url\":\"https://youtu.be/aqz-KE-bpKQ\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VIDEO_LIMIT")).andReturn().getResponse().getContentAsString();
        String video = JsonPath.read(call(get("/api/v1/tutors/me/extras"), tutor, "TUTOR", null).andReturn().getResponse().getContentAsString(), "$.videos[0].id");
        call(post("/api/v1/tutors/admin/videos/" + video + "/reject"), admin, "ADMIN", "{\"reason\":\"Không thấy giảng\"}")
                .andExpect(jsonPath("$.videos[0].status").value("REJECTED"));
        List<String> pending = JsonPath.read(call(get("/api/v1/tutors/admin/review-queue"), admin, "ADMIN", null).andReturn().getResponse().getContentAsString(),
                "$.videos[?(@.accountId == '" + tutor + "')].id");
        String second = pending.get(0);
        call(post("/api/v1/tutors/admin/videos/" + second + "/approve"), admin, "ADMIN", null).andExpect(status().isOk());
        call(get("/api/v1/tutors/" + tutor), null, null, null).andExpect(jsonPath("$.taughtSessions").value(0))
                .andExpect(jsonPath("$.completionRate").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.videos", org.hamcrest.Matchers.hasSize(1))).andExpect(jsonPath("$.newcomer").value(true));
        assertFalse(extras.isEmpty());
    }

    @Test
    void newSubjectAfterApprovalIsReviewedSeparately() throws Exception {
        UUID tutor = approvedTutor("ONLINE", TOAN, "");
        String subject = "{\"subjectCode\":\"ANH\",\"levelCode\":\"THCS\"}";
        call(post("/api/v1/tutors/me/subjects"), tutor, "TUTOR", TOAN).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBJECT_EXISTS"));
        call(post("/api/v1/tutors/me/subjects"), tutor, "TUTOR", subject).andExpect(jsonPath("$.subjectRequests[0].status").value("PENDING"));
        call(get("/api/v1/tutors/internal/profiles/" + tutor), UUID.randomUUID(), "SERVICE", null)
                .andExpect(jsonPath("$.subjects", org.hamcrest.Matchers.hasSize(1))).andExpect(jsonPath("$.rankUnderReview").value(false));
        call(get("/api/v1/tutors/search?subjects=ANH"), null, null, null).andExpect(jsonPath("$.items[*].accountId", not(hasItem(tutor.toString()))));
        String decision = "{\"accountId\":\"" + tutor + "\",\"subjectCode\":\"ANH\",\"levelCode\":\"THCS\"}";
        call(post("/api/v1/tutors/admin/subjects/reject"), admin, "ADMIN", decision).andExpect(status().isBadRequest());
        call(post("/api/v1/tutors/admin/subjects/approve"), admin, "ADMIN", decision).andExpect(jsonPath("$.subjectRequests", org.hamcrest.Matchers.hasSize(0)));
        call(get("/api/v1/tutors/search?subjects=ANH"), null, null, null).andExpect(jsonPath("$.items[*].accountId", hasItem(tutor.toString())));
        call(post("/api/v1/tutors/admin/subjects/approve"), admin, "ADMIN", decision).andExpect(status().isConflict());
    }

    @Test
    void lostRankConditionIsReportedToBooking() throws Exception {
        UUID tutor = approvedTutor("ONLINE", TOAN, "");
        jdbc.sql("UPDATE tutor_profiles SET rank = 2 WHERE account_id = ?").param(tutor).update(); // rank 2 with 0 EXP
        call(get("/api/v1/tutors/internal/profiles/" + tutor), UUID.randomUUID(), "SERVICE", null).andExpect(jsonPath("$.rankUnderReview").value(true));
        call(post("/api/v1/tutors/admin/ranks/" + tutor + "/decide"), admin, "ADMIN", "{\"rank\":1,\"reason\":\"EXP dưới 1.200\"}").andExpect(status().isOk());
        call(get("/api/v1/tutors/internal/profiles/" + tutor), UUID.randomUUID(), "SERVICE", null).andExpect(jsonPath("$.rankUnderReview").value(false));
    }

    @Test
    void tutorRepliesOnceAndAdminHidesAndRestores() throws Exception {
        UUID tutor = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        UUID plan = UUID.randomUUID();
        when(booking.planEligibility(plan)).thenReturn(Optional.of(new BookingClient.PlanEligibility(plan, learner, tutor, "HOAN_TAT_NGHIA_VU", Instant.now())));
        String id = JsonPath.read(call(post("/api/v1/tutors/reviews"), learner, "LEARNER",
                "{\"planId\":\"" + plan + "\",\"content\":\"Dạy khó hiểu, hay đổi lịch.\"}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        String base = "/api/v1/tutors/reviews/" + id;

        call(post(base + "/reply"), UUID.randomUUID(), "TUTOR", "{\"content\":\"x\"}").andExpect(status().isForbidden());
        call(post(base + "/reply"), tutor, "TUTOR", "{\"content\":\"Em xin lỗi, tháng sau lịch ổn định hơn.\"}")
                .andExpect(jsonPath("$.reply").value("Em xin lỗi, tháng sau lịch ổn định hơn."));
        call(post(base + "/reply"), tutor, "TUTOR", "{\"content\":\"Sửa\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPLY_EXISTS"));
        call(post(base + "/report"), tutor, "TUTOR", "{\"reason\":\"Nội dung sai sự thật\"}").andExpect(jsonPath("$.reportedAt").isNotEmpty());
        call(get("/api/v1/tutors/me/reviews"), tutor, "TUTOR", null).andExpect(jsonPath("$[0].id").value(id));
        call(get("/api/v1/tutors/admin/reviews"), admin, "ADMIN", null).andExpect(jsonPath("$[*].id", hasItem(id)));
        call(get("/api/v1/tutors/admin/reviews"), tutor, "TUTOR", null).andExpect(status().isForbidden());

        String admins = "/api/v1/tutors/admin/reviews/" + id;
        call(post(admins + "/hide"), admin, "ADMIN", "{\"reason\":\"Đã xác minh: sai sự thật\"}").andExpect(jsonPath("$.hidden").value(true));
        call(post(admins + "/hide"), admin, "ADMIN", "{\"reason\":\"lại\"}").andExpect(status().isConflict());
        call(get("/api/v1/tutors/" + tutor + "/reviews"), null, null, null).andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
        call(post(admins + "/restore"), admin, "ADMIN", "{\"reason\":\"Kháng nghị hợp lệ\"}").andExpect(jsonPath("$.hidden").value(false));
        call(get("/api/v1/tutors/" + tutor + "/reviews"), null, null, null)
                .andExpect(jsonPath("$[0].content").value("Dạy khó hiểu, hay đổi lịch.")).andExpect(jsonPath("$[0].reply").isNotEmpty());
        call(get(admins + "/moderation"), admin, "ADMIN", null).andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].action").value("HIDE"));
    }
}
