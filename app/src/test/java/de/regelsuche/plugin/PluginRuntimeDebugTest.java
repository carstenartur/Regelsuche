package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginRuntimeDebugTest {
    @Test
    void debugReportIncludesDisabledConditionAndCycleReasons(@TempDir Path tempDir) throws Exception {
        Path rulesDir = Files.createDirectories(tempDir.resolve("rules"));
        Files.writeString(rulesDir.resolve("debug.regelsuche"), """
            rule disabled_by_profile:
              pattern: A + 0
              replace: A
              tags:
                - advanced

            rule conditional_rule:
              pattern: A * 1
              replace: A
              tags:
                - school
              conditions:
                - domain: integers

            rule cycle_forward:
              pattern: A + 1
              replace: 1 + A
              tags:
                - school

            rule cycle_backward:
              pattern: 1 + A
              replace: A + 1
              tags:
                - school

            profile school:
              enable_tags:
                - school
            """);

        try (PluginRuntime runtime = new PluginRuntime(new PluginRuntimeConfig(
            tempDir.resolve("plugins"),
            rulesDir,
            true,
            Set.of(),
            Set.of("binomial_square_forward"),
            "school"
        ))) {
            PluginAwareAstRewriteTransformationEngine engine = runtime.createTransformationEngine();
            engine.enableDebugMode();
            engine.transform("(a + b)^2");

            RuleDebugReport report = engine.lastDebugReport();

            assertTrue(report.disabledByConfigRejections() > 0);
            assertTrue(report.disabledByProfileRejections() > 0);
            assertTrue(report.conditionFailedRejections() > 0);
            assertTrue(report.cycleRiskRejections() > 0);
            assertTrue(report.attempts().stream()
                .anyMatch(attempt -> attempt.reason() == RuleRejectionReason.DISABLED_BY_CONFIG));
            assertTrue(report.attempts().stream()
                .anyMatch(attempt -> attempt.reason() == RuleRejectionReason.DISABLED_BY_PROFILE));
            assertTrue(report.attempts().stream()
                .anyMatch(attempt -> attempt.reason() == RuleRejectionReason.CONDITION_FAILED));
            assertTrue(report.attempts().stream()
                .anyMatch(attempt -> attempt.reason() == RuleRejectionReason.CYCLE_RISK
                    && attempt.subtree().startsWith("cycle-risk-index=")));
        }
    }
}
