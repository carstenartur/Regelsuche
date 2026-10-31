package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import java.util.*;

/** Universal per-bit truth tables. Shifted words use distinct source-bit variables. */
final class WordBitProjection {
    private record Word(Expr expression, NumericKind kind) { }
    private sealed interface Formula permits Bit, Constant, Operation { }
    private record Bit(Word word, int position) implements Formula { }
    private record Constant(boolean bit) implements Formula { }
    private record Operation(NumericOperation operation, Formula left, Formula right) implements Formula { }
    private static final Formula ZERO = new Constant(false), ONE = new Constant(true);
    private final VerificationWork work;
    private final Map<Word, Word> representatives = new LinkedHashMap<>();
    private final Map<Expr, Map<Integer, Formula>> projected = new IdentityHashMap<>();
    private final WordPossibilities possibilities;

    WordBitProjection(VerificationWork work) { this.work = work; possibilities = new WordPossibilities(work); }

    boolean equivalent(Expr left, Expr right, NumericKind kind) {
        if (!kind.integral()) return false;
        if (!hasProjection(left, 0) && !hasProjection(right, 0)) return false;
        for (int bit = 0; bit < kind.bits(); bit++) {
            Formula a = project(left, kind, bit, 0), b = project(right, kind, bit, 0);
            var variables = new LinkedHashSet<Bit>();
            var visited = Collections.newSetFromMap(new IdentityHashMap<Formula, Boolean>());
            collect(a, variables, visited); collect(b, variables, visited);
            if (variables.size() > 8) return false;
            var ordered = List.copyOf(variables);
            for (int assignment = 0; assignment < (1 << ordered.size()); assignment++) {
                work.charge(1);
                var evaluated = new IdentityHashMap<Formula, Boolean>();
                if (value(a, assignment, ordered, evaluated) != value(b, assignment, ordered, evaluated)) return false;
            }
        }
        return true;
    }

    private boolean hasProjection(Expr expression, int depth) {
        work.charge(1);
        if (depth > 128) throw new IllegalArgumentException("BIT_PROJECTION_DEPTH_BOUND");
        var from = JavaExpressions.castSourceKind(expression);
        if (from.isPresent() && from.get().integral() && JavaExpressions.resultKind(expression).integral()) return true;
        var op = JavaExpressions.operationOf(expression).orElse(null);
        var args = JavaExpressions.operands(expression);
        if (op == NumericOperation.ADD && possibilities.disjoint(args.getFirst(), args.get(1), JavaExpressions.resultKind(expression))) return true;
        if ((op == NumericOperation.SHIFT_LEFT || op == NumericOperation.SHIFT_RIGHT || op == NumericOperation.UNSIGNED_SHIFT_RIGHT)
                && JavaExpressions.isLiteral(args.get(1))) return true;
        if (op == NumericOperation.AND || op == NumericOperation.OR || op == NumericOperation.XOR || op == NumericOperation.NOT)
            for (var child : args) if (hasProjection(child, depth + 1)) return true;
        return false;
    }

    private void collect(Formula formula, Set<Bit> variables, Set<Formula> visited) {
        work.charge(1);
        if (variables.size() > 8 || !visited.add(formula)) return;
        if (formula instanceof Bit bit) variables.add(bit);
        else if (formula instanceof Operation op) {
            collect(op.left(), variables, visited);
            if (op.right() != null) collect(op.right(), variables, visited);
        }
    }

    private boolean value(Formula formula, int assignment, List<Bit> variables, Map<Formula, Boolean> evaluated) {
        work.charge(1);
        Boolean known = evaluated.get(formula);
        if (known != null) return known;
        boolean result;
        if (formula instanceof Constant constant) result = constant.bit();
        else if (formula instanceof Bit bit) {
            int index = variables.indexOf(bit);
            if (index < 0) throw new IllegalArgumentException("UNBOUND_PROOF_BIT");
            result = (assignment & (1 << index)) != 0;
        } else {
            var op = (Operation) formula;
            boolean a = value(op.left(), assignment, variables, evaluated);
            result = switch (op.operation()) {
                case NOT -> !a;
                case AND -> a & value(op.right(), assignment, variables, evaluated);
                case OR -> a | value(op.right(), assignment, variables, evaluated);
                case XOR -> a ^ value(op.right(), assignment, variables, evaluated);
                default -> throw new IllegalArgumentException("UNKNOWN_PROOF_BIT_OPERATION");
            };
        }
        evaluated.put(formula, result);
        return result;
    }

    private Formula project(Expr expression, NumericKind kind, int bit, int depth) {
        work.charge(1);
        if (depth > 128) throw new IllegalArgumentException("BIT_PROJECTION_DEPTH_BOUND");
        if (bit < 0 || bit >= kind.bits()) throw new IllegalArgumentException("INVALID_PROOF_BIT");
        int key = kind.ordinal() * 64 + bit;
        var cache = projected.computeIfAbsent(expression, ignored -> new HashMap<>());
        Formula known = cache.get(key);
        if (known != null) return known;
        Formula result = projectUncached(expression, kind, bit, depth);
        cache.put(key, result);
        return result;
    }

