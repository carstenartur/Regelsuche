package de.regelsuche.paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathSortersTest {

    @Test
    void findsAlternativePaths_sortsByScoreDesc() {
        DiscoveredTransformation small = transformation("small", 3, 1, CandidateProofStatus.OBSERVED);
        DiscoveredTransformation big = transformation("big", 10, 4, CandidateProofStatus.OBSERVED);

        List<DiscoveredTransformation> sorted = new PathSorters().sort(List.of(small, big), PathSorters.Mode.SCORE);
        assertEquals("big", sorted.get(0).id());
        assertEquals("small", sorted.get(1).id());
    }

    @Test
    void sortByLengthAscending() {
        DiscoveredTransformation longPath = transformation("long", 5, 3, CandidateProofStatus.OBSERVED);
        DiscoveredTransformation shortPath = transformation("short", 5, 1, CandidateProofStatus.OBSERVED);
        List<DiscoveredTransformation> sorted = new PathSorters().sort(List.of(longPath, shortPath), PathSorters.Mode.LENGTH);
        assertEquals("short", sorted.get(0).id());
    }

    @Test
    void sortByProofRanksHigherStatusFirst() {
        DiscoveredTransformation observed = transformation("obs", 5, 1, CandidateProofStatus.OBSERVED);
        DiscoveredTransformation formal = transformation("formal", 5, 1, CandidateProofStatus.FORMALLY_PROVED);
        List<DiscoveredTransformation> sorted = new PathSorters().sort(List.of(observed, formal), PathSorters.Mode.PROOF);
        assertEquals("formal", sorted.get(0).id());
    }

    private static DiscoveredTransformation transformation(String id, int improvement, int stepCount, CandidateProofStatus status) {
        List<TransformationStep> steps = new java.util.ArrayList<>();
        for (int i = 0; i < stepCount; i++) {
            steps.add(new TransformationStep(i, "a" + i, "a" + (i + 1), "rule" + i,
                RewriteKind.SIMPLIFY, 10 - i, 10 - i - 1, true, ""));
        }
        ExpressionScore origin = new ExpressionScore(10, 10, 5, 1, 0);
        ExpressionScore improved = new ExpressionScore(10 - improvement, 5, 2, 1, 0);
        return new DiscoveredTransformation(
            id, "a0", "a" + stepCount, steps, origin, improved, improvement, status, Instant.now(), "h-" + id);
    }
}
