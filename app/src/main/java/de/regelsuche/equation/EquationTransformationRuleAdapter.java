package de.regelsuche.equation;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Equation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.transform.RewriteKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Adapter that lifts the dedicated {@link LinearEquationSolver} pipeline into
 * the same "first-class transformation step" representation the rest of the
 * Regelsuche workbench uses (search graph, replay, path comparison,
 * macro-rule learning, equality saturation).
 *
 * <p>Each {@link EquationStep} emitted by the solver becomes:</p>
 * <ul>
 *   <li>a {@link Step} with {@code ruleId}, {@code assumptions},
 *       {@code explanation}, {@code scoreDelta} and
 *       {@code proofStatus};</li>
 *   <li>a {@link TransformationStep} compatible with
 *       {@link de.regelsuche.discovery.DiscoveredTransformation};</li>
 *   <li>a {@link GraphEdge} that can be persisted into any
 *       {@link de.regelsuche.graph.ExpressionGraphStore} so the rendered
 *       search graph contains the equation chain rather than treating it as
 *       a special-case side channel.</li>
 * </ul>
 *
 * <p>The adapter is intentionally side-effect free: it produces the data,
 * the caller decides where to persist it.</p>
 */
public final class EquationTransformationRuleAdapter {

    private final LinearEquationSolver solver;
    private final ExpressionScorer scorer;

    public EquationTransformationRuleAdapter() {
        this(new LinearEquationSolver(), new ExpressionScorer());
    }

    public EquationTransformationRuleAdapter(LinearEquationSolver solver, ExpressionScorer scorer) {
        this.solver = Objects.requireNonNull(solver, "solver");
        this.scorer = Objects.requireNonNull(scorer, "scorer");
    }

    /**
     * Solve {@code equation} for {@code variable} and emit a fully-formed
     * trace ({@link Trace}) of first-class transformation steps.
     *
     * @return {@link Optional#empty()} when the equation is not linear in
     *         the requested variable (so the solver cannot honestly produce
     *         a sound rewrite chain).
     */
    public Optional<Trace> trace(Equation equation, String variable) {
        Objects.requireNonNull(equation, "equation");
        Objects.requireNonNull(variable, "variable");
        Optional<LinearEquationSolver.Solution> solution = solver.solve(equation, variable);
        if (solution.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toTrace(equation, solution.get()));
    }

    private Trace toTrace(Equation original, LinearEquationSolver.Solution solution) {
        String originalFormatted = ExpressionFormatter.format(original);
        String previous = originalFormatted;
        int previousScore = scorer.score(previous).weightedTotal();

        List<Step> steps = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        List<TransformationStep> discoverySteps = new ArrayList<>();

        for (int i = 0; i < solution.steps().size(); i++) {
            EquationStep raw = solution.steps().get(i);
            String after = ExpressionFormatter.format(raw.equation());
            int scoreAfter = scorer.score(after).weightedTotal();
            int delta = scoreAfter - previousScore;
            CandidateProofStatus proofStatus = raw.assumptions().isEmpty()
                ? CandidateProofStatus.SYMBOLICALLY_VERIFIED
                : CandidateProofStatus.OBSERVED;
            Step step = new Step(
                raw.ruleId(),
                raw.description(),
                raw.assumptions(),
                delta,
                proofStatus,
                previous,
                after,
                raw.equation()
            );
            steps.add(step);
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
                "equation:" + originalFormatted + "#" + (i + 1),
                Integer.toHexString(after.hashCode()),
                previousScore,
                scoreAfter,
                RewriteKind.SIMPLIFY,
                /* mayIncreaseComplexity = */ false,
                /* estimatedCostDelta = */ delta,
                /* equivalencePreservingByConstruction = */ true,
                proofStatus
            ));
            previous = after;
            previousScore = scoreAfter;
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
            collectAssumptions(steps)
        );
    }

    private static List<Assumption> collectAssumptions(List<Step> steps) {
        List<Assumption> all = new ArrayList<>();
        for (Step step : steps) {
            all.addAll(step.assumptions());
        }
        return List.copyOf(all);
    }

    /**
     * First-class representation of one equation rewrite — equivalent to a
     * normal {@code RewriteRule} application but with the
     * symmetry-preserving "do the same on both sides" semantics baked in.
     */
    public record Step(
        String ruleId,
        String explanation,
        List<Assumption> assumptions,
        int scoreDelta,
        CandidateProofStatus proofStatus,
        String beforeExpression,
        String afterExpression,
        Equation equation
    ) {
        public Step {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(explanation, "explanation");
            Objects.requireNonNull(proofStatus, "proofStatus");
            Objects.requireNonNull(beforeExpression, "beforeExpression");
            Objects.requireNonNull(afterExpression, "afterExpression");
            Objects.requireNonNull(equation, "equation");
            assumptions = List.copyOf(assumptions == null ? List.of() : assumptions);
        }
    }

    /** End-to-end trace produced by {@link #trace(Equation, String)}. */
    public record Trace(
        LinearEquationSolver.Status status,
        Equation originalEquation,
        Equation solvedEquation,
        String originalExpression,
        String solvedExpression,
        List<Step> steps,
        List<TransformationStep> discoverySteps,
        List<GraphEdge> edges,
        List<Assumption> assumptions
    ) {
        public Trace {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(originalEquation, "originalEquation");
            Objects.requireNonNull(solvedEquation, "solvedEquation");
            Objects.requireNonNull(originalExpression, "originalExpression");
            Objects.requireNonNull(solvedExpression, "solvedExpression");
            steps = List.copyOf(steps == null ? List.of() : steps);
            discoverySteps = List.copyOf(discoverySteps == null ? List.of() : discoverySteps);
            edges = List.copyOf(edges == null ? List.of() : edges);
            assumptions = List.copyOf(assumptions == null ? List.of() : assumptions);
        }
    }
}
