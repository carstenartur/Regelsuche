package de.regelsuche.math.algorithms.linalg;

import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.ast.Equation;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.math.algorithms.linalg.DirectScalarEliminationSolver.Certificate;
import de.regelsuche.math.algorithms.linalg.DirectScalarEliminationSolver.Source;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.util.List;
import org.junit.jupiter.api.Test;

class DirectScalarCertificateIdentityTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final DirectScalarEliminationSolver solver =
        new DirectScalarEliminationSolver();

    @Test
    void certificateRequiresExactSchemaSolverAndSha256Identities() {
        Certificate certificate = solver.solve(
            source(),
            new Budget(10_000)).certificate().orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> copy(
            certificate,
            "other.schema",
            certificate.solverId(),
            certificate.sourceHash(),
            certificate.contentHash()));
        assertThrows(IllegalArgumentException.class, () -> copy(
            certificate,
            certificate.schema(),
            "other/solver",
            certificate.sourceHash(),
            certificate.contentHash()));
        assertThrows(IllegalArgumentException.class, () -> copy(
            certificate,
            certificate.schema(),
            certificate.solverId(),
            "not-a-sha256",
            certificate.contentHash()));
        assertThrows(IllegalArgumentException.class, () -> copy(
            certificate,
            certificate.schema(),
            certificate.solverId(),
            certificate.sourceHash(),
            "not-a-sha256"));
    }

    private Source source() {
        List<Equation> equations = parser.parse(
            new InputRequest(InputType.SYSTEM, "x = 1")).equations();
        return new Source(equations, List.of("x"));
    }

    private static Certificate copy(
        Certificate source,
        String schema,
        String solverId,
        String sourceHash,
        String contentHash
    ) {
        return new Certificate(
            schema,
            solverId,
            sourceHash,
            source.sourceEquations(),
            source.variables(),
            source.reducedEquations(),
            source.canonicalOperations(),
            source.consequenceLines(),
            source.workProfile(),
            contentHash);
    }
}
