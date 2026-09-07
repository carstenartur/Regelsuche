package de.regelsuche.evolution;

import static de.regelsuche.search.program.RewritePrograms.budgetedSource;
import static de.regelsuche.search.program.RewritePrograms.choice;
import static de.regelsuche.search.program.RewritePrograms.firstApplicable;
import static de.regelsuche.search.program.RewritePrograms.prune;
import static de.regelsuche.search.program.RewritePrograms.repeat;
import static de.regelsuche.search.program.RewritePrograms.sequence;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.ExactFinitePolynomialPlanCandidateEvidenceVerifier.VerifiedCandidateEvidence;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.LoadedArtifact;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.ExplorationLimits;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.PathBudget;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.Status;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.WorkKind;
import de.regelsuche.search.program.RewriteProgram;
import de.regelsuche.search.program.RewriteProgramInterpreter;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real finite solver -> artifact verification -> independent replay -> selected
 * candidate evidence -> budgeted program. No mathematical source is stubbed.
 * The ansatz grammar and sequence are declared development inputs, not learned
 * tactics or an untouched historical holdout.
 */
@Timeout(20)
class VerifiedFinitePolynomialProgramCompositionTest {
    private static final SchematicProofPlan.Limits PLAN_LIMITS =
        new SchematicProofPlan.Limits(8, 8, 4, 200_000);
    private static final ExplorationLimits PROGRAM_LIMITS =
        new ExplorationLimits(100, 100, 8);
    private final RewriteProgramInterpreter interpreter = new RewriteProgramInterpreter();

    @Test
    void composesIndependentlyVerifiedCompletionAndFactorizationAtExactBudget() {
        Chain chain = chain();
        var execution = execute(chain.program(), chain.input(), chain.totalWork());

        assertEquals(Status.COMPLETE_WITH_CANDIDATES, execution.status());
        assertEquals(1, execution.candidates().size());
        var path = execution.candidates().getFirst();
        assertEquals(chain.input(), path.sourceExpression());
        assertEquals(chain.second().data().transformedExpression(), path.transformedExpression());
        assertEquals(chain.totalWork(), path.mathematicalWorkUnits());
        assertEquals(0L, path.primitiveRewriteSteps());
        assertTrue(path.primitiveRuleIds().isEmpty());
        assertEquals(2L, path.exactTheorySteps());
        assertTrue(path.assumptions().isEmpty());
        assertEquals(List.of(chain.first().evidenceHash(), chain.second().evidenceHash()),
            path.steps().stream().map(step -> step.transition().evidenceHash()).toList());
        assertEquals(List.of(chain.totalWork(), work(chain.second())),
            execution.sourceExecutions().stream()
                .map(call -> call.execution().availableMathematicalWorkUnits()).toList());
        assertEquals(List.of("complete-square", "factor-square"),
            path.steps().stream().map(step -> step.node().id()).toList());
        assertTrue(execution.budgetBlocks().isEmpty());

        // These reference representations are checked only after execution.
        // Source selection used a declared content-hash order, not this endpoint.
        assertEquals("(x + 3) ^ 2 + 0 - 4", chain.first().data().transformedExpression());
        assertTrue(List.of("(x + 1) * (x + 5)", "(x + 5) * (x + 1)")
            .contains(path.transformedExpression()));
        assertEquals(chain.first().data().exactNormalForm(), chain.second().data().exactNormalForm());
        assertEquals(35L, chain.first().data().evaluatedAssignments());
        assertEquals(49L, chain.second().data().evaluatedAssignments());
        assertSourceEvidence(execution, List.of(chain.first(), chain.second()));
    }

    @Test
    void oneUnitShortRetainsRealSuffixBudgetFailureWithoutAFalseNoMatch() {
        Chain chain = chain();
        var execution = execute(chain.program(), chain.input(), chain.totalWork() - 1);
        assertEquals(Status.INCOMPLETE_WITHOUT_CANDIDATES, execution.status());
        assertEquals(2, execution.sourceExecutions().size());
        var block = execution.budgetBlocks().getFirst();
        assertEquals("factor-square", block.nodeId());
        assertEquals(chain.first().data().transformedExpression(), block.inputExpression());
        assertEquals(work(chain.second()) - 1, block.availableWorkUnits());
        assertEquals(work(chain.second()), block.requiredWorkUnits());
        assertEquals(chain.second().evidenceHash(),
            block.call().execution().sourceIdentity().authorityHash());
        assertEquals("INSUFFICIENT_MATHEMATICAL_WORK_AUTHORITY",
            block.call().execution().sourceResult().detailCode());
    }

