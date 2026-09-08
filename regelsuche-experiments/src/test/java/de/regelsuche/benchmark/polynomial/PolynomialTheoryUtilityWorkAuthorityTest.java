package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactNestedFactorizationTransformationPipeline;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityWorkAuthorityTest {
    @Test
    void chargesTheUnchangedProjectionBeforeCommittingAnAtomicLedger() {
        var authority = new PolynomialTheoryUtilityWorkAuthority(input(8, 8, 2));
        authority.consume(new PolynomialWorkLedger(Map.of(
            "projection.revalidation-literal-bindings", 1L,
            "projection.revalidation-literal-code-units", 516L)));
        assertEquals(3, authority.work().matchingWork());
        authority.consume("new-native-stage", 2);
        var retained = authority.ledger();
        assertThrows(PolynomialWorkAuthority.LimitReached.class,
            () -> authority.consume("new-native-stage", 1));
        assertEquals(retained, authority.ledger());
        assertEquals(2, authority.work().factorizationWork());
        assertThrows(IllegalArgumentException.class,
            () -> authority.consume("projection.revalidation-literal-bindings", 1));
        assertEquals(retained, authority.ledger());
    }

    @Test
    void roundUpOccursOnTheCumulativeStageAndNeverResetsAcrossInvocations() {
        var authority = new PolynomialTheoryUtilityWorkAuthority(input(4, 4, 4));
        for (int index = 0; index < 128; index++) {
            authority.consume("nested.replay-structural-hash", 1);
        }
        assertEquals(1, authority.work().mechanicalWork());
        authority.consumePrimitive(4);
        assertThrows(PolynomialWorkAuthority.LimitReached.class,
            () -> authority.consumePrimitive(1));
        assertEquals(authority.work(), authority.projection().work());
    }

    @Test
    void theSameNativePipelineCanCompleteUnderAnExplicitComponentTestAuthority() {
        // This is a component test, not a substituted frozen matrix row.
        var authority = new PolynomialTheoryUtilityWorkAuthority(input(1_000_000, 1_000_000, 1_000_000));
        var result = execute(authority);
        assertTrue(result.transformed(), result.detailCode());
        assertLedgerEquals(result.totalWork(), authority.ledger());
    }

    @Test
    void exhaustedPrefixesAreRetainedAndRemainWithinBothBudgets() {
        for (int mechanical : List.of(1, 8, 22, 64, 128, 256, 512, 1024)) {
            var authority = new PolynomialTheoryUtilityWorkAuthority(input(mechanical, mechanical, mechanical));
            var result = execute(authority);
            assertLedgerEquals(result.totalWork(), authority.ledger());
            assertTrue(authority.work().mechanicalWork() <= mechanical);
            assertTrue(authority.work().factorizationWork() <= mechanical);
            assertEquals(authority.work(), authority.projection().work());
        }
    }

    private static ExactNestedFactorizationTransformationPipeline.Result execute(
            PolynomialTheoryUtilityWorkAuthority authority) {
        var parsed = new ExpressionParser().parseExactTerm("x^2-1");
        var position = new TreePosition(List.of(), ExpressionFormatter.format(parsed.expression()));
        return new ExactNestedFactorizationTransformationPipeline(authority).transform(
            parsed, position, NativeUnivariateFactorizationEngine.rationals(
                de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationPolicy.boundedDefaults()), 0);
    }

    private static void assertLedgerEquals(PolynomialWorkLedger first, PolynomialWorkLedger second) {
        var expected = new TreeMap<>(first.stages());
        var actual = new TreeMap<>(second.stages());
        expected.values().removeIf(value -> value == 0);
        actual.values().removeIf(value -> value == 0);
        assertEquals(expected, actual);
    }

    private static PolynomialTheoryUtilityExecutionInput input(int primitive, int mechanical, int factorization) {
        var frozen = PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(value -> value.profileId().equals("ON_DEMAND_VERIFIED_FACTORIZATION"))
            .findFirst().orElseThrow();
        return new PolynomialTheoryUtilityExecutionInput(frozen.inputId(), frozen.rowId(), frozen.runId(),
            frozen.caseId(), frozen.profileId(), frozen.checkpointId(), frozen.adapterId(),
            primitive, mechanical, factorization, frozen.inputStatus());
    }
}
