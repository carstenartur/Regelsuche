package de.regelsuche.evolution;

import static de.regelsuche.search.program.RewritePrograms.budgetedSource;
import static de.regelsuche.search.program.RewritePrograms.sequence;
import de.regelsuche.evolution.ExactFinitePolynomialPlanCandidateEvidenceVerifier.VerifiedCandidateEvidence;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.LoadedArtifact;
import de.regelsuche.evolution.ExactFinitePolynomialTraceLearner.LearnedPlan;
import de.regelsuche.evolution.ExactFinitePolynomialTraceLearner.Limits;
import de.regelsuche.evolution.ExactFinitePolynomialTraceLearner.TrainingTrace;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.ExplorationLimits;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.PathBudget;
import de.regelsuche.search.program.RewriteProgram;
import de.regelsuche.search.program.RewriteProgramInterpreter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Shared real solver/replay fixtures; every call creates fresh verified evidence. */
final class FinitePolynomialTraceFixtures {
    static final SchematicProofPlan.Limits PLAN_LIMITS = new SchematicProofPlan.Limits(8, 8, 4, 200_000);
    static final ExplorationLimits PROGRAM_LIMITS = new ExplorationLimits(100, 100, 8);

    private FinitePolynomialTraceFixtures() {}

    static List<TrainingTrace> training() {
        return new ArrayList<>(List.of(chain("x", 6, 5), chain("y", 8, 12), chain("u", 8, 7)));
    }

    static TrainingTrace chain(String variable, int linear, int constant) {
        // The learner receives none of these generating templates, only the resulting checked paths.
        String completion = "(" + variable + "+${shift})^2 + ${constant}";
        var completionDomains = List.of(HoleDomain.integerRange("shift", 0, 6),
            HoleDomain.integerRange("constant", -12, 0));
        String factorization = "(" + variable + "+${left})*(" + variable + "+${right})";
        var factorDomains = List.of(HoleDomain.integerRange("left", 0, 8), HoleDomain.integerRange("right", 0, 8));
        var first = prepare("completion", variable + "^2+" + linear + "*" + variable + "+" + constant,
            completion, completionDomains, 2);
        var second = prepare("factorization", first.data().transformedExpression(), factorization, factorDomains, 2);
        return trace(List.of(first, second));
    }

    static TrainingTrace trace(List<VerifiedCandidateEvidence> evidence) {
        var execution = execute(evidence, total(evidence));
        return new TrainingTrace(execution, execution.candidates().getFirst().contentHash(), evidence);
    }

    static long total(List<VerifiedCandidateEvidence> evidence) {
        return evidence.stream().mapToLong(e -> e.data().canonicalWork().totalWorkUnits()).reduce(0L, Math::addExact);
    }

    static BudgetedRewriteProgramExecution execute(List<VerifiedCandidateEvidence> evidence, long budget) {
        List<RewriteProgram> steps = new ArrayList<>();
        for (int i = 0; i < evidence.size(); i++) {
            steps.add(budgetedSource("stage-" + i, new VerifiedFinitePolynomialCandidateSource(evidence.get(i))));
        }
        return new RewriteProgramInterpreter().executeBudgeted(new RewriteProgram.Sequence(
            RewriteProgram.NodeMetadata.named("verified-training-sequence"), steps),
            evidence.getFirst().data().sourceExpression(), new PathBudget(0, budget), PROGRAM_LIMITS);
    }

    static BudgetedRewriteProgramExecution reuse(LearnedPlan learned, String source) {
        List<VerifiedCandidateEvidence> evidence = new ArrayList<>();
        String current = source;
        for (int step = 0; step < learned.stages().size(); step++) {
            var request = learned.instantiate(step, current).orElseThrow();
            var checked = prepare("reused-stage-" + step, request.sourceExpression(), request.ansatzTemplate(), request.holeDomains(), 2);
            evidence.add(checked);
            current = checked.data().transformedExpression();
        }
        return execute(evidence, total(evidence));
    }

    static VerifiedCandidateEvidence prepare(String id, String source, String ansatz,
                                                     List<HoleDomain> domains, int retained) {
        var resolver = new ExactFinitePolynomialPlanResolver();
        var plan = resolver.createPlan(id, source, ansatz, domains, retained, PLAN_LIMITS);
        var run = resolver.resolve(plan, source, ansatz, domains, retained);
        var receipt = new ExactFinitePolynomialPlanReplayVerifier().verify(plan, source, ansatz, domains, retained, run);
        var bytesVerifier = new ExactFinitePolynomialPlanReplayArtifactVerifier();
        var receiptRef = bytesVerifier.describeReceipt(receipt);
        var receiptBytes = receipt.toCanonicalJson().getBytes(StandardCharsets.UTF_8);
        var checkedReceipt = bytesVerifier.verifyReceipt(receiptRef,
            ignored -> new LoadedArtifact(receiptRef.artifactId(), receiptBytes));
        var receiptArtifact = new ExactFinitePolynomialPlanReplayReceiptArtifactVerifier().verify(checkedReceipt);
        var runRef = bytesVerifier.describePlanRun(run);
        var runBytes = run.toCanonicalJson().getBytes(StandardCharsets.UTF_8);
        var checkedRun = bytesVerifier.verifyPlanRun(runRef, ignored -> new LoadedArtifact(runRef.artifactId(), runBytes));
        var confirmation = new ExactFinitePolynomialPlanReplayConfirmationVerifier().verify(
            receiptArtifact, checkedRun, run, plan, source, ansatz, domains, retained);
        String selected = run.candidates().stream().map(ExactFinitePolynomialResolvedCandidate::contentHash)
            .min(Comparator.naturalOrder()).orElseThrow();
        return new ExactFinitePolynomialPlanCandidateEvidenceVerifier().verify(confirmation, plan, run, selected);
    }
}
