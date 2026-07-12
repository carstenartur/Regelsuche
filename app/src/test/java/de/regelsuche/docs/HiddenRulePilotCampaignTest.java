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
    void emitsStableFiveCaseEvidenceWithoutHiddenIdsOrWallClockTime() {
        HiddenRulePilotCampaign campaign = new HiddenRulePilotCampaign();
        HiddenRulePilotCampaign.PilotReport report =
            campaign.run(HiddenRulePilotCatalog.cases());
        String json = report.toJson();
        Path output = Path.of("build", "reports", "hidden-rule-pilot", "report.json");
        campaign.write(output, report);

        assertEquals(HiddenRulePilotCampaign.SCHEMA, report.schema());
        assertEquals(5, report.cases().size());
        assertEquals(3, report.familyCount());
        assertEquals(5, report.frozenCandidates());
        assertEquals(5, report.materialAblations());
        assertEquals(5, report.acceptedCases());
        assertEquals(json, report.toJson());
        assertTrue(Files.isRegularFile(output));
        assertTrue(json.contains("\"schema\":\"regelsuche.hidden-rule-pilot/v1\""));
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

    private static String compact(String value) {
        return value == null
            ? ""
            : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
