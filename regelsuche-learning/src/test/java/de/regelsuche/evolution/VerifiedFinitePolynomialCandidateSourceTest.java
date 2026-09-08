package de.regelsuche.evolution;

import static de.regelsuche.search.program.RewritePrograms.budgetedSource;
import static de.regelsuche.search.program.RewritePrograms.choice;
import static de.regelsuche.search.program.RewritePrograms.firstApplicable;
import static de.regelsuche.search.program.RewritePrograms.prune;
import static de.regelsuche.search.program.RewritePrograms.repeat;
import static de.regelsuche.search.program.RewritePrograms.sequence;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import de.regelsuche.search.program.BudgetedTransformationSource;
import de.regelsuche.search.program.BudgetedTransformationSourceExecutor;
import de.regelsuche.search.program.RewriteProgram;
import de.regelsuche.search.program.RewriteProgramInterpreter;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.ExactTheoryEvidence;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationProvenance;
import de.regelsuche.transform.TransformationBatch;
import de.regelsuche.transform.TransformationWorkMetrics;
import de.regelsuche.search.program.RewriteCandidate;
import de.regelsuche.search.program.RewriteExecution;
import de.regelsuche.search.program.RewriteTraceEvent;
import de.regelsuche.search.program.RewriteTraceEventType;
import de.regelsuche.search.program.RewriteTraceLevel;
import de.regelsuche.search.program.RewritePrograms;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
 *
 * <p>The isolated-source and composed-program cases deliberately share one
 * evidence preparation pipeline. Each invocation still runs every resolver,
 * byte-verification and replay stage; no precomputed evidence is cached.</p>
 */
@Timeout(20)
class VerifiedFinitePolynomialCandidateSourceTest {
    private static final SchematicProofPlan.Limits PLAN_LIMITS =
        new SchematicProofPlan.Limits(8, 8, 4, 200_000);
    private static final ExplorationLimits PROGRAM_LIMITS =
        new ExplorationLimits(100, 100, 8);
    private final RewriteProgramInterpreter interpreter = new RewriteProgramInterpreter();
    private final BudgetedTransformationSourceExecutor executor = new BudgetedTransformationSourceExecutor();

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

    @Test
    @Timeout(10)
    void exposesOnlyTheEvidenceSelectedCandidateUnderExplicitWorkAuthority() {
        VerifiedCandidateEvidence evidence = signEvidence();
        var source = new VerifiedFinitePolynomialCandidateSource(evidence);
        long required = evidence.data().canonicalWork().totalWorkUnits();
        String canonicalSource = evidence.data().sourceExpression();

        var execution = executor.execute(source, canonicalSource, required);

        assertEquals(BudgetedTransformationSource.Status.CANDIDATES, execution.status());
        assertTrue(execution.complete());
        assertEquals(evidence.evidenceHash(), source.evidenceHash());
        assertEquals(evidence.evidenceHash(),
            execution.sourceIdentity().authorityHash());
        assertEquals(1, execution.candidates().size());
        var candidate = execution.candidates().getFirst();
        assertEquals(canonicalSource, candidate.sourceExpression());
        assertEquals(evidence.data().transformedExpression(),
            candidate.transformedExpression());
        assertEquals(evidence.data().theoryStepId(), candidate.theoryStepId());
        assertEquals(evidence.evidenceHash(), candidate.evidenceHash());
        assertEquals(required, candidate.mathematicalWorkUnits());
        assertTrue(candidate.assumptions().isEmpty());
        assertEquals(3,
            execution.sourceResult().mechanicalWorkUnits());
        assertEquals(8,
            execution.mechanicalWork().totalMechanicalWorkUnits());
        assertEquals(
            execution,
            executor.execute(source, canonicalSource, required));
    }

