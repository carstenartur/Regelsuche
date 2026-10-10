package de.regelsuche.math.algorithms.modular;

import de.regelsuche.ast.*;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.*;

/** Bounded affine modular laws and their independent semantic checker.
 * No search engine, desired target, variable-name convention or special exponent is built in.
 * Proofs use the existing exact Polynomial representation, on the integer affine fragment.
 */
public final class ModularComputationDomain {
    public static final String REVISION = "regelsuche.modular-affine-domain/v3";
    private static final int MAX_NODES = 2_048;
    private static final int MAX_DEPTH = 96;
    public record NormalizedInput(String value, String modulus) {
        public NormalizedInput {
            if (value == null || value.isBlank() || modulus == null || modulus.isBlank())
                throw new IllegalArgumentException("normalization names required");
        }
    }
    public record Rewrite(String rule, List<Expr> outputs) {
        public Rewrite { outputs = List.copyOf(outputs); }
    }
    public record Generation(List<Rewrite> rewrites, long work, boolean complete) {
        public Generation { rewrites = List.copyOf(rewrites); }
    }
    public record Verification(boolean accepted, long work) {}
    private record Power(Expr base, Polynomial exponent, Expr modulus) {}
    private final Set<String> nonnegative;
    private final Set<String> positive;
    private final Set<NormalizedInput> normalized;

    public ModularComputationDomain(Set<String> nonnegative, Set<String> positive, Set<NormalizedInput> normalized) {
        this.nonnegative = Set.copyOf(nonnegative);
        this.positive = Set.copyOf(positive);
        this.normalized = Set.copyOf(normalized);
    }

    /** Check these assumptions every run, even when every optimized output is an input reference. */
    public void validateInputs(Map<String, ?> inputs) {
        for (String name : nonnegative) if (integer(inputs, name).signum() < 0) throw new IllegalArgumentException("negative exponent input");
        for (String name : positive) if (integer(inputs, name).signum() <= 0) throw new IllegalArgumentException("nonpositive modulus input");
        for (var premise : normalized) {
            var value = integer(inputs, premise.value());
            var modulus = integer(inputs, premise.modulus());
            if (modulus.signum() <= 0 || value.signum() < 0 || value.compareTo(modulus) >= 0)
                throw new IllegalArgumentException("input is not normalized modulo positive modulus");
        }
    }

