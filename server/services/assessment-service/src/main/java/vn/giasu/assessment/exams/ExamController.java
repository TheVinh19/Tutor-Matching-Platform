package vn.giasu.assessment.exams;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.assessment.BookingClient;
import vn.giasu.assessment.BookingClient.PlanSummary;
import vn.giasu.assessment.Notifier;
import vn.giasu.common.web.ApiException;

/**
 * P16 levels 1–2: the tutor of a confirmed period assigns placement, periodic and final exams; the learner (on the hirer's account)
 * takes each once: random questions by a 30/50/20 easy/medium/hard mix, shuffled options, a time limit. Late answers are graded
 * and flagged, never thrown away. Results are per topic; the gradebook follows the renewal chain.
 */
@RestController
class ExamController {
    enum Kind { PLACEMENT, PERIODIC, FINAL }

    record CreateRequest(@NotNull UUID planId, @NotNull Kind kind, @NotBlank @Size(max = 200) String title,
                         @NotNull @Min(5) @Max(50) Integer questionCount, @NotNull @Min(5) @Max(180) Integer durationMinutes,
                         @Size(max = 20) List<@NotBlank @Size(max = 100) String> topics) {}
    record Exam(UUID id, UUID planId, UUID tutorId, UUID learnerId, String kind, String subjectCode, String levelCode, String title,
                String topics, int questionCount, int durationMinutes, OffsetDateTime createdAt) {}
    record AttemptSummary(UUID id, OffsetDateTime startedAt, OffsetDateTime deadlineAt, OffsetDateTime submittedAt, boolean late,
                          Integer correctCount, int total) {}
    record ExamView(Exam exam, AttemptSummary attempt) {}
    record ItemView(int position, String topic, String content, List<String> options) {}
    record AttemptView(UUID attemptId, UUID examId, OffsetDateTime deadlineAt, List<ItemView> items) {}
    record Answer(@NotNull @Min(1) Integer position, @Pattern(regexp = "[ABCD]") String choice) {}
    record SubmitRequest(@NotNull @Size(max = 50) List<@Valid @NotNull Answer> answers) {}
    record TopicScore(String topic, int correct, int total, double percent) {}
    record ResultItem(int position, String topic, String content, List<String> options, String chosen, String correctOption,
                      boolean correct, String explanation) {}
    record Result(Exam exam, AttemptSummary attempt, double percent, List<TopicScore> byTopic, List<ResultItem> items) {}
    record GradebookEntry(Exam exam, AttemptSummary attempt, Double percent, List<TopicScore> byTopic) {}
    record Progress(String topic, Double placementPercent, Double latestPercent, Double change) {}
    record Gradebook(List<UUID> planChain, List<GradebookEntry> exams, List<Progress> progress) {}
    record Signal(UUID learnerId, Double placementPercent, Double previousPercent, Double latestPercent) {}
    record Candidate(UUID id, int difficulty) {}
    record Row(int position, UUID questionId, String optionOrder, String answer, Boolean correct, String topic, String content,
               String optionA, String optionB, String optionC, String optionD, String rightAnswer, String explanation) {
        String text(char original) {
            return switch (original) { case 'A' -> optionA; case 'B' -> optionB; case 'C' -> optionC; default -> optionD; };
        }
        List<String> displayed() {
            return List.of(text(optionOrder.charAt(0)), text(optionOrder.charAt(1)), text(optionOrder.charAt(2)), text(optionOrder.charAt(3)));
        }
        String shown(String original) {
            return original == null ? null : String.valueOf(LETTERS.charAt(optionOrder.indexOf(original)));
        }
    }

