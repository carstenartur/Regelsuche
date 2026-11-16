package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectiveHandoffTest {
    private static final IterationLimits LIMITS = new IterationLimits(100_000, 256, 30_000);
    private static final UUID SCOPE = new UUID(0, 1);
    private static final Expr X = variable(1), Y = variable(2), Z = variable(3);
    private static final Expr SCALE = binary(Z, ADD, new NumberExpr(1));
    private static final Expr EXPANDED_SQUARE = binary(binary(power(Z, 2), ADD,
        binary(new NumberExpr(2), MUL, Z)), ADD, new NumberExpr(1));
    private static final List<Expr> AFFINE = List.of(
        binary(X, DIV, SCALE), binary(Y, DIV, EXPANDED_SQUARE), binary(binary(X, ADD, Y), DIV, power(SCALE, 4)));

    @Test
    void discoversAndReplaysThreeCoordinatesWithDifferentWeights() {
        var found = ProjectiveHandoff.search(AFFINE, List.of(), LIMITS, () -> false);
        var proof = found.verified().orElseThrow();
        assertEquals(List.of(1, 2, 4), proof.candidate().weights());
        assertTrue(ProjectiveHandoff.verify(AFFINE, proof.candidate(), LIMITS, () -> false).verified().isPresent());
        var inverseCount = new AtomicInteger();
        var carried = proof.carry(integer(3), List.of(integer(6), integer(4), integer(10)),
            value -> value.signum() != 0).orElseThrow();
        var direct = List.of(integer(6).divide(integer(3)), integer(4).divide(integer(9)),
            integer(10).divide(integer(81)));
        assertEquals(direct, carried.materialize(value -> {
                inverseCount.incrementAndGet();
                return ExactRational.ONE.divide(value);
            }, ExactRational::multiply));
        assertEquals(1, inverseCount.get());
    }

    @Test
    void rejectsMismatchedIdentitiesOrUnprovenDivisors() {
        var valid = ProjectiveHandoff.search(AFFINE, List.of(SCALE), LIMITS, () -> false)
            .verified().orElseThrow().candidate();
        assertTrue(ProjectiveHandoff.verify(AFFINE,
            new ProjectiveHandoff.Candidate(SCALE, valid.weights(), List.of(Y, X, binary(X, ADD, Y))),
            LIMITS, () -> false).verified().isEmpty());
        assertTrue(ProjectiveHandoff.verify(AFFINE,
            new ProjectiveHandoff.Candidate(new NumberExpr(0), valid.weights(), valid.numerators()),
            LIMITS, () -> false).verified().isEmpty());
        assertTrue(ProjectiveHandoff.search(List.of(binary(X, DIV, SCALE), binary(Y, DIV, Z)),
            List.of(), LIMITS, () -> false).verified().isEmpty());
        var zeroDenominator = ProjectiveHandoff.search(List.of(binary(X, DIV, binary(Z, SUB, Z))),
            List.of(SCALE), LIMITS, () -> false);
        assertEquals("ZERO_PROJECTIVE_DENOMINATOR", zeroDenominator.reason());
        assertTrue(zeroDenominator.verified().isEmpty());
        assertTrue(ProjectiveHandoff.search(List.of(binary(X, DIV, binary(new NumberExpr(2), MUL, SCALE)), binary(Y, DIV, SCALE)),
            List.of(SCALE), LIMITS, () -> false).verified().isEmpty());
    }

    @Test
    void acceptsSignedDenominatorsAndPaddedWeightsWithTheSameUnitDomain() {
        var negativeScale = binary(new NumberExpr(-1), MUL, SCALE);
        var affine = List.of(binary(X, DIV, negativeScale), Y);
        var weights = List.of(3, 2);
        var numerators = List.of(binary(binary(new NumberExpr(-1), MUL, X), MUL, power(SCALE, 2)),
            binary(Y, MUL, power(SCALE, 2)));
        var candidate = new ProjectiveHandoff.Candidate(SCALE, weights, numerators);
        var proof = ProjectiveHandoff.verify(affine, candidate, LIMITS, () -> false).verified().orElseThrow();
        var carried = proof.carry(integer(3), List.of(integer(-54), integer(36)), value -> value.signum() != 0)
            .orElseThrow();
        assertEquals(List.of(integer(-2), integer(4)),
            carried.materialize(ExactRational.ONE::divide, ExactRational::multiply));
        assertTrue(ProjectiveHandoff.verify(affine,
            new ProjectiveHandoff.Candidate(SCALE, List.of(0, 2), numerators), LIMITS, () -> false).verified().isEmpty());
    }

    @Test
    void failsClosedOnPartialWorkAndRuntimeNonunits() {
        assertTrue(ProjectiveHandoff.search(AFFINE, List.of(), LIMITS.withMaximumWork(8), () -> false).verified().isEmpty());
        assertEquals("CANCELLED", ProjectiveHandoff.search(AFFINE, List.of(), LIMITS, () -> true).reason());
        assertEquals("PROJECTIVE_CANDIDATE_LIMIT",
            ProjectiveHandoff.search(AFFINE, List.of(X), LIMITS, 1, () -> false).reason());
        var proof = ProjectiveHandoff.search(AFFINE, List.of(), LIMITS, () -> false).verified().orElseThrow();
        assertTrue(proof.carry(0, List.of(1, 2, 3), value -> value % 2 != 0).isEmpty());
        assertTrue(proof.carry(2, List.of(1, 2, 3), value -> value % 2 != 0).isEmpty());
        var replay = ProjectiveHandoff.verify(AFFINE, proof.candidate(), LIMITS, () -> false);
        var interruptedReplay = ProjectiveHandoff.verify(AFFINE, proof.candidate(),
            LIMITS.withMaximumWork(replay.work() - 1), () -> false);
        assertEquals("WORK_LIMIT", interruptedReplay.reason());
        assertTrue(interruptedReplay.verified().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> proof.carry(3, List.of(1, 2), value -> true));
    }

    @Test
    void passesAnExactProjectiveVectorThroughASecondStage() {
        var first = ProjectiveHandoff.search(AFFINE, List.of(), LIMITS, () -> false).verified().orElseThrow();
        var w = variable(4);
        var secondExpressions = List.of(binary(binary(new NumberExpr(2), MUL, X), DIV, Z),
            binary(binary(new NumberExpr(3), MUL, Y), DIV, power(Z, 2)),
            binary(binary(new NumberExpr(5), MUL, w), DIV, power(Z, 4)));
        var second = ProjectiveHandoff.search(secondExpressions, List.of(), LIMITS, () -> false)
            .verified().orElseThrow();
        var state = first.carry(integer(3), List.of(integer(6), integer(4), integer(10)), value -> value.signum() != 0)
            .orElseThrow();
        assertEquals(second.candidate().weights(), state.weights());
        var factors = List.of(integer(2), integer(3), integer(5));
        var scaledNumerators = IntStream.range(0, factors.size())
            .mapToObj(i -> factors.get(i).multiply(state.numerators().get(i))).toList();
        var outgoing = second.carry(state.scale(), scaledNumerators, value -> value.signum() != 0).orElseThrow();
        var direct = List.of(integer(6).divide(integer(3)).multiply(factors.get(0)),
            integer(4).divide(integer(9)).multiply(factors.get(1)),
            integer(10).divide(integer(81)).multiply(factors.get(2)));
        assertEquals(direct, outgoing.materialize(ExactRational.ONE::divide, ExactRational::multiply));
    }

    @Test
    void accountsForAChainWithOneAffineBoundary() {
        var proof = ProjectiveHandoff.search(AFFINE, List.of(), LIMITS, () -> false).verified().orElseThrow();
        var usage = List.of(new ProjectiveHandoff.ChainUse(proof, 3, false, 5),
            new ProjectiveHandoff.ChainUse(proof, 2, true, 2));
        var favorable = ProjectiveHandoff.estimateChain(usage, new ProjectiveHandoff.Costs(12, 1, 1));
        assertEquals(5, favorable.originalInversions());
        assertEquals(1, favorable.remainingInversions());
        assertEquals(4, favorable.savedInversions());
        assertEquals(13, favorable.additionalMultiplications());
        assertEquals(2, favorable.unitChecks());
        assertEquals(33, favorable.netSaving().longValueExact());
        assertTrue(favorable.profitable());
        var unfavorable = ProjectiveHandoff.estimateChain(usage, new ProjectiveHandoff.Costs(2, 1, 1));
        assertEquals(-7, unfavorable.netSaving().longValueExact());
        assertFalse(unfavorable.profitable());
        assertThrows(IllegalArgumentException.class, () -> new ProjectiveHandoff.ChainUse(proof, 0, true, 0));
    }

    private static Expr variable(long ordinal) {
        return VariableExpr.scoped(new SymbolId(SCOPE, ordinal));
    }

    private static Expr power(Expr base, int exponent) {
        return binary(base, POW, new NumberExpr(exponent));
    }

    private static Expr binary(Expr left, BinaryOperator operator, Expr right) {
        return new BinaryExpr(left, operator, right);
    }

    private static ExactRational integer(long value) {
        return ExactRational.integer(value);
    }
}
