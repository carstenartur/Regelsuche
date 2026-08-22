package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.search.reachability.PatternTargetedLocalBridgeSearch;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymPyRuleAmplificationExperimentTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";

    @Test
    void retainsFourAdditionalApplicationsAcrossThreeFamilies() {
        SymPyRuleAmplificationExperiment.Report report =
            new SymPyRuleAmplificationExperiment().run(REVISION);

        assertTrue(report.qualified());
        assertEquals(3, report.principals().size());
        assertEquals(11, report.rows().size());
        assertEquals(4, report.directApplications());
        assertEquals(4, report.preparedApplications());
        assertEquals(4, report.amplificationGain());
        assertEquals(3, report.amplifiedRuleFamilies());
        assertEquals(3, report.conclusiveNearMisses());
        assertEquals(
            Set.of(
                SymPyRuleAmplificationExperiment.PYTHAGOREAN_RULE_ID,
                SymPyRuleAmplificationExperiment
                    .DIFFERENCE_OF_SQUARES_RULE_ID,
                SymPyRuleAmplificationExperiment.TELESCOPING_RULE_ID),
            report.principals().stream()
                .map(SymPyRuleAmplificationExperiment
                    .PrincipalDescriptor::ruleId)
                .collect(Collectors.toSet()));
        assertTrue(report.principals().stream()
            .allMatch(value -> "low".equals(value.riskLevel())));
    }

    @Test
    void everyPositiveAndNearMissRetainsItsExpectedEvidence() {
        SymPyRuleAmplificationExperiment.Report report =
            new SymPyRuleAmplificationExperiment().run(REVISION);

        assertTrue(report.rows().stream()
            .filter(row -> row.coordinatorStatus()
                == PatternTargetedLocalBridgeSearch.Status.PREPARED)
            .allMatch(row -> row.coordinatorVerified()
                && row.principalReplayVerified()
                && row.preparationDepth() > 0
                && row.assumptions().equals(row.requiredAssumptions())
                && row.primitiveRuleIds().getLast()
                    .equals(row.principalRuleId())
                && row.unexpectedApplicableRuleIds().isEmpty()));
        assertTrue(report.rows().stream()
            .filter(row -> row.coordinatorStatus()
                == PatternTargetedLocalBridgeSearch.Status
                    .NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE)
            .allMatch(row -> row.coordinatorVerified()
                && !row.principalReplayVerified()
                && row.reachedLimits().isEmpty()
                && row.resultExpression().isEmpty()
                && row.primitiveRuleIds().isEmpty()
                && row.unexpectedApplicableRuleIds().isEmpty()));
    }

    @Test
    void jsonEncodingEscapesTabs() {
        String encoded = SymPyRuleAmplificationExperiment.json(
            "left\tright");

        assertEquals("left\\tright", encoded);
        assertFalse(encoded.contains("\t"));
    }

    @Test
    void canonicalReportIsDeterministicAndWritten(@TempDir Path directory)
            throws Exception {
        SymPyRuleAmplificationExperiment experiment =
            new SymPyRuleAmplificationExperiment();
        SymPyRuleAmplificationExperiment.Report first =
            experiment.run(REVISION);
        SymPyRuleAmplificationExperiment.Report second =
            experiment.run(REVISION);

        assertEquals(first, second);
        assertEquals(first.toJson(), second.toJson());
        first.write(directory);
        assertTrue(Files.isRegularFile(directory.resolve(
            "sympy-rule-amplification.json")));
        assertTrue(Files.isRegularFile(directory.resolve(
            "sympy-rule-amplification.md")));
        String json = Files.readString(directory.resolve(
            "sympy-rule-amplification.json"));
        assertTrue(json.contains("\"qualified\": true"));
        assertTrue(json.contains("\"amplifiedRuleFamilies\": 3"));
        assertTrue(first.toMarkdown().contains(
            "three unchanged low-risk imported rules"));
        assertTrue(first.toMarkdown().contains(
            "not a general SymPy performance"));
    }
}
