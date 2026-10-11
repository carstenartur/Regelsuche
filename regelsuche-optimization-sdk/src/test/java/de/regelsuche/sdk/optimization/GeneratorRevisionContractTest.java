package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.VariableExpr;
import org.junit.jupiter.api.Test;

class GeneratorRevisionContractTest {
    @Test void rejectsEvidenceFromBeforeSmallModularPowerGeneration() {
        var source = OptimizationContractTest.plan(NumericKind.INT,
                JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD,
                        new VariableExpr("x"), JavaExpressions.literal(0)));
        var request = OptimizationContractTest.request(source, SafetyProfile.PRESERVE_JAVA);
        var optimizer = new ComputationOptimizer();
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class,
                optimizer.optimize(request, CancellationToken.NONE));
        assertInstanceOf(VerificationResult.Verified.class,
                optimizer.reverify(request, candidate, CancellationToken.NONE));

        var current = candidate.evidence();
        var stale = new VerificationEvidence(current.schemaRevision(), current.semanticsRevision(),
                current.checkerRevision(), "java-core-algebra-proposals/v1", current.sourceHash(),
                current.candidateHash(), current.traceHash(), current.assumptionsHash(), current.safetyProfile(),
                current.checkedPolicy(), current.obligations(), current.proofMethods(), current.verificationWork());
        var staleCandidate = new OptimizationResult.Candidate(candidate.plan(), candidate.prepared(), stale,
                candidate.obligations(), candidate.cost(), candidate.searchCompletion(), candidate.work(), candidate.derivation());

        var rejected = assertInstanceOf(VerificationResult.Unsupported.class,
                optimizer.reverify(request, staleCandidate, CancellationToken.NONE));
        assertEquals("EVIDENCE_BINDING_OR_REVISION_DIFFERS", rejected.diagnostic());
    }
}