    @Test
    void firstApplicableCannotSkipAnUnresolvedVerifiedCandidate() {
        Chain chain = chain();
        var program = firstApplicable("first",
            node("blocked", chain.first()), node("fallback", chain.first()));
        var execution = execute(program, chain.input(), work(chain.first()) - 1);
        assertEquals(Status.INCOMPLETE_WITHOUT_CANDIDATES, execution.status());
        assertEquals(1, execution.sourceExecutions().size());
        assertEquals("blocked", execution.budgetBlocks().getFirst().nodeId());
        assertEquals(1L, execution.work().get(WorkKind.ALTERNATIVES_SKIPPED).longValue());
    }

    @Test
    void verifiedSignAlternativesShareAuthorityWithoutCollapsingEvidence() {
        Prepared prepared = prepare("sign-choices", "x*x", "(${sign}*x)^2",
            List.of(HoleDomain.signs("sign")), 2);
        List<VerifiedCandidateEvidence> evidence = prepared.evidence();
        assertEquals(2, evidence.size());
        assertNotEquals(evidence.getFirst().evidenceHash(), evidence.getLast().evidenceHash());
        assertEquals(work(evidence.getFirst()), work(evidence.getLast()));
        long budget = work(evidence.getFirst());
        var execution = execute(choice("sign-choice", node("a", evidence.getFirst()),
            node("b", evidence.getLast())), evidence.getFirst().data().sourceExpression(), budget);
        assertEquals(Status.COMPLETE_WITH_CANDIDATES, execution.status());
        assertEquals(2, execution.candidates().size());
        assertTrue(execution.candidates().stream().allMatch(path -> path.mathematicalWorkUnits() == budget));
        assertEquals(List.of(budget, budget), execution.sourceExecutions().stream()
            .map(call -> call.execution().availableMathematicalWorkUnits()).toList());
        assertSourceEvidence(execution, evidence);
        assertEquals(execution.sourceExecutions().stream()
                .mapToLong(call -> call.execution().mechanicalWork().totalMechanicalWorkUnits()).sum(),
            execution.work().get(WorkKind.DELEGATED_MECHANICAL_WORK).longValue());
    }

    @Test
    void pruningPreservesDiscardedVerifiedEvidenceWithoutInflatingPathWork() {
        var prepared = prepare("pruning-control", "x*x", "(${sign}*x)^2",
            List.of(HoleDomain.signs("sign")), 2);
        var first = prepared.evidence().getFirst();
        var second = prepared.evidence().getLast();
        var program = prune("prune", choice("choice", node("first", first),
            node("second", second)), 1, "declared-first-source");
        var execution = execute(program, first.data().sourceExpression(), work(first));
        assertEquals(Status.INCOMPLETE_WITH_CANDIDATES, execution.status());
        assertEquals(1, execution.candidates().size());
        assertEquals(work(first), execution.candidates().getFirst().mathematicalWorkUnits());
        var removed = execution.pruning().getFirst().removedPaths().getFirst();
        assertEquals(second.evidenceHash(), removed.steps().getFirst().transition().evidenceHash());
        assertEquals(work(second), removed.mathematicalWorkUnits());
        assertEquals(2, execution.sourceExecutions().size());
        assertSourceEvidence(execution, prepared.evidence());
    }