    @Test
    @Timeout(10)
    void executesVerifiedCandidateThroughExplicitTopLevelProgramSource() {
        VerifiedCandidateEvidence evidence = signEvidence();
        var source = new VerifiedFinitePolynomialCandidateSource(evidence);
        var program = budgetedSource("verified-finite-plan", source);
        long required = evidence.data().canonicalWork().totalWorkUnits();
        String canonicalSource = evidence.data().sourceExpression();

        var execution = interpreter.executeBudgetedSource(
            program,
            canonicalSource,
            required);

        assertEquals(BudgetedTransformationSource.Status.CANDIDATES, execution.status());
        assertTrue(execution.complete());
        assertEquals(evidence.evidenceHash(),
            execution.sourceExecution().sourceIdentity().authorityHash());
        assertEquals(1, execution.candidates().size());
        var candidate = execution.candidates().getFirst();
        assertEquals("verified-finite-plan", candidate.originNodeId());
        assertEquals(canonicalSource, candidate.sourceExpression());
        assertEquals(evidence.data().transformedExpression(),
            candidate.transformedExpression());
        assertEquals(evidence.data().theoryStepId(), candidate.theoryStepId());
        assertEquals(evidence.evidenceHash(), candidate.evidenceHash());
        assertEquals(required, candidate.mathematicalWorkUnits());
        assertEquals(0, candidate.primitiveRewriteSteps());
        assertEquals(1, candidate.exactTheorySteps());
        assertEquals(8,
            execution.programWork().delegatedMechanicalWorkUnits());
        assertEquals(12,
            execution.programWork().totalMechanicalWorkUnits());
        assertEquals(
            execution,
            interpreter.executeBudgetedSource(
                program,
                canonicalSource,
                required));

        assertThrows(
            IllegalArgumentException.class,
            () -> interpreter.execute(program, canonicalSource));
    }

    @Test
    @Timeout(10)
    void distinguishesSourceMismatchFromInsufficientMathematicalBudget() {
        VerifiedCandidateEvidence evidence = signEvidence();
        var source = new VerifiedFinitePolynomialCandidateSource(evidence);
        long required = evidence.data().canonicalWork().totalWorkUnits();
        String canonicalSource = evidence.data().sourceExpression();

        var mismatch = executor.execute(source, "y", required);
        assertEquals(BudgetedTransformationSource.Status.NO_MATCH, mismatch.status());
        assertTrue(mismatch.complete());
        assertTrue(mismatch.candidates().isEmpty());
        assertEquals("SOURCE_MISMATCH",
            mismatch.sourceResult().detailCode());
        assertEquals(1, mismatch.sourceResult().mechanicalWorkUnits());

        var insufficient = executor.execute(
            source,
            canonicalSource,
            required - 1);
        assertEquals(BudgetedTransformationSource.Status.BUDGET_INCONCLUSIVE, insufficient.status());
        assertFalse(insufficient.complete());
        assertTrue(insufficient.candidates().isEmpty());
        assertEquals(required,
            insufficient.sourceResult()
                .minimumRequiredMathematicalWorkUnits());
        assertEquals(2,
            insufficient.sourceResult().mechanicalWorkUnits());
    }


    @Test
    void executesRealPrimitiveTheoryPrimitivePathWithCanonicalProvenance() {
        var evidence = unitEvidence("mixed-path");
        var result = mixed(evidence, new PathBudget(2, work(evidence)));
        assertTrue(result.complete());
        var candidate = result.candidates().getFirst();
        assertEquals("x ^ 2", candidate.outputExpression());
        assertEquals(List.of("ast_add_zero_right", "ast_multiply_one_left"), candidate.primitiveRuleIds());
        assertEquals(new ExecutionWork(2, 1, work(evidence)), candidate.executionWork());
        var transformation = candidate.toTransformation();
        assertEquals(candidate.provenance(), transformation.provenance());
        assertEquals(2, transformation.primitiveStepCount());
        assertEquals(1L, transformation.exactTheoryStepCount());
        var sequence = (TransformationProvenance.Sequence) transformation.provenance();
        var theory = (TransformationProvenance.ExactTheoryStep) sequence.steps().get(1).provenance();
        assertEquals(evidence.evidenceHash(), theory.evidence().binding().evidenceHash());
        assertEquals(evidence.toCanonicalJson(), theory.evidence().binding().canonicalEvidenceJson());
        assertEquals(evidence.data().receiptReference().artifactId(), theory.evidence().binding().receiptArtifactId());
        assertEquals(evidence.data().planRunReference().artifactId(), theory.evidence().binding().runArtifactId());
        assertEquals(new PathBudget(1, 0), result.sourceObservations().getLast().availableBudget());
        assertEquals(candidate.executionWork(), result.workMetrics().candidateWork());
        assertEquals(result.workMetrics().totalWorkUnits() + 2 + work(evidence), result.workMetrics().totalWorkUnitsV2());
        assertEquals("regelsuche.rewrite-program-work/v2", result.workRevision());
    }

