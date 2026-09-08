package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge;
import de.regelsuche.representation.RepresentationPreparation;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static de.regelsuche.math.algorithms.linalg.ExactMatrixExpression.*;
import static de.regelsuche.math.algorithms.linalg.MatrixPreparation.Profile.*;
import static org.junit.jupiter.api.Assertions.*;

class MatrixPreparationTest {
    private final MatrixPreparation preparation = new MatrixPreparation();

    @Test
    void exposesRepeatedSourceFormsAndReplaysEveryRow() {
        var request = scalar("2*(x+y)+3*(x-y)=5; (x+y)+4*(x-y)=6");
        var result = preparation.analyze(request);
        assertEquals(RepresentationBridge.Status.REPRESENTED, result.status());
        var prepared = accepted(result, "REPEATED_SOURCE_LINEAR_FORMS");
        var product = assertInstanceOf(Product.class, prepared.expression());
        assertEquals(matrix("2,3;1,4"), assertInstanceOf(Matrix.class, product.left()).value());
        assertEquals(matrix("1,1;1,-1"), assertInstanceOf(Matrix.class, product.right()).value());
        assertTrue(prepared.provenance().stream().anyMatch(p -> p.contains("row[0].left")));
        assertEquals(List.of("5*x - y", "5*x - 3*y"), prepared.outcome().replay().orElseThrow()
            .representation().orElseThrow().stream().map(Polynomial::toCanonicalString).toList());
        assertTrue(preparation.verify(result));
        assertEquals(result, preparation.analyze(request));
    }

    @Test
    void preservesUnusedDeclaredCoordinatesAndRequestedOrderDuringSolving() {
        var result = preparation.analyze(MatrixPreparation.Request.scalar("x=2", List.of("unused", "x"),
            SAFE_PREPARED_REPRESENTATION_V1, MatrixPreparation.DEFAULT_WORK));
        assertEquals(List.of("unused", "x"), result.exactSystem().orElseThrow().variables());
        assertEquals(ExactLinearSystem.SolutionClassification.UNDERDETERMINED,
            result.rowReduction().orElseThrow().reduction().orElseThrow().solutionClassification());
    }

    @Test
    void restoresBlockMappingsWithNonalphabeticCoordinates() {
        var result = preparation.analyze(MatrixPreparation.Request.scalar("x+y=2; z=3; x-y=0",
            List.of("z", "y", "x"), SAFE_PREPARED_REPRESENTATION_V1, MatrixPreparation.DEFAULT_WORK));
        var candidate = accepted(result, "CERTIFIED_BLOCK_DIAGONAL_EXPOSURE");
        assertTrue(candidate.outcome().accepted());
        var mapped = assertInstanceOf(Mapped.class, candidate.expression());
        assertEquals(List.of(0, 2, 1), mapped.rows());
        assertEquals(List.of(1, 2, 0), mapped.columns());
        assertEquals(List.of("z", "y", "x"), candidate.outcome().formation().representation().orElseThrow()
            .obligation().coordinates());
    }

    @Test
    void identityExposureRequiresVisibleSourceEvidence() {
        accepted(preparation.analyze(scalar("x+(2*x+y)=4; y+(x+3*y)=5")), "SOURCE_IDENTITY_PLUS_OPERATOR");
        assertFalse(preparation.analyze(scalar("3*x+y=4; x+4*y=5")).attempts().stream()
            .anyMatch(a -> a.origin().equals("SOURCE_IDENTITY_PLUS_OPERATOR")));
    }

    @Test
    void neverSynthesizesArbitraryFactorsWithoutSourcesOrCatalog() {
        var result = preparation.analyze(scalar("2*x+3*y=5; 5*x+7*y=12"));
        assertEquals(List.of("DIRECT_MATRIX_REPRESENTATION"), result.attempts().stream()
            .filter(a -> a.outcome().accepted()).map(MatrixPreparation.Attempt::origin).toList());
    }

    @Test
    void verifiesOrderedProductsInsteadOfCommutingTheirEntries() {
        Matrix a = new Matrix("A", matrix("1,1;0,1"));
        Matrix b = new Matrix("B", matrix("1,0;1,1"));
        var bridge = new MatrixRepresentationBridge();
        var ab = obligation(matrix("2,1;1,1"), new Product(a, b));
        var ba = obligation(matrix("2,1;1,1"), new Product(b, a));
        assertTrue(bridge.analyze(ab, RepresentationBridge.Budget.DEFAULT).represented());
        assertFalse(bridge.analyze(ba, RepresentationBridge.Budget.DEFAULT).represented());
        var alteredLastCell = obligation(matrix("2,1;1,2"), new Product(a, b));
        assertFalse(bridge.analyze(alteredLastCell, RepresentationBridge.Budget.DEFAULT).represented());
    }

