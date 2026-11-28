package vn.giasu.ai.learning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.ai.Clients;
import vn.giasu.ai.Clients.Gradebook;
import vn.giasu.ai.Clients.GradebookEntry;
import vn.giasu.ai.Clients.PlanSummary;
import vn.giasu.ai.Clients.Progress;
import vn.giasu.ai.Clients.Result;
import vn.giasu.ai.Clients.ResultItem;
import vn.giasu.ai.Clients.TopicScore;
import vn.giasu.common.web.ApiException;

/**
 * P17 learning assistant on top of P16 results: explains wrong answers from the question bank's explanations, points to topics to
 * review, and sums up a learner's level for the tutor. Retrieval mode: the text comes from stored explanations and scores, no model.
 */
@RestController
class LearningController {
    static final double STRONG = 80;
    static final double WEAK = 50;

    record WrongItem(int position, String topic, String content, String chosen, String correctOption, String correctText, String explanation) {}
    record Review(String topic, double percent, String advice) {}
    record Explain(UUID examId, String title, double percent, List<WrongItem> wrong, List<Review> review) {}
    record Summary(UUID planId, int examsTaken, Double placementPercent, Double latestPercent, List<String> strengths, List<String> weaknesses,
                   List<String> improved, List<String> declined, String text) {}

    private final Clients clients;

    LearningController(Clients clients) { this.clients = clients; }

    @GetMapping("/api/v1/ai/learning/exams/{examId}/explain")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    Explain explain(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID examId) {
        Result r = clients.examResult(examId);
        UUID me = UUID.fromString(jwt.getSubject());
        if (!me.equals(r.exam().learnerId()) && !me.equals(r.exam().tutorId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Không phải bài kiểm tra của bạn");
        }
        List<WrongItem> wrong = r.items().stream().filter(i -> !i.correct()).map(i -> new WrongItem(i.position(), i.topic(), i.content(), i.chosen(),
                i.correctOption(), optionText(i), i.explanation() == null || i.explanation().isBlank() ? "Câu này chưa có lời giải thích." : i.explanation()))
                .toList();
        List<Review> review = r.byTopic().stream().filter(t -> t.percent() < STRONG).sorted(Comparator.comparingDouble(TopicScore::percent))
                .map(t -> new Review(t.topic(), t.percent(), t.percent() < WEAK ? "Cần ôn lại từ kiến thức nền của chủ đề này"
                        : "Nắm được cơ bản, nên luyện thêm bài tập")).toList();
        return new Explain(examId, r.exam().title(), r.percent(), wrong, review);
    }

    /** For the plan's tutor (and the learner): strengths, weak topics and change since the placement test, across renewed periods. */
    @GetMapping("/api/v1/ai/learning/plans/{planId}/summary")
    @PreAuthorize("hasAnyRole('LEARNER', 'TUTOR')")
    Summary summary(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID planId) {
        PlanSummary plan = clients.plan(planId);
        UUID me = UUID.fromString(jwt.getSubject());
        if (!me.equals(plan.learnerId()) && !me.equals(plan.tutorId())) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Không thuộc kỳ học này");
        return summarize(planId, clients.gradebook(planId));
    }

    static Summary summarize(UUID planId, Gradebook g) {
        List<GradebookEntry> done = g.exams().stream().filter(e -> e.percent() != null).toList();
        if (done.isEmpty()) return new Summary(planId, 0, null, null, List.of(), List.of(), List.of(), List.of(), "Chưa có bài kiểm tra nào được nộp.");
        Double placement = done.stream().filter(e -> "PLACEMENT".equals(e.exam().kind())).map(GradebookEntry::percent).findFirst().orElse(null);
        Double latest = done.getLast().percent();
        List<String> strengths = g.progress().stream().filter(p -> p.latestPercent() != null && p.latestPercent() >= STRONG).map(Progress::topic).toList();
        List<String> weaknesses = g.progress().stream().filter(p -> p.latestPercent() != null && p.latestPercent() < WEAK).map(Progress::topic).toList();
        List<String> improved = g.progress().stream().filter(p -> p.change() != null && p.change() > 0).map(Progress::topic).toList();
        List<String> declined = g.progress().stream().filter(p -> p.change() != null && p.change() < 0).map(Progress::topic).toList();

        List<String> text = new ArrayList<>();
        text.add("Đã làm " + done.size() + " bài, bài gần nhất đạt " + latest + "%" + (placement == null ? "." : ", bài đầu vào " + placement + "%."));
        if (!strengths.isEmpty()) text.add("Vững: " + String.join(", ", strengths) + ".");
        if (!weaknesses.isEmpty()) text.add("Cần ôn: " + String.join(", ", weaknesses) + ".");
        if (!improved.isEmpty()) text.add("Tiến bộ so với đầu vào: " + String.join(", ", improved) + ".");
        if (!declined.isEmpty()) text.add("Giảm so với đầu vào: " + String.join(", ", declined) + ".");
        return new Summary(planId, done.size(), placement, latest, strengths, weaknesses, improved, declined, String.join(" ", text));
    }

    private static String optionText(ResultItem i) {
        int index = i.correctOption() == null ? -1 : "ABCD".indexOf(i.correctOption());
        return index < 0 || i.options() == null || index >= i.options().size() ? null : i.options().get(index);
    }
}