    @Test
    void rejectsBothInsufficientDimensionsBeforeAcceptingAMixedEndpoint() {
        var evidence = unitEvidence("mixed-short");
        for (PathBudget budget : List.of(new PathBudget(1, work(evidence)), new PathBudget(2, work(evidence) - 1))) {
            var result = mixed(evidence, budget);
            assertFalse(result.complete());
            assertTrue(result.candidates().isEmpty());
            var block = result.sourceObservations().getLast();
            assertFalse(block.admitted());
            assertFalse(block.availableBudget().admits(block.candidate().executionWork()));
            assertEquals(work(evidence), result.workMetrics().candidateWork().exactTheoryWorkUnits());
        }
    }

    @Test
    void mixedRepeatRetainsEndpointsAndDoesNotResetEitherWorkDimension() {
        var evidence = unitEvidence("mixed-repeat");
        var body = choice("route", primitive("pre", "ast_add_zero_right"), ordinaryTheory("theory", evidence),
            primitive("post", "ast_multiply_one_left"));
        var result = interpreter.executeWithWorkBudget(repeat("repeat-mixed", 1, 4, body),
            "x*x + 0", new PathBudget(1, work(evidence)));
        assertFalse(result.complete());
        assertEquals(2, result.candidates().size());
        assertEquals(new ExecutionWork(1, 1, work(evidence)), result.candidates().getLast().executionWork());
        var block = result.sourceObservations().getLast();
        assertEquals("post", block.candidate().originNodeId());
        assertEquals(new PathBudget(0, 0), block.availableBudget());
        assertFalse(block.admitted());
    }

