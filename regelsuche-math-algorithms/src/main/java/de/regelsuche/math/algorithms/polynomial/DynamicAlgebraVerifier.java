package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.polynomial.DynamicAlgebraEvaluation.*;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Checks complete factor coverage and replays the original expression separately on each fixed factor. */
public final class DynamicAlgebraVerifier {
    private DynamicAlgebraVerifier() {}

    public static Result<Boolean> verify(Request request, Certificate certificate,
                                         IterationLimits limits, BooleanSupplier cancellation) {
        return DynamicAlgebraEvaluation.bounded(limits, cancellation, work -> {
            var domain = new DynamicAlgebraDomain(request, work);
            check(request, certificate, domain);
            return true;
        });
    }

    static void check(Request expected, Certificate certificate, DynamicAlgebraDomain domain) {
        long retained = 0;
        if (certificate.source() != expected) {
            var shape = DynamicAlgebraDomain.inspect(certificate.source(), domain.work);
            retained = shape.cells();
            domain.certificateCells(retained);
            domain.work.charge(retained);
            if (!expected.equals(certificate.source())) throw new Invalid("SOURCE_MISMATCH");
        }
        var branches = certificate.branches();
        int degree = domain.modulus.size() - 1;
        domain.certificateCells(retained + branches.size());
        if (branches.isEmpty() || branches.size() > degree) throw new Invalid("BRANCH_COUNT");
        var product = DynamicAlgebraDomain.ONE;
        for (var branch : branches) {
            int size = branch.factor().size();
            retained += 1L + size + branch.decisions().size() + branch.value().map(List::size).orElse(0);
            domain.certificateCells(retained);
            if (size < 2 || size + (long) product.size() - 1 > domain.modulus.size()) throw new Invalid("FACTOR_DEGREE");
            domain.canonical(branch.factor(), degree + 1);
            if (!branch.factor().getLast().equals(BigInteger.ONE)) throw new Invalid("FACTOR_NOT_MONIC");
            if (!domain.gcd(product, branch.factor()).equals(DynamicAlgebraDomain.ONE)) throw new Invalid("FACTORS_OVERLAP");
            product = domain.multiply(product, branch.factor());
            if (branch.value().isPresent()) domain.canonical(branch.value().orElseThrow(), size - 1);
            if (branch.decisions().size() > domain.nodes) throw new Invalid("TOO_MANY_DECISIONS");
            for (var decision : branch.decisions()) {
                domain.work.charge(1);
                if (decision instanceof Unit unit) {
                    retained += unit.inverse().size();
                    domain.certificateCells(retained);
                    domain.canonical(unit.inverse(), size - 1);
                }
            }
        }
        if (!product.equals(domain.modulus)) throw new Invalid("INCOMPLETE_FACTOR_COVER");
        for (var branch : branches) {
            var replay = new Replay(branch, domain);
            var value = replay.value(expected.expression());
            if (replay.index != branch.decisions().size()) throw new Invalid("UNUSED_DECISION");
            if (!Optional.ofNullable(value).equals(branch.value())) throw new Invalid("BRANCH_VALUE_MISMATCH");
        }
    }

    // On a fixed factor a witness must be either identically zero or a two-sided unit.
    // A mixed zero divisor cannot pass either check. No factor search or Bézout synthesis occurs here.
    private static final class Replay {
        private final Branch branch;
        private final DynamicAlgebraDomain domain;
        private int index;

        Replay(Branch branch, DynamicAlgebraDomain domain) {
            this.branch = branch;
            this.domain = domain;
        }

        List<BigInteger> value(Expr expression) {
            domain.work.charge(1);
            return switch (expression) {
                case Literal literal -> domain.remainder(literal.coefficients(), branch.factor());
                case Add add -> binary(add.left(), add.right(), false);
                case Product product -> binary(product.left(), product.right(), true);
                case Inverse inverse -> {
                    var operand = value(inverse.operand());
                    yield operand == null ? null : checkedInverse(operand);
                }
                case IfZero test -> {
                    var condition = value(test.condition());
                    if (condition == null) yield null;
                    yield value(checkedInverse(condition) == null ? test.whenZero() : test.whenUnit());
                }
            };
        }

        private List<BigInteger> binary(Expr left, Expr right, boolean multiply) {
            var a = value(left);
            if (a == null) return null;
            var b = value(right);
            if (b == null) return null;
            var result = multiply ? domain.multiply(a, b) : domain.add(a, b);
            return domain.remainder(result, branch.factor());
        }

        private List<BigInteger> checkedInverse(List<BigInteger> operand) {
            if (index == branch.decisions().size()) throw new Invalid("MISSING_DECISION");
            var decision = branch.decisions().get(index++);
            if (decision instanceof Zero) {
                if (!operand.isEmpty()) throw new Invalid("FALSE_ZERO_DECISION");
                return null;
            }
            var inverse = ((Unit) decision).inverse();
            var check = domain.remainder(domain.multiply(operand, inverse), branch.factor());
            if (!check.equals(DynamicAlgebraDomain.ONE)) throw new Invalid("FALSE_INVERSE");
            return inverse;
        }
    }
}
