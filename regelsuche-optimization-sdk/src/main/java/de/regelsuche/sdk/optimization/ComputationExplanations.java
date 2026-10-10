package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.PreparedJointComputation;
import java.util.*;

/** Headless, structured presentation data; the independent checker is the proof authority. */
public final class ComputationExplanations {
    private ComputationExplanations() {}
    public record Step(int index, NumericKind kind, Expr expression, List<Integer> arguments, boolean shared) {
        public Step { arguments = List.copyOf(arguments); }
    }
    public record PlanView(List<Step> steps, Map<String,Integer> outputs) {
        public PlanView { steps = List.copyOf(steps); outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs)); }
    }
    public record Explanation(VerificationEvidence proof, Set<SemanticAssumption> assumptions,
            RuntimeObligations obligations, PlanView original, PlanView replacement,
            long originalModularPowers, long replacementModularPowers, long presentationWork) {
        public Explanation { assumptions = Set.copyOf(assumptions); }
    }
    public record Result(VerificationResult verification, Optional<Explanation> explanation) {
        public Result {
            Objects.requireNonNull(verification); Objects.requireNonNull(explanation);
            if (explanation.isPresent() && !(verification instanceof VerificationResult.Verified))
                throw new IllegalArgumentException("EXPLANATION_REQUIRES_VERIFIED_RESULT");
        }
    }
    public static Result describe(OptimizationRequest request, OptimizationResult.Candidate candidate, CancellationToken token) {
        Objects.requireNonNull(request); Objects.requireNonNull(candidate); Objects.requireNonNull(token);
        var work = new VerificationWork(request, token);
        var verified = new ComputationOptimizer().reverifyWithin(request, candidate, work);
        if (!(verified instanceof VerificationResult.Verified proof)) return new Result(verified, Optional.empty());
        long verificationWork = work.used();
        try {
            work.charge(1);
            // Always reconstruct presentation from the verified plan, not a rule
            // label or a caller-supplied schedule/cost estimate.
            var original = ComputationOptimizer.prepare(request.plan());
            var replacement = ComputationOptimizer.prepare(candidate.plan());
            work.charge(original.cost().inspectionWork() + replacement.cost().inspectionWork());
            var before = view(original, work);
            var after = view(replacement, work);
            long originalPowers = 0;
            for (var occurrence : request.sourceTrace().occurrences()) {
                work.charge(1);
                if (JavaExpressions.operationOf(occurrence.expression()).orElse(null) == NumericOperation.MOD_POW) originalPowers++;
            }
            long replacementPowers = after.steps().stream().filter(s ->
                JavaExpressions.operationOf(s.expression()).orElse(null) == NumericOperation.MOD_POW).count();
            work.charge(after.steps().size());
            return new Result(verified, Optional.of(new Explanation(proof.evidence(), request.assumptions(),
                proof.obligations(), before, after, originalPowers, replacementPowers, work.used() - verificationWork)));
        } catch (VerificationWork.Stopped stopped) {
            return new Result(stopped.cancelled ? new VerificationResult.Cancelled("CANCELLED")
                : new VerificationResult.BudgetExceeded("EXPLANATION_BUDGET_EXCEEDED", work.used()), Optional.empty());
        } catch (IllegalArgumentException outsideBound) {
            return new Result(new VerificationResult.Unsupported("EXPLANATION_OUTSIDE_STRUCTURAL_BOUND"), Optional.empty());
        }
    }
    private static PlanView view(PreparedJointComputation prepared, VerificationWork work) {
        var nodes = prepared.nodes();
        int[] uses = new int[nodes.size()];
        for (int output : prepared.outputBindings().values()) { work.charge(1); uses[output]++; }
        for (var node : nodes) {
            work.charge(1 + node.arguments().size());
            if (!JavaExpressions.isLiteral(node.expression())) for (int argument : node.arguments()) uses[argument]++;
        }
        var steps = new ArrayList<Step>();
        for (int index = 0; index < nodes.size(); index++) {
            work.charge(1);
            var node = nodes.get(index);
            boolean shared = uses[index] > 1 && !(node.expression() instanceof VariableExpr)
                    && !JavaExpressions.isLiteral(node.expression());
            steps.add(new Step(index, NumericKind.fromType(node.type()), node.expression(), node.arguments(), shared));
        }
        return new PlanView(steps, prepared.outputBindings());
    }
}
