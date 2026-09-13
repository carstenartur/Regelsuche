package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.polynomial.PolynomialOperationAccounting;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;

/** Actual primitive calls of the historical dense quotient algorithm, charged before execution. */
final class PreparationArithmetic {
    final PolynomialWorkAuthority work;
    PreparationArithmetic(PolynomialWorkAuthority work) { this.work = work; }
    void before(String operation, BigInteger... operands) { PolynomialOperationAccounting.before(work, operation, operands); }
    int signum(BigInteger value) { before("integer.signum", value); return value.signum(); }
    BigInteger abs(BigInteger value) { before("integer.abs", value); return value.abs(); }
    BigInteger negate(BigInteger value) { before("integer.negate", value); return value.negate(); }
    int compare(BigInteger first, BigInteger second) { before("integer.compare", first, second); return first.compareTo(second); }
    boolean equal(BigInteger first, BigInteger second) { before("integer.equals", first, second); return first.equals(second); }
    BigInteger add(BigInteger first, BigInteger second) { before("integer.add", first, second); return first.add(second); }
    BigInteger subtract(BigInteger first, BigInteger second) { before("integer.subtract", first, second); return first.subtract(second); }
    BigInteger multiply(BigInteger first, BigInteger second) { before("integer.multiply", first, second); return first.multiply(second); }
    BigInteger[] divide(BigInteger first, BigInteger second) { before("integer.divide-remainder", first, second); return first.divideAndRemainder(second); }
    int intValue(BigInteger value) { before("integer.int-value", value); return value.intValue(); }
    boolean isInteger(ExactRational value) { before("scalar.integer-predicate", value.denominator()); return value.isInteger(); }
    Expr number(BigInteger value) {
        before("render.integer-value-constructions", value);
        ExactRational exact = ExactRational.integer(value);
        work.consume("render.ast-node-constructions", 1);
        return new NumberExpr(exact);
    }
    Expr number(long value) {
        before("render.small-integer-value-constructions");
        ExactRational exact = ExactRational.integer(value);
        work.consume("render.ast-node-constructions", 1);
        return new NumberExpr(exact);
    }
    Expr variable(String name) { work.consume("render.ast-node-constructions", 1); return new VariableExpr(name); }
    Expr binary(Expr left, BinaryOperator operator, Expr right) {
        work.consume("render.ast-node-constructions", 1); return new BinaryExpr(left, operator, right);
    }
    boolean sameText(String first, String second) {
        if (work == PolynomialWorkAuthority.unbounded()) return first.equals(second);
        work.consume("text.equality-dispatch", 1);
        if (first == second) return true;
        if (first.length() != second.length()) return false;
        for (int index = 0; index < first.length(); index++) {
            work.consume("text.equality-code-units", 2);
            if (first.charAt(index) != second.charAt(index)) return false;
        }
        return true;
    }

    boolean sameExpression(Expr first, Expr second) {
        if (work == PolynomialWorkAuthority.unbounded()) return java.util.Objects.equals(first, second);
        var pending = new java.util.ArrayDeque<ExpressionPair>();
        pending.push(new ExpressionPair(first, second));
        while (!pending.isEmpty()) {
            work.consume("verification.ast-node-pairs", 1);
            if (!samePair(pending.pop(), pending)) return false;
        }
        return true;
    }

    private boolean samePair(ExpressionPair pair, java.util.Deque<ExpressionPair> pending) {
        if (pair.first == pair.second) return true;
        if (pair.first == null || pair.second == null || pair.first.getClass() != pair.second.getClass()) return false;
        if (pair.first instanceof NumberExpr left) {
            var right = (NumberExpr) pair.second;
            return equal(left.value().numerator(), right.value().numerator())
                && equal(left.value().denominator(), right.value().denominator());
        }
        if (pair.first instanceof VariableExpr left) return sameText(left.name(), ((VariableExpr) pair.second).name());
        if (pair.first instanceof BinaryExpr left) {
            var right = (BinaryExpr) pair.second;
            if (left.operator() != right.operator()) return false;
            pending.push(new ExpressionPair(left.right(), right.right()));
            pending.push(new ExpressionPair(left.left(), right.left()));
        }
        if (pair.first instanceof FunctionExpr left) return sameFunction(left, (FunctionExpr) pair.second, pending);
        return true;
    }

    private boolean sameFunction(FunctionExpr left, FunctionExpr right, java.util.Deque<ExpressionPair> pending) {
        if (!sameText(left.name(), right.name()) || left.arguments().size() != right.arguments().size()) return false;
        for (int index = left.arguments().size() - 1; index >= 0; index--)
            pending.push(new ExpressionPair(left.arguments().get(index), right.arguments().get(index)));
        return true;
    }
    boolean sameStrings(java.util.List<String> first, java.util.List<String> second) {
        if (work == PolynomialWorkAuthority.unbounded()) return first.equals(second);
        work.consume("text.list-equality-dispatch", 1);
        if (first.size() != second.size()) return false;
        for (int index = 0; index < first.size(); index++) if (!sameText(first.get(index), second.get(index))) return false;
        return true;
    }
    private record ExpressionPair(Expr first, Expr second) { }
}
