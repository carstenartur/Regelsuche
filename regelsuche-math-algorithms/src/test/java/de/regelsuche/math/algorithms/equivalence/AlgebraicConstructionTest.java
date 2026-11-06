package de.regelsuche.math.algorithms.equivalence;

import org.junit.jupiter.api.Test;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;

/** Real-kernel tests; main runs the same assertions without a separate contract class. */
public final class AlgebraicConstructionTest {
    private final AlgebraicConstruction construction = new AlgebraicConstruction();
    private AlgebraicConstruction.Result run(AlgebraicConstruction.Operation op, String source, String auxiliary, String... premises) {
        return construction.construct(new AlgebraicConstruction.Request(op, source, auxiliary, List.of(premises), 100_000));
    }
    @Test
    public void exactAdditiveAndMultiplicativeSplits() {
        var sum = run(AlgebraicConstruction.Operation.ADDITIVE_SPLIT, "7*x", "3*x");
        require(sum.generated(), "a known summand must leave the exact residual");
        new ExactPolynomialAnalysis().requireEquivalent("7*x", sum.expression().orElseThrow());
        require(sum.expression().orElseThrow().contains("4*x"), "compute the remaining summand, not a free wildcard");
        var product = run(AlgebraicConstruction.Operation.EXACT_FACTOR_SPLIT, "12*x^4", "3*x^2");
        require(product.generated(), "an exact factor split requires no x != 0 assumption");
        new ExactPolynomialAnalysis().requireEquivalent("12*x^4", product.expression().orElseThrow());
        require(!run(AlgebraicConstruction.Operation.EXACT_FACTOR_SPLIT, "x+1", "x").generated(), "no false polynomial quotient");
        var large = run(AlgebraicConstruction.Operation.ADDITIVE_SPLIT, "9007199254740993*x", "9007199254740992*x");
        require(large.generated(), "large exact integers remain distinct");
        new ExactPolynomialAnalysis().requireEquivalent("9007199254740993*x", large.expression().orElseThrow());
    }
    @Test
    public void multiplicativeDomainIsPreserved() {
        var op = AlgebraicConstruction.Operation.MULTIPLICATIVE_COMPLEMENT;
        require(run(op, "x+1", "2").generated(), "nonzero rational constants need no new premise");
        require(!run(op, "x+1", "t").generated(), "symbolic nonzero premise cannot be invented");
        require(run(op, "x+1", "t", "t != 0").generated(), "explicit nonzero premise admits neutral product");
        require(!run(op, "x+1", "0", "0 != 0").generated(), "even a false premise cannot authorize division by zero");
        require(!run(op, "x+1", "t-t", "t-t != 0").generated(), "semantic zero is still zero");
        require(run(AlgebraicConstruction.Operation.FACTOR_TRANSFER, "x*y", "2").generated(), "move a nonzero scale between factors");
        require(!run(AlgebraicConstruction.Operation.FACTOR_TRANSFER, "x*y", "t").generated(), "redistribution also checks the divisor");
    }
    @Test
    public void enumerationAndFailuresAreBoundedAndPaid() {
        var result = construction.construct(new AlgebraicConstruction.Request(AlgebraicConstruction.Operation.ADDITIVE_COMPLEMENT,
            "x^2+y^2", "2*x*y", List.of(), 1));
        require(!result.generated() && result.work() > 0, "limited work is not a free success");
        require(!run(AlgebraicConstruction.Operation.ADDITIVE_SPLIT, "sin(x)", "x").generated(), "unsupported domain fails closed");
        var candidates = construction.explore(AlgebraicConstruction.Operation.ADDITIVE_COMPLEMENT, "x^2+y^2", List.of(), 8, 100_000);
        require(!candidates.results().isEmpty() && candidates.results().size() <= 8, "source-only finite auxiliary generation");
        require(candidates.work() > 0 && candidates.results().stream().allMatch(r -> r.work() > 0), "all attempts are paid");
    }

    @Test
    public void explorationFindsCrossTermsFromCompositeSquareBasesWithinSmallAttemptLimit() {
        assertAuxiliarySelected("x^2+y^2", "2*x*y");
        assertAuxiliarySelected("(3*x)^2+(y+1)^2", "2*(3*x)*(y+1)");
    }

    @Test
    public void explorationFindsAnExactPowerFactorInsideAProduct() {
        String source = "12*x^4";
        var results = construction.explorePolynomial(AlgebraicConstruction.Operation.EXACT_FACTOR_SPLIT,
            source, List.of(), 4, 100_000);
        var exact = new ExactPolynomialAnalysis();
        var parser = new ExpressionParser();
        boolean found = results.results().stream().filter(AlgebraicConstruction.Result::generated)
            .map(result -> result.expression().orElseThrow())
            .filter(candidate -> {
                exact.requireEquivalent(source, candidate);
                return true;
            })
            .map(candidate -> parser.parseExactTerm(candidate).expression())
            .filter(BinaryExpr.class::isInstance).map(BinaryExpr.class::cast)
            .filter(root -> root.operator() == BinaryOperator.MUL)
            .anyMatch(root -> exact.normalForm(ExpressionFormatter.format(root.left())).equals(exact.normalForm("x^2")));
        require(found, "a bounded scan should propose a proper split of an internal power");
        var commonFactor = construction.explorePolynomial(AlgebraicConstruction.Operation.EXACT_FACTOR_SPLIT,
            "x+x*y", List.of(), 4, 100_000);
        require(commonFactor.results().stream().filter(AlgebraicConstruction.Result::generated)
            .map(result -> parser.parseExactTerm(result.expression().orElseThrow()).expression())
            .filter(BinaryExpr.class::isInstance).map(BinaryExpr.class::cast)
            .filter(root -> root.operator() == BinaryOperator.MUL)
            .anyMatch(root -> exact.normalForm(ExpressionFormatter.format(root.left())).equals(exact.normalForm("x"))
                && exact.normalForm(ExpressionFormatter.format(root.right())).equals(exact.normalForm("1+y"))),
            "nonconstant source fragments remain eligible as common factors");
    }

