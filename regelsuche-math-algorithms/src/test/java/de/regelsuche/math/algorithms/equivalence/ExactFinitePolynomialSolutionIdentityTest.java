package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.Binding;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleKind;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.Solution;
import de.regelsuche.scalar.ExactRational;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ExactFinitePolynomialSolutionIdentityTest {
    @Test
    void bindsHoleKindIntoTheSolutionContentIdentity() {
        Solution sign = solution(new Binding(
            "unit",
            HoleKind.SIGN,
            ExactRational.ONE));
        Solution coefficient = solution(new Binding(
            "unit",
            HoleKind.COEFFICIENT,
            ExactRational.ONE));

        assertEquals(sign.bindingKey(), coefficient.bindingKey());
        assertNotEquals(sign.contentHash(), coefficient.contentHash());
    }

    @Test
    void canonicalizesBindingOrderWithoutChangingReadableKeys() {
        Binding alpha = new Binding(
            "alpha",
            HoleKind.COEFFICIENT,
            ExactRational.integer(2));
        Binding beta = new Binding(
            "beta",
            HoleKind.SIGN,
            ExactRational.NEGATIVE_ONE);
        Solution first = new Solution(
            List.of(beta, alpha),
            "x^2",
            "x^2");
        Solution reordered = new Solution(
            List.of(alpha, beta),
            "x^2",
            "x^2");

        assertEquals("alpha=2|beta=-1", first.bindingKey());
        assertEquals(first.bindingKey(), reordered.bindingKey());
        assertEquals(first.contentHash(), reordered.contentHash());
    }

    @Test
    void keepsAdjacentValuesAboveBinary64PrecisionDistinct() {
        Solution lower = solution(new Binding(
            "value",
            HoleKind.COEFFICIENT,
            ExactRational.integer(9_007_199_254_740_992L)));
        Solution higher = solution(new Binding(
            "value",
            HoleKind.COEFFICIENT,
            ExactRational.integer(9_007_199_254_740_993L)));

        assertNotEquals(lower.bindingKey(), higher.bindingKey());
        assertNotEquals(lower.contentHash(), higher.contentHash());
    }

    @Test
    void advancesBothSolverAndPersistedSolutionIdentityRevisions() {
        Solution current = solution(new Binding(
            "unit",
            HoleKind.SIGN,
            ExactRational.ONE));

        assertEquals(
            "regelsuche.exact-finite-polynomial-solution-identity/v2",
            ExactFinitePolynomialHoleSolver.SOLUTION_IDENTITY_REVISION);
        assertNotEquals(
            legacySolverRevision(),
            ExactFinitePolynomialHoleSolver.REVISION_HASH);
        assertNotEquals(
            legacySolutionHash(current),
            current.contentHash());
    }

    private static Solution solution(Binding binding) {
        return new Solution(
            List.of(binding),
            "x + 1",
            "x+1");
    }

    private static String legacySolverRevision() {
        return ExactFinitePolynomialHoleSolver.hash(lengthPrefixed(
            ExactFinitePolynomialHoleSolver.SOLVER_ID,
            "source-exact-polynomial-arithmetic",
            "complete-finite-cartesian-enumeration",
            "coefficient-and-sign-holes",
            "unsupported-instantiation-fails-closed"));
    }

    private static String legacySolutionHash(Solution solution) {
        return ExactFinitePolynomialHoleSolver.hash(lengthPrefixed(
            solution.bindingKey(),
            solution.instantiatedExpression(),
            solution.exactNormalForm()));
    }

    private static String lengthPrefixed(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            result.append(value.getBytes(StandardCharsets.UTF_8).length)
                .append(':')
                .append(value);
        }
        return result.toString();
    }
}
