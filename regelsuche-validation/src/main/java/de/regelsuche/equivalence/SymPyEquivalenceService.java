package de.regelsuche.equivalence;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactParsedUnivariatePolynomialView;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.validation.OracleValidator;

public class SymPyEquivalenceService implements EquivalenceService, OracleValidator {
    private final DeterministicNumericEquivalence numericEquivalence = new DeterministicNumericEquivalence();
    private final ExpressionParser parser = new ExpressionParser();
    private final ExactParsedUnivariatePolynomialView exact = new ExactParsedUnivariatePolynomialView();

    /** Includes sample agreement for legacy empirical/example-validation callers. */
    @Override
    public boolean areEquivalent(String leftExpression, String rightExpression) {
        var status = validateEquivalence(leftExpression, rightExpression).status();
        return status == OracleValidationStatus.AGREE || status == OracleValidationStatus.SAMPLE_AGREEMENT;
    }

    @Override
    public String evidence(String leftExpression, String rightExpression) {
        return validateEquivalence(leftExpression, rightExpression).evidence();
    }

    @Override
    public OracleValidation validateEquivalence(String leftExpression, String rightExpression) {
        try {
            var left = parser.parseExactTerm(leftExpression);
            var right = parser.parseExactTerm(rightExpression);
            var first = exact.analyze(left).polynomial();
            var second = exact.analyze(right).polynomial();
            if (first.isPresent() && second.isPresent()) {
                return compareExact(first.orElseThrow(), second.orElseThrow());
            }
            if (groundArithmetic(left.expression()) && groundArithmetic(right.expression())) {
                return OracleValidation.unavailable("exact rational evaluation unavailable within the supported bounds");
            }
            Boolean sampled = numericEquivalence.areEquivalent(left.expression(), right.expression());
            if (sampled == null) return OracleValidation.unavailable("no equivalence evidence found");
            return sampled
                ? new OracleValidation(OracleValidationStatus.SAMPLE_AGREEMENT, "validated by deterministic numeric samples")
                : new OracleValidation(OracleValidationStatus.SAMPLE_DISAGREEMENT, "not equivalent under deterministic numeric samples");
        } catch (IllegalArgumentException ex) {
            return OracleValidation.unavailable("oracle input could not be parsed");
        }
    }

    private static OracleValidation compareExact(SparsePolynomial<ExactRational> left, SparsePolynomial<ExactRational> right) {
        if (left.isConstant() && right.isConstant()) {
            var a = left.isZero() ? ExactRational.ZERO : left.leadingCoefficient();
            var b = right.isZero() ? ExactRational.ZERO : right.leadingCoefficient();
            return a.equals(b) ? OracleValidation.agrees("matching exact rational constants")
                : OracleValidation.disagrees("different exact rational constants");
        }
        return left.equals(right) ? OracleValidation.agrees("matching exact univariate polynomial normal forms")
            : OracleValidation.disagrees("different exact univariate polynomial normal forms");
    }

    private static boolean groundArithmetic(Expr expression) {
        return expression instanceof NumberExpr || expression instanceof BinaryExpr binary
            && groundArithmetic(binary.left()) && groundArithmetic(binary.right());
    }
}
