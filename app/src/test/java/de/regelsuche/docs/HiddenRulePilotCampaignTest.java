package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.docs.HiddenRulePilotEvaluator.HiddenReference;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class HiddenRulePilotCampaignTest {
    @Test
    void emitsStableTwentyCaseEvidenceWithAggregateRatesAndFailureTaxonomy() {
        HiddenRulePilotCampaign campaign = new HiddenRulePilotCampaign();
        HiddenRulePilotCampaign.PilotReport report = HiddenRulePilotTestEvidence.report();
        String json = report.toJson();
        String runtimeJson = report.runtimeJson();
        Path directory = Path.of("build", "reports", "hidden-rule-pilot");
        Path output = directory.resolve("report.json");
        Path runtimeOutput = directory.resolve("runtime.json");
        campaign.write(output, report);
        campaign.writeRuntime(runtimeOutput, report);

        assertEquals(HiddenRulePilotCampaign.SCHEMA, report.schema());
        assertEquals(20, report.cases().size());
        assertTrue(report.familyCount() >= 4);
        assertEquals(40, report.negativeHoldouts());
        assertTrue(report.generatedValidationExamples() > 0);
        assertTrue(report.counterexampleSearches() > 0);
        assertTrue(report.frozenCandidates() >= 1);
        assertTrue(report.rediscoveredCases() >= 1);
        assertTrue(report.acceptedCases() >= 1);
        assertTrue(report.rediscoveredCases() <= report.cases().size());
        assertTrue(report.falsePositiveHoldouts() <= report.negativeHoldouts());
        assertTrue(report.cases().stream().allMatch(caseReport -> caseReport.split().passed()),
            report.cases().stream()
                .filter(caseReport -> !caseReport.split().passed())
                .map(caseReport -> caseReport.opaqueCaseId() + ":" + caseReport.split().collisions())
                .toList().toString());
        assertEquals(json, report.toJson());
        assertTrue(Files.isRegularFile(output));
        assertTrue(Files.isRegularFile(runtimeOutput));
        assertTrue(json.contains("\"schema\":\"regelsuche.hidden-rule-benchmark/v2\""));
        assertTrue(json.contains("\"rediscoveryRatePermille\""));
        assertTrue(json.contains("\"falsePositiveRatePermille\""));
        assertTrue(json.contains("\"generatedValidationExamples\""));
        assertTrue(json.contains("\"counterexampleSearches\""));
        assertTrue(json.contains("\"failureTaxonomy\""));
        assertTrue(json.indexOf("case-001") < json.indexOf("case-020"));
        assertFalse(json.contains("hidden_"));
        assertFalse(json.contains("elapsedNanos"));
        assertTrue(runtimeJson.contains(
            "\"schema\":\"regelsuche.hidden-rule-benchmark-runtime/v1\""));
        assertTrue(runtimeJson.contains("\"elapsedNanos\""));
    }

    @Test
    void productionRuntimeContainsNoHiddenManifestIdentifiersOrTemplates() {
        assertEquals(20, HiddenRulePilotCatalog.references().size());
        String runtimeSurface = productionRuntimeSurface();
        for (HiddenReference reference : HiddenRulePilotCatalog.references().values()) {
            List<String> forbidden = new ArrayList<>(reference.forbiddenRuntimeTokens());
            forbidden.add(reference.hiddenRuleId());
            forbidden.add(reference.leftPattern());
            forbidden.add(reference.rightPattern());
            for (String token : forbidden) {
                String compactToken = compact(token);
                if (compactToken.length() <= 1) {
                    continue;
                }
                assertFalse(runtimeSurface.contains(compactToken),
                    () -> "hidden manifest token is reachable from benchmark runtime: "
                        + Integer.toHexString(compactToken.hashCode()));
            }
        }
    }

    private static String productionRuntimeSurface() {
        Path packageRoot = Path.of("src", "main", "java", "de", "regelsuche", "docs");
        if (!Files.isDirectory(packageRoot)) {
            return "";
        }
        StringBuilder surface = new StringBuilder();
        try (var files = Files.list(packageRoot)) {
            files.filter(Files::isRegularFile)
                .filter(file -> file.getFileName().toString().startsWith("HiddenRulePilot"))
                .filter(file -> file.getFileName().toString().endsWith(".java"))
                .sorted()
                .forEach(file -> {
                    try {
                        surface.append(compact(Files.readString(file, StandardCharsets.UTF_8)))
                            .append('\n');
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return surface.toString();
    }

    private static String compact(String value) {
        return value == null
            ? ""
            : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
