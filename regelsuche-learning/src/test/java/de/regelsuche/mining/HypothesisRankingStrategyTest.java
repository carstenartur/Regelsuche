package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HypothesisRankingStrategyTest {
    @Test
    void interestingnessRankingOrdersByCompositeScoreAndKnownRulePenalty() {
        HypothesisCandidate known = hypothesis("known", "A", "A + 0", 1,
            CandidateProofStatus.SYMBOLICALLY_VERIFIED);
        HypothesisCandidate crossDomain = hypothesis("cross", "X + X", "2 * X", 3,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES);

        List<RankedHypothesis> ranked = new InterestingnessRankingStrategy().rank(
            List.of(known, crossDomain),
            Map.of("known", 1.0, "cross", 0.0),
            Map.of("cross", Set.of("algebra", "matrix"))
        );

        assertEquals("cross", ranked.getFirst().hypothesis().id());
        assertTrue(ranked.getFirst().score().total() > ranked.getLast().score().total());
    }

    private static HypothesisCandidate hypothesis(
        String id,
        String left,
        String right,
        int support,
        CandidateProofStatus status
    ) {
        return new HypothesisCandidate(
            id,
            left,
            right,
            java.util.stream.IntStream.range(0, support).mapToObj(i -> "p" + i).toList(),
            java.util.stream.IntStream.range(0, support)
                .mapToObj(i -> new HypothesisCandidate.ExpressionPair(left + i, right + i))
                .toList(),
            List.of(),
            0.0,
            status,
            false,
            List.of(),
            Map.of(),
            Instant.EPOCH
        );
    }
}
