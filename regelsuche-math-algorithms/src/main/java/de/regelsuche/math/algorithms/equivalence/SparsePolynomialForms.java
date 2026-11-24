package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** Source-only compact forms of small exact sparse polynomials. Each form still requires a receiver proof. */
public final class SparsePolynomialForms {
    private static final int MAX_TERMS = 32;
    private static final int MAX_COEFFICIENT_BITS = 256;

    private SparsePolynomialForms() {}

    public static void propose(Expr source, boolean sharedArithmetic, LongConsumer work, Consumer<Expr> output) {
        propose(source, sharedArithmetic, work, output, false);
    }

    /** Proposes the same forms and returns a lower bound for binary arithmetic tree size. */
    public static int proposeWithSurfaceBound(Expr source, boolean sharedArithmetic, LongConsumer work, Consumer<Expr> output) {
        return propose(source, sharedArithmetic, work, output, true);
    }

    private static int propose(Expr source, boolean sharedArithmetic, LongConsumer work,
            Consumer<Expr> output, boolean needBound) {
        var form = new ExactResidualPolynomialArithmetic(work, true).project(source);
        var variables = form.variables().stream().sorted().toList();
        var terms = form.terms();
        if (variables.size() > ExactPolynomialAnalysis.MAX_VARIABLES || terms.size() > MAX_TERMS) return 0;
        for (var scalar : terms.values()) {
            work.accept(1);
            if (!bounded(scalar)) return 0;
        }
        var generator = new Generator(sharedArithmetic, work, output);
        generator.commonContent(terms);
        if (variables.size() == 1) generator.binomialPower(variables.getFirst(), form);
        if (terms.size() >= 4) generator.separable(variables, terms);
        return needBound ? surfaceLowerBound(terms, variables, work) : 0;
    }

    private static int surfaceLowerBound(Map<Monomial, Rational> terms, List<String> variables, LongConsumer work) {
        int count = variables.size();
        if (count == 0) return 0;
        if (count == 1) {
            // A single operation on leaves yields a monomial, or x+c / c-x with linear coefficient ±1.
            String variable = variables.getFirst();
            if (terms.size() > 2) return 2;
            for (var term : terms.entrySet()) {
                work.accept(1);
                int exponent = term.getKey().exponentOf(variable);
                var coefficient = term.getValue();
                if (exponent == 0) continue;
                if (terms.size() == 1) {
                    if (exponent == 1) return coefficient.isOne() ? 0 : 1;
                    return coefficient.isOne() ? 1 : 2;
                }
                return exponent == 1 && (coefficient.isOne() || coefficient.isNegativeOne())
                    && terms.containsKey(Monomial.constant()) ? 1 : 2;
            }
            return 0;
        }
        // With exactly count-1 binary nodes every leaf must be a different essential variable.
        // A polynomial in that read-once tree can only use +, -, *: an exponent or a
        // divisor in the admitted grammar must be constant. Its expansion has no constant,
        // has only signed unit coefficients, and no variable occurs twice in a monomial.
        for (var term : terms.entrySet()) {
            var powers = term.getKey().powers();
            work.accept(1L + powers.size());
            var coefficient = term.getValue();
            if (powers.isEmpty() || !(coefficient.isOne() || coefficient.isNegativeOne())
                    || powers.values().stream().anyMatch(exponent -> exponent > 1)) return count;
        }
        return count - 1;
    }

    private static final class Generator {
        private final boolean sharedArithmetic;
        private final LongConsumer work;
        private final Consumer<Expr> output;

        Generator(boolean sharedArithmetic, LongConsumer work, Consumer<Expr> output) {
            this.sharedArithmetic = sharedArithmetic;
            this.work = work;
            this.output = output;
        }