    private Formula projectUncached(Expr expression, NumericKind kind, int bit, int depth) {
        if (JavaExpressions.isLiteral(expression))
            return SemanticChecker.integer(JavaExpressions.literalValue(expression)).testBit(bit) ? ONE : ZERO;
        var from = JavaExpressions.castSourceKind(expression);
        var args = JavaExpressions.operands(expression);
        if (from.isPresent() && from.get().integral() && JavaExpressions.resultKind(expression).integral())
            return castBit(args.getFirst(), from.get(), bit, depth);
        var op = JavaExpressions.operationOf(expression).orElse(null);
        if (op == NumericOperation.NOT)
            return new Operation(op, project(args.getFirst(), kind, bit, depth + 1), null);
        if (op == NumericOperation.AND || op == NumericOperation.OR || op == NumericOperation.XOR)
            return booleanBit(op, args, kind, bit, depth);
        if (op == NumericOperation.ADD && possibilities.disjoint(args.getFirst(), args.get(1), kind))
            return booleanBit(NumericOperation.OR, args, kind, bit, depth);
        if ((op == NumericOperation.SHIFT_LEFT || op == NumericOperation.SHIFT_RIGHT || op == NumericOperation.UNSIGNED_SHIFT_RIGHT)
                && (kind == NumericKind.INT || kind == NumericKind.LONG) && JavaExpressions.isLiteral(args.get(1)))
            return shiftedBit(op, args, kind, bit, depth);
        Word representative = representative(new Word(expression, kind));
        if (JavaExpressions.isLiteral(representative.expression()))
            return SemanticChecker.integer(JavaExpressions.literalValue(representative.expression())).testBit(bit) ? ONE : ZERO;
        return new Bit(representative, bit);
    }

    private Formula castBit(Expr value, NumericKind input, int bit, int depth) {
        if (bit >= input.bits()) {
            if (!input.signed()) return ZERO;
            bit = input.bits() - 1;
        }
        return project(value, input, bit, depth + 1);
    }

    private Formula booleanBit(NumericOperation op, List<Expr> args, NumericKind kind, int bit, int depth) {
        Formula a = project(args.getFirst(), kind, bit, depth + 1);
        Formula b = project(args.get(1), kind, bit, depth + 1);
        if (a instanceof Constant constant) return constant(op, constant.bit(), b);
        if (b instanceof Constant constant) return constant(op, constant.bit(), a);
        return new Operation(op, a, b);
    }

    private Formula shiftedBit(NumericOperation op, List<Expr> args, NumericKind kind, int bit, int depth) {
        int distance = SemanticChecker.integer(JavaExpressions.literalValue(args.get(1))).intValue() & (kind.bits() - 1);
        int sourceBit = op == NumericOperation.SHIFT_LEFT ? bit - distance : bit + distance;
        if (sourceBit < 0) return ZERO;
        if (sourceBit >= kind.bits()) {
            if (op != NumericOperation.SHIFT_RIGHT) return ZERO;
            sourceBit = kind.bits() - 1;
        }
        return project(args.getFirst(), kind, sourceBit, depth + 1);
    }

    private static Formula constant(NumericOperation operation, boolean value, Formula other) {
        return switch (operation) {
            case AND -> value ? other : ZERO;
            case OR -> value ? ONE : other;
            case XOR -> value ? new Operation(NumericOperation.NOT, other, null) : other;
            default -> throw new IllegalArgumentException("UNKNOWN_PROOF_BIT_OPERATION");
        };
    }

    private Word representative(Word atom) {
        Word known = representatives.get(atom);
        if (known != null) return known;
        // Independent polynomial normalization never identifies different widths.
        if (atom.kind() == NumericKind.INT || atom.kind() == NumericKind.LONG) {
            var options = new ArrayList<Word>();
            options.add(new Word(atom.kind() == NumericKind.LONG ? JavaExpressions.literal(0L) : JavaExpressions.literal(0), atom.kind()));
            options.add(new Word(atom.kind() == NumericKind.LONG ? JavaExpressions.literal(-1L) : JavaExpressions.literal(-1), atom.kind()));
            representatives.values().stream().filter(w -> w.kind() == atom.kind()).distinct().forEach(options::add);
            for (var option : options) {
                work.charge(1);
                try {
                    if (new PolynomialProof(atom.kind(), false, false, work).equivalent(atom.expression(), option.expression())) {
                        representatives.put(atom, option);
                        return option;
                    }
                } catch (PolynomialProof.OutsideFragment outside) { /* Unproved words stay independent. */ }
            }
        }
        representatives.put(atom, atom);
        return atom;
    }
}