    @Test
    void dimensionMismatchAndWrongRowMappingFailClosed() {
        var wrongProduct = obligation(matrix("1,0;0,1"), new Product(
            new Matrix("wide", matrix("1,2,3;4,5,6")), new Identity(2)));
        var result = new MatrixRepresentationBridge().analyze(wrongProduct, RepresentationBridge.Budget.DEFAULT);
        assertEquals(RepresentationBridge.Status.DOMAIN_UNSUPPORTED, result.status());
        assertEquals("PRODUCT_DIMENSION_MISMATCH", result.detailCode());
        assertThrows(IllegalArgumentException.class, () -> new Mapped(new Identity(2), List.of(0, 0), List.of(0, 1)));
    }

    @Test
    void exactInverseEvidenceAuthorizesOnlyOrderedCancellation() {
        Matrix a = new Matrix("A", matrix("1,1;0,1"));
        Inverse inverse = new Inverse(a, matrix("1,-1;0,1"));
        var work = new ExactMatrixAlgebra.Work(10_000);
        var rules = MatrixOperatorRules.prepare(new Product(a, inverse), new ExactMatrixAlgebra(work), work);
        assertTrue(rules.stream().anyMatch(r -> r.rule().equals("VERIFIED_INVERSE_CANCELLATION")));
        var falseInverse = new Inverse(a, matrix("1,0;0,1"));
        var secondWork = new ExactMatrixAlgebra.Work(10_000);
        assertThrows(ExactMatrixAlgebra.Unsupported.class, () -> MatrixOperatorRules.prepare(
            new Product(a, falseInverse), new ExactMatrixAlgebra(secondWork), secondWork));
        var singular = new Inverse(new Matrix("singular", matrix("1,0;0,0")), matrix("1,0;0,1"));
        assertEquals(RepresentationBridge.Status.DOMAIN_UNSUPPORTED,
            new MatrixRepresentationBridge().analyze(obligation(matrix("1,0;0,1"), singular),
                RepresentationBridge.Budget.DEFAULT).status());
    }

    @Test
    void explicitOperatorProfileReplaysDistributivity() {
        var catalog = List.of(named("A", "1,1;0,1"), named("B", "1,0;1,1"));
        var request = new MatrixPreparation.Request("3*x+2*y=5; x+2*y=3", List.of("x", "y"),
            EXPERIMENTAL_OPERATOR_V1, MatrixPreparation.DEFAULT_WORK, catalog, List.of("A*(B+I(2))"),
            "", false, "", List.of());
        var result = preparation.analyze(request);
        assertTrue(accepted(result, "LEFT_DISTRIBUTIVITY").outcome().accepted());
        assertEquals(RepresentationBridge.Relation.LINEAR_MAP_REPRESENTATION_EQUIVALENCE,
            accepted(result, "LEFT_DISTRIBUTIVITY").outcome().formation().relation().orElseThrow());
    }

    @Test
    void safeProfileDoesNotRunSpeculativeOperatorRecipes() {
        var request = new MatrixPreparation.Request("x=1", List.of("x"), SAFE_PREPARED_REPRESENTATION_V1,
            MatrixPreparation.DEFAULT_WORK, List.of(named("A", "1")), List.of("A*I(1)"), "", false, "", List.of());
        assertEquals(RepresentationBridge.Status.DOMAIN_UNSUPPORTED, preparation.analyze(request).status());
    }

    @Test
    void recognitionProfileDoesNotSolveOrPrepare() {
        var result = preparation.analyze(MatrixPreparation.Request.scalar("x=1; y=2", List.of("x", "y"),
            RECOGNITION_ONLY_V1, MatrixPreparation.DEFAULT_WORK));
        assertTrue(result.formation().orElseThrow().represented());
        assertTrue(result.attempts().isEmpty());
        assertTrue(result.rowReduction().isEmpty());
    }

