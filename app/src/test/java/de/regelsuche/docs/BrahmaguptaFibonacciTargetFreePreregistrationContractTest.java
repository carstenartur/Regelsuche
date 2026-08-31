package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Binds the executable target-free study to its pre-execution protocol. */
class BrahmaguptaFibonacciTargetFreePreregistrationContractTest {
    private static final String RESOURCE =
        "/de/regelsuche/docs/"
            + "brahmagupta-fibonacci-target-free-single-rule-"
            + "preregistration-v1.json";

    @Test
    void preregistrationFreezesTheExecutableSearchProtocol()
            throws IOException {
        String document = readResource();

        for (String required : List.of(
            "\"status\": \"PREREGISTERED_NOT_EXECUTED\"",
            "\"sourceExpression\": \"(a^2 + b^2) * (c^2 + d^2)\"",
            "\"frozenLearnedRuleCount\": 1",
            "\"targetAttached\": false",
            "\"objective\": \"PROOF_FRIENDLY\"",
            "\"strategy\": \"BEST_FIRST\"",
            "\"maxDepth\": 11",
            "\"maxVisitedExpressions\": 60000",
            "\"maxExpandingSteps\": 24",
            "\"maxCandidatesPerState\": 192",
            "\"beamWidth\": 8192",
            "\"engineCandidateLimit\": 192",
            "\"pairCandidateLimit\": 24",
            "\"subtreeCandidateLimit\": 16",
            "\"structuralOccurrenceLimit\": 32",
            "\"structuralCandidateLimit\": 128",
            "\"environmentGate\": \"REGELSUCHE_RUN_BRAHMAGUPTA_TARGET_FREE_STUDY\"",
            "\"normalBuildExecutesHeavyStudy\": false",
            "REGELSUCHE_RUN_BRAHMAGUPTA_TARGET_FREE_STUDY=true ./gradlew :app:test --tests de.regelsuche.docs.BrahmaguptaFibonacciTargetFreeSingleRuleIntegrationTest",
            "REGELSUCHE_RUN_BRAHMAGUPTA_TARGET_FREE_STUDY=true mvn --batch-mode --no-transfer-progress -pl app -am -Dtest=de.regelsuche.docs.BrahmaguptaFibonacciTargetFreeSingleRuleIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test",
            "\"schema\": \"regelsuche.target-free-historical-search-comparison/v1\"",
            "\"fileName\": \"target-free-historical-search-comparison.json\"",
            "\"comparisonContainsBothInformationRegimes\": true",
            "\"freezesBeforePostHocCorrespondence\": true",
            "\"retainsEveryExploredState\": true",
            "\"retainsCompletePathRuleAssumptionAndMetricEvidence\": true",
            "\"contentAddressed\": true",
            "\"atomicWriteAndReadbackVerified\": true",
            "\"evaluatedOnlyFromVerifiedFrozenArtifact\": true",
            "\"requiresExactlyTwoTopLevelAddends\": true",
            "\"requiresBothAddendsToBeExplicitSquares\": true",
            "\"requiresWholeExpressionExactEquivalence\": true",
            "\"requiresDynamicFrozenRuleApplications\": 2",
            "\"requiresSquareBaseSignSymmetryApplications\": 1",
            "\"expectedDepth\": 11",
            "\"bothRunsMustReportUntargetedStatus\": true")) {
            assertTrue(document.contains(required),
                () -> "missing preregistered protocol field: " + required);
        }

        assertTrue(document.contains("ast_distribute_left_add"));
        assertTrue(document.contains("ast_distribute_right_add"));
        assertTrue(document.contains("ast_canonical_normalize"));
        assertTrue(document.contains("expose_exact_monomial_square"));
        assertTrue(document.contains(
            "additive_pair(frozen_completion_rule)"));
        assertTrue(document.contains(
            "subtree(square_base_sign_symmetry)"));
        assertTrue(document.contains("(a*c - b*d)^2"));
        assertTrue(document.contains("(a*d + b*c)^2"));
        assertTrue(document.contains("(a*c + b*d)^2"));
        assertTrue(document.contains("(a*d - b*c)^2"));
    }

    private String readResource() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(stream, RESOURCE);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
