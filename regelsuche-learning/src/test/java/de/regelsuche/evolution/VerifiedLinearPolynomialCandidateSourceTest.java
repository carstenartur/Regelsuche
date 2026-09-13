package de.regelsuche.evolution;

import static de.regelsuche.search.program.RewritePrograms.budgetedSource;
import static de.regelsuche.search.program.RewritePrograms.firstApplicable;
import static de.regelsuche.search.program.RewritePrograms.sequence;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.LoadedArtifact;
import de.regelsuche.evolution.ExactLinearPolynomialPlanResolver.Formation;
import de.regelsuche.evolution.ExactLinearPolynomialPlanResolver.Run;
import de.regelsuche.evolution.ExactLinearPolynomialPlanEvidenceVerifier.VerifiedCandidateEvidence;
import de.regelsuche.math.algorithms.equivalence.ExactLinearPolynomialHoleSolver;
import de.regelsuche.math.algorithms.equivalence.ExactLinearPolynomialHoleSolver.Limits;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.ExplorationLimits;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.PathBudget;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.Status;
import de.regelsuche.search.program.RewriteProgram;
import de.regelsuche.search.program.RewriteProgramInterpreter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Public synthetic algebra, real solver/replay/program; no supplied expected coefficients form an ansatz. */
@Timeout(20)
class VerifiedLinearPolynomialCandidateSourceTest {
    private static final Limits SOLVER_LIMITS = new Limits(4, 32, 128, 100_000);
    private static final SchematicProofPlan.Limits PLAN_LIMITS = new SchematicProofPlan.Limits(8, 8, 4, 200_000);
    private static final ExplorationLimits PROGRAM_LIMITS = new ExplorationLimits(100, 100, 8);
    private final ExactLinearPolynomialPlanResolver resolver = new ExactLinearPolynomialPlanResolver();
    private final ExactLinearPolynomialPlanEvidenceVerifier verifier = new ExactLinearPolynomialPlanEvidenceVerifier();

    @Test
    void realCoupledRationalSolutionCompilesAndComposesWithIndependentReplay() {
        var first = prepare("basis-change", formation("17*x/7   +3*y/11", "${alpha}*(x+y)+${beta}*(x-y)", "alpha", "beta"));
        // The second grammar is predeclared; its source is the actual previous endpoint, not an expected answer.
        var second = prepare("collect-again", formation(first.evidence().transformedExpression(),
            "${gamma}*x+${delta}*y", "gamma", "delta"));
        long work = first.evidence().mathematicalWorkUnits() + second.evidence().mathematicalWorkUnits();
        RewriteProgram program = sequence("linear-sequence", node("basis", first.evidence()), node("collect", second.evidence()));
        var execution = execute(program, first.formation().sourceExpression(), work);

        assertEquals(Status.COMPLETE_WITH_CANDIDATES, execution.status());
        var path = execution.candidates().getFirst();
        assertEquals(second.evidence().transformedExpression(), path.transformedExpression());
        assertEquals(2, path.exactTheorySteps());
        assertEquals(0, path.primitiveRewriteSteps());
        assertEquals(work, path.mathematicalWorkUnits());
        assertTrue(path.assumptions().isEmpty());
        assertEquals(List.of(first.evidence().evidenceHash(), second.evidence().evidenceHash()),
            path.steps().stream().map(step -> step.transition().evidenceHash()).toList());
        assertEquals(List.of(work, second.evidence().mathematicalWorkUnits()),
            execution.sourceExecutions().stream().map(call -> call.execution().availableMathematicalWorkUnits()).toList());
        assertEquals(execution.contentHash(), execute(program, first.formation().sourceExpression(), work).contentHash());

        // Expected values are assertions only, after formation, native solving and program execution.
        var bindings = first.run().solverResult().candidate().orElseThrow().bindings();
        assertEquals("104/77", bindings.get("alpha").canonicalText());
        assertEquals("83/77", bindings.get("beta").canonicalText());
        assertTrue(first.plan().holes().stream().allMatch(hole -> hole.grammarRevision().equals("exact-linear-coefficients/v1")));
        assertTrue(first.run().resolution().orElseThrow().isStructurallyCompleteFor(first.plan()));
        assertEquals(2L * first.run().solverResult().work().consumed(), first.evidence().mathematicalWorkUnits());
        assertEquals(first.run().solverResult().work(), first.evidence().replay().replayWork());
        assertEquals(1, first.evidence().replay().replayExecutions());
    }