    @Test
    void exhaustedWorkRetainsAnInconclusiveStatusAndBalancedLedger() {
        var request = MatrixPreparation.Request.scalar("x=1", List.of("x"), SAFE_PREPARED_REPRESENTATION_V1, 0);
        var result = preparation.analyze(request);
        assertEquals(RepresentationBridge.Status.BUDGET_INCONCLUSIVE, result.status());
        assertEquals(0, result.acceptedCount());
        assertEquals(RepresentationBridge.WorkLedger.of(0, 0), result.work());
        var full = preparation.analyze(scalar("2*(x+y)+3*(x-y)=5; (x+y)+4*(x-y)=6"));
        var limited = preparation.analyze(MatrixPreparation.Request.scalar(full.request().equations(), List.of("x", "y"),
            SAFE_PREPARED_REPRESENTATION_V1, full.work().consumedWorkUnits() / 2));
        assertEquals(RepresentationBridge.Status.BUDGET_INCONCLUSIVE, limited.status());
        assertTrue(limited.work().consumedWorkUnits() <= limited.work().configuredWorkUnits());
    }

    @Test
    void acceptedRepresentationRequiresConcreteReplayWithinRemainingBudget() {
        var source = obligation(matrix("1,0;0,1"), new Identity(2));
        var bridge = new MatrixRepresentationBridge();
        int formationWork = bridge.analyze(source, RepresentationBridge.Budget.DEFAULT).work().consumedWorkUnits();
        var result = RepresentationPreparation.analyze(source, bridge, Set.of(MatrixRepresentationBridge.RELATION),
            new MatrixRepresentationBridge.VectorReplay(), new RepresentationBridge.Budget(formationWork));
        assertFalse(result.accepted());
        assertEquals(RepresentationBridge.Status.BUDGET_INCONCLUSIVE, result.replay().orElseThrow().status());
    }

    @Test
    void incompatibleRelationsAndTamperedCertificatesCannotBecomeScalarRewrites() {
        var source = obligation(matrix("1,0;0,1"), new Identity(2));
        var bridge = new MatrixRepresentationBridge();
        var result = RepresentationPreparation.analyze(source, bridge,
            Set.of(RepresentationBridge.Relation.EXACT_EXPRESSION_EQUALITY), new MatrixRepresentationBridge.VectorReplay(),
            RepresentationBridge.Budget.DEFAULT);
        assertFalse(result.accepted());
        assertTrue(result.replay().isEmpty());
        var formation = bridge.analyze(source, RepresentationBridge.Budget.DEFAULT);
        var forged = RepresentationBridge.Result.represented(formation.representation().orElseThrow(),
            new MatrixRepresentationBridge.Certificate(MatrixRepresentationBridge.SCHEMA, "0".repeat(64)),
            MatrixRepresentationBridge.RELATION, formation.work(), formation.detailCode());
        assertFalse(bridge.verify(source, forged));
    }

    @Test
    void eigenRecognitionRequiresDeclaredRolesAndNonzeroVector() {
        var guarded = new MatrixPreparation.Request("a*x+b*y=lambda*x; c*x+d*y=lambda*y", List.of("x", "y"),
            SAFE_PREPARED_REPRESENTATION_V1, MatrixPreparation.DEFAULT_WORK, List.of(), List.of(), "lambda", true, "", List.of());
        var result = preparation.analyze(guarded);
        assertTrue(result.eigenproblem().orElseThrow().represented());
        assertTrue(result.characteristicPolynomial().orElseThrow().characteristicPolynomial().isPresent());
        assertEquals(EigenproblemRepresentation.ModelInterpretation.NONE,
            result.eigenproblem().orElseThrow().representation().orElseThrow().modelInterpretation());
        var unguarded = new MatrixPreparation.Request(guarded.equations(), guarded.unknowns(), guarded.profile(),
            guarded.maxWorkUnits(), List.of(), List.of(), "lambda", false, "", List.of());
        assertEquals(RepresentationBridge.Status.ASSUMPTION_REQUIRED,
            preparation.analyze(unguarded).eigenproblem().orElseThrow().status());
    }

    @Test
    void matrixEquationRetainsItsNotationAndExactScalarRoundTrip() {
        var request = new MatrixPreparation.Request("", List.of("x", "y"), SAFE_PREPARED_REPRESENTATION_V1,
            MatrixPreparation.DEFAULT_WORK, List.of(named("P", "2,3;1,4"), named("Q", "1,1;1,-1")),
            List.of(), "", false, "P*Q", List.of("5", "6"));
        var result = preparation.analyze(request);
        assertEquals("P*Q", result.request().matrixExpression());
        assertEquals(matrix("5,-1;5,-3"), result.formation().orElseThrow().representation().orElseThrow().coefficients());
        accepted(result, "DECLARED_MATRIX_EQUATION");
        assertTrue(preparation.verify(result));
    }

