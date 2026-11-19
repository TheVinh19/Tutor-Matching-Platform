package vn.giasu.tutor;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
@SpringBootTest(classes = TutorApplication.class, properties = "JWT_SECRET=test-secret-test-secret-test-secret-123")
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

    /** Creates a tutor with an approved profile and returns its id. */
    UUID approvedTutor(String mode, String subjects, String areas) throws Exception {
        UUID tutor = UUID.randomUUID();
        call(put("/api/v1/tutors/me/profile"), tutor, "TUTOR", profileJson(mode, subjects, areas)).andExpect(status().isOk());
        call(post("/api/v1/tutors/me/profile/submit"), tutor, "TUTOR", null).andExpect(status().isOk());
        call(post("/api/v1/tutors/admin/profiles/" + tutor + "/approve"), admin, "ADMIN", null).andExpect(status().isOk());
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

        when(booking.planEligibility(plan)).thenReturn(Optional.of(new BookingClient.PlanEligibility(plan, learner, tutor, "COMPLETED", Instant.now().minus(Duration.ofDays(15)))));
        call(post("/api/v1/tutors/reviews"), learner, "LEARNER", json).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVIEW_WINDOW_CLOSED"));

        when(booking.planEligibility(plan)).thenReturn(Optional.of(new BookingClient.PlanEligibility(plan, learner, tutor, "COMPLETED", Instant.now().minus(Duration.ofDays(1)))));
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
}
