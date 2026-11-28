package vn.giasu.assessment.questions;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import vn.giasu.common.web.ApiException;

/**
 * P16 question bank. Admins write, import (CSV) and review; a tutor may add private questions used only in their own exams.
 * Copyrighted material (commercial books, test-prep sites) must not be entered; official public exams need their source.
 */
@RestController
class QuestionController {
    record QuestionRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9_]{2,32}") String subjectCode,
            @NotBlank @Pattern(regexp = "[A-Z0-9_]{2,32}") String levelCode,
            @NotBlank @Size(max = 100) String topic,
            @NotNull @Min(1) @Max(3) Integer difficulty,
            @NotBlank @Size(min = 5, max = 4000) String content,
            @NotNull @Size(min = 4, max = 4) List<@NotBlank @Size(max = 1000) String> options,
            @NotBlank @Pattern(regexp = "[ABCD]") String correct,
            @Size(max = 4000) String explanation,
            @Size(max = 300) String source) {}
    record ImportRequest(@NotBlank @Size(max = 2_000_000) String csv) {}
    record ImportResult(int imported) {}
    record LineError(int line, String message) {}
    record NoteRequest(@Size(max = 1000) String note) {}
    public record Question(UUID id, String subjectCode, String levelCode, String topic, int difficulty, String content, String optionA,
                           String optionB, String optionC, String optionD, String correct, String explanation, String source, String origin,
                           String status, UUID ownerTutorId, UUID createdBy, UUID reviewedBy, String reviewNote, OffsetDateTime createdAt,
                           OffsetDateTime reviewedAt) {}

    static final String SELECT = """
            SELECT id, subject_code, level_code, topic, difficulty, content, option_a, option_b, option_c, option_d, correct, explanation,
                   source, origin, status, owner_tutor_id, created_by, reviewed_by, review_note, created_at, reviewed_at FROM questions""";
    static final int MAX_IMPORT_ROWS = 1000;

    private final JdbcClient jdbc;
    private final Validator validator;

    QuestionController(JdbcClient jdbc, Validator validator) {
        this.jdbc = jdbc;
        this.validator = validator;
    }

    @PostMapping("/api/v1/assessments/admin/questions")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    Question create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody QuestionRequest r) {
        UUID admin = UUID.fromString(jwt.getSubject());
        return get(insert(r, "MANUAL", "APPROVED", null, admin, admin));
    }

    /** All-or-nothing: one bad row rejects the file with every error listed, so a half-imported bank never happens. */
    @PostMapping("/api/v1/assessments/admin/questions/import")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    ImportResult importCsv(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ImportRequest r) {
        List<List<String>> rows = Csv.parse(r.csv());
        if (rows.isEmpty() || !rows.get(0).get(0).strip().equalsIgnoreCase("subject")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CSV",
                    "Dòng đầu phải là tiêu đề: subject,level,topic,difficulty,content,a,b,c,d,correct,explanation,source");
        }
        if (rows.size() - 1 > MAX_IMPORT_ROWS) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CSV", "Tối đa 1000 câu mỗi lần nhập");
        List<QuestionRequest> requests = new ArrayList<>();
        List<LineError> errors = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> c = rows.get(i);
            if (c.size() == 1 && c.get(0).isBlank()) continue;
            if (c.size() < 10) {
                errors.add(new LineError(i + 1, "Thiếu cột: cần ít nhất 10 cột"));
                continue;
            }
            Integer difficulty;
            try {
                difficulty = Integer.valueOf(c.get(3).strip());
            } catch (NumberFormatException e) {
                difficulty = null;
            }
            QuestionRequest q = new QuestionRequest(c.get(0).strip().toUpperCase(), c.get(1).strip().toUpperCase(), c.get(2).strip(), difficulty,
                    c.get(4).strip(), List.of(c.get(5).strip(), c.get(6).strip(), c.get(7).strip(), c.get(8).strip()), c.get(9).strip().toUpperCase(),
                    c.size() > 10 ? c.get(10).strip() : "", c.size() > 11 ? c.get(11).strip() : "");
            Set<ConstraintViolation<QuestionRequest>> violations = validator.validate(q);
            if (violations.isEmpty()) {
                requests.add(q);
            } else {
                int line = i + 1;
                violations.forEach(v -> errors.add(new LineError(line, v.getPropertyPath() + ": " + v.getMessage())));
            }
        }
        if (!errors.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CSV", "Tệp có dòng không hợp lệ", errors);
        UUID admin = UUID.fromString(jwt.getSubject());
        requests.forEach(q -> insert(q, "IMPORT", "APPROVED", null, admin, admin));
        return new ImportResult(requests.size());
    }

    // shortcut: latest 200 matches only; page it when the bank grows past that per filter.
    @GetMapping("/api/v1/assessments/admin/questions")
    @PreAuthorize("hasRole('ADMIN')")
    List<Question> list(@RequestParam(required = false) String status, @RequestParam(required = false) String subject,
                        @RequestParam(required = false) String level) {
        return jdbc.sql(SELECT + " WHERE (cast(:status AS varchar) IS NULL OR status = :status)"
                        + " AND (cast(:subject AS varchar) IS NULL OR subject_code = :subject)"
                        + " AND (cast(:level AS varchar) IS NULL OR level_code = :level) ORDER BY created_at DESC LIMIT 200")
                .param("status", status).param("subject", subject).param("level", level).query(Question.class).list();
    }

    @PostMapping("/api/v1/assessments/admin/questions/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Question approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody(required = false) NoteRequest r) {
        return review(jwt, id, "APPROVED", r == null ? null : r.note());
    }

    @PostMapping("/api/v1/assessments/admin/questions/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Question reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody NoteRequest r) {
        if (r.note() == null || r.note().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Cần lý do từ chối");
        return review(jwt, id, "REJECTED", r.note());
    }

    /** A tutor's own question: usable right away, but only in that tutor's exams (never enters the shared bank). */
    @PostMapping("/api/v1/assessments/questions")
    @PreAuthorize("hasRole('TUTOR')")
    @ResponseStatus(HttpStatus.CREATED)
    Question createPrivate(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody QuestionRequest r) {
        UUID tutor = UUID.fromString(jwt.getSubject());
        return get(insert(r, "TUTOR", "APPROVED", tutor, tutor, null));
    }

    @GetMapping("/api/v1/assessments/questions/mine")
    @PreAuthorize("hasRole('TUTOR')")
    List<Question> mine(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.sql(SELECT + " WHERE owner_tutor_id = ? ORDER BY created_at DESC LIMIT 200").param(UUID.fromString(jwt.getSubject()))
                .query(Question.class).list();
    }

    private Question review(Jwt jwt, UUID id, String to, String note) {
        int updated = jdbc.sql("UPDATE questions SET status = ?, reviewed_by = ?, review_note = ?, reviewed_at = now() WHERE id = ? AND status = 'PENDING'")
                .params(to, UUID.fromString(jwt.getSubject()), note == null || note.isBlank() ? null : note.strip(), id).update();
        if (updated == 0) {
            get(id);
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS", "Câu hỏi không ở trạng thái chờ duyệt");
        }
        return get(id);
    }

    UUID insert(QuestionRequest r, String origin, String status, UUID owner, UUID createdBy, UUID reviewedBy) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO questions (id, subject_code, level_code, topic, difficulty, content, option_a, option_b, option_c, option_d, correct,
                    explanation, source, origin, status, owner_tutor_id, created_by, reviewed_by, reviewed_at)
                VALUES (:id, :subject, :level, :topic, :difficulty, :content, :a, :b, :c, :d, :correct, :explanation, :source, :origin, :status,
                    cast(:owner AS uuid), :createdBy, cast(:reviewedBy AS uuid), CASE WHEN cast(:reviewedBy AS uuid) IS NULL THEN NULL ELSE now() END)""")
                .param("id", id).param("subject", r.subjectCode()).param("level", r.levelCode()).param("topic", r.topic().strip())
                .param("difficulty", r.difficulty()).param("content", r.content().strip())
                .param("a", r.options().get(0).strip()).param("b", r.options().get(1).strip())
                .param("c", r.options().get(2).strip()).param("d", r.options().get(3).strip()).param("correct", r.correct())
                .param("explanation", r.explanation() == null ? "" : r.explanation().strip())
                .param("source", r.source() == null ? "" : r.source().strip()).param("origin", origin).param("status", status)
                .param("owner", owner == null ? null : owner.toString()).param("createdBy", createdBy)
                .param("reviewedBy", reviewedBy == null ? null : reviewedBy.toString())
                .update();
        return id;
    }

    Question get(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(Question.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "QUESTION_NOT_FOUND", "Không tìm thấy câu hỏi"));
    }
}
