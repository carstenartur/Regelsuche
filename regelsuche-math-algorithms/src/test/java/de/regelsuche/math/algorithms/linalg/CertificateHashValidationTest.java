package de.regelsuche.math.algorithms.linalg;

import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.ast.Equation;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.math.algorithms.linalg.EigenproblemRepresentation.ModelDomain;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CertificateHashValidationTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final SymbolicLinearSystemRepresentationBridge symbolicBridge =
        new SymbolicLinearSystemRepresentationBridge();
    private final EigenproblemRepresentationBridge eigenBridge =
        new EigenproblemRepresentationBridge();
    private final BoundedCharacteristicPolynomialSolver characteristicSolver =
        new BoundedCharacteristicPolynomialSolver();

    @Test
    void symbolicCertificateRejectsMalformedContentHash() {
        SymbolicLinearSystemRepresentationBridge.Certificate certificate =
            symbolicResult().certificate().orElseThrow();

        assertThrows(IllegalArgumentException.class, () ->
            new SymbolicLinearSystemRepresentationBridge.Certificate(
                certificate.schema(),
                certificate.bridgeId(),
                certificate.relation(),
                certificate.sourceEquations(),
                certificate.unknownOrder(),
                certificate.scalarParameters(),
                certificate.coefficientRows(),
                certificate.rightHandSide(),
                "not-a-sha256"));
    }

    @Test
    void eigenproblemCertificateRejectsMalformedSourceAndContentHashes() {
        EigenproblemRepresentationBridge.Certificate certificate =
            eigenResult().certificate().orElseThrow();

        assertThrows(IllegalArgumentException.class, () ->
            eigenCertificate(certificate, "not-a-sha256", "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () ->
            eigenCertificate(
                certificate,
                certificate.sourceSystemHash(),
                "not-a-sha256"));
    }

    @Test
    void characteristicCertificateRejectsMalformedSourceAndContentHashes() {
        BoundedCharacteristicPolynomialSolver.Certificate certificate =
            characteristicSolver.solve(
                eigenResult().representation().orElseThrow(),
                new BoundedCharacteristicPolynomialSolver.Budget(10_000))
                .certificate()
                .orElseThrow();

        assertThrows(IllegalArgumentException.class, () ->
            characteristicCertificate(
                certificate,
                "not-a-sha256",
                "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () ->
            characteristicCertificate(
                certificate,
                certificate.sourceHash(),
                "not-a-sha256"));
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

    private static EigenproblemRepresentationBridge.Certificate
            eigenCertificate(
        EigenproblemRepresentationBridge.Certificate source,
        String sourceHash,
        String contentHash
    ) {
        return new EigenproblemRepresentationBridge.Certificate(
            source.schema(),
            source.bridgeId(),
            source.relation(),
            sourceHash,
            source.eigenvalueParameter(),
            source.vectorCoordinates(),
            source.operatorRows(),
            source.shiftedOperatorRows(),
            source.requiredAssumptions(),
            source.modelInterpretation(),
            source.declaredModelDomain(),
            source.declaredOperatorProperties(),
            source.unlockedCapabilities(),
            contentHash);
    }

    private static BoundedCharacteristicPolynomialSolver.Certificate
            characteristicCertificate(
        BoundedCharacteristicPolynomialSolver.Certificate source,
        String sourceHash,
        String contentHash
    ) {
        return new BoundedCharacteristicPolynomialSolver.Certificate(
            source.schema(),
            source.solverId(),
            sourceHash,
            source.dimension(),
            source.eigenvalueParameter(),
            source.canonicalPolynomial(),
            source.singularityEquation(),
            contentHash);
    }
}
