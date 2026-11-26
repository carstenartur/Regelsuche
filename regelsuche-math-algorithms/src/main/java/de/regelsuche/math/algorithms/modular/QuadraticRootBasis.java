package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static de.regelsuche.math.algorithms.modular.QuadraticRootProgram.*;

/** Collect the source base, its power and the second coordinate's multiplicative scale. */
final class QuadraticRootBasis {
    // Logical runtime cells, independent of a particular JVM's object size.
    private static final int CELLS_PER_STEP = 7; // definition, four fields and two map slots
    private static final int CELLS_PER_FACTOR = 4; // map key/value, register and formal degree
    private final List<Step> source = new ArrayList<>();
    private final List<Step> steps = new ArrayList<>();
    private final Map<Step, Integer> common = new HashMap<>();
    private final Map<Integer, Factor> factors = new HashMap<>();
    private final BigInteger p;
    private final CertificateWork work;
    private final int[] aliases;
    private final int root;
    private final int base;
    private final int scale;
    private final SharedPowerPlan.Request request;

    private record Factor(int register, BigInteger degree) {}
    record Template(List<Step> prefix, int guardLeft, int guardRight, int real, int imaginary) {
        QuadraticRootProgram program(Arm equal, Arm unequal) {
            return new QuadraticRootProgram(prefix, guardLeft, guardRight, equal, unequal);
        }
    }

    QuadraticRootBasis(QuadraticRootContract contract, CertificateWork work) {
        this.p = contract.modulus();
        this.work = work;
        var original = contract.original();
        int count = original.prefix().size() + original.equal().steps().size();
        work.cells(12L + INPUTS + 2L * count);
        aliases = new int[INPUTS + count];
        source.addAll(original.prefix());
        source.addAll(original.equal().steps());
        for (int i = 0; i < INPUTS; i++) aliases[i] = i;
        for (int ref = INPUTS; ref < aliases.length; ref++) {
            work.charge(1);
            aliases[ref] = representative(ref);
        }
        root = aliases[original.equal().real()];
        var power = sourceStep(root);
        if (power.op() != Op.POWER || original.equal().normalizeRealCharacter()) throw unsupported();
        int sourceBase = aliases[power.left()];
        int square;
        if (aliases[original.guardLeft()] == sourceBase) square = aliases[original.guardRight()];
        else if (aliases[original.guardRight()] == sourceBase) square = aliases[original.guardLeft()];
        else throw unsupported();
        var guard = sourceStep(square);
        boolean squaresOperand = switch (guard.op()) {
            case SQUARE -> true;
            case POWER -> guard.literal().equals(BigInteger.TWO);
            case MULTIPLY -> aliases[guard.right()] == root;
            default -> false;
        };
        if (!squaresOperand || aliases[guard.left()] != root) throw unsupported();
        base = factor(power.left()).register();
        var imaginary = factor(original.equal().imaginary());
        scale = imaginary.register();
        if (!imaginary.degree().equals(BigInteger.ONE.negate())) throw unsupported();
        var exponent = power.literal();
        request = new SharedPowerPlan.Request(p.subtract(BigInteger.ONE), List.of(
                new SharedPowerPlan.Output(exponent, BigInteger.TWO),
                new SharedPowerPlan.Output(exponent.multiply(imaginary.degree()), BigInteger.TWO)));
    }

    SharedPowerPlan.Request request() { return request; }

    /** Caller-owned state that remains live while the shared power search or verifier runs. */
    long retainedCells() {
        return 12L + aliases.length + source.size() + (long) CELLS_PER_STEP * steps.size() + (long) CELLS_PER_FACTOR * factors.size();
    }

    private static IllegalArgumentException unsupported() { return new IllegalArgumentException("UNSUPPORTED_POWER_SOURCE"); }

    private Step sourceStep(int ref) {
        if (ref < INPUTS || ref - INPUTS >= source.size()) throw unsupported();
        return source.get(ref - INPUTS);
    }

    // Every alias is an unconditional field identity. The verifier still interprets the unmodified source.
    private int representative(int ref) {
        var step = sourceStep(ref);
        if (step.left() < 0) return ref;
        int a = aliases[step.left()];
        if (step.op() == Op.NEGATE && a >= INPUTS && sourceStep(a).op() == Op.NEGATE) {
            return aliases[sourceStep(a).left()];
        }
        if (step.right() < 0) return ref;
        int b = aliases[step.right()];
        if (step.op() == Op.MULTIPLY) {
            if (isConstant(a, BigInteger.ONE)) return b;
            if (isConstant(b, BigInteger.ONE)) return a;
        }
        if (step.op() == Op.ADD && isConstant(a, BigInteger.ZERO)) return b;
        if ((step.op() == Op.ADD || step.op() == Op.SUBTRACT) && isConstant(b, BigInteger.ZERO)) return a;
        return ref;
    }

    private boolean isConstant(int ref, BigInteger value) {
        return ref >= INPUTS && sourceStep(ref).op() == Op.CONSTANT && value.equals(sourceStep(ref).literal());
    }

