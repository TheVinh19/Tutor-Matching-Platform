package vn.giasu.assessment.exams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import vn.giasu.assessment.exams.ExamController.Candidate;

class PickTest {
    static List<Candidate> bank(int easy, int medium, int hard) {
        List<Candidate> c = new ArrayList<>();
        for (int i = 0; i < easy; i++) c.add(new Candidate(UUID.randomUUID(), 1));
        for (int i = 0; i < medium; i++) c.add(new Candidate(UUID.randomUUID(), 2));
        for (int i = 0; i < hard; i++) c.add(new Candidate(UUID.randomUUID(), 3));
        return c;
    }

    static Map<Integer, Long> mix(List<Candidate> bank, List<UUID> picked) {
        Map<UUID, Integer> level = bank.stream().collect(Collectors.toMap(Candidate::id, Candidate::difficulty));
        return picked.stream().collect(Collectors.groupingBy(level::get, Collectors.counting()));
    }

    @Test
    void tenQuestionsFollowThe30_50_20Mix() {
        List<Candidate> bank = bank(10, 10, 10);
        List<UUID> picked = ExamController.pick(bank, 10, new Random(1));
        assertEquals(10, new HashSet<>(picked).size());
        assertEquals(Map.of(1, 3L, 2, 5L, 3, 2L), mix(bank, picked));
    }

    @Test
    void shortBucketIsFilledFromTheOthers() {
        List<Candidate> bank = bank(10, 10, 0);
        List<UUID> picked = ExamController.pick(bank, 10, new Random(2));
        assertEquals(10, new HashSet<>(picked).size());
        assertTrue(mix(bank, picked).get(1) >= 3);
    }

    @Test
    void neverMoreThanTheBankHas() {
        assertEquals(4, ExamController.pick(bank(2, 1, 1), 10, new Random(3)).size());
    }

    @Test
    void optionOrderIsAPermutation() {
        Random rnd = new Random(4);
        for (int i = 0; i < 20; i++) {
            String order = ExamController.shuffledOrder(rnd);
            assertEquals(List.of('A', 'B', 'C', 'D'), order.chars().sorted().mapToObj(c -> (char) c).toList());
        }
    }
}
