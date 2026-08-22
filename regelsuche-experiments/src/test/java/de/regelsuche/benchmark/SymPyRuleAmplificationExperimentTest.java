package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.search.reachability.PatternTargetedLocalBridgeSearch;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymPyRuleAmplificationExperimentTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";

    @Test
    void retainsTwoAdditionalApplicationsAndOneNearMiss() {
        SymPyRuleAmplificationExperiment.Report report =
            new SymPyRuleAmplificationExperiment().run(REVISION);

        assertTrue(report.qualified());
        assertEquals(5, report.rows().size());
        assertEquals(2, report.directApplications());
        assertEquals(2, report.preparedApplications());
        assertEquals(2, report.amplificationGain());
        assertEquals(1, report.conclusiveNearMisses());
        assertTrue(report.rows().stream()
            .filter(row -> row.bridgeStatus()
                == PatternTargetedLocalBridgeSearch.Status.PREPARED)
            .allMatch(row -> !row.directApplicable()
                && row.independentlyVerified()
                && "1".equals(row.resultExpression())
                && row.preparationDepth() > 0));
        assertTrue(report.rows().stream()
            .filter(row -> row.caseId().equals(
                "different-argument-near-miss"))
            .allMatch(row -> row.reachedLimits().isEmpty()
                && row.resultExpression().isEmpty()));
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
        assertTrue(Files.readString(directory.resolve(
            "sympy-rule-amplification.json"))
            .contains("\"qualified\": true"));
        assertTrue(first.toMarkdown().contains(
            "not a general SymPy performance"));
    }
}