    @Test
    void repeatRoutesRealSourceMatchesAndPreservesBothVerifiedEndpoints() {
        Chain chain = chain();
        var program = repeat("repeat", 1, 3,
            firstApplicable("applicable", node("complete-square", chain.first()),
                node("factor-square", chain.second())));
        var execution = execute(program, chain.input(), chain.totalWork());
        assertEquals(Status.COMPLETE_WITH_CANDIDATES, execution.status());
        assertEquals(List.of(work(chain.first()), chain.totalWork()),
            execution.candidates().stream().map(path -> path.mathematicalWorkUnits()).toList());
        // First iteration: one match. Second: miss then match. Third: two misses.
        assertEquals(5, execution.sourceExecutions().size());
        assertEquals(0L, execution.sourceExecutions().getLast().execution().availableMathematicalWorkUnits());
        assertEquals("SOURCE_MISMATCH",
            execution.sourceExecutions().getLast().execution().sourceResult().detailCode());
        assertSourceEvidence(execution, List.of(chain.first(), chain.second()));

        var shortExecution = execute(program, chain.input(), chain.totalWork() - 1);
        assertEquals(Status.INCOMPLETE_WITH_CANDIDATES, shortExecution.status());
        assertEquals(1, shortExecution.candidates().size());
        assertEquals(work(chain.first()), shortExecution.candidates().getFirst().mathematicalWorkUnits());
    }

    @Test
    void reversedSequenceRejectsEquivalentButUnboundInputRepresentation() {
        Chain chain = chain();
        var execution = execute(sequence("reversed", node("factor", chain.second()),
            node("complete", chain.first())), chain.input(), chain.totalWork());
        assertEquals(Status.COMPLETE_WITHOUT_CANDIDATES, execution.status());
        assertEquals(1, execution.sourceExecutions().size());
        assertEquals("SOURCE_MISMATCH",
            execution.sourceExecutions().getFirst().execution().sourceResult().detailCode());
        assertTrue(execution.budgetBlocks().isEmpty());
    }

    @Test
    void selectedCandidateDoesNotUpgradeTruncatedSolverCoverage() {
        Prepared prepared = prepare("retention-control", "x*x", "(${sign}*x)^2",
            List.of(HoleDomain.signs("sign")), 1);
        var evidence = prepared.evidence().getFirst();
        assertEquals("COMPLETE_RESOLUTION_SET_TRUNCATED", prepared.run().status().name());
        assertEquals(2L, evidence.data().matchingAssignments());
        assertEquals(1, evidence.data().retainedCandidateCount());
        var execution = execute(node("selected", evidence), evidence.data().sourceExpression(), work(evidence));
        assertTrue(execution.complete()); // Complete for this one explicitly selected source only.
        assertEquals(1, execution.candidates().size());
        assertEquals("COMPLETE_RESOLUTION_SET_TRUNCATED", evidence.data().runStatus().name());
        assertEquals(evidence.evidenceHash(),
            execution.candidates().getFirst().steps().getFirst().transition().evidenceHash());
    }

    @Test
    void independentRerunsProduceTheSameProgramEvidenceAndCosts() {
        Chain first = chain();
        Chain second = chain();
        var a = execute(first.program(), first.input(), first.totalWork());
        var b = execute(second.program(), second.input(), second.totalWork());
        assertEquals(first.first().evidenceHash(), second.first().evidenceHash());
        assertEquals(first.second().evidenceHash(), second.second().evidenceHash());
        assertEquals(a.programHash(), b.programHash());
        assertEquals(a.contentHash(), b.contentHash());
        assertEquals(a.work(), b.work());
        assertEquals(a.candidates().getFirst().mathematicalWorkUnits(),
            b.candidates().getFirst().mathematicalWorkUnits());
    }