    @Test
    void nonlinearUndefinedAndOversizedScalarInputsAreNotGuessed() {
        assertEquals(RepresentationBridge.Status.NONLINEAR, preparation.analyze(scalar("x*y=1; x+y=2")).status());
        assertEquals(RepresentationBridge.Status.DOMAIN_UNSUPPORTED, preparation.analyze(scalar("x/0=1; y=2")).status());
        assertEquals(RepresentationBridge.Status.DOMAIN_UNSUPPORTED,
            preparation.analyze(scalar("(x+y)^2147483647=1; x=2")).status());
        assertEquals(RepresentationBridge.Status.DOMAIN_UNSUPPORTED, preparation.analyze(scalar("x^0=1; y=2")).status());
        assertEquals(RepresentationBridge.Status.DOMAIN_UNSUPPORTED, preparation.analyze(scalar("0^0=1; y=2")).status());
    }

    @Test
    void overflowingDegreeInAnExternallyConstructedPolynomialIsRejected() {
        var polynomial = Polynomial.term(new de.regelsuche.math.algorithms.equivalence.Monomial(
            java.util.Map.of("a", Integer.MAX_VALUE, "b", Integer.MAX_VALUE)), Rational.ONE);
        var algebra = new ExactMatrixAlgebra(new ExactMatrixAlgebra.Work(1000));
        assertThrows(ExactMatrixAlgebra.Unsupported.class, () -> algebra.checked(new PolynomialMatrix(List.of(List.of(polynomial)))));
    }

    private MatrixPreparation.Request scalar(String source) {
        return MatrixPreparation.Request.scalar(source, List.of("x", "y"), SAFE_PREPARED_REPRESENTATION_V1, MatrixPreparation.DEFAULT_WORK);
    }

    @Test
    void qualificationRetainsAllProfilesAndRecomputesExactBaselineComparisons(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var artifacts = MatrixPreparationQualification.artifacts();
        assertEquals(37, artifacts.size());
        assertTrue(artifacts.get("ordered-distributivity-EXPERIMENTAL_OPERATOR_V1.json").contains("LEFT_DISTRIBUTIVITY"));
        assertTrue(artifacts.get("guarded-inverse-EXPERIMENTAL_OPERATOR_V1.json").contains("VERIFIED_INVERSE_CANCELLATION"));
        assertTrue(artifacts.get("qualification.json").contains("EXACT_SOLUTION_CONSEQUENCES_AGREE"));
        assertEquals(artifacts, MatrixPreparationQualification.artifacts());
        var host = directory.resolve("host");
        var container = directory.resolve("container");
        MatrixPreparationQualification.write(host);
        MatrixPreparationQualification.write(container);
        MatrixPreparationQualification.verifyDirectories(host, container);
        java.nio.file.Files.writeString(container.resolve("qualification.json"), "{}");
        assertThrows(IllegalStateException.class, () -> MatrixPreparationQualification.verifyDirectories(host, container));
    }

    private static MatrixPreparation.Attempt accepted(MatrixPreparation.Analysis result, String origin) {
        return result.attempts().stream().filter(a -> a.origin().equals(origin) && a.outcome().accepted())
            .findFirst().orElseThrow(() -> new AssertionError(origin + " missing: " + result.status() + "/" + result.detailCode()));
    }

    private static MatrixRepresentationBridge.Obligation obligation(PolynomialMatrix matrix, ExactMatrixExpression expression) {
        return new MatrixRepresentationBridge.Obligation(matrix, List.of("row 0", "row 1"), List.of("x", "y"),
            "formation-test-root", expression, List.of("explicit test factors"));
    }

    private static MatrixPreparation.NamedMatrix named(String name, String source) {
        return new MatrixPreparation.NamedMatrix(name, strings(source), List.of());
    }

    private static List<List<String>> strings(String source) {
        return java.util.Arrays.stream(source.split(";")).map(row -> List.of(row.split(","))).toList();
    }

    private static PolynomialMatrix matrix(String source) {
        var algebra = new ExactMatrixAlgebra(new ExactMatrixAlgebra.Work(100_000));
        var parser = new ExpressionParser();
        return new PolynomialMatrix(strings(source).stream().map(row -> row.stream()
            .map(value -> algebra.scalar(parser.parseTerm(value))).toList()).toList());
    }
}
