package de.regelsuche.inequality;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.transform.RewriteKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Inequality counterpart to
 * {@link de.regelsuche.equation.EquationTransformationRuleAdapter}: bridges
 * the dedicated {@link LinearInequalitySolver} into the regular Regelsuche
 * workbench representation so the comparator-flip / divide-by-negative
 * steps appear in the search graph and the replay overlay just like any
 * normal rewrite.
 *
 * <p>Each step carries a {@code comparatorFlipped} flag so the replay
 * UI can highlight the sign-flip transition explicitly — that is the
 * piece that motivated lifting inequalities into the unified workbench
 * in the first place.</p>
 */
public final class InequalityTransformationRuleAdapter {

    private final LinearInequalitySolver solver;
    private final ExpressionScorer scorer;

    public InequalityTransformationRuleAdapter() {
        this(new LinearInequalitySolver(), new ExpressionScorer());
    }

    public InequalityTransformationRuleAdapter(LinearInequalitySolver solver, ExpressionScorer scorer) {
        this.solver = Objects.requireNonNull(solver, "solver");
        this.scorer = Objects.requireNonNull(scorer, "scorer");
    }

    public Optional<Trace> trace(Inequality inequality, String variable) {
        Objects.requireNonNull(inequality, "inequality");
        Objects.requireNonNull(variable, "variable");
        Optional<LinearInequalitySolver.Solution> solution = solver.solve(inequality, variable);
        if (solution.isEmpty() || solution.get().solved() == null) {
            return Optional.empty();
        }
        return Optional.of(toTrace(inequality, solution.get()));
    }

    private Trace toTrace(Inequality original, LinearInequalitySolver.Solution solution) {
        String originalFormatted = original.formatted();
        String previous = originalFormatted;
        Comparator previousComparator = original.comparator();
        int previousScore = scorer.score(previous).weightedTotal();

        List<Step> steps = new ArrayList<>();
        List<TransformationStep> discoverySteps = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (int i = 0; i < solution.steps().size(); i++) {
            InequalityStep raw = solution.steps().get(i);
            String after = raw.inequality().formatted();
            int scoreAfter = scorer.score(after).weightedTotal();
            int delta = scoreAfter - previousScore;
            boolean comparatorFlipped = raw.inequality().comparator() != previousComparator;
            CandidateProofStatus proofStatus = raw.assumptions().isEmpty()
                ? CandidateProofStatus.SYMBOLICALLY_VERIFIED
                : CandidateProofStatus.OBSERVED;
            steps.add(new Step(
                raw.ruleId(),
                raw.description(),
                raw.assumptions(),
                delta,
                proofStatus,
                previous,
                after,
                previousComparator,
                raw.inequality().comparator(),
                comparatorFlipped
            ));
            discoverySteps.add(new TransformationStep(
                i,
                previous,
                after,
                raw.ruleId(),
                RewriteKind.SIMPLIFY,
                previousScore,
                scoreAfter,
                /* equivalencePreservingByConstruction = */ true,
                raw.ruleId()
            ));
            edges.add(new GraphEdge(
                previous,
                after,
                raw.ruleId(),
                i + 1,
                previousScore - scoreAfter,
                "inequality:" + originalFormatted + "#" + (i + 1),
                Integer.toHexString(after.hashCode()),
                previousScore,
                scoreAfter,
                RewriteKind.SIMPLIFY,
                false,
                delta,
                true,
                proofStatus
            ));
            previous = after;
            previousComparator = raw.inequality().comparator();
            previousScore = scoreAfter;
        }

        List<Assumption> all = new ArrayList<>();
        for (Step step : steps) {
            all.addAll(step.assumptions());
        }

        return new Trace(
            solution.status(),
            original,
            solution.solved(),
            originalFormatted,
            previous,
            steps,
            discoverySteps,
            edges,
            List.copyOf(all)
        );
    }

    /** One inequality rewrite, lifted into a first-class transformation step. */
    public record Step(
        String ruleId,
        String explanation,
        List<Assumption> assumptions,
        int scoreDelta,
        CandidateProofStatus proofStatus,
        String beforeExpression,
        String afterExpression,
        Comparator comparatorBefore,
        Comparator comparatorAfter,
        boolean comparatorFlipped
    ) {
        public Step {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(explanation, "explanation");
            Objects.requireNonNull(proofStatus, "proofStatus");
            Objects.requireNonNull(beforeExpression, "beforeExpression");
            Objects.requireNonNull(afterExpression, "afterExpression");
            Objects.requireNonNull(comparatorBefore, "comparatorBefore");
            Objects.requireNonNull(comparatorAfter, "comparatorAfter");
            assumptions = List.copyOf(assumptions == null ? List.of() : assumptions);
        }
    }

    /** End-to-end trace produced by {@link #trace(Inequality, String)}. */
    public record Trace(
        LinearInequalitySolver.Status status,
        Inequality originalInequality,
        Inequality solvedInequality,
        String originalExpression,
        String solvedExpression,
        List<Step> steps,
        List<TransformationStep> discoverySteps,
        List<GraphEdge> edges,
        List<Assumption> assumptions
    ) {
        public Trace {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(originalInequality, "originalInequality");
            Objects.requireNonNull(originalExpression, "originalExpression");
            steps = List.copyOf(steps == null ? List.of() : steps);
            discoverySteps = List.copyOf(discoverySteps == null ? List.of() : discoverySteps);
            edges = List.copyOf(edges == null ? List.of() : edges);
            assumptions = List.copyOf(assumptions == null ? List.of() : assumptions);
        }

        /**
         * @return {@code true} when any step in the trace flipped the
         *         inequality comparator. The replay overlay uses this to
         *         render a "Vergleichszeichen gedreht" marker.
         */
        public boolean anyComparatorFlipped() {
            for (Step step : steps) {
                if (step.comparatorFlipped()) {
                    return true;
                }
            }
            return false;
        }
    }
}
