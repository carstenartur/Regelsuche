package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.Binding;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleKind;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.SearchResult;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.SearchStatus;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ExactFinitePolynomialHoleSolverTest {
    private final ExactFinitePolynomialHoleSolver solver =
        new ExactFinitePolynomialHoleSolver();
    private final ExactResidualPolynomialArithmetic arithmetic =
        new ExactResidualPolynomialArithmetic();

    @Test
    void solvesQuadraticCompletionWithoutReceivingTheCompletedSquare() {
        SearchResult result = solver.solve(
            "x^2 + 6*x + 5",
            "(x + ${shift})^2 + ${constant}",
            List.of(
                HoleDomain.integerRange("shift", -5, 5),
                HoleDomain.integerRange("constant", -10, 10)),
            8);

        assertEquals(231, result.totalAssignments());
        assertEquals(231, result.evaluatedAssignments());
        assertEquals(1, result.matchingAssignments());
        assertEquals(SearchStatus.COMPLETE_WITH_SOLUTIONS, result.status());
        assertEquals(Map.of(
            "constant", ExactRational.integer(-4),
            "shift", ExactRational.integer(3)),
            bindings(result.solutions().getFirst().bindings()));
        assertEquals(
            arithmetic.parse(result.sourceExpression()),
            arithmetic.parse(
                result.solutions().getFirst().instantiatedExpression()));
        assertTrue(solver.replay(result));
    }

    @Test
    void solvesSophieGermainCoefficientAnsatzWithBothSquareSigns() {
        SearchResult result = solver.solve(
            "x^4 + 4*y^4",
            "(x^2 + ${alpha}*y^2)^2 - (${beta}*x*y)^2",
            List.of(
                HoleDomain.integerRange("beta", -3, 3),
                HoleDomain.integerRange("alpha", -3, 3)),
            8);

        assertEquals(49, result.totalAssignments());
        assertEquals(2, result.matchingAssignments());
        assertEquals(SearchStatus.COMPLETE_WITH_SOLUTIONS, result.status());
        assertEquals(List.of(
            "alpha=2|beta=-2",
            "alpha=2|beta=2"),
            result.solutions().stream()
                .map(ExactFinitePolynomialHoleSolver.Solution::bindingKey)
                .toList());
        assertTrue(result.solutions().stream().allMatch(solution ->
            arithmetic.parse(result.sourceExpression()).equals(
                arithmetic.parse(solution.instantiatedExpression()))));
    }

    @Test
    void distinguishesCompleteNullResultsFromTruncatedSolutionSets() {
        SearchResult none = solver.solve(
            "x^2 + 1",
            "(x + ${shift})^2",
            List.of(HoleDomain.integerRange("shift", -2, 2)),
            4);
        assertEquals(SearchStatus.COMPLETE_WITHOUT_SOLUTION, none.status());
        assertEquals(0, none.matchingAssignments());
        assertTrue(none.solutions().isEmpty());

        SearchResult truncated = solver.solve(
            "x^2",
            "(${sign}*x)^2",
            List.of(HoleDomain.signs("sign")),
            1);
        assertEquals(2, truncated.evaluatedAssignments());
        assertEquals(2, truncated.matchingAssignments());
        assertEquals(1, truncated.solutions().size());
        assertEquals(
            SearchStatus.COMPLETE_SOLUTION_SET_TRUNCATED,
            truncated.status());
        assertTrue(solver.replay(truncated));
    }

    @Test
    void preservesExactRationalAndLargeIntegerValues() {
        SearchResult large = solver.solve(
            "9007199254740993*x",
            "${coefficient}*x",
            List.of(new HoleDomain(
                "coefficient",
                HoleKind.COEFFICIENT,
                List.of(
                    ExactRational.integer(9007199254740992L),
                    ExactRational.integer(9007199254740993L)))),
            4);
        assertEquals(1, large.matchingAssignments());
        assertEquals(
            "9007199254740993",
            large.solutions().getFirst().bindings().getFirst()
                .value().canonicalText());

        SearchResult rational = solver.solve(
            "x/2",
            "${factor}*x",
            List.of(new HoleDomain(
                "factor",
                HoleKind.COEFFICIENT,
                List.of(
                    new ExactRational(
                        BigInteger.ONE,
                        BigInteger.valueOf(3)),
                    new ExactRational(
                        BigInteger.ONE,
                        BigInteger.valueOf(2))))),
            4);
        assertEquals(1, rational.matchingAssignments());
        assertEquals(
            "1/2",
            rational.solutions().getFirst().bindings().getFirst()
                .value().canonicalText());
    }

    @Test
    void canonicalizesDomainOrderAndBindsItIntoTheResultIdentity() {
        SearchResult first = solver.solve(
            "x^2",
            "(${left}*${right}*x)^2",
            List.of(
                new HoleDomain(
                    "right",
                    HoleKind.SIGN,
                    List.of(
                        ExactRational.ONE,
                        ExactRational.NEGATIVE_ONE)),
                HoleDomain.signs("left")),
            8);
        SearchResult reordered = solver.solve(
            "x^2",
            "(${left}*${right}*x)^2",
            List.of(
                HoleDomain.signs("left"),
                HoleDomain.signs("right")),
            8);

        assertEquals(first.contentHash(), reordered.contentHash());
        assertEquals(List.of("left", "right"), first.holeDomains().stream()
            .map(HoleDomain::holeId)
            .toList());
        SearchResult differentTemplate = solver.solve(
            "x^2",
            "(${left}*x*${right})^2",
            first.holeDomains(),
            8);
        assertNotEquals(first.contentHash(), differentTemplate.contentHash());
        assertTrue(solver.replay(first));
    }

    @Test
    void rejectsMalformedUnboundedAndUnsupportedSearchDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> solver.solve(
            "x", "${missing}",
            List.of(HoleDomain.integerRange("other", 0, 1)), 4));
        assertThrows(IllegalArgumentException.class, () -> solver.solve(
            "x", "${broken", List.of(
                HoleDomain.integerRange("broken", 0, 1)), 4));
        assertThrows(IllegalArgumentException.class, () -> solver.solve(
            "x", "${value}", List.of(
                HoleDomain.integerRange("value", 0, 1),
                HoleDomain.integerRange("value", 2, 3)), 4));
        assertThrows(IllegalArgumentException.class, () -> solver.solve(
            "x", "x/${denominator}", List.of(
                HoleDomain.integerRange("denominator", 0, 1)), 4));
        assertThrows(IllegalArgumentException.class, () -> solver.solve(
            "x", "sin(x) + ${value}", List.of(
                HoleDomain.integerRange("value", 0, 1)), 4));
        assertThrows(IllegalArgumentException.class, () -> solver.solve(
            "x", "${value}", List.of(
                HoleDomain.integerRange("value", 0, 1)), 0));

        assertThrows(IllegalArgumentException.class, () -> solver.solve(
            "x", "${first} + ${second} + ${third}",
            List.of(
                HoleDomain.integerRange("first", 0, 99),
                HoleDomain.integerRange("second", 0, 99),
                HoleDomain.integerRange("third", 0, 99)),
            4));
        assertThrows(IllegalArgumentException.class, () ->
            HoleDomain.integerRange("range", 0, 128));
        assertThrows(IllegalArgumentException.class, () ->
            new HoleDomain(
                "sign",
                HoleKind.SIGN,
                List.of(ExactRational.ZERO)));
        assertThrows(IllegalArgumentException.class, () ->
            new HoleDomain(
                "huge",
                HoleKind.COEFFICIENT,
                List.of(ExactRational.integer(
                    BigInteger.ONE.shiftLeft(513)))));
    }

    @Test
    void rejectsForgedResultStructureAndReplayMismatch() {
        SearchResult result = solver.solve(
            "x^2",
            "(${sign}*x)^2",
            List.of(HoleDomain.signs("sign")),
            2);
        assertThrows(IllegalArgumentException.class, () ->
            new SearchResult(
                result.solverId(),
                result.solverRevisionHash(),
                result.sourceExpression(),
                result.ansatzTemplate(),
                result.holeDomains(),
                result.totalAssignments(),
                result.evaluatedAssignments() - 1,
                result.matchingAssignments(),
                result.retainedSolutionLimit(),
                result.status(),
                result.solutions()));
        SearchResult different = solver.solve(
            "x^2 + 1",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 2)),
            2);
        assertFalse(result.equals(different));
        assertTrue(solver.replay(different));
    }

    private static Map<String, ExactRational> bindings(
        List<Binding> bindings
    ) {
        return bindings.stream().collect(Collectors.toMap(
            Binding::holeId,
            Binding::value));
    }
}
