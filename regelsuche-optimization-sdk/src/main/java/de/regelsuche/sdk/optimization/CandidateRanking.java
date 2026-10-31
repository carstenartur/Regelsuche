package de.regelsuche.sdk.optimization;

import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.search.program.PreparedJointComputation;

/** Consumer cost estimates order the existing search; they never establish equivalence.
 * Implementations must be deterministic, bounded and report their inspection work.
 */
@FunctionalInterface
public interface CandidateRanking {
    Assessment assess(JointComputationPlan plan, PreparedJointComputation prepared);
    /** Original execution may repeat values already shared in the prepared graph. */
    default Assessment assessSource(JointComputationPlan plan, PreparedJointComputation prepared) {
        return assess(plan, prepared);
    }
    /** runtimeWork is separate from the weighted score; -1 retains the SDK's structural estimate. */
    record Assessment(long score, long work, long runtimeWork) {
        public Assessment(long score, long work) { this(score, work, -1); }
        public Assessment {
            if (score < 0 || score > Long.MAX_VALUE / 16 || work < 1 || runtimeWork < -1 || runtimeWork > Long.MAX_VALUE / 16)
                throw new IllegalArgumentException("INVALID_CANDIDATE_RANKING");
        }
    }
}
