package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityTransitionOutcome.CacheDisposition;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityTransitionTrace.PrimitiveStep;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityTransitionTraceTest {
    private static final String CASE_ID = "z02-difference-of-squares";
    private static final String PROFILE_ID =
        "ON_DEMAND_VERIFIED_FACTORIZATION";
    private static final String FACTORED = "(x-1)*(x+1)";

    @Test
    void retainsOrderedPrimitivePathAssumptionsAndAstGrowth() {
        var transition = transition(0, work(4L));
        var steps = steps(transition);

        var trace = PolynomialTheoryUtilityTransitionTrace.create(
            transition,
            3,
            steps,
            List.of(" 0 != x ")
        );

        assertEquals(
            "regelsuche.polynomial-theory-utility-transition-trace/v1",
            trace.schema()
        );
        assertEquals(3, trace.pathDepth());
        assertEquals(4, trace.primitiveExpansionLength());
        assertEquals(List.of("x != 0"), trace.normalizedAssumptions());
        assertEquals(
            nodeCount(transition.sourceRootExpression()),
            trace.sourceAstNodeCount()
        );
        assertEquals(
            nodeCount(transition.transformedRootExpression()),
            trace.transformedAstNodeCount()
        );
        assertEquals(
            trace.transformedAstNodeCount() - trace.sourceAstNodeCount(),
            trace.astNodeGrowth()
        );
        assertEquals(List.of(0, 0, 1, 2), trace.primitiveSteps().stream()
            .map(PrimitiveStep::pathEdgeIndex)
            .toList());
        trace.validateAgainst(
            0,
            transition,
            trace.sourceAstNodeCount()
        );

        assertThrows(
            UnsupportedOperationException.class,
            () -> trace.primitiveSteps().clear()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> trace.normalizedAssumptions().clear()
        );

        var changedSteps = new ArrayList<>(steps);
        changedSteps.set(
            3,
            PrimitiveStep.create(
                transition,
                3,
                2,
                "factorization-replay",
                hash("changed-evidence")
            )
        );
        var changed = PolynomialTheoryUtilityTransitionTrace.create(
            transition,
            3,
            changedSteps,
            List.of("x != 0")
        );
        assertNotEquals(trace.traceId(), changed.traceId());
    }

    @Test
    void rejectsMissingExcessiveAndGappedPrimitiveLineage() {
        var transition = transition(0, work(4L));

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityTransitionTrace.create(
                transition,
                1,
                List.of(),
                List.of()
            )
        );

        var excessive = new ArrayList<>(steps(transition));
        excessive.add(
            PrimitiveStep.create(
                transition,
                4,
                2,
                "unexpected-extra-step",
                hash("extra-step")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityTransitionTrace.create(
                transition,
                3,
                excessive,
                List.of()
            )
        );

        var gap = List.of(
            PrimitiveStep.create(
                transition,
                0,
                0,
                "first-edge",
                hash("first-edge")
            ),
            PrimitiveStep.create(
                transition,
                1,
                2,
                "third-edge",
                hash("third-edge")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityTransitionTrace.create(
                transition,
                3,
                gap,
                List.of()
            )
        );

        var shortPath = List.of(
            PrimitiveStep.create(
                transition,
                0,
                0,
                "first-edge",
                hash("first-edge")
            ),
            PrimitiveStep.create(
                transition,
                1,
                1,
                "second-edge",
                hash("second-edge")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityTransitionTrace.create(
                transition,
                3,
                shortPath,
                List.of()
            )
        );
    }

    @Test
    void rejectsCounterfeitPrimitiveTraceAndUnnormalizedConstructorInput() {
        var transition = transition(0, work(4L));
        var steps = steps(transition);
        var trace = PolynomialTheoryUtilityTransitionTrace.create(
            transition,
            3,
            steps,
            List.of("x != 0")
        );
        var first = steps.getFirst();

        assertThrows(
            IllegalArgumentException.class,
            () -> new PrimitiveStep(
                hash("counterfeit-step"),
                first.primitiveIndex(),
                first.pathEdgeIndex(),
                first.transitionId(),
                first.ruleId(),
                first.evidenceHash()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityTransitionTrace(
                trace.traceId(),
                transition,
                trace.pathDepth(),
                trace.primitiveSteps(),
                List.of("0 != x"),
                trace.sourceAstNodeCount(),
                trace.transformedAstNodeCount()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityTransitionTrace(
                trace.traceId(),
                transition,
                trace.pathDepth(),
                trace.primitiveSteps(),
                trace.normalizedAssumptions(),
                trace.sourceAstNodeCount() + 1,
                trace.transformedAstNodeCount()
            )
        );
    }

    @Test
    void rejectsReorderedStepsAndTransitionRebinding() {
        var transition = transition(0, work(4L));
        var steps = steps(transition);
        var reordered = List.of(
            steps.get(1),
            steps.get(0),
            steps.get(2),
            steps.get(3)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityTransitionTrace.create(
                transition,
                3,
                reordered,
                List.of()
            )
        );

        var trace = PolynomialTheoryUtilityTransitionTrace.create(
            transition,
            3,
            steps,
            List.of()
        );
        var foreign = transition(1, work(4L));
        assertThrows(
            IllegalArgumentException.class,
            () -> trace.validateAgainst(
                1,
                foreign,
                trace.sourceAstNodeCount()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> trace.validateAgainst(
                0,
                transition,
                trace.sourceAstNodeCount() + 1
            )
        );
    }

    @Test
    void rejectsNonPositiveDepthAndWrongTransitionStepIdentity() {
        var transition = transition(0, work(4L));
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityTransitionTrace.create(
                transition,
                0,
                steps(transition),
                List.of()
            )
        );

        var foreign = transition(1, work(4L));
        var mixed = new ArrayList<>(steps(transition));
        mixed.set(
            2,
            PrimitiveStep.create(
                foreign,
                2,
                1,
                "foreign-transition-step",
                hash("foreign-transition-step")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityTransitionTrace.create(
                transition,
                3,
                mixed,
                List.of()
            )
        );
    }

    private static List<PrimitiveStep> steps(
        PolynomialTheoryUtilityTransitionOutcome transition
    ) {
        return List.of(
            PrimitiveStep.create(
                transition,
                0,
                0,
                "expose-square",
                hash("step-0")
            ),
            PrimitiveStep.create(
                transition,
                1,
                0,
                "difference-of-squares",
                hash("step-1")
            ),
            PrimitiveStep.create(
                transition,
                2,
                1,
                "factor-left",
                hash("step-2")
            ),
            PrimitiveStep.create(
                transition,
                3,
                2,
                "factor-right",
                hash("step-3")
            )
        );
    }

    private static PolynomialTheoryUtilityTransitionOutcome transition(
        int transitionIndex,
        PolynomialTheoryUtilityWorkBreakdown work
    ) {
        var input = input();
        var profile = PolynomialTheoryUtilityExecutionInputs.profile(
            input.profileId()
        );
        return PolynomialTheoryUtilityTransitionOutcome.create(
            transitionIndex,
            input.inputId(),
            List.of(),
            formationCase().sourceExpression(),
            FACTORED,
            formationCase().sourceExpression(),
            FACTORED,
            profile.transformationId(),
            profile.engineId(),
            hash("source-evidence:" + transitionIndex),
            hash("transition-evidence:" + transitionIndex),
            CacheDisposition.CACHE_DISABLED,
            "NONE",
            "NONE",
            "NONE",
            work
        );
    }

    private static PolynomialTheoryUtilityWorkBreakdown work(
        long primitive
    ) {
        return new PolynomialTheoryUtilityWorkBreakdown(
            primitive,
            1L,
            1L,
            2L,
            1L,
            1L,
            1L,
            1L,
            1L,
            0L,
            0L,
            0L,
            0L,
            1L
        );
    }

    private static PolynomialTheoryUtilityExecutionInput input() {
        return PolynomialTheoryUtilityExecutionInputs.freeze().inputs()
            .stream()
            .filter(value -> PROFILE_ID.equals(value.profileId()))
            .filter(value -> CASE_ID.equals(value.caseId()))
            .filter(value -> "CP06_FULL".equals(
                value.checkpointId()
            ))
            .findFirst()
            .orElseThrow();
    }

    private static PolynomialTheoryUtilityCaseCorpus.FormationCase
            formationCase() {
        return PolynomialTheoryUtilityCaseCorpus.load().cases().stream()
            .filter(value -> CASE_ID.equals(value.caseId()))
            .findFirst()
            .orElseThrow();
    }

    private static int nodeCount(String expression) {
        return new ExpressionCanonicalizer().astNodeCount(expression);
    }

    private static String hash(String value) {
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            value.getBytes(StandardCharsets.UTF_8)
        );
    }
}
