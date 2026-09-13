package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.validation.OracleValidator.OracleValidationStatus;
import de.regelsuche.validation.SymPyOracleValidator;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ReferenceIndependentOracleSessionTest {
    @Test
    void acceptsCrlfReadinessAndConsecutiveResponsesFromARealWorker() {
        try (var oracle = worker("crlf", 5000)) {
            assertEquals(OracleValidationStatus.AGREE,
                assertDoesNotThrow(() -> oracle.validateEquivalence("x + x", "2 * x")).status());
            assertEquals(OracleValidationStatus.DISAGREE,
                assertDoesNotThrow(() -> oracle.validateEquivalence("x", "x + 1")).status());
            assertTrue(oracle.process().isAlive());
        }
    }

    @Test
    void realWorkerRetainsAgreementDisagreementAndUnsupportedEvidence() {
        Process handle;
        try (var oracle = new ReferenceIndependentOracleSession(5000)) {
            assertEquals(OracleValidationStatus.AGREE,
                oracle.validateEquivalence("x + x", "2 * x").status());
            assertEquals(OracleValidationStatus.DISAGREE,
                oracle.validateEquivalence("x", "x + 1").status());
            assertEquals(OracleValidationStatus.UNAVAILABLE,
                oracle.validateEquivalence("unimplemented(x)", "x").status());
            handle = oracle.process();
            assertTrue(handle.isAlive());
        }
        assertFalse(handle.isAlive());
    }

    @Test
    void killsARealHungWorkerAndRetainsTimeoutInIntrinsicValidation() throws Exception {
        try (var oracle = worker("hang", 1000)) {
            oracle.start();
            Process handle = oracle.process();
            var result = TargetFreeIntrinsicCandidateValidator.validate(
                "x", "x", List.of(), true, oracle);
            assertEquals("VALIDATOR_ERROR_OracleTimeoutException", result.oracleStatus());
            assertEquals("TIMEOUT", ReferenceIndependentCandidateValidationRunner.terminal(result));
            assertTrue(handle.waitFor(5, TimeUnit.SECONDS));
            assertFalse(handle.isAlive());
            assertEquals(OracleValidationStatus.UNAVAILABLE,
                oracle.validateEquivalence("x", "x").status());
        }
    }

    @Test
    void alreadyExitedWorkerCannotEscapeAsAnUnretainedPipeCleanupFailure() throws Exception {
        try (var oracle = worker("exit", 5000)) {
            oracle.start();
            assertTrue(oracle.process().waitFor(5, TimeUnit.SECONDS));
            var result = TargetFreeIntrinsicCandidateValidator.validate(
                "x", "x", List.of(), true, oracle);
            assertEquals("TECHNICAL_FAILURE",
                ReferenceIndependentCandidateValidationRunner.terminal(result));
        }
    }

    @Test
    void rejectsAResponseBoundToAnotherRequest() {
        try (var oracle = worker("forge", 5000)) {
            assertThrows(ReferenceIndependentOracleSession.OracleTransportException.class,
                () -> oracle.validateEquivalence("x", "x"));
            assertFalse(oracle.process().isAlive());
        }
    }

    @Test
    void matrixRetainsRealTimeoutAndTerminatedBackendWithinTheCallBudget() {
        try (var oracle = worker("hang", 1000)) {
            oracle.start();
            var artifact = ReferenceIndependentCandidateValidationRunner.run(
                ReferenceIndependentValidationFixtures.PLAN,
                ReferenceIndependentValidationFixtures.FREEZE,
                ReferenceIndependentValidationFixtures.FREEZE_HASH,
                ReferenceIndependentValidationFixtures.REVISION,
                new ReferenceIndependentCandidateValidation.Budget(2, 8192, 1000), oracle);
            var summary = artifact.content().summary();
            assertEquals(4316, summary.candidates());
            assertEquals(2, summary.oracleCalls());
            assertEquals(1, summary.completedOracleCalls());
            assertEquals(1, summary.terminalReasons().get("TIMEOUT"));
            assertEquals(1, summary.terminalReasons().get("UNSUPPORTED"));
            assertEquals(4314, summary.terminalReasons().get("ORACLE_BUDGET_EXHAUSTED"));
            String retained = artifact.toCanonicalJson();
            ReferenceIndependentCandidateValidationVerifier.verifyBindings(
                ReferenceIndependentValidationFixtures.PLAN,
                ReferenceIndependentValidationFixtures.FREEZE,
                ReferenceIndependentValidationFixtures.FREEZE_HASH, retained);
            assertThrows(IllegalArgumentException.class, () ->
                ReferenceIndependentCandidateValidationVerifier.verifyReplay(
                    ReferenceIndependentValidationFixtures.PLAN,
                    ReferenceIndependentValidationFixtures.FREEZE,
                    ReferenceIndependentValidationFixtures.FREEZE_HASH, retained,
                    new SymPyOracleValidator()));
            assertEquals(retained, artifact.toCanonicalJson());
            assertFalse(oracle.process().isAlive());
        }
    }

    @Test
    void matrixRetainsRealProcessFailureWithoutLosingItsChargedWork() throws Exception {
        try (var oracle = worker("exit", 5000)) {
            oracle.start();
            assertTrue(oracle.process().waitFor(5, TimeUnit.SECONDS));
            var artifact = ReferenceIndependentCandidateValidationRunner.run(
                ReferenceIndependentValidationFixtures.PLAN,
                ReferenceIndependentValidationFixtures.FREEZE,
                ReferenceIndependentValidationFixtures.FREEZE_HASH,
                ReferenceIndependentValidationFixtures.REVISION,
                ReferenceIndependentCandidateValidationTest.budget(1), oracle);
            assertEquals(4316, artifact.content().summary().candidates());
            assertEquals(1, artifact.content().summary().oracleCalls());
            assertEquals(0, artifact.content().summary().completedOracleCalls());
            assertEquals(1, artifact.content().summary().terminalReasons().get("TECHNICAL_FAILURE"));
            assertEquals(4315, artifact.content().summary().terminalReasons().get("ORACLE_BUDGET_EXHAUSTED"));
        }
    }

    private static ReferenceIndependentOracleSession worker(String mode, int timeout) {
        return new ReferenceIndependentOracleSession(timeout,
            ReferenceIndependentOracleSession.javaCommand(ControlledWorker.class.getName(), mode));
    }

    /** Real process faults; no production fault switches or timing sleeps. */
    public static final class ControlledWorker {
        public static void main(String[] args) throws Exception {
            System.out.print("REFERENCE_INDEPENDENT_ORACLE_READY_V1"
                + (args[0].equals("crlf") ? "\r\n" : "\n"));
            System.out.flush();
            if (args[0].equals("exit")) {
                return;
            }
            var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            if (args[0].equals("crlf")) {
                var oracle = new SymPyOracleValidator();
                for (String line; (line = input.readLine()) != null;) {
                    var request = ReferenceIndependentCandidateValidation.JSON.readValue(
                        line, ReferenceIndependentOracleSession.Request.class);
                    var result = oracle.validateEquivalence(request.source(), request.candidate());
                    var response = new ReferenceIndependentOracleSession.Response(
                        request.requestHash(), result.status(), result.evidence());
                    System.out.print(ReferenceIndependentCandidateValidation.JSON.writeValueAsString(response)
                        + "\r\n");
                    System.out.flush();
                }
                return;
            }
            input.readLine();
            if (args[0].equals("hang")) {
                new CountDownLatch(1).await();
            } else {
                System.out.println("{\"requestHash\":\"sha256:" + "0".repeat(64)
                    + "\",\"status\":\"AGREE\",\"evidence\":\"forged\"}");
            }
        }
    }
}
