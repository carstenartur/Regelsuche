package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.polynomial.DynamicAlgebraEvaluation.*;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicAlgebraEvaluationTest {
    private static final IterationLimits LIMITS = new IterationLimits(2_000_000, 100_000, 5000);
    private static final Literal T = new Literal(poly(0,1));
    private static final Literal T_MINUS_ONE = new Literal(poly(-1,1));

    @Test void inverseSplitsAndPreservesUndefinedComponent() {
        var req = new Request(5, poly(-1,0,1), new Inverse(T_MINUS_ONE));
        var cert = evaluate(req);
        assertEquals(2, cert.branches().size());
        var zero = branch(cert, poly(4,1));
        var unit = branch(cert, poly(1,1));
        assertTrue(zero.value().isEmpty());
        assertEquals(Optional.of(poly(2)), unit.value());
        assertEquals(List.of(new Zero()), zero.decisions());
        assertEquals(List.of(new Unit(poly(2))), unit.decisions());
        assertEquals(Status.VERIFIED, verify(req, cert).status());
    }

    @Test void nestedBranchesAgreeWithEveryFieldElementAndUnselectedInverseIsLazy() {
        for (int prime : List.of(2,3,5,7)) {
            var coefficients = new BigInteger[prime + 1];
            Arrays.fill(coefficients, BigInteger.ZERO);
            coefficients[1] = BigInteger.ONE.negate();
            coefficients[prime] = BigInteger.ONE;
            // (if t==0 then 3 else 1/t) + (if t==1 then 1/0 else t*(t-1)).
            var expr = new Add(new IfZero(T, new Literal(poly(3)), new Inverse(T)),
                new IfZero(T_MINUS_ONE, new Inverse(new Literal(poly())), new Product(T, T_MINUS_ONE)));
            var req = new Request(prime, List.of(coefficients), expr);
            var cert = evaluate(req);
            for (int x = 0; x < prime; x++) {
                final int root = x;
                var valid = cert.branches().stream().filter(b -> at(b.factor(), root, prime) == 0).toList();
                assertEquals(1, valid.size());
                if (x == 1) assertTrue(valid.getFirst().value().isEmpty());
                else {
                    int reciprocal = x == 0 ? 3 : BigInteger.valueOf(x).modInverse(BigInteger.valueOf(prime)).intValueExact();
                    assertEquals(Math.floorMod(reciprocal + x*(x-1), prime),
                        at(valid.getFirst().value().orElseThrow(), x, prime));
                }
            }
        }
    }

    @Test void irreducibleNonlinearComponentSupportsFullPolynomialInverseAndProjectedWitnesses() {
        // T*(T²+2) over F5; T²+2 is irreducible. T+1 is a unit globally.
        var expr = new Add(new Inverse(new Literal(poly(1,1))), new IfZero(T, T, new Inverse(T)));
        var req = new Request(5, poly(0,2,0,1), expr);
        var cert = evaluate(req);
        assertEquals(Optional.of(poly(1)), branch(cert, poly(0,1)).value());
        // 1/(T+1)=(1-T)/3=2+3T, 1/T=2T; sum=2.
        assertEquals(Optional.of(poly(2)), branch(cert, poly(2,0,1)).value());
    }

    @Test void rejectsChangedSourcesCoverageFalseInversesAndUnusedDecisions() {
        var req = new Request(5, poly(-1,0,1), new Inverse(T_MINUS_ONE));
        var cert = evaluate(req);
        var zero = branch(cert, poly(4,1));
        var unit = branch(cert, poly(1,1));
        var wrong = new Branch(unit.factor(), unit.value(), List.of(new Unit(poly(3))));
        for (var branches : List.of(List.of(zero), List.of(unit,unit), List.of(zero,wrong),
                List.of(zero, new Branch(unit.factor(), Optional.empty(), unit.decisions())),
                List.of(zero, new Branch(unit.factor(), Optional.of(poly(3)), unit.decisions())),
                List.of(zero, new Branch(unit.factor(), unit.value(), List.of(new Zero()))),
                List.of(zero, new Branch(unit.factor(), unit.value(), List.of(new Unit(poly(2)),new Zero()))),
                List.of(new Branch(poly(3,1), zero.value(), zero.decisions()), unit))) {
            assertEquals(Status.REJECTED, verify(req, new Certificate(req, branches)).status());
        }
        var changed = new Request(5, req.modulus(), new Add(req.expression(), new Literal(poly())));
        assertEquals(Status.REJECTED, verify(changed, cert).status());
        var mixed = new Branch(poly(4,0,1), Optional.empty(), List.of(new Zero()));
        assertEquals(Status.REJECTED, verify(req, new Certificate(req, List.of(mixed))).status());
    }

    @Test void fixedFactorReplayAcceptsAnIndependentRefinementAndRejectsForgedDecisions() {
        var req = new Request(5, poly(4,0,1), T);
        assertEquals(1, evaluate(req).branches().size());
        // No factor-search trace is needed when the caller supplies a valid finer partition.
        var linear = List.of(new Branch(poly(4,1), Optional.of(poly(1)), List.of()),
            new Branch(poly(1,1), Optional.of(poly(4)), List.of()));
        assertEquals(Status.VERIFIED, verify(req, new Certificate(req, linear)).status());
        var unsafeTest = new Request(5, req.modulus(), new IfZero(T_MINUS_ONE, T, T));
        for (var hints : List.of(List.<Decision>of(new Unit(poly(1))), List.<Decision>of(new Zero()))) {
            var bad = new Branch(req.modulus(), Optional.of(poly(0,1)), hints);
            assertEquals(Status.REJECTED, verify(unsafeTest, new Certificate(unsafeTest, List.of(bad))).status());
        }
        assertEquals(Status.REJECTED, verify(new Request(7,req.modulus(),T),
            new Certificate(req, linear)).status());
    }

    @Test void independentlyInterpretsFortyRandomTreesAtEveryBaseFieldElement() {
        var random = new Random(142);
        for (int sample = 0; sample < 40; sample++) {
            int p = sample % 2 == 0 ? 3 : 5;
            var modulus = p == 3 ? poly(0,-1,0,1) : poly(0,-1,0,0,0,1);
            var expr = tree(random, 3);
            var req = new Request(p, modulus, expr);
            var cert = evaluate(req);
            for (int root = 0; root < p; root++) {
                final int t = root;
                var component = cert.branches().stream().filter(b -> at(b.factor(),t,p) == 0).toList();
                assertEquals(1, component.size());
                assertEquals(Optional.ofNullable(inField(expr, t, p)),
                    component.getFirst().value().map(v -> at(v,t,p)));
            }
        }
    }

    private static Expr tree(Random random, int depth) {
        if (depth == 0) return new Literal(poly(random.nextInt(7)-3, random.nextInt(7)-3));
        return switch (random.nextInt(4)) {
            case 0 -> new Add(tree(random,depth-1), tree(random,depth-1));
            case 1 -> new Product(tree(random,depth-1), tree(random,depth-1));
            case 2 -> new Inverse(tree(random,depth-1));
            default -> new IfZero(tree(random,depth-1), tree(random,depth-1), tree(random,depth-1));
        };
    }

    private static Integer inField(Expr expr, int t, int p) {
        return switch (expr) {
            case Literal literal -> Math.floorMod(at(literal.coefficients(),t,p), p);
            case Inverse inv -> {
                Integer v = inField(inv.operand(),t,p);
                if (v == null || v == 0) yield null;
                yield BigInteger.valueOf(v).modInverse(BigInteger.valueOf(p)).intValueExact();
            }
            case IfZero test -> {
                Integer v = inField(test.condition(),t,p);
                if (v == null) yield null;
                yield inField(v == 0 ? test.whenZero() : test.whenUnit(),t,p);
            }
            case Add sum -> {
                Integer a = inField(sum.left(),t,p), b = inField(sum.right(),t,p);
                if (a == null || b == null) yield null;
                yield (a+b)%p;
            }
            case Product product -> {
                Integer a = inField(product.left(),t,p), b = inField(product.right(),t,p);
                if (a == null || b == null) yield null;
                yield (a*b)%p;
            }
        };
    }

    @Test void domainValidationRejectsCompositePrimeConstantAndRepeatedFactors() {
        assertEquals(Status.REJECTED, run(new Request(9,poly(0,1),T)).status());
        assertEquals(Status.REJECTED, run(new Request(5,poly(1),T)).status());
        assertEquals(Status.REJECTED, run(new Request(5,poly(1,-2,1),T)).status());
        assertEquals(Status.REJECTED, run(new Request(2,poly(0,0,1),T)).status());
        assertEquals(poly(1,1), evaluate(new Request(5,poly(2,2),T)).branches().getFirst().factor());
    }

    @Test void resourceStopsDiscardAllBranchesIncludingDeepOrSharedExpressions() {
        var req = new Request(5,poly(-1,0,1),new Inverse(T_MINUS_ONE));
        var cert = evaluate(req);
        assertEquals("CANCELLED", DynamicAlgebraEvaluation.evaluate(req, LIMITS, () -> true).reason());
        assertEquals("WORK_LIMIT", DynamicAlgebraVerifier.verify(req, cert, LIMITS.withMaximumWork(1), () -> false).reason());
        var polls = new AtomicInteger();
        var stop = DynamicAlgebraEvaluation.evaluate(req, LIMITS, () -> polls.incrementAndGet() > 40);
        assertEquals("CANCELLED", stop.reason());
        assertTrue(stop.value().isEmpty());
        assertEquals("TERM_LIMIT", DynamicAlgebraEvaluation.evaluate(req, new IterationLimits(1000, 3, 5000),
            () -> false).reason());
        var huge = new Literal(List.of(BigInteger.ONE.shiftLeft(65_536)));
        assertEquals("RUNTIME_BIT_LIMIT", run(new Request(5, req.modulus(), huge)).reason());
        Expr deep = T;
        for (int i = 0; i < 3000; i++) deep = new Inverse(deep);
        assertEquals(Status.INCONCLUSIVE, run(new Request(5, req.modulus(), deep)).status());
        Expr shared = T;
        for (int i = 0; i < 30; i++) shared = new Add(shared, shared);
        assertEquals(Status.INCONCLUSIVE, run(new Request(5, req.modulus(), shared)).status());
        assertEquals("WORK_LIMIT", DynamicAlgebraEvaluation.evaluate(new Request(Integer.MAX_VALUE,
            poly(0,1), T), LIMITS.withMaximumWork(100), () -> false).reason());
    }

    @Test void timeoutAndWorkShortfallBeforeIndependentVerificationDiscardTheEntireCertificate() {
        var req = new Request(5, poly(-1,0,1), new Inverse(T_MINUS_ONE));
        var full = run(req);
        var stopped = DynamicAlgebraEvaluation.evaluate(req, LIMITS.withMaximumWork(full.work()-1), () -> false);
        assertEquals("WORK_LIMIT", stopped.reason());
        assertTrue(stopped.value().isEmpty());
        var timed = DynamicAlgebraVerifier.verify(req, full.value().orElseThrow(), new IterationLimits(100_000,1000,1),
            () -> {
                long begin = System.nanoTime();
                while (System.nanoTime() - begin < 2_000_000) Thread.onSpinWait();
                return false;
            });
        assertEquals("TIME_LIMIT", timed.reason());
        assertTrue(timed.value().isEmpty());
    }

    private static int at(List<BigInteger> coeff, int x, int p) {
        int sum = 0;
        for (var c : coeff.reversed()) sum = (sum * x + c.intValueExact()) % p;
        return sum;
    }

    private static List<BigInteger> poly(int... coeff) {
        return Arrays.stream(coeff).mapToObj(i -> BigInteger.valueOf(i)).toList();
    }

    private static Branch branch(Certificate cert, List<BigInteger> factor) {
        return cert.branches().stream().filter(b -> b.factor().equals(factor)).findFirst().orElseThrow();
    }

    private static Certificate evaluate(Request req) { return run(req).value().orElseThrow(); }
    private static Result<Certificate> run(Request req) {
        return DynamicAlgebraEvaluation.evaluate(req, LIMITS, () -> false);
    }
    private static Result<Boolean> verify(Request req, Certificate cert) {
        return DynamicAlgebraVerifier.verify(req, cert, LIMITS, () -> false);
    }
}