    @Test
    void sequenceAndFirstApplicableRetainInsufficientWorkAsIncomplete() {
        var first = prepare("first-work", formation("17*x/7", "${alpha}*x", "alpha"));
        var second = prepare("second-work", formation(first.evidence().transformedExpression(), "x*${beta}", "beta"));
        long total = first.evidence().mathematicalWorkUnits() + second.evidence().mathematicalWorkUnits();
        var program = sequence("work-sequence", node("first", first.evidence()), node("second", second.evidence()));
        var execution = execute(program, first.formation().sourceExpression(), total - 1);
        assertEquals(Status.INCOMPLETE_WITHOUT_CANDIDATES, execution.status());
        assertEquals(2, execution.sourceExecutions().size());
        assertEquals("second", execution.budgetBlocks().getFirst().nodeId());
        assertEquals(second.evidence().mathematicalWorkUnits() - 1, execution.budgetBlocks().getFirst().availableWorkUnits());
        var alternatives = firstApplicable("alternatives", node("blocked", first.evidence()), node("fallback", first.evidence()));
        var blocked = execute(alternatives, first.formation().sourceExpression(), first.evidence().mathematicalWorkUnits() - 1);
        assertEquals(Status.INCOMPLETE_WITHOUT_CANDIDATES, blocked.status());
        assertEquals(1, blocked.sourceExecutions().size());
    }

