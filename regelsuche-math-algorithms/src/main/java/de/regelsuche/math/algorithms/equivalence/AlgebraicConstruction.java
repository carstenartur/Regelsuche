package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExactExpressionFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Finite scalar constructions using the existing exact polynomial and rational checkers.
 * A chosen auxiliary is data, never an unbound rewrite placeholder. Products that introduce
 * division require the caller's existing nonzero premises; exact polynomial splitting does not.
 */
public final class AlgebraicConstruction {
    public static final String REVISION = "regelsuche.algebraic-construction/v1";
    public enum Operation {
        ADDITIVE_COMPLEMENT, ADDITIVE_SPLIT, MULTIPLICATIVE_COMPLEMENT,
        MULTIPLICATIVE_SPLIT, EXACT_FACTOR_SPLIT, FACTOR_TRANSFER
    }
    public enum Status { GENERATED, NO_MATCH, MISSING_ASSUMPTION, UNSUPPORTED, WORK_LIMIT }
    public record Request(Operation operation, String source, String auxiliary,
            List<String> assumptions, long maximumWork) {
        public Request {
            Objects.requireNonNull(operation, "operation");
            boundedText(source); boundedText(auxiliary);
            assumptions = List.copyOf(assumptions);
            if (assumptions.size() > 16 || assumptions.stream().anyMatch(s -> s.length() > 512))
                throw new IllegalArgumentException("assumption bound exceeded");
            if (maximumWork < 1) throw new IllegalArgumentException("positive construction budget required");
        }
    }
    public record Result(Status status, Optional<String> expression, long work, String detail) {
        public Result {
            Objects.requireNonNull(status); Objects.requireNonNull(expression); Objects.requireNonNull(detail);
            if (work < 0 || expression.isPresent() != (status == Status.GENERATED))
                throw new IllegalArgumentException("inconsistent construction result");
        }
        public boolean generated() { return status == Status.GENERATED; }
    }
    public record Exploration(List<Result> results, long work) {
        public Exploration { results = List.copyOf(results); if (work < 0) throw new IllegalArgumentException("negative work"); }
    }

    /** Failed and atomically over-budget attempts keep their work, but issue no candidate. */
    public Result construct(Request request) {
        Objects.requireNonNull(request, "request");
        var work = new Work(request.maximumWork());
        try {
            var arithmetic = new ExactResidualPolynomialArithmetic(work::add);
            work.add(1);
            Polynomial source = boundedPolynomial(arithmetic.parse(request.source()));
            Polynomial auxiliary = boundedPolynomial(arithmetic.parse(request.auxiliary()));
            String candidate = candidate(request, source, auxiliary, arithmetic, work);
            if (candidate == null) return rejected(Status.NO_MATCH, work, "NO_EXACT_CONSTRUCTION");
            work.add(candidate.length());
            // Conservative declared charge for this bounded rational audit. This is not a CPU/heap receipt.
            long terms = source.termCount() + (long) auxiliary.termCount() + 1;
            work.add(Math.multiplyExact(16, Math.multiplyExact(terms, terms)));
            var proof = new RationalFunctionNormalFormEquivalenceService()
                .evaluate(request.source(), candidate, request.assumptions());
            if (proof.equivalent()) return new Result(Status.GENERATED, Optional.of(candidate), work.units, "EXACT_RATIONAL_CONSTRUCTION");
            Status status = proof.status() == RationalFunctionNormalFormEquivalenceService.Status.MISSING_ASSUMPTION
                ? Status.MISSING_ASSUMPTION : Status.UNSUPPORTED;
            return rejected(status, work, proof.status().name());
        } catch (WorkLimit limit) {
            return rejected(Status.WORK_LIMIT, work, "CONSTRUCTION_WORK_LIMIT");
        } catch (IllegalArgumentException | ArithmeticException unsupported) {
            return rejected(Status.UNSUPPORTED, work, "OUTSIDE_BOUNDED_SCALAR_DOMAIN");
        }
    }

    /** Source subexpressions and bounded cross-products; no target expression or historical name. */
    public Exploration explore(Operation operation, String source, List<String> assumptions,
            int maximumAttempts, long maximumWork) {
        if (maximumAttempts < 1 || maximumAttempts > 32 || maximumWork < 1)
            throw new IllegalArgumentException("finite positive construction limits required");
        boundedText(source);
        var results = new ArrayList<Result>();
        var work = new Work(maximumWork);
        try {
            for (String auxiliary : auxiliaries(source, maximumAttempts, work)) {
                if (work.units >= maximumWork) break;
                var result = construct(new Request(operation, source, auxiliary, assumptions, maximumWork - work.units));
                results.add(result); work.add(result.work());
            }
        } catch (WorkLimit limited) {
            // No unperformed attempt or successful candidate is invented at the cutoff.
        } catch (IllegalArgumentException unsupported) {
            results.add(new Result(Status.UNSUPPORTED, Optional.empty(), 0, "UNSUPPORTED_AUXILIARY_SOURCE"));
        }
        return new Exploration(results, work.units);
    }

