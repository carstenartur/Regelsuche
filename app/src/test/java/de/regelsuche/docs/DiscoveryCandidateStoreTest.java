package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryCandidateStoreTest {

    @Test
    void storeAggregatesAlphaEquivalentSupportExamples() {
        DiscoveryCandidateStore store = new DiscoveryCandidateStore();
        PromotionRecord first = record(
            "tpr-sin-x",
            "discovery-campaign-2",
            "trig-power-reduction",
            PromotionStage.PROMOTED,
            "1 - sin(x)^2",
            "cos(x)^2",
            "trig_power_reduction",
            "sympy-trig-basic",
            "AGREE",
            "DEGRADED",
            List.of("trig_power_reduction"),
            false,
            false
        );
        PromotionRecord alphaEquivalent = record(
            "tpr-sin-y",
            "discovery-campaign-9",
            "trig-power-reduction",
            PromotionStage.PROMOTED,
            "1 - sin(y)^2",
            "cos(y)^2",
            "trig_power_reduction",
            "sympy-trig-basic",
            "AGREE",
            "DEGRADED",
            List.of("trig_power_reduction"),
            false,
            false
        );

        DiscoveryCandidateStore.CandidateStoreReport report = store.build(List.of(first, alphaEquivalent));

        assertEquals(1, report.candidates().size(), "alpha-equivalent support must aggregate into one entry");
        DiscoveryCandidateStore.CandidateEntry entry = report.candidates().getFirst();
        assertEquals("tpr-sin-x", entry.candidateId());
        assertEquals(2, entry.supportCount());
        assertEquals(1, report.metrics().mergedSupportRecords());
        assertTrue(entry.concreteExamples().stream()
            .anyMatch(example -> example.noveltyStatus() == NoveltyStatus.ALPHA_EQUIVALENT
                && "tpr-sin-x".equals(example.matchedCandidateId())));
        assertTrue(entry.sourceCampaigns().contains("discovery-campaign-2"));
        assertTrue(entry.sourceCampaigns().contains("discovery-campaign-9"));
    }

    @Test
    void storeKeepsVariantsSeparateButLinksThem() {
        DiscoveryCandidateStore store = new DiscoveryCandidateStore();
        PromotionRecord first = record(
            "qf-small",
            "discovery-campaign-8",
            "quadratic-factorization",
            PromotionStage.VALIDATED,
            "x^2 + 3*x + 2",
            "(x + 1) * (x + 2)",
            "quadratic_factorization",
            "sympy-polynomial-basic",
            "AGREE",
            "DEGRADED",
            List.of("quadratic_factorization"),
            false,
            false
        );
        PromotionRecord variant = record(
            "qf-negative",
            "discovery-campaign-8",
            "quadratic-factorization",
            PromotionStage.VALIDATED,
            "y^2 - y - 6",
            "(y - 3) * (y + 2)",
            "quadratic_factorization",
            "sympy-polynomial-basic",
            "AGREE",
            "DEGRADED",
            List.of("quadratic_factorization"),
            false,
            false
        );

        DiscoveryCandidateStore.CandidateStoreReport report = store.build(List.of(first, variant));

        assertEquals(2, report.candidates().size(), "variants are related but not merged");
        DiscoveryCandidateStore.CandidateEntry variantEntry = report.candidates().stream()
            .filter(entry -> "qf-negative".equals(entry.candidateId()))
            .findFirst()
            .orElseThrow();
        assertEquals(NoveltyStatus.VARIANT, variantEntry.noveltyStatus());
        assertTrue(variantEntry.relatedCandidateIds().contains("qf-small"));
    }

    @Test
    void storeMarksRejectedLifecycleForUnsafePublicEvidence() {
        DiscoveryCandidateStore store = new DiscoveryCandidateStore();
        PromotionRecord rejected = record(
            "bad-oracle",
            "discovery-campaign-test",
            "counterexample",
            PromotionStage.CANDIDATE,
            "(a + b) / b",
            "a + 1",
            "rational_normalization",
            "rational-basic",
            "DISAGREE",
            "DEGRADED",
            List.of("rational_normalization"),
            false,
            false
        );
        PromotionRecord fallback = record(
            "fallback",
            "discovery-campaign-test",
            "factorization",
            PromotionStage.VALIDATED,
            "x^2 - 1",
            "(x - 1) * (x + 1)",
            "difference_of_squares",
            "sympy-polynomial-basic",
            "AGREE",
            "DEGRADED",
            List.of("fallback_rule"),
            false,
            true
        );

        DiscoveryCandidateStore.CandidateStoreReport report = store.build(List.of(rejected, fallback));

        for (DiscoveryCandidateStore.CandidateEntry entry : report.candidates()) {
            assertEquals(DiscoveryCandidateStore.CandidateLifecycleStatus.REJECTED, entry.lifecycleStatus());
            assertFalse(entry.rejectionReason().isBlank());
        }
    }

    @Test
    void storeWritesJsonAndMarkdownReports(@TempDir Path tempDir) throws Exception {
        DiscoveryCandidateStore store = new DiscoveryCandidateStore();
        PromotionRecord record = record(
            "complete-square-family",
            "discovery-campaign-1",
            "polynomial",
            PromotionStage.PROMOTED,
            "x^2 + 10*x + 21",
            "(x + 3) * (x + 7)",
            "complete_square_bridge",
            "sympy-polynomial-basic",
            "AGREE",
            "DEGRADED",
            List.of("complete_square_bridge", "ast_square_difference_factor"),
            false,
            false
        );

        DiscoveryCandidateStore.CandidateStoreReport report = store.write(tempDir, List.of(record));

        assertEquals(1, report.candidates().size());
        assertTrue(Files.exists(tempDir.resolve("discovery-candidate-store.json")));
        assertTrue(Files.exists(tempDir.resolve("discovery-candidate-store.md")));
        String markdown = Files.readString(tempDir.resolve("discovery-candidate-store.md"), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("| Candidate | Lifecycle | Promotion | Novelty |"));
        assertTrue(markdown.contains("complete-square-family"));
        assertTrue(markdown.contains("## Support examples"));
    }

    private PromotionRecord record(
        String id,
        String campaign,
        String family,
        PromotionStage stage,
        String input,
        String target,
        String operator,
        String pack,
        String oracleStatus,
        String ablationStatus,
        List<String> rulePath,
        boolean curatedPath,
        boolean fallback
    ) {
        return new PromotionRecord(
            id,
            campaign,
            "2026-01-01",
            family,
            stage,
            input,
            target,
            oracleStatus,
            "oracle evidence",
            ablationStatus,
            operator,
            pack,
            List.of(),
            "rationale",
            rulePath,
            stage.atLeast(PromotionStage.PROMOTED),
            fallback ? List.of("fallback=true") : List.of(),
            true,
            curatedPath,
            fallback,
            rulePath.size() >= 2,
            "",
            List.of(),
            false,
            ""
        );
    }
}
