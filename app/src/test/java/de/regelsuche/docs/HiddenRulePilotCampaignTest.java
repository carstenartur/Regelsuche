package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.junit.jupiter.api.io.TempDir;

class HiddenRulePilotCampaignTest {
    @Test
    void emitsStableTwentyCaseEvidenceWithAggregateRatesAndFailureTaxonomy() {
        HiddenRulePilotCampaign campaign = new HiddenRulePilotCampaign();
        HiddenRulePilotCampaign.PilotReport report = HiddenRulePilotTestEvidence.report();
        String json = report.toJson();
        String runtimeJson = report.runtimeJson();
        String configuredDirectory = System.getProperty("regelsuche.hiddenRulePilot.outputDirectory");
        boolean maven = Boolean.getBoolean("regelsuche.maven.reactor");
        if (maven) assertNotNull(configuredDirectory, "Maven must declare its own hidden-rule output");
        Path directory = configuredDirectory == null
            ? Path.of("build", "reports", "hidden-rule-pilot") : Path.of(configuredDirectory);
        Path output = directory.resolve("report.json");
        Path runtimeOutput = directory.resolve("runtime.json");
        checkMavenOutput(output);
        checkMavenOutput(runtimeOutput);
        campaign.write(output, report);
        campaign.writeRuntime(runtimeOutput, report);

        int configuredNegativeHoldouts = HiddenRulePilotCatalog.cases().stream()
            .mapToInt(pilotCase -> pilotCase.task().negativeHoldouts().size())
            .sum();
        int auditedNegativeHoldouts = report.cases().stream()
            .mapToInt(caseReport -> caseReport.split().negatives().size())
            .sum();

        assertEquals(HiddenRulePilotCampaign.SCHEMA, report.schema());
        assertEquals(20, report.cases().size());
        assertTrue(report.familyCount() >= 4);
        assertEquals(40, configuredNegativeHoldouts);
        assertEquals(configuredNegativeHoldouts, auditedNegativeHoldouts);
        assertEquals(configuredNegativeHoldouts, report.negativeHoldouts());
        assertEquals(report.negativeHoldouts(),
            report.evaluatedNegativeHoldouts() + report.skippedNegativeHoldouts());
        assertTrue(report.evaluatedNegativeHoldouts() <= report.negativeHoldouts());
        assertTrue(report.generatedValidationExamples() > 0);
        assertTrue(report.counterexampleSearches() > 0);
        assertTrue(report.frozenCandidates() >= 1);
        assertTrue(report.rediscoveredCases() >= 1);
        assertTrue(report.acceptedCases() >= 1);
        assertTrue(report.rediscoveredCases() <= report.cases().size());
        assertTrue(report.falsePositiveHoldouts() <= report.evaluatedNegativeHoldouts());
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
        assertTrue(json.contains("\"evaluatedNegativeHoldouts\""));
        assertTrue(json.contains("\"skippedNegativeHoldouts\""));
        assertTrue(json.contains("\"holdoutsComplete\""));
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

    static void checkMavenOutput(Path member) {
        if (Boolean.getBoolean("regelsuche.maven.reactor")) {
            Path directory = Path.of(System.getProperty("regelsuche.repositoryRoot"))
                .resolve("app/target/reports/hidden-rule-pilot");
            assertEquals(directory.toAbsolutePath().normalize(), member.toAbsolutePath().normalize().getParent(),
                "Maven's real producer must write its own target output, without overwriting Gradle evidence");
            for (Path current = member.toAbsolutePath(); current != null; current = current.getParent()) {
                assertFalse(Files.isSymbolicLink(current), "Maven output must not traverse a symbolic link");
            }
        }
    }

    @Test
    void productionRuntimeContainsNoHiddenManifestIdentifiersOrTemplates() {
        assertEquals(20, HiddenRulePilotCatalog.references().size());
        String runtimeSurface = productionRuntimeSurface(
            Path.of("src", "main", "java", "de", "regelsuche", "docs"));
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

    @Test
    void sourceAuditRejectsAnEmptyOrMissingSourceDirectory(@TempDir Path directory) {
        assertThrows(IllegalStateException.class, () -> productionRuntimeSurface(directory));
        assertThrows(UncheckedIOException.class, () -> productionRuntimeSurface(directory.resolve("absent")));
    }

    private static String productionRuntimeSurface(Path packageRoot) {
        try (var files = Files.list(packageRoot)) {
            List<Path> sources = files.filter(Files::isRegularFile)
                .filter(file -> file.getFileName().toString().startsWith("HiddenRulePilot"))
                .filter(file -> file.getFileName().toString().endsWith(".java"))
                .sorted().toList();
            for (String required : List.of("HiddenRulePilotRunner.java", "HiddenRulePilotRuntimeCatalog.java")) {
                if (!sources.contains(packageRoot.resolve(required))) {
                    throw new IllegalStateException("Missing runtime source for leakage audit: " + required);
                }
            }
            StringBuilder surface = new StringBuilder();
            for (Path source : sources) {
                surface.append(compact(Files.readString(source, StandardCharsets.UTF_8))).append('\n');
            }
            return surface.toString();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String compact(String value) {
        return value == null
            ? ""
            : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
