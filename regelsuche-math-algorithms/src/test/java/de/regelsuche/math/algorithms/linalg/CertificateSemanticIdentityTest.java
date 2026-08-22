package de.regelsuche.math.algorithms.linalg;

import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.ast.Equation;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.math.algorithms.linalg.EigenproblemRepresentation.ModelDomain;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.representation.RepresentationBridge.Relation;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CertificateSemanticIdentityTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final SymbolicLinearSystemRepresentationBridge symbolicBridge =
        new SymbolicLinearSystemRepresentationBridge();
    private final EigenproblemRepresentationBridge eigenBridge =
        new EigenproblemRepresentationBridge();
    private final BoundedCharacteristicPolynomialSolver characteristicSolver =
        new BoundedCharacteristicPolynomialSolver();

    @Test
    void symbolicCertificateRequiresItsExactSchemaBridgeAndRelation() {
        SymbolicLinearSystemRepresentationBridge.Certificate certificate =
            symbolicResult().certificate().orElseThrow();

        assertThrows(IllegalArgumentException.class, () ->
            symbolicCertificate(
                certificate,
                "other.schema",
                certificate.bridgeId(),
                certificate.relation()));
        assertThrows(IllegalArgumentException.class, () ->
            symbolicCertificate(
                certificate,
                certificate.schema(),
                "other/bridge",
                certificate.relation()));
        assertThrows(IllegalArgumentException.class, () ->
            symbolicCertificate(
                certificate,
                certificate.schema(),
                certificate.bridgeId(),
                Relation.EXACT_EXPRESSION_EQUALITY));
    }

    @Test
    void eigenproblemCertificateRequiresItsExactSchemaBridgeAndRelation() {
        EigenproblemRepresentationBridge.Certificate certificate =
            eigenResult().certificate().orElseThrow();

        assertThrows(IllegalArgumentException.class, () ->
            eigenCertificate(
                certificate,
                "other.schema",
                certificate.bridgeId(),
                certificate.relation()));
        assertThrows(IllegalArgumentException.class, () ->
            eigenCertificate(
                certificate,
                certificate.schema(),
                "other/bridge",
                certificate.relation()));
        assertThrows(IllegalArgumentException.class, () ->
            eigenCertificate(
                certificate,
                certificate.schema(),
                certificate.bridgeId(),
                Relation.SOLUTION_SET_EQUIVALENCE));
    }

    @Test
    void characteristicCertificateRequiresItsExactSchemaAndSolver() {
        BoundedCharacteristicPolynomialSolver.Certificate certificate =
            characteristicSolver.solve(
                eigenResult().representation().orElseThrow(),
                new BoundedCharacteristicPolynomialSolver.Budget(10_000))
                .certificate()
                .orElseThrow();

        assertThrows(IllegalArgumentException.class, () ->
            characteristicCertificate(
                certificate,
                "other.schema",
                certificate.solverId()));
        assertThrows(IllegalArgumentException.class, () ->
            characteristicCertificate(
                certificate,
                certificate.schema(),
                "other/solver"));
    }

    private de.regelsuche.representation.RepresentationBridge.Result<
            SymbolicLinearSystem,
            SymbolicLinearSystemRepresentationBridge.Certificate>
            symbolicResult() {
        List<Equation> equations = parser.parse(
            new InputRequest(
                InputType.SYSTEM,
                "a*x = lambda*x"))
            .equations();
        return symbolicBridge.analyze(
            new SymbolicLinearSystemRepresentationBridge.Source(
                equations,
                List.of("x")),
            new Budget(5_000));
    }

    private de.regelsuche.representation.RepresentationBridge.Result<
            EigenproblemRepresentation,
            EigenproblemRepresentationBridge.Certificate>
            eigenResult() {
        return eigenBridge.analyze(
            new EigenproblemRepresentationBridge.Source(
                symbolicResult().representation().orElseThrow(),
                "lambda",
                true,
                ModelDomain.GENERIC_LINEAR_ALGEBRA,
                Set.of()),
            new Budget(5_000));
    }

    private static SymbolicLinearSystemRepresentationBridge.Certificate
            symbolicCertificate(
        SymbolicLinearSystemRepresentationBridge.Certificate source,
        String schema,
        String bridgeId,
        Relation relation
    ) {
        return new SymbolicLinearSystemRepresentationBridge.Certificate(
            schema,
            bridgeId,
            relation,
            source.sourceEquations(),
            source.unknownOrder(),
            source.scalarParameters(),
            source.coefficientRows(),
            source.rightHandSide(),
            source.contentHash());
    }

    private static EigenproblemRepresentationBridge.Certificate
            eigenCertificate(
        EigenproblemRepresentationBridge.Certificate source,
        String schema,
        String bridgeId,
        Relation relation
    ) {
        return new EigenproblemRepresentationBridge.Certificate(
            schema,
            bridgeId,
            relation,
            source.sourceSystemHash(),
            source.eigenvalueParameter(),
            source.vectorCoordinates(),
            source.operatorRows(),
            source.shiftedOperatorRows(),
            source.requiredAssumptions(),
            source.modelInterpretation(),
            source.declaredModelDomain(),
            source.declaredOperatorProperties(),
            source.unlockedCapabilities(),
            source.contentHash());
    }

    private static BoundedCharacteristicPolynomialSolver.Certificate
            characteristicCertificate(
        BoundedCharacteristicPolynomialSolver.Certificate source,
        String schema,
        String solverId
    ) {
        return new BoundedCharacteristicPolynomialSolver.Certificate(
            schema,
            solverId,
            source.sourceHash(),
            source.dimension(),
            source.eigenvalueParameter(),
            source.canonicalPolynomial(),
            source.singularityEquation(),
            source.contentHash());
    }
}
