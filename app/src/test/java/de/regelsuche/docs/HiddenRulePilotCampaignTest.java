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
import java.util.Set;
import org.junit.jupiter.api.Test;

class HiddenRulePilotCampaignTest {
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        ".java", ".json", ".yaml", ".yml", ".properties", ".xml",
        ".md", ".txt", ".csv", ".toml", ".html", ".js", ".css", ".svg");

    @Test
    void emitsStableFiveCaseEvidenceWithoutHiddenIdsOrWallClockTime() {
        HiddenRulePilotCampaign campaign = new HiddenRulePilotCampaign();
        HiddenRulePilotCampaign.PilotReport report = HiddenRulePilotTestEvidence.report();
        String json = report.toJson();
        Path output = Path.of("build", "reports", "hidden-rule-pilot", "report.json");
        campaign.write(output, report);

        assertEquals(HiddenRulePilotCampaign.SCHEMA, report.schema());
        assertEquals(5, report.cases().size());
        assertEquals(3, report.familyCount());
        assertEquals(5, report.frozenCandidates());
        assertEquals(5, report.materialAblations());
        assertEquals(5, report.acceptedCases());
        assertTrue(report.cases().stream()
            .allMatch(caseReport -> caseReport.evaluation().validationPassed()));
        assertTrue(report.cases().stream()
            .allMatch(caseReport -> caseReport.runtime()
                .validationEvidence().generatedValidationExamples() > 0));
        assertTrue(report.cases().stream()
            .allMatch(caseReport -> caseReport.runtime()
                .validationEvidence().failedValidationExamples() == 0));
        assertTrue(report.cases().stream()
            .flatMap(caseReport -> caseReport.runtime()
                .validationEvidence().counterexampleSearches().stream())
            .noneMatch(search -> search.counterexamplePresent()
                || search.status().equals("COUNTEREXAMPLE_FOUND")));
        assertEquals(json, report.toJson());
        assertTrue(Files.isRegularFile(output));
        assertTrue(json.contains("\"schema\":\"regelsuche.hidden-rule-pilot/v1\""));
        assertTrue(json.contains("\"validationPassed\":true"));
        assertTrue(json.contains("\"proofStatus\":\"SYMBOLICALLY_VERIFIED\""));
        assertTrue(json.contains("\"splitPassed\":true"));
        assertTrue(json.contains("\"materialBenefit\":true"));
        assertTrue(json.indexOf("case-001") < json.indexOf("case-005"));
        assertFalse(json.contains("hidden_"));
        assertFalse(json.contains("elapsedNanos"));
    }

    @Test
    void productionRuntimeContainsNoHiddenManifestIdentifiersOrTemplates() {
        String runtimeSurface = productionRuntimeSurface();
        for (HiddenReference reference : HiddenRulePilotCatalog.references().values()) {
            List<String> forbidden = new ArrayList<>(reference.forbiddenRuntimeTokens());
            forbidden.add(reference.hiddenRuleId());
            forbidden.add(reference.leftPattern());
            forbidden.add(reference.rightPattern());
            for (String token : forbidden) {
                String compactToken = compact(token);
                if (compactToken.length() <= 1) {
                    continue; // A lone placeholder carries no hidden structural information.
                }
                assertFalse(runtimeSurface.contains(compactToken),
                    () -> "hidden manifest token is reachable from src/main: "
                        + Integer.toHexString(compactToken.hashCode()));
            }
        }
    }

    private static String productionRuntimeSurface() {
        List<Path> roots = List.of(Path.of("src", "main", "java"), Path.of("src", "main", "resources"));
        StringBuilder surface = new StringBuilder();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                    .filter(HiddenRulePilotCampaignTest::isTextResource)
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
        }
        return surface.toString();
    }

    private static boolean isTextResource(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return TEXT_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static String compact(String value) {
        return value == null
            ? ""
            : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