    static final String LETTERS = "ABCD";
    static final Set<String> ACTIVE = Set.of("CONFIRMED");
    static final Set<String> FINAL_ALLOWED = Set.of("CONFIRMED", "HOAN_TAT_NGHIA_VU", "KET_THUC_CO_HOAN_MOT_PHAN", "HET_KY_CON_NGHIA_VU");
    private static final String EXAM = """
            SELECT id, plan_id, tutor_id, learner_id, kind, subject_code, level_code, title, topics, question_count, duration_minutes,
                   created_at FROM exams""";
    private static final String ATTEMPT = "SELECT id, started_at, deadline_at, submitted_at, late, correct_count, total FROM attempts";
    private static final String ROWS = """
            SELECT i.position, i.question_id, i.option_order, i.answer, i.correct, q.topic, q.content, q.option_a, q.option_b, q.option_c,
                   q.option_d, q.correct AS right_answer, q.explanation
            FROM attempt_items i JOIN questions q ON q.id = i.question_id WHERE i.attempt_id = ? ORDER BY i.position""";

    private final JdbcClient jdbc;
    private final BookingClient booking;
    private final Notifier notifier;

    ExamController(JdbcClient jdbc, BookingClient booking, Notifier notifier) {
        this.jdbc = jdbc;
        this.booking = booking;
        this.notifier = notifier;
    }