    private static List<String> auxiliaries(String source, int limit, Work work) {
        var arithmetic = new ExactResidualPolynomialArithmetic(work::add);
        boundedPolynomial(arithmetic.parse(source));
        var parsed = arithmetic.exactTerm(source);
        var terms = new LinkedHashSet<String>(); terms.add("2");
        var pending = new ArrayDeque<Expr>(); pending.add(parsed.expression());
        while (!pending.isEmpty() && terms.size() < limit) {
            Expr term = pending.removeFirst(); work.add(1);
            String text = ExactExpressionFormatter.format(term, parsed); work.add(text.length()); terms.add(text);
            if (term instanceof BinaryExpr binary) { pending.addLast(binary.left()); pending.addLast(binary.right()); }
        }
        var atoms = List.copyOf(terms);
        for (int left = 0; left < atoms.size() && terms.size() < limit; left++) {
            for (int right = left; right < atoms.size() && terms.size() < limit; right++) {
                work.add(1); terms.add("2*(" + atoms.get(left) + ")*(" + atoms.get(right) + ")");
            }
        }
        return List.copyOf(terms);
    }

    private static String candidate(Request request, Polynomial source, Polynomial auxiliary,
            ExactResidualPolynomialArithmetic arithmetic, Work work) {
        String s = "(" + arithmetic.syntax(request.source()) + ")";
        String a = "(" + arithmetic.syntax(request.auxiliary()) + ")";
        work.add(s.length() + (long) a.length());
        return switch (request.operation()) {
            case ADDITIVE_COMPLEMENT -> "(" + s + "+" + a + ")-" + a;
            case ADDITIVE_SPLIT -> a + "+(" + source.subtract(auxiliary).toCanonicalString() + ")";
            case MULTIPLICATIVE_COMPLEMENT -> auxiliary.isZero() ? null : "(" + s + "*" + a + ")/" + a;
            case MULTIPLICATIVE_SPLIT -> auxiliary.isZero() ? null : a + "*(" + s + "/" + a + ")";
            case EXACT_FACTOR_SPLIT -> exactFactor(source, auxiliary, a, work);
            case FACTOR_TRANSFER -> transfer(request, auxiliary, a, arithmetic);
        };
    }

    private static String exactFactor(Polynomial source, Polynomial factor, String factorText, Work work) {
        if (factor.termCount() != 1) return null;
        var divisor = factor.terms().entrySet().iterator().next();
        Polynomial quotient = Polynomial.zero();
        for (var term : source.terms().entrySet()) {
            work.add(1);
            if (!divisor.getKey().divides(term.getKey())) return null;
            quotient = quotient.addTerm(term.getKey().divideBy(divisor.getKey()), term.getValue().divide(divisor.getValue()));
        }
        return factorText + "*(" + quotient.toCanonicalString() + ")";
    }

    private static String transfer(Request request, Polynomial auxiliary, String a, ExactResidualPolynomialArithmetic arithmetic) {
        var parsed = arithmetic.exactTerm(request.source());
        if (auxiliary.isZero() || !(parsed.expression() instanceof BinaryExpr product)
                || product.operator() != BinaryOperator.MUL) return null;
        String left = ExactExpressionFormatter.format(product.left(), parsed);
        String right = ExactExpressionFormatter.format(product.right(), parsed);
        return "((" + left + ")*" + a + ")*((" + right + ")/" + a + ")";
    }

    private static Polynomial boundedPolynomial(Polynomial value) {
        if (value.termCount() > 32 || value.totalDegree() > 12 || value.variables().size() > 4)
            throw new IllegalArgumentException("construction polynomial bound exceeded");
        return value;
    }
    private static void boundedText(String value) {
        if (value == null || value.isBlank() || value.length() > 4096)
            throw new IllegalArgumentException("bounded scalar expression required");
    }
    private static Result rejected(Status status, Work work, String detail) {
        return new Result(status, Optional.empty(), work.units, detail);
    }
    private static final class Work {
        private final long maximum;
        private long units;
        private Work(long maximum) { this.maximum = maximum; }
        private void add(long value) {
            units = Math.addExact(units, value);
            if (units > maximum) throw new WorkLimit();
        }
    }
    private static final class WorkLimit extends RuntimeException { }
}