    /** Exact execution semantics. All local modpow/modmul premises are checked at use as well. */
    public BigInteger evaluate(String operation, List<BigInteger> arguments) {
        int arity = switch (operation) { case "modpow", "modmul" -> 3; case "add", "sub", "mul" -> 2;
            default -> throw new IllegalArgumentException("unsupported modular operator"); };
        if (arguments.size() != arity || arguments.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("operator arity/value differs");
        BigInteger left = arguments.getFirst(), right = arguments.get(1);
        if (arity == 3 && arguments.get(2).signum() <= 0) throw new IllegalArgumentException("positive modulus required");
        return switch (operation) {
            case "add" -> left.add(right);
            case "sub" -> left.subtract(right);
            case "mul" -> left.multiply(right);
            case "modmul" -> left.multiply(right).mod(arguments.get(2));
            case "modpow" -> {
                if (right.signum() < 0) throw new IllegalArgumentException("nonnegative integer exponent required");
                yield left.modPow(right, arguments.get(2));
            }
            default -> throw new IllegalArgumentException("unsupported modular operator");
        };
    }

    /** Split E using an already available F with a common base/modulus and nonnegative affine E-F.
     * The new exponent is derived from current expressions, never a supplied optimization target.
     */
    public Generation generate(List<Expr> outputs, int maximumCandidates) {
        if (maximumCandidates < 1) throw new IllegalArgumentException("positive candidate cap required");
        var candidates = new Candidates(outputs, maximumCandidates);
        var powers = new LinkedHashSet<FunctionExpr>();
        var products = new LinkedHashSet<FunctionExpr>();
        for (Expr output : outputs) collect(output, powers, products, candidates.work, 0);
        boolean complete = addReducingProducts(products, candidates) && addAvailablePowers(powers, candidates);
        return new Generation(List.copyOf(candidates.rewrites.values()), candidates.work.units, complete);
    }

    /** A residue may replace a unit power only at its matching reducing use. */
    private boolean addReducingProducts(Set<FunctionExpr> products, Candidates candidates) {
        for (var product : products) {
            if (product.arguments().size() != 3) continue;
            for (int index = 0; index < 2; index++) {
                Expr factor = product.arguments().get(index);
                if (!(factor instanceof FunctionExpr function)) continue;
                Power unit = supportedPower(function, candidates.work);
                if (unit == null || !unit.modulus().equals(product.arguments().get(2))
                        || !unit.exponent().equals(Polynomial.constant(Rational.ONE))) continue;
                var arguments = new ArrayList<>(product.arguments());
                arguments.set(index, unit.base());
                if (!candidates.add(product, new FunctionExpr("modmul", arguments), "modmul-unit-power-residue"))
                    return false;
            }
        }
        return true;
    }

    private boolean addAvailablePowers(Set<FunctionExpr> powers, Candidates candidates) {
        for (var current : powers) {
            Power exponent = supportedPower(current, candidates.work);
            if (exponent == null) continue;
            if (exponent.exponent().equals(Polynomial.constant(Rational.ONE))
                    && normalized(exponent.base(), exponent.modulus())
                    && !candidates.add(current, exponent.base(), "modpow-unit-normalized")) return false;
            if (!addSmallConstantPower(current, candidates)) return false;
            if (!addExponentDifferences(current, exponent, powers, candidates)) return false;
        }
        return true;
    }

    /** Bounded binary exponentiation proposals, still checked by the ordinary modular normal form. */
    private boolean addSmallConstantPower(FunctionExpr current, Candidates candidates) {
        if (!(current.arguments().get(1) instanceof NumberExpr number) || !number.value().isInteger()) return true;
        BigInteger integer = number.value().numerator();
        if (integer.compareTo(BigInteger.TWO) < 0 || integer.compareTo(BigInteger.valueOf(16)) > 0) return true;
        int exponent = integer.intValueExact();
        Expr factor = current.arguments().getFirst(), result = null, modulus = current.arguments().get(2);
        while (exponent != 0) {
            candidates.work.units++;
            if ((exponent & 1) != 0) result = result == null ? factor : new FunctionExpr("modmul", List.of(result, factor, modulus));
            exponent >>>= 1;
            if (exponent != 0) factor = new FunctionExpr("modmul", List.of(factor, factor, modulus));
        }
        return candidates.add(current, result, "modpow-small-binary-chain");
    }

    private boolean addExponentDifferences(FunctionExpr current, Power exponent,
            Set<FunctionExpr> powers, Candidates candidates) {
        for (var available : powers) {
            candidates.work.units++;
            if (current.equals(available)) continue;
            Power known = supportedPower(available, candidates.work);
            if (known == null || !exponent.base().equals(known.base())
                    || !exponent.modulus().equals(known.modulus()) || known.exponent().isZero()) continue;
            Polynomial difference = exponent.exponent().subtract(known.exponent());
            candidates.work.units += exponent.exponent().termCount() + known.exponent().termCount();
            if (difference.isZero() || !nonnegative(difference)) continue;
            Expr residual = new FunctionExpr("modpow", List.of(exponent.base(), expression(difference), exponent.modulus()));
            Expr product = new FunctionExpr("modmul", List.of(available, residual, exponent.modulus()));
            if (!candidates.add(current, product, "modpow-available-exponent-difference")) return false;
        }
        return true;
    }

    private Power supportedPower(FunctionExpr expression, Work work) {
        try { return power(expression, work); }
        catch (IllegalArgumentException outsideFragment) { return null; }
    }

    /** Preserve insertion order, unique-output counting and one shared work ledger. */
    private static final class Candidates {
        private final List<Expr> outputs;
        private final int maximum;
        private final Work work = new Work();
        private final Map<List<Expr>, Rewrite> rewrites = new LinkedHashMap<>();
        Candidates(List<Expr> outputs, int maximum) { this.outputs = outputs; this.maximum = maximum; }
        boolean add(Expr source, Expr replacement, String rule) {
            var changed = replace(outputs, source, replacement, work);
            rewrites.putIfAbsent(changed, new Rewrite(rule, changed));
            return rewrites.size() < maximum;
        }
    }

    /** Independently compare exponent normal forms. Does not call generate or trust its rule labels. */
    public Verification verifyEquivalent(List<Expr> source, List<Expr> target) {
        var work = new Work();
        try {
            if (source.size() != target.size() || source.isEmpty()) return new Verification(false, 1);
            for (int i = 0; i < source.size(); i++) {
                Power left = normalForm(source.get(i), null, work, 0, false);
                Power right = normalForm(target.get(i), left.modulus(), work, 0, false);
                work.units++;
                if (!left.equals(right)) return new Verification(false, work.units);
            }
            return new Verification(true, Math.max(1, work.units));
        } catch (IllegalArgumentException invalid) {
            return new Verification(false, Math.max(1, work.units));
        }
    }

    private Power normalForm(Expr expression, Expr expectedModulus, Work work, int depth, boolean reducedByParent) {
        work.visit(depth);
        if (expression instanceof FunctionExpr function && function.name().equals("modpow")) {
            Power result = power(function, work);
            if (expectedModulus != null && !expectedModulus.equals(result.modulus())) throw new IllegalArgumentException("modulus differs");
            return result;
        }
        if (expression instanceof FunctionExpr function && function.name().equals("modmul") && function.arguments().size() == 3) {
            Expr modulus = function.arguments().get(2);
            requirePositive(modulus);
            if (expectedModulus != null && !expectedModulus.equals(modulus)) throw new IllegalArgumentException("modulus differs");
            Power left = normalForm(function.arguments().getFirst(), modulus, work, depth + 1, true);
            Power right = normalForm(function.arguments().get(1), modulus, work, depth + 1, true);
            if (!left.base().equals(right.base())) throw new IllegalArgumentException("product bases differ");
            work.units += left.exponent().termCount() + right.exponent().termCount();
            return new Power(left.base(), left.exponent().add(right.exponent()), modulus);
        }
        // Congruent factors need not already be normalized: the parent modmul
        // reduces their product. This permission must never reach a bare output.
        if (reducedByParent && expectedModulus != null
                && (expression instanceof VariableExpr || expression instanceof NumberExpr n && n.value().isInteger())) {
            requirePositive(expectedModulus);
            return new Power(expression, Polynomial.constant(Rational.ONE), expectedModulus);
        }
        if (expression instanceof VariableExpr variable) {
            var matches = normalized.stream().filter(p -> p.value().equals(variable.name())
                && (expectedModulus == null || expectedModulus.equals(new VariableExpr(p.modulus())))).toList();
            if (matches.size() == 1) {
                Expr modulus = new VariableExpr(matches.getFirst().modulus());
                requirePositive(modulus);
                return new Power(variable, Polynomial.constant(Rational.ONE), modulus);
            }
        }
        throw new IllegalArgumentException("unsupported modular normal form");
    }
    private Power power(FunctionExpr expression, Work work) {
        if (!expression.name().equals("modpow") || expression.arguments().size() != 3) throw new IllegalArgumentException("modpow arity differs");
        Expr base = expression.arguments().getFirst(), modulus = expression.arguments().get(2);
        if (!(base instanceof VariableExpr || base instanceof NumberExpr n && n.value().isInteger())) throw new IllegalArgumentException("scalar base required");
        requirePositive(modulus);
        Polynomial exponent = affine(expression.arguments().get(1), work, 0);
        if (!nonnegative(exponent)) throw new IllegalArgumentException("nonnegative affine exponent not proved");
        return new Power(base, exponent, modulus);
    }
    private Polynomial affine(Expr expression, Work work, int depth) {
        work.visit(depth);
        Polynomial result;
        if (expression instanceof NumberExpr number) {
            if (!number.value().isInteger()) throw new IllegalArgumentException("integer coefficient required");
            result = Polynomial.constant(Rational.fromExact(number.value()));
        } else if (expression instanceof VariableExpr variable) result = Polynomial.variable(variable.name());
        else if (expression instanceof BinaryExpr binary) {
            Polynomial left = affine(binary.left(), work, depth + 1), right = affine(binary.right(), work, depth + 1);
            work.units += (long) Math.max(1, left.termCount()) * Math.max(1, right.termCount());
            result = switch (binary.operator()) {
                case ADD -> left.add(right);
                case SUB -> left.subtract(right);
                case MUL -> {
                    if (left.totalDegree() + right.totalDegree() > 1) throw new IllegalArgumentException("affine exponent required");
                    yield left.multiply(right);
                }
                default -> throw new IllegalArgumentException("unsupported affine operator");
            };
        } else throw new IllegalArgumentException("unsupported affine expression");
        if (result.termCount() > 128 || result.terms().values().stream().anyMatch(c -> c.numerator().bitLength() > 4096))
            throw new IllegalArgumentException("affine proof bound exceeded");
        return result;
    }
    private boolean nonnegative(Polynomial polynomial) {
        return polynomial.totalDegree() <= 1 && polynomial.terms().entrySet().stream().allMatch(term ->
            term.getValue().numerator().signum() >= 0 && term.getValue().denominator().equals(BigInteger.ONE)
                && term.getKey().powers().keySet().stream().allMatch(name -> nonnegative.contains(name) || positive.contains(name)));
    }
    private void requirePositive(Expr modulus) {
        if (modulus instanceof VariableExpr v && positive.contains(v.name())) return;
        if (modulus instanceof NumberExpr n && n.value().isInteger() && n.value().signum() > 0) return;
        throw new IllegalArgumentException("positive modulus not proved");
    }
    private boolean normalized(Expr base, Expr modulus) {
        return base instanceof VariableExpr b && modulus instanceof VariableExpr m && normalized.contains(new NormalizedInput(b.name(), m.name()));
    }
    private static BigInteger integer(Map<String, ?> inputs, String name) {
        if (!(inputs.get(name) instanceof BigInteger value)) throw new IllegalArgumentException("integer input required: " + name);
        return value;
    }
    private static Expr expression(Polynomial polynomial) {
        Expr result = null;
        var terms = polynomial.terms().entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().key())).toList();
        for (var term : terms) {
            Expr coefficient = new NumberExpr(ExactRational.integer(term.getValue().numerator()));
            Expr value = coefficient;
            if (!term.getKey().powers().isEmpty()) {
                Expr variable = new VariableExpr(term.getKey().powers().keySet().iterator().next());
                value = term.getValue().isOne() ? variable : new BinaryExpr(coefficient, BinaryOperator.MUL, variable);
            }
            result = result == null ? value : new BinaryExpr(result, BinaryOperator.ADD, value);
        }
        return result == null ? new NumberExpr(0) : result;
    }
    private static void collect(Expr expression, Set<FunctionExpr> powers, Set<FunctionExpr> products, Work work, int depth) {
        work.visit(depth);
        if (expression instanceof FunctionExpr f) {
            for (var argument : f.arguments()) collect(argument, powers, products, work, depth + 1);
            if (f.name().equals("modpow")) powers.add(f);
            if (f.name().equals("modmul")) products.add(f);
        } else if (expression instanceof BinaryExpr b) {
            collect(b.left(), powers, products, work, depth + 1); collect(b.right(), powers, products, work, depth + 1);
        }
    }
    private static List<Expr> replace(List<Expr> outputs, Expr source, Expr target, Work work) {
        return outputs.stream().map(output -> replace(output, source, target, work)).toList();
    }
    private static Expr replace(Expr expression, Expr source, Expr target, Work work) {
        work.units++;
        if (expression.equals(source)) return target;
        if (expression instanceof FunctionExpr f) return new FunctionExpr(f.name(), f.arguments().stream().map(a -> replace(a, source, target, work)).toList());
        if (expression instanceof BinaryExpr b) return new BinaryExpr(replace(b.left(), source, target, work), b.operator(), replace(b.right(), source, target, work));
        return expression;
    }
    private static final class Work {
        private long units = 1;
        private int visits;
        void visit(int depth) {
            units++;
            if (++visits > MAX_NODES || depth > MAX_DEPTH) throw new IllegalArgumentException("modular proof structural bound exceeded");
        }
    }
}
