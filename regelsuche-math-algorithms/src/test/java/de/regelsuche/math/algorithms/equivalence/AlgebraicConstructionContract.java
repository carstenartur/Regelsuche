package de.regelsuche.math.algorithms.equivalence;

import java.util.List;

/** Direct real-kernel contracts, also invoked by JUnit. */
public final class AlgebraicConstructionContract {
    private final AlgebraicConstruction construction = new AlgebraicConstruction();
    private AlgebraicConstruction.Result run(AlgebraicConstruction.Operation op, String source, String auxiliary, String... premises) {
        return construction.construct(new AlgebraicConstruction.Request(op, source, auxiliary, List.of(premises), 100_000));
    }
    public void splitsAdditiveAndMultiplicativeStructureExactly() {
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
    public void multiplicativeCompensationRetainsTheDomain() {
        var op = AlgebraicConstruction.Operation.MULTIPLICATIVE_COMPLEMENT;
        require(run(op, "x+1", "2").generated(), "nonzero rational constants need no new premise");
        require(!run(op, "x+1", "t").generated(), "symbolic nonzero premise cannot be invented");
        require(run(op, "x+1", "t", "t != 0").generated(), "explicit nonzero premise admits neutral product");
        require(!run(op, "x+1", "0", "0 != 0").generated(), "even a false premise cannot authorize division by zero");
        require(!run(op, "x+1", "t-t", "t-t != 0").generated(), "semantic zero is still zero");
        require(run(AlgebraicConstruction.Operation.FACTOR_TRANSFER, "x*y", "2").generated(), "move a nonzero scale between factors");
        require(!run(AlgebraicConstruction.Operation.FACTOR_TRANSFER, "x*y", "t").generated(), "redistribution also checks the divisor");
    }
    public void boundedEnumerationAndFailuresRemainPaid() {
        var result = construction.construct(new AlgebraicConstruction.Request(AlgebraicConstruction.Operation.ADDITIVE_COMPLEMENT,
            "x^2+y^2", "2*x*y", List.of(), 1));
        require(!result.generated() && result.work() > 0, "limited work is not a free success");
        require(!run(AlgebraicConstruction.Operation.ADDITIVE_SPLIT, "sin(x)", "x").generated(), "unsupported domain fails closed");
        var candidates = construction.explore(AlgebraicConstruction.Operation.ADDITIVE_COMPLEMENT, "x^2+y^2", List.of(), 8, 100_000);
        require(!candidates.results().isEmpty() && candidates.results().size() <= 8, "source-only finite auxiliary generation");
        require(candidates.work() > 0 && candidates.results().stream().allMatch(r -> r.work() > 0), "all attempts are paid");
    }
    public static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        var c = new AlgebraicConstructionContract();
        c.splitsAdditiveAndMultiplicativeStructureExactly(); c.multiplicativeCompensationRetainsTheDomain(); c.boundedEnumerationAndFailuresRemainPaid();
        System.out.println("PASS additive/product constructions, zero/domain guards and paid limits");
    }
}
