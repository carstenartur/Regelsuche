package de.regelsuche.api.searchgraph.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.api.searchgraph.SearchGraphDto;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MainPathSelectorTest {

    private final MainPathSelector selector = new WeightedMainPathSelector();

    @Test
    void prefersShorterHighSignalPath() {
        DiscoveredTransformation shortHigh = new DiscoveredTransformation(
            "short",
            "a+0",
            "a",
            List.of(new TransformationStep(0, "a+0", "a", "remove_zero", RewriteKind.SIMPLIFY, 8, 5, true, "")),
            new ExpressionScore(8, 0, 0, 0, 0),
            new ExpressionScore(5, 0, 0, 0, 0),
            3,
            CandidateProofStatus.OBSERVED,
            Instant.now(),
            ""
        );
        DiscoveredTransformation longLow = new DiscoveredTransformation(
            "long",
            "a+0",
            "a",
            List.of(
                new TransformationStep(0, "a+0", "0+a", "commutativity", RewriteKind.NORMALIZE, 8, 8, true, ""),
                new TransformationStep(1, "0+a", "a", "remove_zero", RewriteKind.SIMPLIFY, 8, 5, true, "")
            ),
            new ExpressionScore(8, 0, 0, 0, 0),
            new ExpressionScore(5, 0, 0, 0, 0),
            3,
            CandidateProofStatus.OBSERVED,
            Instant.now(),
            ""
        );
        String id = selector.selectMainPath(List.of(longLow, shortHigh), new SearchGraphDto(List.of(), List.of(), List.of(), null), MainPathCriteria.defaults())
            .orElseThrow().id();
        assertEquals("short", id);
    }

    @Test
    void penalizesNormalizationOnlyPath() {
        DiscoveredTransformation normalizeOnly = new DiscoveredTransformation(
            "normalize",
            "a+b",
            "b+a",
            List.of(new TransformationStep(0, "a+b", "b+a", "commutativity", RewriteKind.NORMALIZE, 5, 5, true, "")),
            new ExpressionScore(5, 0, 0, 0, 0),
            new ExpressionScore(5, 0, 0, 0, 0),
            0,
            CandidateProofStatus.OBSERVED,
            Instant.now(),
            ""
        );
        DiscoveredTransformation useful = new DiscoveredTransformation(
            "useful",
            "a+0",
            "a",
            List.of(new TransformationStep(0, "a+0", "a", "remove_zero", RewriteKind.SIMPLIFY, 5, 3, true, "")),
            new ExpressionScore(5, 0, 0, 0, 0),
            new ExpressionScore(3, 0, 0, 0, 0),
            2,
            CandidateProofStatus.OBSERVED,
            Instant.now(),
            ""
        );
        String id = selector.selectMainPath(List.of(normalizeOnly, useful), new SearchGraphDto(List.of(), List.of(), List.of(), null), MainPathCriteria.defaults())
            .orElseThrow().id();
        assertEquals("useful", id);
    }
}