        void commonContent(Map<Monomial, Rational> terms) {
            if (terms.size() < 2) return;
            var common = new LinkedHashMap<>(terms.keySet().iterator().next().powers());
            for (var monomial : terms.keySet()) {
                work.accept(1L + common.size());
                common.replaceAll((variable, exponent) -> Math.min(exponent, monomial.exponentOf(variable)));
            }
            var scalar = content(terms.values(), work);
            var divisor = new Monomial(common);
            if (!bounded(scalar) || scalar.isOne() && divisor.powers().isEmpty()) return;
            var residual = new LinkedHashMap<Monomial, Rational>();
            for (var term : terms.entrySet()) {
                work.accept(1L + term.getKey().powers().size());
                var powers = new LinkedHashMap<>(term.getKey().powers());
                common.forEach((variable, exponent) -> powers.computeIfPresent(variable, (key, old) -> old - exponent));
                residual.put(new Monomial(powers), term.getValue().divide(scalar));
            }
            output.accept(binary(product(divisor, scalar), BinaryOperator.MUL, sum(residual)));
        }

        void binomialPower(String variable, Polynomial polynomial) {
            int exponent = polynomial.totalDegree();
            if (exponent < 2 || exponent > 8) return;
            var terms = polynomial.terms();
            var leading = terms.get(powerOf(variable, exponent));
            var second = terms.getOrDefault(powerOf(variable, exponent - 1), Rational.ZERO);
            if (leading == null || second.isZero()) return;
            work.accept(3);
            var shift = second.divide(leading.multiply(Rational.of(exponent)));
            if (!bounded(shift)) return;
            var coefficient = leading;
            for (int power = exponent; power >= 0; power--) {
                work.accept(4);
                if (!bounded(coefficient) || !coefficient.equals(terms.getOrDefault(powerOf(variable, power), Rational.ZERO))) return;
                coefficient = coefficient.multiply(shift).multiply(Rational.of(power)).divide(Rational.of(exponent - power + 1L));
            }
            Expr base = append(new VariableExpr(variable), Monomial.constant(), shift);
            var raised = power(base, exponent);
            output.accept(leading.isOne() ? raised : binary(new NumberExpr(leading.exactValue()), BinaryOperator.MUL, raised));
        }

        void separable(List<String> variables, Map<Monomial, Rational> terms) {
            int dimensions = variables.size();
            for (int mask = 1; mask < (1 << Math.max(0, dimensions - 1)); mask++) {
                work.accept(1);
                var variableSet = new java.util.HashSet<String>();
                for (int i = 0; i < dimensions; i++) if ((mask & (1 << i)) != 0) variableSet.add(variables.get(i));
                var rows = new LinkedHashMap<Monomial, Map<Monomial, Rational>>();
                for (var term : terms.entrySet()) {
                    work.accept(1L + term.getKey().powers().size());
                    var left = new LinkedHashMap<String, Integer>();
                    var right = new LinkedHashMap<String, Integer>();
                    term.getKey().powers().forEach((name, exponent) -> (variableSet.contains(name) ? left : right).put(name, exponent));
                    rows.computeIfAbsent(new Monomial(left), ignored -> new LinkedHashMap<>())
                        .put(new Monomial(right), term.getValue());
                }
                separableRows(rows, terms.size());
            }
        }

        private void separableRows(Map<Monomial, Map<Monomial, Rational>> rows, int terms) {
            var first = rows.values().iterator().next();
            if (rows.size() < 2 || first.size() < 2 || rows.size() * first.size() != terms) return;
            var firstKey = first.keySet().iterator().next();
            var firstScale = first.get(firstKey);
            var scale = content(first.values(), work);
            if (!bounded(scale)) return;
            var left = new LinkedHashMap<Monomial, Rational>();
            for (var row : rows.entrySet()) {
                work.accept(1);
                var pivot = row.getValue().get(firstKey);
                if (pivot == null || row.getValue().size() != first.size()) return;
                var multiple = pivot.divide(firstScale);
                if (!bounded(multiple) || !matches(first, row.getValue(), multiple)) return;
                left.put(row.getKey(), multiple);
            }
            output.accept(binary(sum(left), BinaryOperator.MUL, sum(first)));
            if (scale.isOne()) return;
            for (var entry : left.entrySet()) {
                work.accept(1);
                var coefficient = entry.getValue().multiply(scale);
                if (!bounded(coefficient)) return;
                entry.setValue(coefficient);
            }
            var right = new LinkedHashMap<Monomial, Rational>();
            for (var term : first.entrySet()) {
                work.accept(1);
                var coefficient = term.getValue().divide(scale);
                if (!bounded(coefficient)) return;
                right.put(term.getKey(), coefficient);
            }
            output.accept(binary(sum(left), BinaryOperator.MUL, sum(right)));
        }

