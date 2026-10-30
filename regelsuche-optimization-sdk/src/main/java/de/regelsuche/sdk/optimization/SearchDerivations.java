package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.search.program.JointPlanSearch;
import java.util.ArrayList;

/** Retains the existing frontier's witness and replays it; never searches for a new explanation. */
final class SearchDerivations {
    private SearchDerivations() {}

    static SearchDerivation capture(OptimizationRequest request, VerificationEvidence evidence,
            JointPlanSearch.Result found, VerificationWork work) {
        var witness = found.search().witness();
        if (witness.size() > SearchDerivation.MAX_STEPS)
            throw new IllegalArgumentException("DERIVATION_STRUCTURAL_BOUND");
        var steps = new ArrayList<SearchDerivation.Step>();
        // The search retains the bounded envelope built from this request.
        // Capture reuses its immutable root; untrusted replay below rebuilds it.
        work.charge(1);
        Expr cursor = found.sourceExpression();
        for (var edge : witness) {
            work.charge(1);
            // These producer states already passed the bounded frontier and
            // independent move verifier. Retain their immutable expressions;
            // rebuilding their plans would repeat resolution solely for capture.
            Expr before = edge.source().expression();
            Expr after = edge.target().expression();
            if (!edge.verification().accepted() || !cursor.equals(before))
                throw new IllegalArgumentException("SELECTED_DERIVATION_DISCONTINUOUS");
            steps.add(new SearchDerivation.Step(edge.move().ruleId(), before, after));
            cursor = after;
        }
        // found.plan was constructed from this incumbent and independently
        // verified before capture. Untrusted replay below still rebuilds bounds.
        if (!cursor.equals(found.search().incumbent().expression()))
            throw new IllegalArgumentException("SELECTED_DERIVATION_ENDPOINT_DIFFERS");
        return new SearchDerivation(evidence, request.budget().maximumCandidates(), request.budget().maximumWork(), steps);
    }

    static void replay(OptimizationRequest request, OptimizationResult.Candidate candidate,
            SearchDerivation derivation, VerificationWork work) {
        work.charge(1);
        if (!derivation.evidence().equals(candidate.evidence()))
            throw new IllegalArgumentException("DERIVATION_EVIDENCE_BINDING_DIFFERS");
        var checker = new SemanticChecker(request, work);
        var generator = new JavaCandidateGenerator(request, work, derivation.generationBudget());
        var current = request.plan();
        Expr cursor = envelope(current, work);
        for (var step : derivation.steps()) {
            work.charge(1);
            // Rebuild and bound the envelope before inspecting untrusted equality or rule claims.
            Expr before = envelope(request.plan().withExpression(step.before()), work);
            if (!cursor.equals(before)) throw new IllegalArgumentException("DERIVATION_DISCONTINUOUS");
            var next = request.plan().withExpression(step.after());
            Expr after = envelope(next, work);
            requireGeneratedEdge(generator, current, next, step.rule(), after, derivation.candidateLimit(), work);
            if (!checker.check(current, next).accepted())
                throw new IllegalArgumentException("DERIVATION_STEP_NOT_PROVED");
            current = next;
            cursor = after;
        }
        if (!cursor.equals(envelope(candidate.plan(), work)))
            throw new IllegalArgumentException("DERIVATION_ENDPOINT_DIFFERS");
    }

    private static void requireGeneratedEdge(JavaCandidateGenerator generator, JointComputationPlan current,
            JointComputationPlan next, String rule, Expr after, int candidateLimit, VerificationWork work) {
        // Uses the original bounded candidate cap, not the caller's potentially different search settings.
        // Generation already charges this same work object. There is no new frontier or deadline.
        var generated = generator.generate(current, candidateLimit);
        var resolution = next.resolveOutputs();
        work.chargeCompleted(resolution.work());
        long comparisonWork = Math.max(1, resolution.expandedNodes());
        for (var proposal : generated.proposals()) {
            work.charge(1);
            if (proposal.rule().equals(rule)) {
                work.charge(comparisonWork);
                if (proposal.expression().equals(after)) return;
            }
        }
        throw new IllegalArgumentException("DERIVATION_RULE_OR_TARGET_NOT_GENERATED");
    }

    private static Expr envelope(JointComputationPlan plan, VerificationWork work) {
        work.charge(1);
        var expression = plan.searchExpression();
        work.chargeCompleted(expression.work());
        return expression.expression();
    }
}
