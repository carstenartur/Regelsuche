package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.validation.CounterexampleSearchService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for the initial {@link RefinementStrategy} implementations. */
class RefinementStrategyTest {

    // ─── NonZeroDenominatorRefinementStrategy ─────────────────────────────────

    @Test
    void nonZeroDenominator_appliesWhenDivisionAndZeroAssignment() {
        NonZeroDenominatorRefinementStrategy strategy = new NonZeroDenominatorRefinementStrategy();
        HypothesisRevision revision = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "a / b", "a * (1/b)",
            List.of(), null, null, HypothesisRevisionStatus.COUNTEREXAMPLE_FOUND, null
        );
        CounterexampleSearchService.CounterexampleSearchResult result =
            CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
                new CounterexampleSearchService.Counterexample(
                    List.of("a=2", "b=0"), "undefined", "2"
                ),
                List.of(), List.of("numeric-random")
            );

        Optional<RefinementStrategy.RefinementProposal> proposal = strategy.refine(revision, result);

        assertTrue(proposal.isPresent(), "should produce a proposal when denominator is zero");
        assertTrue(proposal.get().newAssumptions().contains("b != 0"),
            "proposal must add non-zero denominator constraint");
    }

    @Test
    void nonZeroDenominator_doesNotApplyWithoutDivision() {
        NonZeroDenominatorRefinementStrategy strategy = new NonZeroDenominatorRefinementStrategy();
        HypothesisRevision revision = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "a + b", "b + a",
            List.of(), null, null, HypothesisRevisionStatus.COUNTEREXAMPLE_FOUND, null
        );
        CounterexampleSearchService.CounterexampleSearchResult result =
            CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
                new CounterexampleSearchService.Counterexample(
                    List.of("a=0", "b=0"), "0", "1"
                ),
                List.of(), List.of("numeric-random")
            );

        Optional<RefinementStrategy.RefinementProposal> proposal = strategy.refine(revision, result);

        assertFalse(proposal.isPresent(), "should not apply when pattern has no division");
    }

    @Test
    void nonZeroDenominator_doesNotDuplicateExistingConstraint() {
        NonZeroDenominatorRefinementStrategy strategy = new NonZeroDenominatorRefinementStrategy();
        HypothesisRevision revision = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "a / b", "a * (1/b)",
            List.of("b != 0"), null, null, HypothesisRevisionStatus.COUNTEREXAMPLE_FOUND, null
        );
        CounterexampleSearchService.CounterexampleSearchResult result =
            CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
                new CounterexampleSearchService.Counterexample(
                    List.of("b=0"), "undefined", "1"
                ),
                List.of(), List.of("numeric-random")
            );

        Optional<RefinementStrategy.RefinementProposal> proposal = strategy.refine(revision, result);

        assertFalse(proposal.isPresent(), "should not add duplicate constraint");
    }

    @Test
    void nonZeroDenominator_onlyRestrictsVariablesUsedAsDenominators() {
        NonZeroDenominatorRefinementStrategy strategy = new NonZeroDenominatorRefinementStrategy();
        HypothesisRevision revision = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "a / b", "a",
            List.of(), null, null, HypothesisRevisionStatus.COUNTEREXAMPLE_FOUND, null
        );
        CounterexampleSearchService.CounterexampleSearchResult result =
            CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
                new CounterexampleSearchService.Counterexample(
                    List.of("a=0", "b=2"), "0", "1"
                ),
                List.of(), List.of("numeric-random")
            );

        Optional<RefinementStrategy.RefinementProposal> proposal = strategy.refine(revision, result);

        assertFalse(proposal.isPresent(), "should ignore zero assignments outside denominator positions");
    }

    @Test
    void nonZeroDenominator_recognizesEquivalentZeroRepresentations() {
        NonZeroDenominatorRefinementStrategy strategy = new NonZeroDenominatorRefinementStrategy();
        HypothesisRevision revision = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "a / b", "a",
            List.of(), null, null, HypothesisRevisionStatus.COUNTEREXAMPLE_FOUND, null
        );
        CounterexampleSearchService.CounterexampleSearchResult result =
            CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
                new CounterexampleSearchService.Counterexample(
                    List.of("b=-0.0"), "undefined", "1"
                ),
                List.of(), List.of("numeric-random")
            );

        Optional<RefinementStrategy.RefinementProposal> proposal = strategy.refine(revision, result);

        assertTrue(proposal.isPresent(), "should treat -0.0 as a zero denominator assignment");
        assertTrue(proposal.get().newAssumptions().contains("b != 0"));
    }

    // ─── PositivityRefinementStrategy ─────────────────────────────────────────

    @Test
    void positivity_appliesForSqrtWithNegativeArgument() {
        PositivityRefinementStrategy strategy = new PositivityRefinementStrategy();
        HypothesisRevision revision = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "sqrt(x) * sqrt(x)", "x",
            List.of(), null, null, HypothesisRevisionStatus.COUNTEREXAMPLE_FOUND, null
        );
        CounterexampleSearchService.CounterexampleSearchResult result =
            CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
                new CounterexampleSearchService.Counterexample(
                    List.of("x=-4"), "NaN", "-4"
                ),
                List.of(), List.of("numeric-random")
            );

        Optional<RefinementStrategy.RefinementProposal> proposal = strategy.refine(revision, result);

        assertTrue(proposal.isPresent(), "should produce a proposal for sqrt with negative arg");
        assertTrue(proposal.get().newAssumptions().contains("x >= 0"),
            "proposal must add non-negativity constraint for sqrt argument");
    }

    @Test
    void positivity_appliesForLogWithNegativeArgument() {
        PositivityRefinementStrategy strategy = new PositivityRefinementStrategy();
        HypothesisRevision revision = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "log(x) + log(y)", "log(x * y)",
            List.of(), null, null, HypothesisRevisionStatus.COUNTEREXAMPLE_FOUND, null
        );
        CounterexampleSearchService.CounterexampleSearchResult result =
            CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
                new CounterexampleSearchService.Counterexample(
                    List.of("x=-1", "y=1"), "NaN", "0"
                ),
                List.of(), List.of("numeric-random")
            );

        Optional<RefinementStrategy.RefinementProposal> proposal = strategy.refine(revision, result);

        assertTrue(proposal.isPresent());
        assertTrue(proposal.get().newAssumptions().contains("x > 0"),
            "log requires strictly positive argument");
    }

    @Test
    void positivity_doesNotApplyWithoutPositivityFunctions() {
        PositivityRefinementStrategy strategy = new PositivityRefinementStrategy();
        HypothesisRevision revision = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "x + y", "y + x",
            List.of(), null, null, HypothesisRevisionStatus.COUNTEREXAMPLE_FOUND, null
        );
        CounterexampleSearchService.CounterexampleSearchResult result =
            CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
                new CounterexampleSearchService.Counterexample(
                    List.of("x=-1", "y=2"), "1", "2"
                ),
                List.of(), List.of("numeric-random")
            );

        Optional<RefinementStrategy.RefinementProposal> proposal = strategy.refine(revision, result);

        assertFalse(proposal.isPresent());
    }

    // ─── NumericRangeRefinementStrategy ───────────────────────────────────────

    @Test
    void numericRange_appliesForFractionalUppercaseParameter() {
        NumericRangeRefinementStrategy strategy = new NumericRangeRefinementStrategy();
        HypothesisRevision revision = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "x ^ N", "x * x ^ (N - 1)",
            List.of(), null, null, HypothesisRevisionStatus.COUNTEREXAMPLE_FOUND, null
        );
        CounterexampleSearchService.CounterexampleSearchResult result =
            CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
                new CounterexampleSearchService.Counterexample(
                    List.of("x=2", "N=0.5"), "1.414", "2.828"
                ),
                List.of(), List.of("numeric-random")
            );

        Optional<RefinementStrategy.RefinementProposal> proposal = strategy.refine(revision, result);

        assertTrue(proposal.isPresent(), "should restrict N to integer when fractional value causes failure");
        assertTrue(proposal.get().newAssumptions().contains("N is integer"));
    }

    @Test
    void numericRange_doesNotApplyForIntegerParameter() {
        NumericRangeRefinementStrategy strategy = new NumericRangeRefinementStrategy();
        HypothesisRevision revision = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "x ^ N", "x * x ^ (N - 1)",
            List.of(), null, null, HypothesisRevisionStatus.COUNTEREXAMPLE_FOUND, null
        );
        CounterexampleSearchService.CounterexampleSearchResult result =
            CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
                new CounterexampleSearchService.Counterexample(
                    List.of("x=2", "N=3"), "8", "4"
                ),
                List.of(), List.of("numeric-random")
            );

        Optional<RefinementStrategy.RefinementProposal> proposal = strategy.refine(revision, result);

        assertFalse(proposal.isPresent(), "should not apply when N is already an integer");
    }

    // ─── StructuralCompatibilityRefinementStrategy ────────────────────────────

    @Test
    void structuralCompatibility_appliesForMatrixAssignment() {
        StructuralCompatibilityRefinementStrategy strategy = new StructuralCompatibilityRefinementStrategy();
        HypothesisRevision revision = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "a + b", "b + a",
            List.of(), null, null, HypothesisRevisionStatus.COUNTEREXAMPLE_FOUND, null
        );
        CounterexampleSearchService.CounterexampleSearchResult result =
            CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
                new CounterexampleSearchService.Counterexample(
                    List.of("a=[1,2]", "b=[3,4]"), "[4,6]", "[4,6]"
                ),
                List.of(), List.of("matrix-non-commutative")
            );

        Optional<RefinementStrategy.RefinementProposal> proposal = strategy.refine(revision, result);

        assertTrue(proposal.isPresent(), "should restrict when matrix value assigned to scalar placeholder");
        assertTrue(proposal.get().newAssumptions().contains("a is scalar"));
    }

    @Test
    void structuralCompatibility_doesNotApplyForScalarAssignment() {
        StructuralCompatibilityRefinementStrategy strategy = new StructuralCompatibilityRefinementStrategy();
        HypothesisRevision revision = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "a + b", "b + a",
            List.of(), null, null, HypothesisRevisionStatus.COUNTEREXAMPLE_FOUND, null
        );
        CounterexampleSearchService.CounterexampleSearchResult result =
            CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
                new CounterexampleSearchService.Counterexample(
                    List.of("a=3", "b=4"), "7", "8"
                ),
                List.of(), List.of("numeric-random")
            );

        Optional<RefinementStrategy.RefinementProposal> proposal = strategy.refine(revision, result);

        assertFalse(proposal.isPresent());
    }

    // ─── AstPlaceholderSpecializationRefinementStrategy ───────────────────────

    @Test
    void astSpecialization_appliesForSymbolicAssignment() {
        AstPlaceholderSpecializationRefinementStrategy strategy =
            new AstPlaceholderSpecializationRefinementStrategy();
        HypothesisRevision revision = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "x + 0", "x",
            List.of(), null, null, HypothesisRevisionStatus.COUNTEREXAMPLE_FOUND, null
        );
        CounterexampleSearchService.CounterexampleSearchResult result =
            CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
                new CounterexampleSearchService.Counterexample(
                    List.of("x=y+z"), "y+z+0", "y"
                ),
                List.of(), List.of("symbolic")
            );

        Optional<RefinementStrategy.RefinementProposal> proposal = strategy.refine(revision, result);

        assertTrue(proposal.isPresent(), "should restrict x to numeric when symbolic value breaks rule");
        assertTrue(proposal.get().newAssumptions().contains("x is numeric"));
    }

    @Test
    void astSpecialization_doesNotApplyForNumericAssignment() {
        AstPlaceholderSpecializationRefinementStrategy strategy =
            new AstPlaceholderSpecializationRefinementStrategy();
        HypothesisRevision revision = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "x + 0", "x",
            List.of(), null, null, HypothesisRevisionStatus.COUNTEREXAMPLE_FOUND, null
        );
        CounterexampleSearchService.CounterexampleSearchResult result =
            CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
                new CounterexampleSearchService.Counterexample(
                    List.of("x=5"), "5", "6"
                ),
                List.of(), List.of("numeric-random")
            );

        Optional<RefinementStrategy.RefinementProposal> proposal = strategy.refine(revision, result);

        assertFalse(proposal.isPresent(), "should not apply when assignment is already numeric");
    }

    @Test
    void astSpecialization_treatsRationalAssignmentsAsNumeric() {
        AstPlaceholderSpecializationRefinementStrategy strategy =
            new AstPlaceholderSpecializationRefinementStrategy();
        HypothesisRevision revision = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "x + 0", "x",
            List.of(), null, null, HypothesisRevisionStatus.COUNTEREXAMPLE_FOUND, null
        );
        CounterexampleSearchService.CounterexampleSearchResult result =
            CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
                new CounterexampleSearchService.Counterexample(
                    List.of("x=1/3"), "1/3", "2/3"
                ),
                List.of(), List.of("rational-samples")
            );

        Optional<RefinementStrategy.RefinementProposal> proposal = strategy.refine(revision, result);

        assertFalse(proposal.isPresent(), "should not add numeric constraints for rational assignments");
    }
}
