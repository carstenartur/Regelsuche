package de.regelsuche.math.algorithms.noncommutative;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Inverse;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Product;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Scalar;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Sum;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Variable;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.AffinePencil;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.Certificate;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.Constant;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.InverseObligation;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.PencilEntry;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.Status;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.VariableTerm;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Assignment;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Evaluation;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Matrix;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static de.regelsuche.scalar.ExactRational.NEGATIVE_ONE;
import static de.regelsuche.scalar.ExactRational.ONE;
import static de.regelsuche.scalar.ExactRational.ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RationalFormulaRealizationTest {
    private static final IterationLimits LIMITS = new IterationLimits(1_000_000, 50_000, 5_000);
    private static final Variable X = new Variable(7);
    private static final Variable Y = new Variable(11);
    private static final Scalar ONE_FORMULA = new Scalar(ONE);

    @Test void nestedInverseAndSumRetainInnerDomain() {
        var formula = new Sum(new Inverse(new Inverse(X)), ONE_FORMULA);
        var certificate = realize(formula);
        assertEquals(6, certificate.pencil().order());
        assertEquals(List.of(new InverseObligation(1, 0, 3), new InverseObligation(2, 0, 4)),
            certificate.obligations());
        var undefined = evaluate(formula, certificate, new Assignment(1, Map.of(7, matrix(1, 0))));
        assertEquals(Status.UNDEFINED, undefined.status());
        assertEquals(1, undefined.inverseOccurrence().orElseThrow());
        assertFalse(undefined.value().isPresent());

        var assignment = new Assignment(2, Map.of(7, matrix(2, 1, 2, 3, 4)));
        var defined = evaluate(formula, certificate, assignment);
        assertEquals(Status.DEFINED, defined.status(), defined.reason());
        assertEquals(matrix(2, 2, 2, 3, 5), defined.value().orElseThrow());
        assertEquals(formula, defined.source());
        assertEquals(assignment, defined.assignment());
    }

    @Test void multipliedZeroPreservesAnInverseInTheRightBranch() {
        var formula = new Product(new Scalar(ZERO), new Inverse(X));
        var certificate = realize(formula);
        assertEquals(List.of(new InverseObligation(2, 2, 3)), certificate.obligations());
        var singular = evaluate(formula, certificate, new Assignment(2, Map.of(7, matrix(2, 1, 2, 2, 4))));
        assertEquals(Status.UNDEFINED, singular.status(), singular.reason());
        assertEquals(2, singular.inverseOccurrence().orElseThrow());
        var defined = evaluate(formula, certificate, new Assignment(2, Map.of(7, matrix(2, 0, 1, 1, 0))));
        assertEquals(Status.DEFINED, defined.status(), defined.reason());
        assertEquals(matrix(2, 0, 0, 0, 0), defined.value().orElseThrow());
    }

    @Test void orderedProductsUseNoncommutingMatrixValues() {
        var assignment = new Assignment(2, Map.of(7, matrix(2, 0, 1, 0, 0), 11, matrix(2, 0, 0, 1, 0)));
        var xy = new Product(X, Y);
        var yx = new Product(Y, X);
        assertEquals(matrix(2, 1, 0, 0, 0), evaluate(xy, realize(xy), assignment).value().orElseThrow());
        assertEquals(matrix(2, 0, 0, 0, 1), evaluate(yx, realize(yx), assignment).value().orElseThrow());
    }

    @Test void productOfInversesKeepsTheOrderAndBorderSigns() {
        var formula = new Product(new Inverse(X), new Inverse(Y));
        var assignment = new Assignment(2, Map.of(7, matrix(2, 1, 2, 0, 1), 11, matrix(2, 1, 0, 3, 1)));
        var result = evaluate(formula, realize(formula), assignment);
        assertEquals(Status.DEFINED, result.status(), result.reason());
        assertEquals(matrix(2, 7, -2, -3, 1), result.value().orElseThrow());
    }

    @Test void reportsFirstSingularOccurrenceInPostorder() {
        var formula = new Sum(new Inverse(X), new Inverse(Y));
        var certificate = realize(formula);
        var bothSingular = evaluate(formula, certificate, new Assignment(1, Map.of(7, matrix(1, 0), 11, matrix(1, 0))));
        assertEquals(Status.UNDEFINED, bothSingular.status());
        assertEquals(1, bothSingular.inverseOccurrence().orElseThrow());

        var rightSingular = evaluate(formula, certificate, new Assignment(1, Map.of(7, matrix(1, 1), 11, matrix(1, 0))));
        assertEquals(Status.UNDEFINED, rightSingular.status());
        assertEquals(3, rightSingular.inverseOccurrence().orElseThrow());
    }

    @Test void inversionRequiresInvertibleOperandValueAndProducesExactFractions() {
        var formula = new Inverse(new Sum(X, ONE_FORMULA));
        var certificate = realize(formula);
        var assignment = new Assignment(2, Map.of(7, matrix(2, 1, 1, 0, 2)));
        var result = evaluate(formula, certificate, assignment);
        assertEquals(Status.DEFINED, result.status(), result.reason());
        assertEquals(new Matrix(2, List.of(fraction(1, 2), fraction(-1, 6), ZERO, fraction(1, 3))),
            result.value().orElseThrow());

        var singular = new Assignment(2, Map.of(7, matrix(2, 1, 1, 3, 0)));
        var singularFormula = new Inverse(new Sum(X, new Scalar(ExactRational.integer(2))));
        var singularResult = evaluate(singularFormula, realize(singularFormula),
            new Assignment(2, Map.of(7, matrix(2, 1, 2, 3, 0))));
        assertEquals(Status.UNDEFINED, singularResult.status(), singularResult.reason());
        assertEquals(Status.INVALID_ASSIGNMENT,
            evaluate(formula, certificate, new Assignment(1, singular.variables())).status());
    }

    @Test void structurallyValidRealizationCanHaveAnEmptyDomain() {
        var formula = new Inverse(new Scalar(ZERO));
        var certificate = realize(formula);
        assertEquals(Status.VERIFIED, verify(formula, certificate));
        var result = evaluate(formula, certificate, new Assignment(2, Map.of()));
        assertEquals(Status.UNDEFINED, result.status());
        assertEquals(1, result.inverseOccurrence().orElseThrow());
    }

    @Test void duplicateReferencesStillProduceDistinctObligations() {
        var inverse = new Inverse(X);
        var formula = new Sum(inverse, inverse);
        var certificate = realize(formula);
        assertEquals(List.of(new InverseObligation(1, 0, 3), new InverseObligation(3, 3, 3)),
            certificate.obligations());
        var reassigned = new Sum(new Inverse(new Variable(7)), new Inverse(new Variable(7)));
        assertEquals(Status.VERIFIED, verify(reassigned, certificate));
        assertEquals(Status.INVALID_CERTIFICATE, verify(formula, new Certificate(formula,
            certificate.pencil(), certificate.u(), certificate.v(), List.of(certificate.obligations().getFirst()))));
        assertEquals(Status.INVALID_CERTIFICATE, verify(formula, new Certificate(formula,
            certificate.pencil(), certificate.u(), certificate.v(), List.of(
                certificate.obligations().getFirst(), certificate.obligations().getFirst()))));
    }

    @Test void verifierBindsSourceAndChecksAllEntriesAndSelectors() {
        var formula = new Product(new Inverse(X), Y);
        var certificate = realize(formula);
        assertEquals(Status.INVALID_CERTIFICATE, verify(new Product(Y, new Inverse(X)), certificate));

        var pencil = certificate.pencil();
        for (int index = 0; index < pencil.entries().size(); index++) {
            List<PencilEntry> forged = new ArrayList<>(pencil.entries());
            forged.set(index, new VariableTerm(666, ONE));
            assertEquals(Status.INVALID_CERTIFICATE, verify(formula, new Certificate(formula,
                new AffinePencil(pencil.order(), forged), certificate.u(), certificate.v(),
                certificate.obligations())), "pencil position " + index);
        }
        var forgedU = new ArrayList<>(certificate.u());
        forgedU.set(0, ONE);
        var forgedV = new ArrayList<>(certificate.v());
        forgedV.set(0, NEGATIVE_ONE);
        assertEquals(Status.INVALID_CERTIFICATE, verify(formula, new Certificate(formula, pencil,
            forgedU, certificate.v(), certificate.obligations())));
        assertEquals(Status.INVALID_CERTIFICATE, verify(formula, new Certificate(formula, pencil,
            certificate.u(), forgedV, certificate.obligations())));

        var alternateSource = new Product(new Inverse(Y), X);
        var other = realize(alternateSource);
        var wrongSource = new Certificate(formula, other.pencil(), other.u(), other.v(), other.obligations());
        assertEquals(Status.INVALID_CERTIFICATE, verify(formula, wrongSource));
        assertEquals(Status.INVALID_CERTIFICATE, evaluate(formula, wrongSource,
            new Assignment(1, Map.of(7, matrix(1, 2), 11, matrix(1, 3)))).status());
    }

    @Test void validatesTheEntireAssignmentBeforeDomainChecking() {
        var formula = new Product(new Inverse(X), Y);
        var certificate = realize(formula);
        assertEquals(Status.INVALID_ASSIGNMENT,
            evaluate(formula, certificate, new Assignment(1, Map.of(7, matrix(1, 0)))).status());
        assertEquals(Status.INVALID_ASSIGNMENT,
            evaluate(formula, certificate, new Assignment(2, Map.of(7, matrix(2, 1, 0, 0, 1), 11, matrix(1, 2))))
                .status());
    }

    @Test void resourceStopsExposeNeitherPartialCertificateNorEvaluation() {
        var formula = new Inverse(X);
        var certificate = realize(formula);
        var assignment = new Assignment(1, Map.of(7, matrix(1, 2)));
        var tiny = new IterationLimits(1, 50_000, 5_000);
        var result = RationalFormulaRealization.realize(formula, tiny, () -> false);
        assertEquals(Status.RESOURCE_LIMIT, result.status());
        assertTrue(result.certificate().isEmpty());
        assertEquals(Status.RESOURCE_LIMIT, RationalFormulaVerifier.verify(formula, certificate, tiny, () -> false).status());
        var termLimit = new IterationLimits(1_000_000, 8, 5_000);
        assertEquals(Status.RESOURCE_LIMIT,
            RationalFormulaRealization.realize(formula, termLimit, () -> false).status());
        var evaluation = RationalFormulaVerifier.evaluate(formula, certificate, assignment, termLimit, () -> false);
        assertEquals(Status.RESOURCE_LIMIT, evaluation.status());
        assertTrue(evaluation.value().isEmpty());
        assertTrue(evaluation.inverseOccurrence().isEmpty());

        assertEquals(Status.CANCELLED, RationalFormulaRealization.realize(formula, LIMITS, () -> true).status());
        assertEquals(Status.CANCELLED, RationalFormulaVerifier.verify(formula, certificate, LIMITS, () -> true).status());
        assertEquals(Status.CANCELLED,
            RationalFormulaVerifier.evaluate(formula, certificate, assignment, LIMITS, () -> true).status());
        var huge = new Scalar(new ExactRational(BigInteger.ONE.shiftLeft(IterationLimits.MAX_RUNTIME_BITS), BigInteger.ONE));
        assertEquals(Status.RESOURCE_LIMIT, RationalFormulaRealization.realize(huge, LIMITS, () -> false).status());
        RationalFormula deep = X;
        for (int i = 0; i < 1_000; i++) {
            deep = new Inverse(deep);
        }
        assertEquals(Status.RESOURCE_LIMIT, RationalFormulaRealization.realize(deep, LIMITS, () -> false).status());
    }

    @Test void boundsExpandedMatrixStorageBeforeElimination() {
        var formula = new Inverse(X);
        var certificate = realize(formula);
        var bounded = new IterationLimits(1_000_000, 250, 5_000);
        assertEquals(Status.VERIFIED, RationalFormulaVerifier.verify(formula, certificate, bounded, () -> false).status());

        var assignment = new Assignment(10, Map.of(7, matrix(10, new long[100])));
        var evaluation = RationalFormulaVerifier.evaluate(formula, certificate, assignment, bounded, () -> false);
        assertEquals(Status.RESOURCE_LIMIT, evaluation.status());
        assertEquals("TERM_LIMIT", evaluation.reason());
        assertTrue(evaluation.inverseOccurrence().isEmpty());

        var large = evaluate(formula, certificate, new Assignment(Integer.MAX_VALUE, Map.of()));
        assertEquals(Status.RESOURCE_LIMIT, large.status());
        assertEquals("TERM_LIMIT", large.reason());
    }

    @Test void snapshotsPencilAndAssignmentInputs() {
        var source = new Scalar(fraction(1, 3));
        var original = realize(source);
        var entries = new ArrayList<>(original.pencil().entries());
        var pencil = new AffinePencil(original.pencil().order(), entries);
        var selectors = new ArrayList<>(original.u());
        var certificate = new Certificate(source, pencil, selectors, original.v(), new ArrayList<>());
        entries.set(0, new Constant(ZERO));
        selectors.set(0, ZERO);
        var variables = new HashMap<>(Map.of(7, matrix(2, 1, 0, 0, 1)));
        var assignment = new Assignment(2, variables);
        variables.put(7, matrix(1, 0));
        var value = evaluate(source, certificate, assignment);
        assertEquals(new Matrix(2, List.of(fraction(1, 3), ZERO, ZERO, fraction(1, 3))), value.value().orElseThrow());
    }

    @Test void sparseClusteredVariableIdsKeepExactValuesAndImmutableNullFreeSnapshots() {
        var variables = new HashMap<Integer, Matrix>();
        for (int id = 0; id < 1_000; id++) {
            variables.put(id, matrix(1, 1));
            variables.put(4_000 + id, matrix(1, 2));
        }
        var assignment = new Assignment(1, variables);
        variables.clear();
        RationalFormula formula = new Variable(4_000);
        for (int i = 0; i < 5; i++) formula = new Sum(formula, formula);
        var certificate = realize(formula);
        var result = evaluate(formula, certificate, assignment);
        assertEquals(matrix(1, 64), result.value().orElseThrow());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
            () -> assignment.variables().entrySet().iterator().next().setValue(matrix(1, 0)));
        variables.put(1, null);
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> new Assignment(1, variables));
        variables.clear(); variables.put(null, matrix(1, 0));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> new Assignment(1, variables));
    }

    @Test void rejectsDuplicateNumericIdsFromIdentityKeyedInput() throws ReflectiveOperationException {
        var constructor = Integer.class.getConstructor(int.class);
        var variables = new java.util.IdentityHashMap<Integer, Matrix>();
        variables.put(constructor.newInstance(1_000), matrix(1, 1));
        variables.put(constructor.newInstance(1_000), matrix(1, 2));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new Assignment(1, variables));
    }

    private static Certificate realize(RationalFormula formula) {
        var result = RationalFormulaRealization.realize(formula, LIMITS, () -> false);
        assertEquals(Status.REALIZED, result.status(), result.reason());
        return result.certificate().orElseThrow();
    }

    private static Status verify(RationalFormula formula, Certificate certificate) {
        return RationalFormulaVerifier.verify(formula, certificate, LIMITS, () -> false).status();
    }

    private static Evaluation evaluate(RationalFormula formula, Certificate certificate, Assignment assignment) {
        return RationalFormulaVerifier.evaluate(formula, certificate, assignment, LIMITS, () -> false);
    }

    private static Matrix matrix(int order, long... entries) {
        return new Matrix(order, Arrays.stream(entries).mapToObj(ExactRational::integer).toList());
    }

    private static ExactRational fraction(long numerator, long denominator) {
        return new ExactRational(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }
}
