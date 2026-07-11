package de.regelsuche.docs;

import de.regelsuche.proof.ProofPolicy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatternHypothesisMinerTest {

    @Test
    void minesGeneralizedHypothesisFromMultipleConcreteExamples() {
        DiscoveryCandidateStore.CandidateStoreReport store = store(
            record("square-1", "(x + 1)^2", "x^2 + 2*x + 1"),
            record("square-2", "(x + 2)^2", "x^2 + 4*x + 4"),
            record("square-3", "(x + 3)^2", "x^2 + 6*x + 9")
        );

        PatternHypothesisMiner.PatternHypothesisReport report = new PatternHypothesisMiner().mine(store);

        assertEquals(1, report.hypotheses().size());
        PatternHypothesisMiner.GeneralizedHypothesis hypothesis = report.hypotheses().getFirst();
        assertEquals(3, hypothesis.supportCount());
        assertEquals("binomial-expansion", hypothesis.family());
        assertEquals("square_expansion", hypothesis.operatorId());
        assertTrue(hypothesis.supportingExampleIds().contains("square-1"));
        assertTrue(hypothesis.supportingExampleIds().contains("square-2"));
        assertTrue(hypothesis.supportingExampleIds().contains("square-3"));
        assertFalse(hypothesis.leftPattern().isBlank());
        assertFalse(hypothesis.rightPattern().isBlank());
        assertFalse(hypothesis.parameterRelations().isEmpty(), "hypothesis must explain the parameter relation");
    }

    @Test
    void rejectsClustersWhenExamplesCannotGeneralize() {
        DiscoveryCandidateStore.CandidateStoreReport store = store(
            record("identity-a", "x + 1", "1 + x"),
            record("identity-b", "x + 1", "1 + x")
        );

        PatternHypothesisMiner.PatternHypothesisReport report = new PatternHypothesisMiner().mine(store);

        assertTrue(report.hypotheses().isEmpty(), "identical examples should not claim a new generalized pattern");
        assertFalse(report.rejectedClusters().isEmpty(), "rejected cluster should explain why no hypothesis was produced");
        assertTrue(report.rejectedClusters().stream()
            .anyMatch(cluster -> cluster.reason().contains("generalizer returned no compatible pattern")));
    }

    @Test
    void writesOperatorSuggestionsWithSupportExamples(@TempDir Path tempDir) throws Exception {
        DiscoveryCandidateStore.CandidateStoreReport store = store(
            record("square-1", "(x + 1)^2", "x^2 + 2*x + 1"),
            record("square-2", "(x + 2)^2", "x^2 + 4*x + 4"),
            record("square-3", "(x + 3)^2", "x^2 + 6*x + 9")
        );

        PatternHypothesisMiner.PatternHypothesisReport report = new PatternHypothesisMiner().write(tempDir, store);

        assertEquals(1, report.hypotheses().size());
        assertTrue(Files.exists(tempDir.resolve("pattern-hypotheses.json")));
        assertTrue(Files.exists(tempDir.resolve("pattern-hypotheses.md")));
        assertTrue(Files.exists(tempDir.resolve("operator-suggestions.md")));
        String suggestions = Files.readString(tempDir.resolve("operator-suggestions.md"), StandardCharsets.UTF_8);
        assertTrue(suggestions.contains("support=3"));
        assertTrue(suggestions.contains("square-1"));
        assertTrue(suggestions.contains("square-2"));
        assertTrue(suggestions.contains("square-3"));
        String hypotheses = Files.readString(tempDir.resolve("pattern-hypotheses.md"), StandardCharsets.UTF_8);
        assertTrue(hypotheses.contains("| Hypothesis | Family | Operator | Support | Left pattern | Right pattern | Examples | Candidates |"));
        assertTrue(hypotheses.contains("## Expression placeholder values"));
    }

    @Test
    void rejectsClusterWhenOnlyDuplicateExampleIdsContributeSupport() {
        DiscoveryCandidateStore.CandidateStoreReport store = store(
            record("square-1", "(x + 1)^2", "x^2 + 2*x + 1"),
            record("square-1", "(x + 2)^2", "x^2 + 4*x + 4")
        );

        PatternHypothesisMiner.PatternHypothesisReport report = new PatternHypothesisMiner().mine(store);

        assertTrue(report.hypotheses().isEmpty());
        assertTrue(report.rejectedClusters().stream()
            .anyMatch(cluster -> cluster.reason().contains("support-count<2")));
    }

    private DiscoveryCandidateStore.CandidateStoreReport store(PromotionRecord... records) {
        return new DiscoveryCandidateStore().build(List.of(records));
    }

    private PromotionRecord record(String id, String input, String target) {
        return new PromotionRecord(
            id,
            "discovery-campaign-test",
            "2026-01-01",
            "binomial-expansion",
            PromotionStage.PROMOTED,
            input,
            target,
            "AGREE",
            "oracle evidence",
            "DEGRADED",
            "square_expansion",
            "sympy-polynomial-basic",
            List.of(),
            "support example",
            List.of("square_expansion"),
            true,
            List.of(),
            true,
            false,
            false,
            true,
            "",
            List.of(),
            false,
            "",
            AblationEvidence.statusOnly("DEGRADED"),
            ProofPolicy.PROOF_OPTIONAL,
            ""
        );
    }
}
