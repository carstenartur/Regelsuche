package de.regelsuche.solver.portfolio;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Theory;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmtProofArtifactsTest {
    @Test void roundTripBindsTheExactGoalPremisesAndEvidenceRequest() {
        Obligation request = CheckedLeanProofTest.obligation("a / b * b", "a", "b != 0");
        String artifact = SmtProofArtifacts.artifact(request);

        assertEquals(request, SmtProofArtifacts.readArtifact(artifact));
        assertEquals(artifact, SmtProofArtifacts.artifact(SmtProofArtifacts.readArtifact(artifact)));
        assertTrue(artifact.contains("(assert (not (= b 0)))"));
        assertTrue(artifact.contains("(assert (not (= (* (/ a b) b) a)))"));
        assertTrue(artifact.endsWith("(check-sat)\n(get-proof)\n(exit)\n"));
    }

    @Test void aValidEnvelopeCannotAuthorizeEditedOrSubstitutedScript() {
        String first = SmtProofArtifacts.artifact(CheckedLeanProofTest.obligation("x+0", "x"));
        String second = SmtProofArtifacts.artifact(CheckedLeanProofTest.obligation("x+1", "x"));
        String swapped = first.substring(0, first.indexOf('\n')) + second.substring(second.indexOf('\n'));
        for (String changed : List.of(swapped, first + "(assert false)\n",
                first.replace("(get-proof)", "(get-model)"), first.replace("(+ x 0)", "(+ x 1)"))) {
            assertNotEquals(first, changed);
            assertThrows(IllegalArgumentException.class, () -> SmtProofArtifacts.readArtifact(changed));
        }
    }

    @Test void malformedOversizedAndUnboundArtifactsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SmtProofArtifacts.readArtifact(null));
        for (String artifact : List.of("(check-sat)", SmtProofArtifacts.ENVELOPE + "e30=",
                SmtProofArtifacts.ENVELOPE + "!not-base64!\n", "x".repeat(2_000_001),
                SmtProofArtifacts.ENVELOPE + "e30=\n(check-sat)\n")) {
            assertThrows(IllegalArgumentException.class, () -> SmtProofArtifacts.readArtifact(artifact));
        }
        var request = CheckedLeanProofTest.obligation("x", "x");
        String forgedJson = request.toCanonicalJson().replace(request.contentHash(), SolverIr.sha256("wrong"));
        String forged = SmtProofArtifacts.ENVELOPE + Base64.getEncoder().encodeToString(
            forgedJson.getBytes(StandardCharsets.UTF_8)) + "\n(check-sat)\n";
        assertThrows(IllegalArgumentException.class, () -> SmtProofArtifacts.readArtifact(forged));
    }

    @Test void unsupportedDomainsCannotBeHiddenInsideAnEnvelope() {
        for (Obligation request : List.of(CheckedLeanProofTest.obligation("x/y", "x/y"),
                CheckedLeanProofTest.obligation("sin(x)", "sin(x)"))) {
            assertThrows(IllegalArgumentException.class, () -> SmtProofArtifacts.artifact(request));
        }
        var real = CheckedLeanProofTest.obligation("x", "x");
        var integerTheory = Obligation.create(real.obligationId(), real.declarations(),
            List.of(Theory.INTEGER_ARITHMETIC), real.assumptions(), real.goal(),
            real.requestedEvidence(), real.provenance());
        assertThrows(IllegalArgumentException.class, () -> SmtProofArtifacts.artifact(integerTheory));
    }
}
