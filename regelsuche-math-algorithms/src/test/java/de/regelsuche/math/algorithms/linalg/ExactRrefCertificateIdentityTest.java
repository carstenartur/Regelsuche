package de.regelsuche.math.algorithms.linalg;

import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.RowOrigin;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.SolutionClassification;
import de.regelsuche.math.algorithms.linalg.ExactRrefSolver.Certificate;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.representation.RepresentationBridge.Relation;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactRrefCertificateIdentityTest {
    private final ExactRrefSolver solver = new ExactRrefSolver();

    @Test
    void certificateRequiresExactSchemaSolverRelationAndSha256Identities() {
        Certificate certificate = solver.solve(
            source(),
            new Budget(10_000)).certificate().orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> copy(
            certificate,
            "other.schema",
            certificate.solverId(),
            certificate.relation(),
            certificate.sourceSystemHash(),
            certificate.contentHash()));
        assertThrows(IllegalArgumentException.class, () -> copy(
            certificate,
            certificate.schema(),
            "other/solver",
            certificate.relation(),
            certificate.sourceSystemHash(),
            certificate.contentHash()));
        assertThrows(IllegalArgumentException.class, () -> copy(
            certificate,
            certificate.schema(),
            certificate.solverId(),
            Relation.EXACT_EXPRESSION_EQUALITY,
            certificate.sourceSystemHash(),
            certificate.contentHash()));
        assertThrows(IllegalArgumentException.class, () -> copy(
            certificate,
            certificate.schema(),
            certificate.solverId(),
            certificate.relation(),
            "not-a-sha256",
            certificate.contentHash()));
        assertThrows(IllegalArgumentException.class, () -> copy(
            certificate,
            certificate.schema(),
            certificate.solverId(),
            certificate.relation(),
            certificate.sourceSystemHash(),
            "not-a-sha256"));
    }

    private static Certificate copy(
        Certificate source,
        String schema,
        String solverId,
        Relation relation,
        String sourceHash,
        String contentHash
    ) {
        return new Certificate(
            schema,
            solverId,
            relation,
            sourceHash,
            source.solutionClassification(),
            source.reducedAugmentedRows(),
            source.canonicalOperations(),
            source.coefficientPivots(),
            source.freeVariableColumns(),
            source.contradictionRows(),
            source.particularSolution(),
            source.nullspaceBasis(),
            source.capabilitiesBefore(),
            source.capabilitiesAfter(),
            source.newlyUnlockedCapabilities(),
            source.lostOrConditionalCapabilities(),
            contentHash);
    }

    private static ExactLinearSystem source() {
        return new ExactLinearSystem(
            new ExactMatrix(List.of(List.of(Rational.ONE))),
            List.of("x"),
            new ExactVector(List.of(Rational.ONE)),
            List.of(new RowOrigin(0, "x = 1")),
            1,
            1,
            SolutionClassification.UNIQUE);
    }
}