    @Test
    void mixedFirstApplicableCannotHideAnUnresolvedTheoryAlternative() {
        var evidence = unitEvidence("mixed-first");
        var calls = new AtomicInteger();
        var fallback = RewritePrograms.source("fallback", input -> {
            calls.incrementAndGet();
            return List.of(new Transformation("fallback", "x ^ 2"));
        });
        var result = interpreter.executeWithWorkBudget(firstApplicable("first-mixed",
            ordinaryTheory("theory", evidence), fallback), evidence.data().sourceExpression(),
            new PathBudget(10, work(evidence) - 1));
        assertFalse(result.complete());
        assertTrue(result.candidates().isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    void identicalOutputsKeepDistinctVerifiedEvidenceAndPruningKeepsAllObservedWork() {
        var first = unitEvidence("mixed-identity-a");
        var second = unitEvidence("mixed-identity-b");
        assertEquals(first.data().transformedExpression(), second.data().transformedExpression());
        assertNotEquals(first.evidenceHash(), second.evidenceHash());
        var alternatives = choice("two-proofs", ordinaryTheory("a", first), ordinaryTheory("b", second));
        var budget = new PathBudget(0, Math.max(work(first), work(second)));
        var all = interpreter.executeWithWorkBudget(alternatives, first.data().sourceExpression(), budget);
        assertEquals(2, all.candidates().size());
        assertNotEquals(all.transformations().getFirst().applicationKey(), all.transformations().getLast().applicationKey());
        var result = interpreter.executeWithWorkBudget(prune("one", alternatives, 1, "declared first"),
            first.data().sourceExpression(), budget);
        assertFalse(result.complete());
        assertEquals(1, result.candidates().size());
        assertEquals(2, result.sourceObservations().size());
        assertEquals(work(first) + work(second), result.workMetrics().candidateWork().exactTheoryWorkUnits());
        assertEquals(List.of(budget, budget), result.sourceObservations().stream()
            .map(RewriteExecution.SourceObservation::availableBudget).toList());
        var repeated = choice("same-proof", ordinaryTheory("a", first), ordinaryTheory("b", first));
        var deduplicated = interpreter.executeWithWorkBudget(repeated, first.data().sourceExpression(), budget);
        assertEquals(1, deduplicated.candidates().size());
        assertEquals(2 * work(first), deduplicated.workMetrics().candidateWork().exactTheoryWorkUnits());
        assertEquals(1L, deduplicated.workMetrics().duplicateCandidatesDropped());
    }

    @Test
    void requirementAndOrderingCannotDiscardEvidenceOrResetCandidateWork() {
        var evidence = unitEvidence("mixed-filter");
        var ordered = RewritePrograms.prioritize("order", ordinaryTheory("theory", evidence),
            "output", Comparator.comparing(RewriteCandidate::outputExpression));
        var filtered = RewritePrograms.require("reject", ordered, "development rejection", candidate -> false);
        var result = interpreter.executeWithWorkBudget(filtered, evidence.data().sourceExpression(),
            new PathBudget(0, work(evidence)));
        assertTrue(result.complete());
        assertTrue(result.candidates().isEmpty());
        assertEquals(1, result.sourceObservations().size());
        assertEquals(work(evidence), result.workMetrics().candidateWork().exactTheoryWorkUnits());
        assertEquals(1L, result.workMetrics().requirementRejections());
    }

    @Test
    void mixedTraceLevelsAreObservationalAndNameExactTheorySeparately() {
        var evidence = unitEvidence("mixed-trace");
        RewriteExecution expected = null;
        for (RewriteTraceLevel level : RewriteTraceLevel.values()) {
            var events = new ArrayList<RewriteTraceEvent>();
            var result = interpreter.executeWithWorkBudget(mixedProgram(evidence), "x*x + 0",
                new PathBudget(2, work(evidence)), level, events::add);
            if (expected == null) expected = result;
            assertEquals(expected, result);
            if (level == RewriteTraceLevel.OFF) assertTrue(events.isEmpty());
            if (level == RewriteTraceLevel.FULL) {
                var event = events.stream().filter(value -> value.type() == RewriteTraceEventType.EXACT_THEORY_CANDIDATE)
                    .findFirst().orElseThrow();
                assertTrue(event.ruleIds().isEmpty());
                assertTrue(event.detail().contains(evidence.evidenceHash()));
                assertTrue(event.detail().contains("EXACT_THEORY_STEP"));
            }
        }
    }

    @Test
    void evidenceDescriptionsAndCorrectlyHashedProtocolValuesCannotIssueCoreCapabilities() {
        var evidence = unitEvidence("mixed-forgery");
        var verified = ExactTheoryEvidence.fromVerified(evidence);
        for (Object unverified : List.of(evidence.data(), evidence.toCanonicalJson(), evidence.evidenceHash(),
                verified.binding(), new VerifiedFinitePolynomialCandidateSource(evidence)
                    .transform(evidence.data().sourceExpression(), work(evidence)).candidates().getFirst())) {
            assertThrows(IllegalArgumentException.class, () -> ExactTheoryEvidence.fromVerified(unverified));
        }
        assertThrows(NullPointerException.class, () -> ExactTheoryEvidence.fromVerified(null));
        var transformation = Transformation.exactTheory(verified);
        assertThrows(IllegalArgumentException.class, () -> new RewriteCandidate("forged-source", "y",
            transformation.transformedExpression(), List.of(transformation)));
        assertThrows(IllegalArgumentException.class, () -> new Transformation(transformation.rule(), "y",
            transformation.kind(), true, 0, true, transformation.applicationKey(), List.of(), "exact-theory",
            "PROJECT", List.of(), transformation.provenance()));
        assertThrows(IllegalArgumentException.class, () -> new Transformation(transformation.rule(),
            transformation.transformedExpression(), transformation.kind(), true, 0, true,
            transformation.applicationKey(), List.of(), "exact-theory", "PROJECT", List.of()));
    }

    @Test
    void legacyExecutionAndBatchesCannotTurnVerifiedWorkIntoAFreePrimitiveEdge() {
        var evidence = unitEvidence("mixed-legacy");
        var source = new VerifiedFinitePolynomialTransformationEngine(evidence);
        assertThrows(IllegalArgumentException.class, () -> source.transform(evidence.data().sourceExpression()));
        var calls = new AtomicInteger();
        var program = choice("preflight", RewritePrograms.source("ordinary", input -> {
            calls.incrementAndGet();
            return List.of();
        }), ordinaryTheory("theory", evidence));
        assertThrows(IllegalArgumentException.class, () -> interpreter.execute(program, evidence.data().sourceExpression()));
        assertEquals(0, calls.get());
        var result = mixed(evidence, new PathBudget(2, work(evidence)));
        assertThrows(IllegalArgumentException.class, () -> new TransformationBatch(result.transformations(), result.workMetrics()));
        assertThrows(IllegalArgumentException.class, () -> new RewriteExecution(result.candidates(), true));
        assertThrows(IllegalArgumentException.class, () -> new RewriteExecution(result.candidates(), true,
            TransformationWorkMetrics.ZERO, result.sourceObservations(), result.pathBudget()));
        assertThrows(IllegalArgumentException.class, () -> new RewriteExecution(result.candidates(), true,
            TransformationWorkMetrics.ZERO, List.of(), result.pathBudget()));
    }

    @Test
    void freshReplayReproducesCanonicalMixedProvenanceAndSourceMismatchEmitsNothing() {
        var first = unitEvidence("mixed-replay");
        var replayed = unitEvidence("mixed-replay");
        var before = mixed(first, new PathBudget(2, work(first))).transformations().getFirst();
        var after = mixed(replayed, new PathBudget(2, work(replayed))).transformations().getFirst();
        assertEquals(before, after);
        assertEquals(before.provenance().contentHash(), after.provenance().contentHash());
        assertEquals(before.provenance().toCanonicalJson(), after.provenance().toCanonicalJson());
        var source = new VerifiedFinitePolynomialTransformationEngine(first);
        assertTrue(source.verifiedTransformations("x^2").isEmpty());
        assertTrue(source.verifiedTransformations("y*y").isEmpty());
    }

    @Test
    void nestedProgramSourceRetainsIncompleteOutcomesEvidenceAndIncomingBudget() {
        var evidence = unitEvidence("mixed-nested");
        var nested = new de.regelsuche.search.program.ProgrammedTransformationEngine(
            choice("nested-choice", ordinaryTheory("theory", evidence),
                RewritePrograms.source("too-long", input -> List.of(new Transformation("macro", "y",
                    de.regelsuche.transform.RewriteKind.NORMALIZE, false, 0, true, "macro@y", List.of(),
                    "core", "PROJECT", List.of("one", "two"))))));
        var program = sequence("outer", primitive("pre", "ast_add_zero_right"),
            RewritePrograms.source("nested", nested), primitive("post", "ast_multiply_one_left"));
        var result = interpreter.executeWithWorkBudget(program, "x*x + 0", new PathBudget(2, work(evidence)));
        assertFalse(result.complete());
        assertEquals("x ^ 2", result.candidates().getFirst().outputExpression());
        assertEquals(new ExecutionWork(2, 1, work(evidence)), result.candidates().getFirst().executionWork());
        assertTrue(result.sourceObservations().stream().anyMatch(observation -> !observation.admitted()
            && observation.availableBudget().primitiveRewriteUnits() == 1));
        assertEquals(work(evidence), result.workMetrics().candidateWork().exactTheoryWorkUnits());
    }

    @Test
    void primitiveTextKeyCannotCollideWithExactTheoryProvenanceDuringDeduplication() {
        var evidence = unitEvidence("mixed-kind-collision");
        var theory = new VerifiedFinitePolynomialTransformationEngine(evidence)
            .verifiedTransformations(evidence.data().sourceExpression()).getFirst();
        var collidingTextKey = theory.applicationKey() + "\u0000" + theory.provenance().contentHash();
        var primitive = new Transformation("primitive", theory.transformedExpression(), theory.kind(),
            false, 0, true, collidingTextKey);
        var program = choice("structural-identities", ordinaryTheory("theory", evidence),
            RewritePrograms.source("primitive", input -> List.of(primitive)));
        var result = interpreter.executeWithWorkBudget(program, evidence.data().sourceExpression(),
            new PathBudget(1, work(evidence)));
        assertEquals(2, result.candidates().size());
        assertEquals(0L, result.workMetrics().duplicateCandidatesDropped());
    }

    private static VerifiedCandidateEvidence unitEvidence(String id) {
        return prepare(id, "x*x", "(${unit}*x)^2",
            List.of(HoleDomain.integerRange("unit", 1, 1)), 1).evidence().getFirst();
    }

    private static RewriteProgram ordinaryTheory(String id, VerifiedCandidateEvidence evidence) {
        return RewritePrograms.source(id, new VerifiedFinitePolynomialTransformationEngine(evidence));
    }

    private static RewriteProgram primitive(String id, String ruleId) {
        return RewritePrograms.source(id, new AstRewriteTransformationEngine(AstRewriteTransformationEngine.defaultRules()
            .stream().filter(rule -> rule.id().equals(ruleId)).toList()));
    }

    private static RewriteProgram mixedProgram(VerifiedCandidateEvidence evidence) {
        return sequence("mixed", primitive("pre", "ast_add_zero_right"), ordinaryTheory("theory", evidence),
            primitive("post", "ast_multiply_one_left"));
    }

    private RewriteExecution mixed(VerifiedCandidateEvidence evidence, PathBudget budget) {
        return interpreter.executeWithWorkBudget(mixedProgram(evidence), "x*x + 0", budget);
    }

    private static VerifiedCandidateEvidence signEvidence() {
        return prepare("budgeted-candidate-source", "x*x", "(${sign}*x)^2",
            List.of(HoleDomain.signs("sign")), 2).evidence().getFirst();
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
