package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryPromotionPipelineRunnerTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void pipelineWritesClosedLoopPromotionArtifacts(@TempDir Path tempDir) throws Exception {
        DiscoveryPromotionPipelineRunner runner = new DiscoveryPromotionPipelineRunner();

        DiscoveryPromotionPipelineRunner.PipelineReport report = runner.writeReport(tempDir);

        assertTrue(Files.exists(tempDir.resolve("promotion-records.json")));
        assertTrue(Files.exists(tempDir.resolve("promotion-registry.json")));
        assertTrue(Files.exists(tempDir.resolve("promotion-history.md")));
        assertTrue(Files.exists(tempDir.resolve("campaign-metrics.json")));
        assertTrue(Files.exists(tempDir.resolve("discovery-backlog").resolve("blocked-candidates.md")));
        assertTrue(Files.exists(tempDir.resolve("discovery-backlog").resolve("operator-opportunities.md")));
        assertTrue(Files.exists(tempDir.resolve("discovery-backlog").resolve("macro-opportunities.md")));
        assertTrue(Files.exists(tempDir.resolve("promotion-dashboard.json")));
        assertTrue(Files.exists(tempDir.resolve("promotion-dashboard.md")));
        assertTrue(Files.exists(tempDir.resolve("gallery-2.0.md")));
        assertTrue(Files.exists(tempDir.resolve("discovery-details").resolve("README.md")));
        assertTrue(Files.exists(tempDir.resolve("discovery-campaign-4").resolve("discovery-campaign-4.json")));
        assertTrue(Files.exists(tempDir.resolve("discovery-campaign-4").resolve("macro-reuse-report.md")));

        assertTrue(report.promotionRecords().stream()
            .anyMatch(record -> record.stage() == PromotionStage.REUSED));
        assertTrue(report.campaignFour().improvedCandidates() > 0);

        Set<String> candidateIds = report.registry().records().stream()
            .map(PromotionRecord::candidateId)
            .collect(Collectors.toSet());
        assertEquals(candidateIds.size(), report.registry().records().size());

        String dashboard = Files.readString(tempDir.resolve("promotion-dashboard.md"), StandardCharsets.UTF_8);
        assertTrue(dashboard.contains("Top promoted candidates"));
        assertTrue(dashboard.contains("Unresolved blockers"));

        String detailsIndex = Files.readString(tempDir.resolve("discovery-details").resolve("README.md"), StandardCharsets.UTF_8);
        assertTrue(detailsIndex.contains(".md)"));

        String gallery = Files.readString(tempDir.resolve("gallery-2.0.md"), StandardCharsets.UTF_8);
        List<String> galleryCandidates = gallery.lines()
            .filter(line -> line.startsWith("| ") && !line.contains("Candidate | Stage"))
            .filter(line -> !line.contains("---"))
            .map(line -> line.split("\\|")[1].trim())
            .toList();
        for (String candidateId : galleryCandidates) {
            assertTrue(report.promotionRecords().stream()
                .filter(entry -> entry.candidateId().equals(candidateId))
                .anyMatch(PromotionRecord::galleryEligible), candidateId);
        }

        Map<String, Object> dashboardJson = JSON.readValue(
            Files.readString(tempDir.resolve("promotion-dashboard.json"), StandardCharsets.UTF_8),
            new TypeReference<LinkedHashMap<String, Object>>() { }
        );
        long observed = report.promotionRecords().size();
        long candidate = report.promotionRecords().stream()
            .filter(record -> record.stage().atLeast(PromotionStage.CANDIDATE))
            .count();
        long validated = report.promotionRecords().stream()
            .filter(record -> record.stage().atLeast(PromotionStage.VALIDATED))
            .count();
        long promoted = report.promotionRecords().stream()
            .filter(record -> record.stage().atLeast(PromotionStage.PROMOTED))
            .count();
        long reused = report.promotionRecords().stream()
            .filter(record -> record.stage().atLeast(PromotionStage.REUSED))
            .count();
        assertEquals(observed, ((Number) dashboardJson.get("observed")).longValue());
        assertEquals(candidate, ((Number) dashboardJson.get("candidate")).longValue());
        assertEquals(validated, ((Number) dashboardJson.get("validated")).longValue());
        assertEquals(promoted, ((Number) dashboardJson.get("promoted")).longValue());
        assertEquals(reused, ((Number) dashboardJson.get("reused")).longValue());
    }

    @Test
    void promotionRegistryIsDeterministicAndRerunsDoNotCreateDuplicates(@TempDir Path tempDir) throws Exception {
        DiscoveryPromotionPipelineRunner runner = new DiscoveryPromotionPipelineRunner();
        Path first = tempDir.resolve("first");
        Path second = tempDir.resolve("second");

        runner.writeReport(first);
        runner.writeReport(second);
        runner.writeReport(first);

        String firstRegistry = Files.readString(first.resolve("promotion-registry.json"), StandardCharsets.UTF_8);
        String secondRegistry = Files.readString(second.resolve("promotion-registry.json"), StandardCharsets.UTF_8);
        String firstHistory = Files.readString(first.resolve("promotion-history.md"), StandardCharsets.UTF_8);

        assertEquals(firstRegistry, secondRegistry);
        assertTrue(firstHistory.contains("complete-square-family"));
        assertTrue(firstRegistry.indexOf("\"candidateId\" : \"complete-square-family\"")
            == firstRegistry.lastIndexOf("\"candidateId\" : \"complete-square-family\""));
    }

    @Test
    void galleryExcludesSyntheticNonEligibleRecords() {
        DiscoveryPromotionPipelineRunner runner = new DiscoveryPromotionPipelineRunner();
        PromotionRecord eligible = new PromotionRecord(
            "eligible",
            "campaign",
            "2026-01-01",
            "substitution",
            PromotionStage.REUSED,
            "x + x",
            "2 * x",
            "AGREE",
            "ok",
            "DEGRADED",
            "operator",
            "pack",
            List.of("substitution.placeholder.A=x", "substitution.occurrences.A=2", "substitution.substituted=A + A"),
            "rationale",
            List.of("substitution_introduction"),
            true,
            List.of(),
            true,
            false,
            false,
            true,
            "macro.id",
            List.of("macro.id"),
            true,
            "discovery-campaign-4"
        );
        PromotionRecord blocked = new PromotionRecord(
            "blocked",
            "campaign",
            "2026-01-01",
            "substitution",
            PromotionStage.VALIDATED,
            "x + y",
            "x + y",
            "AGREE",
            "ok",
            "DEGRADED",
            "operator",
            "pack",
            List.of(),
            "rationale",
            List.of("fallback_rule"),
            false,
            List.of("fallback=true", "curated-path=true"),
            true,
            true,
            true,
            true,
            "",
            List.of(),
            false,
            ""
        );

        String gallery = runner.renderGallery(List.of(eligible, blocked));

        assertTrue(gallery.contains("## Selection policy"));
        assertTrue(gallery.contains("fallbackUsed=false"));
        assertTrue(gallery.contains("| eligible |"));
        assertFalse(gallery.contains("| blocked |"));
    }

    @Test
    void detailReportPrioritizesEvidenceMappingsOverParenthesisHeuristics() {
        DiscoveryPromotionPipelineRunner runner = new DiscoveryPromotionPipelineRunner();
        PromotionRecord record = new PromotionRecord(
            "evidence-priority",
            "campaign",
            "2026-01-01",
            "substitution",
            PromotionStage.PROMOTED,
            "(x + 1) * z + (x + 1)",
            "A * z + A",
            "AGREE",
            "oracle",
            "DEGRADED",
            "substitution_introduction",
            "sympy-polynomial-basic",
            List.of(
                "substitution.placeholder.A=y + 2",
                "substitution.occurrences.A=2",
                "substitution.substituted=A * z + A",
                "substitution.expanded.A=true"
            ),
            "rationale",
            List.of("substitution_introduction", "complete_square_bridge"),
            true,
            List.of(),
            true,
            false,
            false,
            true,
            "",
            List.of(),
            false,
            ""
        );

        String details = runner.renderDetailReport(record);

        assertTrue(details.contains("Abstracted subexpression: A -> y + 2 (occurrences=2)"));
        assertTrue(details.contains("Placeholder mappings: A -> y + 2 (occurrences=2)"));
        assertTrue(details.contains("Substituted expression: A * z + A"));
        assertFalse(details.contains("Abstracted subexpression: (x + 1)"));
    }

    @Test
    void detailReportsUseUniqueSlugWhenCandidateIdsCollide(@TempDir Path tempDir) throws Exception {
        DiscoveryPromotionPipelineRunner runner = new DiscoveryPromotionPipelineRunner();
        List<PromotionRecord> records = List.of(
            promotedRecord("a+b", "A + A"),
            promotedRecord("a b", "B + B")
        );
        Path detailsDir = tempDir.resolve("discovery-details");

        invokeWriteDiscoveryDetails(runner, detailsDir, records);

        assertTrue(Files.exists(detailsDir.resolve("a-b.md")));
        assertTrue(Files.exists(detailsDir.resolve("a-b-2.md")));
        try (Stream<Path> files = Files.list(detailsDir)) {
            assertEquals(3L, files.count());
        }
        String index = Files.readString(detailsDir.resolve("README.md"), StandardCharsets.UTF_8);
        assertTrue(index.contains("[a+b]("));
        assertTrue(index.contains("[a b]("));
        assertTrue(index.contains("(a-b.md)"));
        assertTrue(index.contains("(a-b-2.md)"));
    }

    @Test
    void tokenSafeExpansionReplacesOnlyWholePlaceholderTokens() {
        DiscoveryPromotionPipelineRunner runner = new DiscoveryPromotionPipelineRunner();
        PromotionRecord record = new PromotionRecord(
            "token-safe",
            "campaign",
            "2026-01-01",
            "substitution",
            PromotionStage.PROMOTED,
            "ABC + A1 + A + B",
            "A + B",
            "AGREE",
            "ok",
            "DEGRADED",
            "substitution_introduction",
            "sympy-polynomial-basic",
            List.of(
                "substitution.placeholder.A=x",
                "substitution.placeholder.B=y",
                "substitution.substituted=ABC + A1 + A + B",
                "substitution.expanded.A=true",
                "substitution.expanded.B=true"
            ),
            "rationale",
            List.of("substitution_introduction"),
            true,
            List.of(),
            true,
            false,
            false,
            true,
            "",
            List.of(),
            false,
            ""
        );

        String detail = runner.renderDetailReport(record);

        assertTrue(detail.contains("Expanded expression: ABC + A1 + (x) + (y)"));
        assertFalse(detail.contains("Expanded expression: (x)BC"));
        assertFalse(detail.contains("Expanded expression: (x)1"));
    }

    @Test
    void expandsOnlyPlaceholdersMarkedAsExpanded() {
        DiscoveryPromotionPipelineRunner runner = new DiscoveryPromotionPipelineRunner();
        PromotionRecord record = new PromotionRecord(
            "partial-expansion",
            "campaign",
            "2026-01-01",
            "substitution",
            PromotionStage.PROMOTED,
            "A + B + C",
            "A + B + C",
            "AGREE",
            "ok",
            "DEGRADED",
            "substitution_introduction",
            "sympy-polynomial-basic",
            List.of(
                "substitution.placeholder.A=x",
                "substitution.placeholder.B=y",
                "substitution.placeholder.C=z",
                "substitution.substituted=A + B + C",
                "substitution.expanded.A=true",
                "substitution.expanded.C=true"
            ),
            "rationale",
            List.of("substitution_introduction"),
            true,
            List.of(),
            true,
            false,
            false,
            true,
            "",
            List.of(),
            false,
            ""
        );

        String detail = runner.renderDetailReport(record);

        assertTrue(detail.contains("Expanded expression: (x) + B + (z)"));
        assertFalse(detail.contains("Expanded expression: (x) + (y) + (z)"));
    }

    @Test
    void evidenceParsingKeepsEqualsAndTracksInvalidOccurrences() {
        DiscoveryPromotionPipelineRunner runner = new DiscoveryPromotionPipelineRunner();
        PromotionRecord record = new PromotionRecord(
            "parsing",
            "campaign",
            "2026-01-01",
            "substitution",
            PromotionStage.PROMOTED,
            "A + A",
            "A + A",
            "AGREE",
            "ok",
            "DEGRADED",
            "substitution_introduction",
            "sympy-polynomial-basic",
            List.of(
                "substitution.placeholder.A=x=y",
                "substitution.placeholder.B=",
                "substitution.occurrences.A=not-a-number",
                "substitution.substituted=A + A",
                "substitution.invalidWithoutEquals"
            ),
            "rationale",
            List.of("substitution_introduction"),
            false,
            List.of("oracle=UNAVAILABLE"),
            true,
            false,
            false,
            true,
            "",
            List.of(),
            false,
            ""
        );

        String detail = runner.renderDetailReport(record);

        assertTrue(detail.contains("A -> x=y"));
        assertTrue(detail.contains("B -> "));
        assertTrue(detail.contains("ignored.invalid.occurrences=A=not-a-number"));
        assertTrue(detail.contains("substitution.invalidWithoutEquals"));
    }

    @Test
    void markdownOutputEscapesPipesNormalizesNewlinesAndHandlesBackticks() {
        DiscoveryPromotionPipelineRunner runner = new DiscoveryPromotionPipelineRunner();
        PromotionRecord record = new PromotionRecord(
            "md",
            "campaign",
            "2026-01-01",
            "family",
            PromotionStage.REUSED,
            "a | b\nc`d",
            "res | ult`",
            "AGREE",
            "ok",
            "DEGRADED",
            "operator",
            "pack",
            List.of(),
            "rationale",
            List.of("step1"),
            true,
            List.of(),
            true,
            false,
            false,
            true,
            "macro",
            List.of("m1"),
            true,
            "campaign-4"
        );

        String gallery = runner.renderGallery(List.of(record));

        assertTrue(gallery.contains("a \\| b c`d"));
        assertTrue(gallery.contains("res \\| ult`"));
        assertTrue(gallery.contains("``a \\| b c`d``"));
        assertFalse(gallery.contains("a | b\nc`d"));
    }

    private PromotionRecord promotedRecord(String candidateId, String discoveredStructure) {
        return new PromotionRecord(
            candidateId,
            "campaign",
            "2026-01-01",
            "substitution",
            PromotionStage.PROMOTED,
            discoveredStructure,
            discoveredStructure,
            "AGREE",
            "ok",
            "DEGRADED",
            "substitution_introduction",
            "sympy-polynomial-basic",
            List.of("substitution.placeholder.A=x"),
            "rationale",
            List.of("substitution_introduction"),
            true,
            List.of(),
            true,
            false,
            false,
            true,
            "",
            List.of(),
            false,
            ""
        );
    }

    private void invokeWriteDiscoveryDetails(DiscoveryPromotionPipelineRunner runner, Path detailsDir, List<PromotionRecord> records)
        throws Exception {
        Method method = DiscoveryPromotionPipelineRunner.class.getDeclaredMethod(
            "writeDiscoveryDetailReports",
            Path.class,
            List.class
        );
        method.setAccessible(true);
        method.invoke(runner, detailsDir, records);
    }

}