    @Test
    public void polynomialFactorExplorationRejectsAlgebraicallyConstantAuxiliaries() {
        var operation = AlgebraicConstruction.Operation.EXACT_FACTOR_SPLIT;
        for (String source : List.of("u+(2-2+1)", "u+(v-v+1)")) {
            var neutralFragment = construction.explorePolynomial(operation, source, List.of(), 8, 100_000);
            require(neutralFragment.results().stream().noneMatch(AlgebraicConstruction.Result::generated),
                "a compound constant must not insert a neutral factor: " + source);
        }
        var powerBase = construction.explorePolynomial(operation, "u*(2-2+1)^4", List.of(), 8, 100_000);
        var parser = new ExpressionParser();
        var exact = new ExactPolynomialAnalysis();
        require(powerBase.results().stream().anyMatch(AlgebraicConstruction.Result::generated),
            "a nonconstant source factor remains eligible");
        boolean onlyNonconstantFactors = powerBase.results().stream().filter(AlgebraicConstruction.Result::generated)
            .map(result -> (BinaryExpr) parser.parseExactTerm(result.expression().orElseThrow()).expression())
            .allMatch(root -> exact.normalForm(ExpressionFormatter.format(root.left())).totalDegree() > 0);
        require(onlyNonconstantFactors, "powers of compound constants must not become factor candidates");
    }

    @Test
    public void legacyExplorationKeepsSourceFragmentsForConditionalFactorTransfer() {
        var results = construction.explore(AlgebraicConstruction.Operation.FACTOR_TRANSFER,
            "x^2*y^2", List.of("y != 0"), 4, 100_000);
        var parser = new ExpressionParser();
        var exact = new ExactPolynomialAnalysis();
        boolean found = results.results().stream().filter(AlgebraicConstruction.Result::generated)
            .map(result -> parser.parseExactTerm(result.expression().orElseThrow()).expression())
            .filter(BinaryExpr.class::isInstance).map(BinaryExpr.class::cast)
            .filter(root -> root.operator() == BinaryOperator.MUL && root.right() instanceof BinaryExpr)
            .map(root -> (BinaryExpr) root.right())
            .filter(division -> division.operator() == BinaryOperator.DIV)
            .anyMatch(division -> exact.normalForm(ExpressionFormatter.format(division.right())).equals(exact.normalForm("y^2")));
        require(found, "legacy source-first exploration must still consider the y^2 factor");
    }

    @Test
    public void polynomialExplorationChargesExpansionsBeforeCancellation() {
        String source = "(x+y+z+w)^10-(x+y+z+w)^10+x^2+y^2";
        var limited = construction.explorePolynomial(AlgebraicConstruction.Operation.ADDITIVE_COMPLEMENT,
            source, List.of(), 4, 1_000);
        require(limited.results().stream().noneMatch(AlgebraicConstruction.Result::generated),
            "work spent expanding cancelled terms cannot authorize an over-budget candidate");
        require(limited.work() > 1_000, "the atomic projection overrun remains visible");
        boolean rejected = false;
        try {
            construction.explorePolynomial(AlgebraicConstruction.Operation.FACTOR_TRANSFER,
                "x*y", List.of("y != 0"), 4, 100_000);
        } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "the polynomial exploration path cannot bypass rational-domain checks");
    }

    private void assertAuxiliarySelected(String source, String expectedAuxiliary) {
        var results = construction.explorePolynomial(AlgebraicConstruction.Operation.ADDITIVE_COMPLEMENT,
            source, List.of(), 4, 100_000);
        var exact = new ExactPolynomialAnalysis();
        var parser = new ExpressionParser();
        var expected = exact.normalForm(expectedAuxiliary);
        boolean selected = false;
        for (var result : results.results()) {
            if (!result.generated()) continue;
            String candidate = result.expression().orElseThrow();
            exact.requireEquivalent(source, candidate);
            var root = parser.parseExactTerm(candidate).expression();
            if (root instanceof BinaryExpr binary && binary.operator() == BinaryOperator.SUB
                    && exact.normalForm(ExpressionFormatter.format(binary.right())).equals(expected)) {
                selected = true;
            }
        }
        require(results.results().size() <= 4 && results.work() > 0, "bounded exploration reports its work");
        require(selected, "source-derived cross term should not be crowded out by source fragments: " + source);
    }
    public static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        var c = new AlgebraicConstructionTest();
        c.exactAdditiveAndMultiplicativeSplits(); c.multiplicativeDomainIsPreserved(); c.enumerationAndFailuresAreBoundedAndPaid();
        c.explorationFindsCrossTermsFromCompositeSquareBasesWithinSmallAttemptLimit();
        c.explorationFindsAnExactPowerFactorInsideAProduct();
        c.polynomialFactorExplorationRejectsAlgebraicallyConstantAuxiliaries();
        c.legacyExplorationKeepsSourceFragmentsForConditionalFactorTransfer();
        c.polynomialExplorationChargesExpansionsBeforeCancellation();
        System.out.println("PASS additive/product constructions, zero/domain guards and paid limits");
    }
}
