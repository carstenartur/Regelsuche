package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.CommonSubexpressionDiscoveryOperator;
import de.regelsuche.transform.CompleteSquareBridgeOperator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryCampaignThreeRunnerTest {
    @Test
    void campaignThreeWritesProgressAndCandidateMiningReports(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignThreeRunner runner = new DiscoveryCampaignThreeRunner();

        DiscoveryCampaignThreeRunner.CampaignReport report = runner.writeReport(tempDir);
        Map<String, DiscoveryCampaignThreeRunner.CaseResult> results = report.results().stream()
            .collect(Collectors.toMap(DiscoveryCampaignThreeRunner.CaseResult::id, Function.identity()));

        assertEquals(7, results.size());
        assertTrue(Files.exists(tempDir.resolve("discovery-campaign-3.json")));
        assertTrue(Files.exists(tempDir.resolve("campaign-progress.md")));
        assertTrue(Files.exists(tempDir.resolve("discovery-candidates.md")));
        assertTrue(Files.exists(tempDir.resolve("discovery-candidates.json")));
        assertTrue(Files.exists(tempDir.resolve("operator-suggestions.md")));
        assertTrue(Files.exists(tempDir.resolve("macro-candidates.md")));
        assertTrue(Files.exists(tempDir.resolve("sympy-qa").resolve("summary.json")));

        DiscoveryCampaignThreeRunner.CaseResult common = results.get("common-subexpression-affine");
        assertTrue(common.success(), common.failureReason());
        assertTrue(
            !common.shortcutSource().isBlank() || common.rulePath().contains(CommonSubexpressionDiscoveryOperator.RULE_ID),
            common.rulePath().toString()
        );
        assertEquals("DEGRADED", common.ablationStatus());

        DiscoveryCampaignThreeRunner.CaseResult substitution = results.get("substitution-hidden-structure-shifted");
        assertTrue(substitution.success(), substitution.failureReason());
        assertTrue(substitution.rulePath().contains(CompleteSquareBridgeOperator.RULE_ID), substitution.rulePath().toString());

        assertEquals(3, report.progress().size());
        assertTrue(report.progress().stream()
            .anyMatch(summary -> summary.campaignId().equals("discovery-campaign-3") && summary.validatedCount() > 0));

        String candidates = Files.readString(tempDir.resolve("discovery-candidates.md"), StandardCharsets.UTF_8);
        String macros = Files.readString(tempDir.resolve("macro-candidates.md"), StandardCharsets.UTF_8);
        assertTrue(candidates.contains("public-evidence") || candidates.contains("promoted"), candidates);
        assertTrue(macros.contains("substitution-hidden-structure-shifted"), macros);
    }
}