    @Test
    void changedPlanRunBytesCannotBeUsedToIssueCandidateEvidence() {
        Prepared prepared = prepare("tamper-control", "x*x", "(${sign}*x)^2",
            List.of(HoleDomain.signs("sign")), 2);
        var verifier = new ExactFinitePolynomialPlanReplayArtifactVerifier();
        var reference = verifier.describePlanRun(prepared.run());
        byte[] bytes = prepared.run().toCanonicalJson().getBytes(StandardCharsets.UTF_8);
        bytes[bytes.length / 2] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> verifier.verifyPlanRun(reference,
            ignored -> new LoadedArtifact(reference.artifactId(), bytes)));
    }

    private BudgetedRewriteProgramExecution execute(RewriteProgram program, String input, long work) {
        return interpreter.executeBudgeted(program, input, new PathBudget(0, work), PROGRAM_LIMITS);
    }

    private static RewriteProgram.BudgetedSource node(String id, VerifiedCandidateEvidence evidence) {
        return budgetedSource(id, new VerifiedFinitePolynomialCandidateSource(evidence));
    }

    private static long work(VerifiedCandidateEvidence evidence) {
        return evidence.data().canonicalWork().totalWorkUnits();
    }

    private static void assertSourceEvidence(BudgetedRewriteProgramExecution execution,
                                             List<VerifiedCandidateEvidence> evidence) {
        for (var call : execution.sourceExecutions()) {
            var bound = evidence.stream().filter(item -> item.evidenceHash()
                .equals(call.execution().sourceIdentity().authorityHash())).findFirst().orElseThrow();
            var source = new VerifiedFinitePolynomialCandidateSource(bound);
            assertEquals(source.identity(), call.execution().sourceIdentity());
            assertEquals(source.transform(call.execution().inputExpression(),
                call.execution().availableMathematicalWorkUnits()), call.execution().sourceResult());
        }
    }

    private static Chain chain() {
        // Freeze both general ansatz templates and domains before either solver run.
        String completion = "(x + ${shift})^2 + ${constant}";
        var completionDomains = List.of(HoleDomain.integerRange("shift", 0, 4),
            HoleDomain.integerRange("constant", -5, 1));
        String factorization = "(x + ${left}) * (x + ${right})";
        var factorDomains = List.of(HoleDomain.integerRange("left", 0, 6),
            HoleDomain.integerRange("right", 0, 6));
        var first = prepare("completion", "x^2 + 6*x + 5", completion,
            completionDomains, 2).evidence().getFirst();
        var second = prepare("factorization", first.data().transformedExpression(), factorization,
            factorDomains, 2).evidence().getFirst();
        return new Chain(first, second);
    }

    private static Prepared prepare(String id, String source, String ansatz,
                                    List<HoleDomain> domains, int retainedLimit) {
        var resolver = new ExactFinitePolynomialPlanResolver();
        var plan = resolver.createPlan(id, source, ansatz, domains, retainedLimit, PLAN_LIMITS);
        var run = resolver.resolve(plan, source, ansatz, domains, retainedLimit);
        var receipt = new ExactFinitePolynomialPlanReplayVerifier()
            .verify(plan, source, ansatz, domains, retainedLimit, run);
        var bytesVerifier = new ExactFinitePolynomialPlanReplayArtifactVerifier();
        var receiptReference = bytesVerifier.describeReceipt(receipt);
        byte[] receiptBytes = receipt.toCanonicalJson().getBytes(StandardCharsets.UTF_8);
        var checkedReceiptBytes = bytesVerifier.verifyReceipt(receiptReference,
            ignored -> new LoadedArtifact(receiptReference.artifactId(), receiptBytes));
        var receiptArtifact = new ExactFinitePolynomialPlanReplayReceiptArtifactVerifier()
            .verify(checkedReceiptBytes);
        var runReference = bytesVerifier.describePlanRun(run);
        byte[] runBytes = run.toCanonicalJson().getBytes(StandardCharsets.UTF_8);
        var checkedRunBytes = bytesVerifier.verifyPlanRun(runReference,
            ignored -> new LoadedArtifact(runReference.artifactId(), runBytes));
        var confirmation = new ExactFinitePolynomialPlanReplayConfirmationVerifier().verify(
            receiptArtifact, checkedRunBytes, run, plan, source, ansatz, domains, retainedLimit);
        var evidenceVerifier = new ExactFinitePolynomialPlanCandidateEvidenceVerifier();
        // Deterministic selection without a historical endpoint or expected coefficient.
        var evidence = run.candidates().stream()
            .sorted(Comparator.comparing(ExactFinitePolynomialResolvedCandidate::contentHash))
            .map(candidate -> evidenceVerifier.verify(confirmation, plan, run, candidate.contentHash()))
            .toList();
        return new Prepared(run, evidence);
    }

    private record Prepared(ExactFinitePolynomialPlanRun run,
                            List<VerifiedCandidateEvidence> evidence) {}

    private record Chain(VerifiedCandidateEvidence first, VerifiedCandidateEvidence second) {
        String input() { return first.data().sourceExpression(); }
        long totalWork() { return Math.addExact(work(first), work(second)); }
        RewriteProgram program() {
            return sequence("complete-then-factor", node("complete-square", first),
                node("factor-square", second));
        }
    }
}
