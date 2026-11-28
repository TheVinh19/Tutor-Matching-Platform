package vn.giasu.tutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import vn.giasu.tutor.ranks.RankRules;
import vn.giasu.tutor.ranks.RankRules.Flag;
import vn.giasu.tutor.ranks.RankRules.Input;

class RankRulesTest {
    static Input in(int rank, int exp, int learners, int renewals, int taught, int failed, int open, boolean entry, long days, boolean penalty) {
        return new Input(rank, exp, learners, renewals, taught, failed, open, entry, days, penalty);
    }

    @Test
    void rank1PromotionNeedsEveryCondition() {
        assertEquals(Flag.ELIGIBLE_UP, RankRules.flag(in(1, 1200, 3, 2, 92, 8, 0, false, 90, false)));
        assertEquals(Flag.OK, RankRules.flag(in(1, 1199, 3, 2, 100, 0, 0, false, 90, false)));
        assertEquals(Flag.OK, RankRules.flag(in(1, 1500, 2, 2, 100, 0, 0, false, 90, false)));
        assertEquals(Flag.OK, RankRules.flag(in(1, 1500, 3, 1, 100, 0, 0, false, 90, false)));
        assertEquals(Flag.OK, RankRules.flag(in(1, 1500, 3, 2, 91, 9, 0, false, 90, false)));   // 91% < 92%
        assertEquals(Flag.OK, RankRules.flag(in(1, 1500, 3, 2, 100, 0, 0, false, 89, false)));  // 89 days
        assertEquals(Flag.OK, RankRules.flag(in(1, 1500, 3, 2, 100, 0, 1, false, 90, false)));  // open issue blocks
        assertEquals(Flag.OK, RankRules.flag(in(1, 1500, 3, 2, 0, 0, 0, false, 90, false)));    // no sample
    }

    @Test
    void rank2KeepAndPromote() {
        assertEquals(Flag.MUST_REVIEW, RankRules.flag(in(2, 1199, 5, 5, 100, 0, 0, false, 10, false)));
        assertEquals(Flag.MUST_REVIEW, RankRules.flag(in(2, 2000, 2, 5, 100, 0, 0, false, 10, false)));
        assertEquals(Flag.OK, RankRules.flag(in(2, 1200, 0, 0, 0, 0, 0, true, 10, false)));      // entry exemption, no sample
        assertEquals(Flag.MUST_REVIEW, RankRules.flag(in(2, 1200, 0, 0, 9, 1, 0, true, 10, false))); // 90% once there is a sample
        assertEquals(Flag.ELIGIBLE_UP, RankRules.flag(in(2, 3500, 8, 6, 96, 4, 0, false, 90, false)));
        assertEquals(Flag.OK, RankRules.flag(in(2, 3500, 8, 6, 96, 4, 0, false, 90, true)));     // penalty in 90 days
    }

    @Test
    void rank3MustBeReviewedEvenWithEnoughExp() {
        assertEquals(Flag.OK, RankRules.flag(in(3, 4000, 8, 6, 100, 0, 0, false, 1, false)));
        assertEquals(Flag.MUST_REVIEW, RankRules.flag(in(3, 4000, 8, 6, 100, 0, 0, false, 1, true)));
        assertEquals(Flag.MUST_REVIEW, RankRules.flag(in(3, 4000, 8, 6, 95, 5, 0, false, 1, false)));
    }

    @Test
    void completionRateWithoutSampleIsNull() {
        assertNull(RankRules.completionRate(0, 0));
        assertEquals(0.5, RankRules.completionRate(1, 1));
    }
}