    private int append(Step step) {
        work.charge(1);
        var found = common.get(step);
        if (found != null) return found;
        if (steps.size() == MAX_BLOCK) throw new CertificateWork.Stop("FIELD_BLOCK_LIMIT");
        work.cells(retainedCells() + CELLS_PER_STEP);
        int ref = INPUTS + steps.size();
        steps.add(step);
        common.put(step, ref);
        return ref;
    }

    private BigInteger literal(int ref) {
        if (ref < INPUTS) return null;
        var step = steps.get(ref - INPUTS);
        return step.op() == Op.CONSTANT ? step.literal() : null;
    }

    private int product(int left, int right) {
        if (BigInteger.ONE.equals(literal(left))) return right;
        if (BigInteger.ONE.equals(literal(right))) return left;
        return append(Step.multiply(left, right));
    }

    private int inverse(int ref) {
        var value = literal(ref);
        if (value == null) return append(Step.inverse(ref));
        if (value.signum() == 0) throw unsupported();
        long words = SharedPowerVerifier.words(p);
        work.charge(1L + words * words * p.bitLength());
        return append(Step.constant(value.modInverse(p)));
    }

    /** Substitute the distinguished root R=1 to recover scale, tracking the exact Laurent degree in R. */
    private Factor factor(int ref) {
        work.charge(1);
        if (ref < INPUTS) return new Factor(ref, BigInteger.ZERO);
        var known = factors.get(ref);
        if (known != null) return known;
        Factor value;
        if (ref == root) value = new Factor(append(Step.constant(BigInteger.ONE)), BigInteger.ONE);
        else {
            var step = sourceStep(ref);
            var a = step.left() < 0 ? null : factor(step.left());
            var b = step.right() < 0 ? null : factor(step.right());
            value = switch (step.op()) {
                case CONSTANT -> new Factor(append(step), BigInteger.ZERO);
                case MULTIPLY -> new Factor(product(a.register(), b.register()), a.degree().add(b.degree()));
                case INVERSE -> new Factor(inverse(a.register()), a.degree().negate());
                case ADD, SUBTRACT -> {
                    if (!a.degree().equals(b.degree())) {
                        // A neutral zero may carry degree zero beside a nonzero-degree operand.
                        if (aliases[ref] == ref) throw unsupported();
                        yield factor(aliases[ref]);
                    }
                    yield new Factor(append(new Step(step.op(), a.register(), b.register(), step.literal())), a.degree());
                }
                case NEGATE -> new Factor(append(Step.negate(a.register())), a.degree());
                case SQUARE -> new Factor(append(Step.square(a.register())), a.degree().shiftLeft(1));
                case POWER -> new Factor(append(Step.power(a.register(), step.literal())), a.degree().multiply(step.literal()));
            };
        }
        work.check(value.degree());
        work.cells(retainedCells() + CELLS_PER_FACTOR);
        factors.put(ref, value);
        return value;
    }

    Template instantiate(SharedPowerPlan.Schedule schedule) {
        int[] outputs = instantiateOutputs(schedule);
        int real = outputs[0];
        int imaginary = product(scale, outputs[1]);
        int guard = append(Step.square(real));
        return liveTemplate(guard, real, imaginary);
    }

    private int[] instantiateOutputs(SharedPowerPlan.Schedule schedule) {
        int count = 2 + schedule.steps().size();
        work.cells(retainedCells() + count + CELLS_PER_STEP);
        var references = new int[count];
        references[0] = append(Step.constant(BigInteger.ONE));
        references[1] = base;
        int output = 2;
        for (var step : schedule.steps()) {
            // Each instruction defines a reference even if its field operation folds into an existing register.
            work.cells(retainedCells() + count + CELLS_PER_STEP);
            work.charge(1);
            int left = references[step.left()];
            references[output++] = switch (step.operation()) {
                case POWER -> append(Step.power(base, step.exponent()));
                case MULTIPLY -> product(left, references[step.right()]);
                case INVERSE -> inverse(left);
            };
        }
        return new int[]{references[schedule.outputs().get(0)], references[schedule.outputs().get(1)]};
    }

    /** Substitution and constant folding may leave unused literals. Compact them before exporting SSA. */
    private Template liveTemplate(int guard, int real, int imaginary) {
        int n = INPUTS + steps.size();
        work.cells(retainedCells() + 4L * n);
        work.charge(2L * n);
        var live = new boolean[n];
        for (int ref : new int[]{guard, base, real, imaginary}) live[ref] = true;
        for (int ref = n - 1; ref >= INPUTS; ref--) {
            if (!live[ref]) continue;
            var step = steps.get(ref - INPUTS);
            if (step.left() >= 0) live[step.left()] = true;
            if (step.right() >= 0) live[step.right()] = true;
        }
        var remap = new int[n];
        for (int i = 0; i < INPUTS; i++) remap[i] = i;
        var compact = new ArrayList<Step>();
        for (int ref = INPUTS; ref < n; ref++) {
            if (!live[ref]) continue;
            var step = steps.get(ref - INPUTS);
            remap[ref] = INPUTS + compact.size();
            compact.add(new Step(step.op(), step.left() < 0 ? -1 : remap[step.left()],
                    step.right() < 0 ? -1 : remap[step.right()], step.literal()));
        }
        return new Template(List.copyOf(compact), remap[guard], remap[base], remap[real], remap[imaginary]);
    }

}
