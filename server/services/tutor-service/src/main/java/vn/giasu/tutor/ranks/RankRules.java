package vn.giasu.tutor.ranks;

/**
 * P10 rank conditions. Only flags tutors for an admin; rank changes are admin decisions, never automatic (P09).
 * Promotion and keeping a rank are checked separately: 90 days at the previous rank only matters for promotion.
 */
public final class RankRules {
    private RankRules() {}

    public enum Flag { OK, ELIGIBLE_UP, MUST_REVIEW }

    public record Input(int rank, int exp, int distinctLearners, int renewals, int taughtSessions, int tutorFaultFailed,
                        int openIssues, boolean entryRank2, long daysAtRank, boolean penaltyIn90Days) {}

    static final int RANK2_EXP = 1200;
    static final int RANK3_EXP = 3500;

    /** Taught / (taught + verified tutor-fault failures); null when there is no sample yet ("chưa có dữ liệu"). */
    public static Double completionRate(int taught, int tutorFaultFailed) {
        int total = taught + tutorFaultFailed;
        return total == 0 ? null : (double) taught / total;
    }

    public static Flag flag(Input in) {
        Double rate = completionRate(in.taughtSessions(), in.tutorFaultFailed());
        boolean keep = switch (in.rank()) {
            // Entry rank 2 keeps the learner/renewal history exemption; no sample means the rate is not used to demote.
            case 2 -> in.exp() >= RANK2_EXP && (in.entryRank2() || (in.distinctLearners() >= 3 && in.renewals() >= 2))
                    && (rate == null || rate >= 0.92);
            case 3 -> in.exp() >= RANK3_EXP && in.distinctLearners() >= 8 && in.renewals() >= 6
                    && (rate == null || rate >= 0.96) && !in.penaltyIn90Days();
            default -> true;
        };
        if (!keep) return Flag.MUST_REVIEW;
        // An open dispute/absence report blocks promotion decisions; no sample means not reached.
        boolean up = in.openIssues() == 0 && rate != null && in.daysAtRank() >= 90 && switch (in.rank()) {
            case 1 -> in.exp() >= RANK2_EXP && in.distinctLearners() >= 3 && in.renewals() >= 2 && rate >= 0.92;
            case 2 -> in.exp() >= RANK3_EXP && in.distinctLearners() >= 8 && in.renewals() >= 6 && rate >= 0.96 && !in.penaltyIn90Days();
            default -> false;
        };
        return up ? Flag.ELIGIBLE_UP : Flag.OK;
    }
}