    @PostMapping("/api/v1/assessments/exams")
    @PreAuthorize("hasRole('TUTOR')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    ExamView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest r) {
        PlanSummary plan = booking.plan(r.planId());
        UUID tutor = caller(jwt);
        if (!plan.tutorId().equals(tutor)) throw new ApiException(HttpStatus.FORBIDDEN, "NOT_PLAN_TUTOR", "Không phải gia sư của kỳ học này");
        if (!(r.kind() == Kind.FINAL ? FINAL_ALLOWED : ACTIVE).contains(plan.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_NOT_ACTIVE", "Chỉ giao bài cho kỳ học đã chốt");
        }
        List<String> topics = r.topics() == null ? List.of() : r.topics().stream().map(String::strip).distinct().toList();
        int available = candidates(plan.subjectCode(), plan.levelCode(), tutor, topics).size();
        if (available < r.questionCount()) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_ENOUGH_QUESTIONS",
                    "Ngân hàng chỉ có " + available + " câu phù hợp, cần " + r.questionCount(), Map.of("available", available));
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO exams (id, plan_id, tutor_id, learner_id, kind, subject_code, level_code, title, topics, question_count,
                        duration_minutes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, plan.id(), tutor, plan.learnerId(), r.kind().name(), plan.subjectCode(), plan.levelCode(), r.title().strip(),
                            topics.isEmpty() ? null : String.join("\n", topics), r.questionCount(), r.durationMinutes())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "EXAM_EXISTS", "Kỳ học đã có bài " + (r.kind() == Kind.FINAL ? "cuối kỳ" : "đầu kỳ"));
        }
        notifier.send(plan.learnerId(), "EXAM_ASSIGNED", "Có bài kiểm tra mới", r.title().strip(), "EXAM", id);
        return new ExamView(exam(id, false), null);
    }

    @GetMapping("/api/v1/assessments/exams")
    List<ExamView> list(@AuthenticationPrincipal Jwt jwt, @RequestParam UUID planId) {
        requirePlanReader(jwt, booking.plan(planId));
        return jdbc.sql(EXAM + " WHERE plan_id = ? ORDER BY created_at").param(planId).query(Exam.class).list().stream()
                .map(e -> new ExamView(e, attempt(e.id(), false).orElse(null))).toList();
    }

    /** Starting twice resumes the same attempt (same questions, same deadline); a submitted exam cannot be retaken. */
    @PostMapping("/api/v1/assessments/exams/{id}/start")
    @PreAuthorize("hasRole('LEARNER')")
    @Transactional
    AttemptView start(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Exam e = exam(id, true);
        requireLearner(jwt, e);
        Optional<AttemptSummary> existing = attempt(id, false);
        if (existing.isPresent()) {
            if (existing.get().submittedAt() != null) throw new ApiException(HttpStatus.CONFLICT, "ALREADY_SUBMITTED", "Bài đã nộp");
            return attemptView(e, existing.get());
        }
        List<String> topics = e.topics() == null ? List.of() : List.of(e.topics().split("\n"));
        List<UUID> picked = pick(candidates(e.subjectCode(), e.levelCode(), e.tutorId(), topics), e.questionCount(), ThreadLocalRandom.current());
        if (picked.size() < e.questionCount()) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_ENOUGH_QUESTIONS", "Ngân hàng câu hỏi không còn đủ câu cho bài này");
        }
        UUID attemptId = UUID.randomUUID();
        jdbc.sql("INSERT INTO attempts (id, exam_id, learner_id, deadline_at, total) VALUES (?, ?, ?, now() + make_interval(mins => ?), ?)")
                .params(attemptId, id, e.learnerId(), e.durationMinutes(), picked.size()).update();
        for (int i = 0; i < picked.size(); i++) {
            jdbc.sql("INSERT INTO attempt_items (attempt_id, position, question_id, option_order) VALUES (?, ?, ?, ?)")
                    .params(attemptId, i + 1, picked.get(i), shuffledOrder(ThreadLocalRandom.current())).update();
        }
        return attemptView(e, attempt(id, false).orElseThrow());
    }

    @PostMapping("/api/v1/assessments/exams/{id}/submit")
    @PreAuthorize("hasRole('LEARNER')")
    @Transactional
    Result submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody SubmitRequest r) {
        Exam e = exam(id, false);
        requireLearner(jwt, e);
        AttemptSummary a = attempt(id, true).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NOT_STARTED", "Chưa bắt đầu làm bài"));
        if (a.submittedAt() != null) throw new ApiException(HttpStatus.CONFLICT, "ALREADY_SUBMITTED", "Bài đã nộp");
        Map<Integer, String> choices = new HashMap<>();
        for (Answer ans : r.answers()) {
            if (ans.position() > a.total()) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_POSITION", "Câu " + ans.position() + " không có trong bài");
            choices.put(ans.position(), ans.choice());
        }
        int correct = 0;
        for (Row row : rows(a.id())) {
            String shown = choices.get(row.position());
            String original = shown == null ? null : String.valueOf(row.optionOrder().charAt(LETTERS.indexOf(shown)));
            boolean ok = original != null && original.equals(row.rightAnswer());
            if (ok) correct++;
            jdbc.sql("UPDATE attempt_items SET answer = ?, correct = ? WHERE attempt_id = ? AND position = ?")
                    .params(original, ok, a.id(), row.position()).update();
        }
        boolean late = OffsetDateTime.now(ZoneOffset.UTC).isAfter(a.deadlineAt());
        jdbc.sql("UPDATE attempts SET submitted_at = now(), late = ?, correct_count = ? WHERE id = ?").params(late, correct, a.id()).update();
        notifier.send(e.tutorId(), "EXAM_SUBMITTED", "Học sinh đã nộp bài", e.title() + ": " + correct + "/" + a.total(), "EXAM", id);
        return result(e);
    }

    @GetMapping("/api/v1/assessments/exams/{id}/result")
    Result result(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Exam e = exam(id, false);
        if (!staff(jwt) && !caller(jwt).equals(e.learnerId()) && !caller(jwt).equals(e.tutorId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Không có quyền xem kết quả này");
        }
        return result(e);
    }

    @GetMapping("/api/v1/assessments/plans/{planId}/gradebook")
    Gradebook gradebook(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID planId) {
        PlanSummary plan = booking.plan(planId);
        requirePlanReader(jwt, plan);
        return gradebook(plan);
    }

    // ---- internal: ai-service (explanations, tutor summary) and care-service (tier signals) ----

    @GetMapping("/api/v1/assessments/internal/exams/{id}/result")
    Result internalResult(@PathVariable UUID id) {
        return result(exam(id, false));
    }

    @GetMapping("/api/v1/assessments/internal/plans/{planId}/gradebook")
    Gradebook internalGradebook(@PathVariable UUID planId) {
        return gradebook(booking.plan(planId));
    }

    /** Latest placement score, previous and latest submitted scores per learner (percent), for P18 tiers. */
    @GetMapping("/api/v1/assessments/internal/learners/signals")
    List<Signal> signals() {
        return jdbc.sql("""
                WITH s AS (
                    SELECT a.learner_id, e.kind, a.submitted_at, (a.correct_count * 100.0 / a.total)::float8 AS pct,
                           row_number() OVER (PARTITION BY a.learner_id ORDER BY a.submitted_at DESC) AS rn
                    FROM attempts a JOIN exams e ON e.id = a.exam_id WHERE a.submitted_at IS NOT NULL)
                SELECT l.learner_id,
                       (SELECT p.pct FROM s p WHERE p.learner_id = l.learner_id AND p.kind = 'PLACEMENT' ORDER BY p.submitted_at DESC LIMIT 1)
                           AS placement_percent,
                       max(l.pct) FILTER (WHERE l.rn = 2) AS previous_percent,
                       max(l.pct) FILTER (WHERE l.rn = 1) AS latest_percent
                FROM s l GROUP BY l.learner_id""").query(Signal.class).list();
    }

    // ---- helpers ----

    /** Easy/medium/hard targets 30/50/20%; a short bucket is filled from the others; final order shuffled. */
    static List<UUID> pick(List<Candidate> candidates, int n, Random rnd) {
        Map<Integer, List<Candidate>> buckets = new HashMap<>();
        for (Candidate c : candidates) buckets.computeIfAbsent(c.difficulty(), k -> new ArrayList<>()).add(c);
        buckets.values().forEach(b -> Collections.shuffle(b, rnd));
        int easy = Math.round(n * 0.3f);
        int hard = Math.round(n * 0.2f);
        int[] target = {0, easy, n - easy - hard, hard};
        List<UUID> picked = new ArrayList<>();
        List<Candidate> rest = new ArrayList<>();
        for (int d = 1; d <= 3; d++) {
            List<Candidate> b = buckets.getOrDefault(d, List.of());
            int take = Math.min(target[d], b.size());
            b.subList(0, take).forEach(c -> picked.add(c.id()));
            rest.addAll(b.subList(take, b.size()));
        }
        Collections.shuffle(rest, rnd);
        for (int i = 0; picked.size() < n && i < rest.size(); i++) picked.add(rest.get(i).id());
        Collections.shuffle(picked, rnd);
        return picked;
    }

    static String shuffledOrder(Random rnd) {
        List<Character> order = new ArrayList<>(List.of('A', 'B', 'C', 'D'));
        Collections.shuffle(order, rnd);
        StringBuilder sb = new StringBuilder();
        order.forEach(sb::append);
        return sb.toString();
    }

    private List<Candidate> candidates(String subject, String level, UUID tutor, List<String> topics) {
        String sql = """
                SELECT id, difficulty FROM questions WHERE status = 'APPROVED' AND subject_code = :subject AND level_code = :level
                  AND (owner_tutor_id IS NULL OR owner_tutor_id = :tutor)""" + (topics.isEmpty() ? "" : " AND topic IN (:topics)");
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("subject", subject).param("level", level).param("tutor", tutor);
        if (!topics.isEmpty()) spec = spec.param("topics", topics);
        return spec.query(Candidate.class).list();
    }

    private AttemptView attemptView(Exam e, AttemptSummary a) {
        List<ItemView> items = rows(a.id()).stream().map(r -> new ItemView(r.position(), r.topic(), r.content(), r.displayed())).toList();
        return new AttemptView(a.id(), e.id(), a.deadlineAt(), items);
    }

    private Result result(Exam e) {
        AttemptSummary a = attempt(e.id(), false).filter(x -> x.submittedAt() != null)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NOT_SUBMITTED", "Học sinh chưa nộp bài"));
        List<Row> rows = rows(a.id());
        List<ResultItem> items = rows.stream().map(r -> new ResultItem(r.position(), r.topic(), r.content(), r.displayed(), r.shown(r.answer()),
                r.shown(r.rightAnswer()), Boolean.TRUE.equals(r.correct()), r.explanation())).toList();
        return new Result(e, a, percent(a.correctCount(), a.total()), byTopic(rows), items);
    }

    private Gradebook gradebook(PlanSummary plan) {
        List<UUID> chain = new ArrayList<>(List.of(plan.id()));
        PlanSummary cursor = plan;
        while (cursor.renewedFrom() != null && chain.size() < 12) {
            cursor = booking.plan(cursor.renewedFrom());
            chain.add(0, cursor.id());
        }
        List<GradebookEntry> entries = new ArrayList<>();
        for (Exam e : jdbc.sql(EXAM + " WHERE plan_id IN (:chain) ORDER BY created_at").param("chain", chain).query(Exam.class).list()) {
            Optional<AttemptSummary> a = attempt(e.id(), false);
            boolean done = a.isPresent() && a.get().submittedAt() != null;
            entries.add(new GradebookEntry(e, a.orElse(null), done ? percent(a.get().correctCount(), a.get().total()) : null,
                    done ? byTopic(rows(a.get().id())) : List.of()));
        }
        Map<String, Double> placement = new LinkedHashMap<>();
        Map<String, Double> latest = new LinkedHashMap<>();
        for (GradebookEntry g : entries) {
            if (g.percent() == null) continue;
            for (TopicScore t : g.byTopic()) {
                if ("PLACEMENT".equals(g.exam().kind())) placement.putIfAbsent(t.topic(), t.percent());
                latest.put(t.topic(), t.percent());
            }
        }
        List<Progress> progress = latest.entrySet().stream().map(t -> {
            Double first = placement.get(t.getKey());
            return new Progress(t.getKey(), first, t.getValue(), first == null ? null : t.getValue() - first);
        }).toList();
        return new Gradebook(chain, entries, progress);
    }

    private static List<TopicScore> byTopic(List<Row> rows) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (Row r : rows) {
            int[] c = counts.computeIfAbsent(r.topic(), k -> new int[2]);
            if (Boolean.TRUE.equals(r.correct())) c[0]++;
            c[1]++;
        }
        return counts.entrySet().stream().map(t -> new TopicScore(t.getKey(), t.getValue()[0], t.getValue()[1],
                percent(t.getValue()[0], t.getValue()[1]))).toList();
    }

    private static double percent(Integer correct, int total) {
        return total == 0 || correct == null ? 0 : Math.round(correct * 1000.0 / total) / 10.0;
    }

    private List<Row> rows(UUID attemptId) {
        return jdbc.sql(ROWS).param(attemptId).query(Row.class).list();
    }

    private Exam exam(UUID id, boolean forUpdate) {
        return jdbc.sql(EXAM + " WHERE id = ?" + (forUpdate ? " FOR UPDATE" : "")).param(id).query(Exam.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXAM_NOT_FOUND", "Không tìm thấy bài kiểm tra"));
    }

    private Optional<AttemptSummary> attempt(UUID examId, boolean forUpdate) {
        return jdbc.sql(ATTEMPT + " WHERE exam_id = ?" + (forUpdate ? " FOR UPDATE" : "")).param(examId).query(AttemptSummary.class).optional();
    }

    private static void requireLearner(Jwt jwt, Exam e) {
        if (!caller(jwt).equals(e.learnerId())) throw new ApiException(HttpStatus.FORBIDDEN, "NOT_EXAM_LEARNER", "Bài này không giao cho bạn");
    }

    private static void requirePlanReader(Jwt jwt, PlanSummary plan) {
        UUID me = caller(jwt);
        if (!staff(jwt) && !me.equals(plan.learnerId()) && !me.equals(plan.tutorId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Không có quyền với kỳ học này");
        }
    }

    /** Admin and customer care staff may read results (P16/P18); they never take or assign exams. */
    private static boolean staff(Jwt jwt) {
        String role = jwt.getClaimAsString("role");
        return "ADMIN".equals(role) || "SUPPORT".equals(role);
    }

    private static UUID caller(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
