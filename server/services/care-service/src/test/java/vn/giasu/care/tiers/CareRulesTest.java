package vn.giasu.care.tiers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vn.giasu.care.tiers.CareRules.Signals;

class CareRulesTest {
    static Signals quiet() { return new Signals(0, 0, 0, 0, false, false, false, null, null, null, false); }

    @Test
    void steadyLearnerIsTierOne() {
        assertEquals(1, CareRules.tier(quiet()).tier());
        assertEquals(1, CareRules.tier(new Signals(0, 0, 0, 0, false, false, false, 60.0, 70.0, 75.0, false)).tier());
    }

    @Test
    void oneMissOrDropOrEndingIsTierTwo() {
        assertEquals(2, CareRules.tier(new Signals(1, 0, 0, 0, false, false, false, null, null, null, false)).tier());
        assertEquals(2, CareRules.tier(new Signals(0, 0, 0, 0, false, false, false, 50.0, 80.0, 65.0, false)).tier());
        assertEquals(2, CareRules.tier(new Signals(0, 0, 0, 0, false, true, false, null, null, null, false)).tier());
        // A drop of exactly 10 points is not yet a drop.
        assertEquals(1, CareRules.tier(new Signals(0, 0, 0, 0, false, false, false, 50.0, 80.0, 70.0, false)).tier());
    }

    @Test
    void repeatedMissesDisputeNegativeProgressOrOverdueTicketIsTierThree() {
        assertEquals(3, CareRules.tier(new Signals(1, 1, 0, 0, false, false, false, null, null, null, false)).tier());
        assertEquals(3, CareRules.tier(new Signals(0, 0, 1, 0, false, false, false, null, null, null, false)).tier());
        assertEquals(3, CareRules.tier(new Signals(0, 0, 0, 0, false, false, false, 70.0, 75.0, 60.0, false)).tier());
        assertEquals(3, CareRules.tier(new Signals(0, 0, 0, 0, false, false, false, null, null, null, true)).tier());
    }

    @Test
    void openCaseRestrictionOrDropOutIsTierFour() {
        assertEquals(4, CareRules.tier(new Signals(0, 0, 0, 1, false, false, false, null, null, null, false)).tier());
        assertEquals(4, CareRules.tier(new Signals(0, 0, 0, 0, true, false, false, null, null, null, false)).tier());
        CareRules.Tier dropOut = CareRules.tier(new Signals(0, 2, 0, 0, false, true, true, null, null, null, false));
        assertEquals(4, dropOut.tier());
        assertTrue(dropOut.reasons().getFirst().startsWith("Dấu hiệu bỏ học"));
        // Two absences alone, with a renewal under way, stay at tier 3.
        assertEquals(3, CareRules.tier(new Signals(0, 2, 0, 0, false, false, true, null, null, null, false)).tier());
    }
}