        private boolean matches(Map<Monomial, Rational> first, Map<Monomial, Rational> row, Rational multiple) {
            for (var term : first.entrySet()) {
                work.accept(2);
                if (!term.getValue().multiply(multiple).equals(row.get(term.getKey()))) return false;
            }
            return true;
        }

        private Expr sum(Map<Monomial, Rational> terms) {
            var groups = new LinkedHashMap<Rational, List<Monomial>>();
            for (var term : terms.entrySet()) {
                work.accept(1);
                groups.computeIfAbsent(term.getValue(), ignored -> new ArrayList<>()).add(term.getKey());
            }
            Expr result = null;
            for (var group : groups.entrySet()) {
                result = appendGroup(result, group.getKey(), group.getValue());
            }
            return result == null ? new NumberExpr(0) : result;
        }

        private Expr appendGroup(Expr result, Rational scalar, List<Monomial> monomials) {
            if (monomials.size() == 1 || scalar.abs().isOne()) {
                for (var monomial : monomials) result = append(result, monomial, scalar);
                return result;
            }
            Expr inner = null;
            for (var monomial : monomials) inner = append(inner, monomial, Rational.ONE);
            var factor = new NumberExpr((result == null ? scalar : scalar.abs()).exactValue());
            var scaled = binary(factor, BinaryOperator.MUL, inner);
            if (result == null) return scaled;
            return binary(result, scalar.numerator().signum() < 0 ? BinaryOperator.SUB : BinaryOperator.ADD, scaled);
        }

        private Expr append(Expr result, Monomial monomial, Rational coefficient) {
            work.accept(1);
            if (result == null) return product(monomial, coefficient);
            if (coefficient.isZero()) return result;
            return binary(result, coefficient.numerator().signum() > 0 ? BinaryOperator.ADD : BinaryOperator.SUB,
                product(monomial, coefficient.abs()));
        }

        private Expr product(Monomial monomial, Rational coefficient) {
            work.accept(1);
            Expr result = coefficient.isOne() && !monomial.powers().isEmpty() ? null : new NumberExpr(coefficient.exactValue());
            for (var variable : monomial.powers().entrySet()) {
                work.accept(1);
                var item = power(new VariableExpr(variable.getKey()), variable.getValue());
                result = result == null ? item : binary(result, BinaryOperator.MUL, item);
            }
            return result;
        }

        private Expr power(Expr base, int exponent) {
            if (exponent == 1) return base;
            if (!sharedArithmetic) return binary(base, BinaryOperator.POW, new NumberExpr(exponent));
            Expr result = base;
            for (int i = 1; i < exponent; i++) result = binary(result, BinaryOperator.MUL, base);
            return result;
        }

        private Expr binary(Expr first, BinaryOperator operator, Expr second) {
            work.accept(1);
            return new BinaryExpr(first, operator, second);
        }
    }

    private static Monomial powerOf(String variable, int exponent) {
        return exponent == 0 ? Monomial.constant() : new Monomial(Map.of(variable, exponent));
    }

    private static Rational content(Iterable<Rational> coefficients, LongConsumer work) {
        BigInteger gcd = BigInteger.ZERO, lcm = BigInteger.ONE;
        for (var scalar : coefficients) {
            work.accept(1);
            gcd = gcd.gcd(scalar.numerator());
            lcm = lcm.divide(lcm.gcd(scalar.denominator())).multiply(scalar.denominator());
            if (lcm.bitLength() > MAX_COEFFICIENT_BITS) return Rational.ONE;
        }
        return new Rational(gcd, lcm);
    }

    private static boolean bounded(Rational scalar) {
        return scalar.numerator().bitLength() <= MAX_COEFFICIENT_BITS && scalar.denominator().bitLength() <= MAX_COEFFICIENT_BITS;
    }
}