    @Test
    void independentlyExpectedPlanTemplateHoleAndEveryLimitAreBindingBeforeLoading() {
        var original = prepare("bound-inputs", formation("17*x/7", "${alpha}*x", "alpha"));
        List<Formation> foreign = List.of(
            formation("18*x/7", "${alpha}*x", "alpha"),
            formation("17*x/7", "x*${alpha}", "alpha"),
            formation("17*x/7", "${beta}*x", "beta"),
            withLimits(original.formation(), new Limits(3, 32, 128, 100_000)),
            withLimits(original.formation(), new Limits(4, 31, 128, 100_000)),
            withLimits(original.formation(), new Limits(4, 32, 127, 100_000)),
            withLimits(original.formation(), new Limits(4, 32, 128, 99_999)),
            new Formation("17*x/7", "${alpha}*x", List.of("alpha"), List.of("x > 0"), SOLVER_LIMITS));
        var loads = new AtomicInteger();
        var reference = verifier.describeRun(original.run());
        for (Formation different : foreign) {
            assertThrows(IllegalArgumentException.class, () -> resolver.resolve(original.plan(), different));
            assertThrows(IllegalArgumentException.class, () -> verifier.verify(original.plan(), different, reference, id -> {
                loads.incrementAndGet();
                return new LoadedArtifact(id, bytes(original.run()));
            }));
        }
        assertEquals(0, loads.get());
        var foreignPlan = resolver.createPlan("different-plan", original.formation(), PLAN_LIMITS);
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(foreignPlan, original.formation(), reference,
            id -> new LoadedArtifact(id, bytes(original.run()))));
        var differentPlanLimits = resolver.createPlan("bound-inputs", original.formation(),
            new SchematicProofPlan.Limits(9, 8, 4, 200_000));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(differentPlanLimits, original.formation(), reference,
            id -> new LoadedArtifact(id, bytes(original.run()))));
    }

    @Test
    void actualReplayRejectsFullyRehashedFabricatedCandidateAndResolution() {
        var prepared = prepare("forgery-control", formation("17*x/7", "${alpha}*x", "alpha"));
        var original = prepared.run().solverResult();
        var forgedCandidate = new ExactLinearPolynomialHoleSolver.Candidate(original.candidate().orElseThrow().bindings(), "0*x");
        var forgedResult = new ExactLinearPolynomialHoleSolver.Result(original.sourceExpression(), original.ansatzTemplate(),
            original.holeIds(), original.assumptions(), original.limits(), original.status(), original.constraints(),
            original.reduction(), Optional.of(forgedCandidate), original.work(), original.detailCode());
        // Public records and all their hashes are data, including a CONFIRMED plan-resolution outcome.
        var forgedBindings = forgedCandidate.bindings().entrySet().stream().map(entry -> new SchematicProofPlanResolution.HoleBinding(
            entry.getKey(), SchematicProofPlan.HoleSort.EXACT_RATIONAL, entry.getValue().canonicalText(),
            SchematicProofPlan.hash(ExactLinearPolynomialPlanResolver.REVISION_HASH + "|" + prepared.plan().contentHash()
                + "|" + forgedResult.contentHash() + "|" + entry.getKey() + "|" + entry.getValue().canonicalText()))).toList();
        var obligation = prepared.plan().obligations().getFirst();
        var forgedResolution = SchematicProofPlanResolution.create(prepared.plan(), forgedBindings,
            List.of(new SchematicProofPlanResolution.ObligationOutcome(obligation.id(),
                SchematicProofPlanResolution.OutcomeStatus.CONFIRMED, obligation.checkerCapability(),
                obligation.checkerRevisionHash(), forgedResult.contentHash(), "EXACT_LINEAR_IDENTITY_CONFIRMED")));
        var forged = new Run(prepared.plan().contentHash(), forgedResult, Optional.of(forgedResolution));
        var forgedReference = verifier.describeRun(forged);
        assertNotEquals(prepared.run().contentHash(), forged.contentHash());
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(prepared.plan(), prepared.formation(), forgedReference,
            id -> new LoadedArtifact(id, bytes(forged))));
    }

    @Test
    void loadedArtifactBytesKeyAndRoleMustMatchAndSnapshotsAreImmutable() {
        var prepared = prepare("byte-control", formation("17*x/7", "${alpha}*x", "alpha"));
        byte[] canonical = bytes(prepared.run());
        var reference = verifier.describeRun(prepared.run());
        byte[] changed = canonical.clone();
        changed[changed.length / 2] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(prepared.plan(), prepared.formation(), reference,
            id -> new LoadedArtifact(id, changed)));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(prepared.plan(), prepared.formation(), reference,
            id -> new LoadedArtifact("sha256:" + "0".repeat(64), canonical)));
        var foreignRole = ExactFinitePolynomialPlanReplayArtifactVerifier.ArtifactReference.describe(
            "plan-run", ExactFinitePolynomialPlanRun.SCHEMA, "application/json", canonical);
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(prepared.plan(), prepared.formation(), foreignRole,
            id -> new LoadedArtifact(id, canonical)));
        var replay = verifier.verify(prepared.plan(), prepared.formation(), reference, id -> new LoadedArtifact(id, canonical));
        var evidence = verifier.verifyCandidate(replay);
        String before = evidence.toCanonicalJson();
        canonical[0] = '!';
        assertEquals(before, evidence.toCanonicalJson());
        assertTrue(VerifiedCandidateEvidence.class.isSealed());
        assertTrue(ExactLinearPolynomialPlanEvidenceVerifier.VerifiedReplay.class.isSealed());
    }

    @Test
    void negativeNativeOutcomesAreReplayableAndCannotCompile() {
        List<Formation> inputs = List.of(
            formation("x", "${alpha}*x+1", "alpha"),
            formation("x", "(${alpha}+${beta})*x", "alpha", "beta"),
            formation("x*x", "(${alpha}*x)^2", "alpha"),
            new Formation("x", "${alpha}*x", List.of("alpha"), List.of("x > 0"), SOLVER_LIMITS),
            withLimits(formation("17*x/7", "${alpha}*x", "alpha"), new Limits(4, 32, 128, 1)));
        List<ExactLinearPolynomialHoleSolver.Status> statuses = List.of(
            ExactLinearPolynomialHoleSolver.Status.INCONSISTENT, ExactLinearPolynomialHoleSolver.Status.UNDERDETERMINED,
            ExactLinearPolynomialHoleSolver.Status.UNSUPPORTED, ExactLinearPolynomialHoleSolver.Status.UNSUPPORTED,
            ExactLinearPolynomialHoleSolver.Status.BUDGET_INCONCLUSIVE);
        for (int index = 0; index < inputs.size(); index++) {
            Formation input = inputs.get(index);
            var plan = resolver.createPlan("negative-" + index, input, PLAN_LIMITS);
            var run = resolver.resolve(plan, input);
            assertEquals(statuses.get(index), run.solverResult().status());
            assertTrue(run.resolution().isEmpty());
            var reference = verifier.describeRun(run);
            var replay = verifier.verify(plan, input, reference, id -> new LoadedArtifact(id, bytes(run)));
            assertEquals(run, replay.run());
            assertThrows(IllegalArgumentException.class, () -> verifier.verifyCandidate(replay));
            assertTrue(run.toCanonicalJson().contains(run.solverResult().detailCode()));
            if (index == inputs.size() - 1) { assertEquals(1, run.solverResult().work().consumed()); }
        }
    }

    @Test
    void sourceMismatchIsHonestNoMatchAndUnbudgetedExecutionIsRejected() {
        var prepared = prepare("source-control", formation("17*x/7", "${alpha}*x", "alpha"));
        var source = new VerifiedLinearPolynomialCandidateSource(prepared.evidence());
        assertEquals(de.regelsuche.search.program.BudgetedTransformationSource.Status.NO_MATCH,
            source.transform("y", 0).status());
        assertThrows(IllegalArgumentException.class, () -> new RewriteProgramInterpreter()
            .execute(node("linear", prepared.evidence()), prepared.formation().sourceExpression()));
        assertThrows(IllegalArgumentException.class, () -> source.transform("17*x/7", -1));
    }

    private Prepared prepare(String id, Formation formation) {
        var plan = resolver.createPlan(id, formation, PLAN_LIMITS);
        var run = resolver.resolve(plan, formation);
        var reference = verifier.describeRun(run);
        var replay = verifier.verify(plan, formation, reference, key -> new LoadedArtifact(key, bytes(run)));
        return new Prepared(plan, formation, run, verifier.verifyCandidate(replay));
    }

    private static Formation formation(String source, String template, String... holes) {
        return new Formation(source, template, List.of(holes), List.of(), SOLVER_LIMITS);
    }

    private static Formation withLimits(Formation formation, Limits limits) {
        return new Formation(formation.sourceExpression(), formation.ansatzTemplate(), formation.holeIds(), formation.assumptions(), limits);
    }

    private static byte[] bytes(Run run) { return run.toCanonicalJson().getBytes(StandardCharsets.UTF_8); }
    private static RewriteProgram node(String id, VerifiedCandidateEvidence evidence) {
        return budgetedSource(id, new VerifiedLinearPolynomialCandidateSource(evidence));
    }
    private static BudgetedRewriteProgramExecution execute(RewriteProgram program, String input, long work) {
        return new RewriteProgramInterpreter().executeBudgeted(program, input, new PathBudget(0, work), PROGRAM_LIMITS);
    }
    private record Prepared(SchematicProofPlan plan, Formation formation, Run run, VerifiedCandidateEvidence evidence) {}
}
