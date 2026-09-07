package de.regelsuche.evolution;

import static de.regelsuche.search.program.RewritePrograms.budgetedSource;
import static de.regelsuche.search.program.RewritePrograms.sequence;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.ExactFinitePolynomialPlanCandidateEvidenceVerifier.VerifiedCandidateEvidence;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.LoadedArtifact;
import de.regelsuche.evolution.ExactFinitePolynomialTraceLearner.LearnedPlan;
import de.regelsuche.evolution.ExactFinitePolynomialTraceLearner.Limits;
import de.regelsuche.evolution.ExactFinitePolynomialTraceLearner.TrainingTrace;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleKind;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.ExplorationLimits;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.PathBudget;
import de.regelsuche.search.program.RewriteProgram;
import de.regelsuche.search.program.RewriteProgramInterpreter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real solver, verifier-owned evidence and interpreter; no mathematical mocks. */
@Timeout(60)
class ExactFinitePolynomialTraceLearnerTest {
    private static final SchematicProofPlan.Limits PLAN_LIMITS = new SchematicProofPlan.Limits(8, 8, 4, 200_000);
    private static final ExplorationLimits PROGRAM_LIMITS = new ExplorationLimits(100, 100, 8);
    private static final Limits LEARNING_LIMITS = new Limits(3, 8, 4, 4, 0, 16, 10_000);
    private final ExactFinitePolynomialTraceLearner learner = new ExactFinitePolynomialTraceLearner();

    @Test
    void derivesTwoTemplatesFromVerifiedTracesWithoutReceivingTheirAnsatzInputs() {
        LearnedPlan plan = learner.learn(training(), LEARNING_LIMITS);
        assertEquals(2, plan.stages().size());
        assertEquals(3, plan.trainingRoots().size());
        assertEquals(2, plan.stages().getFirst().holeDomains().size());
        assertEquals(2, plan.stages().getLast().holeDomains().size());
        assertTrue(plan.stages().getFirst().ansatzTemplate().contains("${variable}"));
        assertFalse(plan.stages().getFirst().ansatzTemplate().contains("${shift}"));
        assertTrue(plan.toCanonicalJson().contains("NON_EXECUTABLE_REQUIRES_FRESH_VERIFICATION"));
        assertEquals(SchematicProofPlan.hash(plan.toCanonicalJson()), plan.contentHash());
    }

    @Test
    void freezesBeforeRebindingAndVerifiesTwoUnseenCoefficientInstances() {
        LearnedPlan plan = learner.learn(training(), LEARNING_LIMITS);
        String frozen = plan.contentHash();
        // Held out from learner input, but development cases, not a preregistered FINAL TEST.
        var first = reuse(plan, "z^2 + 10*z + 16");
        var second = reuse(plan, "t^2 + 12*t + 35");
        assertEquals(2L, first.candidates().getFirst().exactTheorySteps());
        assertEquals(0L, first.candidates().getFirst().primitiveRewriteSteps());
        assertTrue(List.of("(z + 2) * (z + 8)", "(z + 8) * (z + 2)")
            .contains(first.candidates().getFirst().transformedExpression()));
        assertTrue(List.of("(t + 5) * (t + 7)", "(t + 7) * (t + 5)")
            .contains(second.candidates().getFirst().transformedExpression()));
        assertEquals(frozen, plan.contentHash());
        assertNotEquals(first.contentHash(), second.contentHash());
        assertEquals(first.contentHash(), reuse(plan, "z^2 + 10*z + 16").contentHash());
    }

    @Test
    void sharesOneHoleForRepeatedVaryingCoefficientColumns() {
        List<TrainingTrace> traces = new ArrayList<>();
        for (int shift : List.of(2, 3, 4)) {
            String source = "x^2 + " + (2 * shift) + "*x + " + (shift * shift);
            var evidence = prepare("square", source, "(x+${offset})*(x+${offset})",
                List.of(HoleDomain.integerRange("offset", 0, 6)), 2);
            traces.add(trace(List.of(evidence)));
        }
        LearnedPlan plan = learner.learn(traces, LEARNING_LIMITS);
        assertEquals(1, plan.stages().getFirst().holeDomains().size());
        String template = plan.stages().getFirst().ansatzTemplate();
        assertEquals(2, template.split("\\$\\{coefficient-0}", -1).length - 1);
        var request = plan.instantiate(0, "y^2 + 10*y + 25").orElseThrow();
        var evidence = prepare("repeated-reuse", request.sourceExpression(), request.ansatzTemplate(), request.holeDomains(), 2);
        assertEquals("(y + 5) * (y + 5)", evidence.data().transformedExpression());
    }

    @Test
    void trainingOrderDoesNotChangeFrozenTemplatesOrIdentity() {
        var traces = training();
        var first = learner.learn(traces, LEARNING_LIMITS);
        Collections.reverse(traces);
        var second = learner.learn(traces, LEARNING_LIMITS);
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
    }

    @Test
    void rejectsDuplicateAndAlphaRenamedSupport() {
        var traces = training();
        assertThrows(IllegalArgumentException.class, () -> learner.learn(
            List.of(traces.getFirst(), traces.getFirst(), traces.getLast()), LEARNING_LIMITS));
        assertThrows(IllegalArgumentException.class, () -> learner.learn(
            List.of(chain("x", 6, 5), chain("another", 6, 5), chain("z", 8, 12)), LEARNING_LIMITS));
    }

    @Test
    void rejectsIncompleteTrainingExecution() {
        var valid = training();
        var evidence = valid.getFirst().evidence();
        var incomplete = execute(evidence, total(evidence) - 1);
        var bad = new TrainingTrace(incomplete, valid.getFirst().selectedPathHash(), evidence);
        assertThrows(IllegalArgumentException.class, () -> learner.learn(
            List.of(bad, valid.get(1), valid.get(2)), LEARNING_LIMITS));
    }

    @Test
    void rejectsMissingPathAndSubstitutedVerifierEvidence() {
        var valid = training();
        var absent = new TrainingTrace(valid.getFirst().execution(), SchematicProofPlan.hash("missing"), valid.getFirst().evidence());
        assertThrows(IllegalArgumentException.class, () -> learner.learn(
            List.of(absent, valid.get(1), valid.get(2)), LEARNING_LIMITS));
        var substituted = new TrainingTrace(valid.getFirst().execution(), valid.getFirst().selectedPathHash(), valid.get(1).evidence());
        assertThrows(IllegalArgumentException.class, () -> learner.learn(
            List.of(substituted, valid.get(1), valid.get(2)), LEARNING_LIMITS));
    }

    @Test
    void rejectsTruncatedSolverEvidenceAsTrainingSupport() {
        var evidence = prepare("truncated", "x*x", "(${sign}*x)^2", List.of(HoleDomain.signs("sign")), 1);
        var bad = trace(List.of(evidence));
        assertThrows(IllegalArgumentException.class, () -> learner.learn(
            List.of(bad, training().getFirst()), new Limits(2, 4, 4, 4, 0, 16, 10_000)));
    }

    @Test
    void boundsHolesAndTheirCartesianWorkBeforeCreatingAReusablePlan() {
        var traces = training();
        assertThrows(IllegalArgumentException.class, () -> learner.learn(traces,
            new Limits(3, 8, 4, 1, 0, 16, 10_000)));
        assertThrows(IllegalArgumentException.class, () -> learner.learn(traces,
            new Limits(3, 8, 4, 4, 0, 16, 288)));
        assertThrows(IllegalArgumentException.class, () -> learner.learn(traces,
            new Limits(3, 8, 4, 4, 0, 3, 10_000)));
    }

    @Test
    void keepsNoSolutionAndFrozenFiniteGrammarSeparateFromMathematicalImpossibility() {
        LearnedPlan plan = learner.learn(training(), LEARNING_LIMITS);
        String frozen = plan.contentHash();
        var completeRequest = plan.instantiate(0, "u^2 + 10*u + 17").orElseThrow();
        var completion = prepare("negative-completion", completeRequest.sourceExpression(),
            completeRequest.ansatzTemplate(), completeRequest.holeDomains(), 2);
        var factors = plan.instantiate(1, completion.data().transformedExpression()).orElseThrow();
        var resolver = new ExactFinitePolynomialPlanResolver();
        var pending = resolver.createPlan("negative-factorization", factors.sourceExpression(),
            factors.ansatzTemplate(), factors.holeDomains(), 2, PLAN_LIMITS);
        var result = resolver.resolve(pending, factors.sourceExpression(), factors.ansatzTemplate(), factors.holeDomains(), 2);
        assertEquals(ExactFinitePolynomialPlanRun.Status.COMPLETE_WITHOUT_SOLUTION, result.status());
        assertEquals(frozen, plan.contentHash());
    }

    @Test
    void rejectsWrongShapeUnsupportedSyntaxAndUnboundedInputBeforeSolving() {
        LearnedPlan plan = learner.learn(training(), LEARNING_LIMITS);
        assertTrue(plan.instantiate(0, "x^3 + 10*x + 16").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> plan.instantiate(0, "x+y"));
        assertThrows(IllegalArgumentException.class, () -> plan.instantiate(0, "x/2"));
        assertThrows(IllegalArgumentException.class, () -> plan.instantiate(0, "sin(x)"));
        assertThrows(IllegalArgumentException.class, () -> plan.instantiate(0, "x+0.5"));
        assertThrows(IllegalArgumentException.class, () -> plan.instantiate(0, "x" + "+1".repeat(200)));
    }

    @Test
    void exactLargeIntegersCannotCollapseIntoAFixedCoefficientThroughDouble() {
        List<TrainingTrace> traces = new ArrayList<>();
        for (String integer : List.of("9007199254740992", "9007199254740993")) {
            var domain = new HoleDomain("coefficient", HoleKind.COEFFICIENT,
                List.of(ExactRational.integer(new BigInteger(integer))));
            traces.add(trace(List.of(prepare("large", "x+" + integer,
                "${coefficient}+x", List.of(domain), 1))));
        }
        var error = assertThrows(IllegalArgumentException.class, () -> learner.learn(traces,
            new Limits(2, 4, 4, 4, 0, 16, 10_000)));
        assertTrue(error.getMessage().contains("outside frozen finite domain"));
    }

    @Test
    void rejectsDifferentStepCountsAndSourceStructures() {
        var traces = training();
        var shorter = trace(List.of(traces.getLast().evidence().getFirst()));
        assertThrows(IllegalArgumentException.class, () -> learner.learn(
            List.of(traces.getFirst(), traces.get(1), shorter), LEARNING_LIMITS));
        var unlike = trace(List.of(prepare("unlike", "5*x+3*x", "${coefficient}*x",
            List.of(HoleDomain.integerRange("coefficient", 0, 10)), 1)));
        var other = trace(List.of(prepare("other", "x+x+x", "${coefficient}*x",
            List.of(HoleDomain.integerRange("coefficient", 0, 10)), 1)));
        assertThrows(IllegalArgumentException.class, () -> learner.learn(List.of(unlike, other),
            new Limits(2, 4, 4, 4, 0, 16, 10_000)));
    }

    @Test
    void refusesEquivalentSourcesDisguisedAsDifferentDecompositions() {
        var first = trace(List.of(prepare("fixed-one", "3*x+1*x", "${coefficient}*x",
            List.of(HoleDomain.integerRange("coefficient", 0, 8)), 1)));
        var second = trace(List.of(prepare("fixed-two", "2*x+2*x", "${coefficient}*x",
            List.of(HoleDomain.integerRange("coefficient", 0, 8)), 1)));
        var error = assertThrows(IllegalArgumentException.class, () -> learner.learn(List.of(first, second),
            new Limits(2, 4, 4, 4, 0, 16, 10_000)));
        assertTrue(error.getMessage().contains("equivalent training input"));
    }

    @Test
    void immutablePlanAndPolicyIdentityCannotChangeAfterReuse() {
        var traces = training();
        var plan = learner.learn(traces, LEARNING_LIMITS);
        var other = learner.learn(traces, new Limits(3, 8, 4, 4, 0, 17, 10_000));
        assertNotEquals(plan.contentHash(), other.contentHash());
        assertThrows(UnsupportedOperationException.class, () -> plan.stages().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.trainingRoots().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.stages().getFirst().holeDomains().clear());
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 8, 4, 4, 0, 16, 10_000));
        assertThrows(IllegalArgumentException.class, () -> new Limits(3, 8, 4, 4, Integer.MIN_VALUE, Integer.MAX_VALUE, 10_000));
    }

    @Test
    void doesNotGeneralizeExponentsAsNumericCoefficients() {
        var first = trace(List.of(prepare("power-one", "2*x-1*x", "1*x+0*x^2+${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 1)), 1)));
        var second = trace(List.of(prepare("power-two", "3*y-1*y", "2*y+0*y^3+${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 1)), 1)));
        var error = assertThrows(IllegalArgumentException.class, () -> learner.learn(List.of(first, second),
            new Limits(2, 4, 4, 4, 0, 16, 10_000)));
        assertTrue(error.getMessage().contains("varying exponent"));
    }

    @Test
    void rejectsDifferentOutputOperatorsRatherThanInventingATermHole() {
        var first = trace(List.of(prepare("operator-one", "2*x", "${coefficient}*x+0",
            List.of(HoleDomain.integerRange("coefficient", 0, 4)), 1)));
        var second = trace(List.of(prepare("operator-two", "3*y", "${coefficient}*y*1",
            List.of(HoleDomain.integerRange("coefficient", 0, 4)), 1)));
        var error = assertThrows(IllegalArgumentException.class, () -> learner.learn(List.of(first, second),
            new Limits(2, 4, 4, 4, 0, 16, 10_000)));
        assertTrue(error.getMessage().contains("different operator structure"));
    }


    @Test
    void rejectsReorderedAndFactoredVersionsOfTheSameTrainingPolynomial() {
        var base = trace(List.of(prepare("base", "x^2+6*x+5", "(x+${left})*(x+${right})",
            List.of(HoleDomain.integerRange("left", 0, 6), HoleDomain.integerRange("right", 0, 6)), 2)));
        for (String duplicate : List.of("5+6*y+y^2", "(y+1)*(y+5)", "y^2+7*y-y+5")) {
            var another = trace(List.of(prepare("duplicate", duplicate, "(y+${shift})^2+${constant}",
                List.of(HoleDomain.integerRange("shift", 0, 4), HoleDomain.integerRange("constant", -5, 0)), 2)));
            var error = assertThrows(IllegalArgumentException.class, () -> learner.learn(List.of(base, another),
                new Limits(2, 4, 4, 4, 0, 16, 10_000)));
            assertTrue(error.getMessage().contains("equivalent training input"));
        }
    }

    @Test
    void stageZeroRejectsTrainingEquivalentReuseBeforeShapeSelection() {
        var plan = learner.learn(training(), LEARNING_LIMITS);
        String identity = plan.contentHash();
        for (String duplicate : List.of("x^2+6*x+5", "5+6*z+z^2", "(z+1)*(z+5)",
                "z^2+7*z-z+5", "(z+3)^2-4")) {
            var error = assertThrows(IllegalArgumentException.class, () -> plan.instantiate(0, duplicate));
            assertTrue(error.getMessage().contains("training-equivalent input"));
        }
        assertEquals(3, plan.trainingInputIdentities().size());
        assertTrue(plan.trainingIdentityWorkUnits() > 0);
        assertTrue(plan.toCanonicalJson().contains("trainingInputIdentities"));
        assertThrows(UnsupportedOperationException.class, () -> plan.trainingInputIdentities().clear());
        assertEquals(identity, plan.contentHash());
    }

    @Test
    void exactInputIdentityDoesNotRejectDistinctIntegersBeyondBinary64() {
        // A common large literal stays fixed in the outputs; another exact column varies.
        List<TrainingTrace> traces = new ArrayList<>();
        for (int k : List.of(0, 2)) {
            traces.add(trace(List.of(prepare("large-fixed", "x+9007199254740992+" + k,
                "x+9007199254740992+${constant}+0", List.of(HoleDomain.integerRange("constant", 0, 2)), 1))));
        }
        var plan = learner.learn(traces, new Limits(2, 4, 4, 4, 0, 16, 10_000));
        assertTrue(plan.instantiate(0, "y+9007199254740993+0").isPresent());
        assertThrows(IllegalArgumentException.class, () -> plan.instantiate(0, "y+9007199254740992+0"));
    }

    @Test
    void inputIdentityWorkLimitIsNotTreatedAsANovelInputOrAMiss() {
        var plan = learner.learn(training(), LEARNING_LIMITS);
        var error = assertThrows(IllegalArgumentException.class, () -> plan.instantiate(0, "(x^32)^32"));
        assertTrue(error.getMessage().contains("BUDGET_INCONCLUSIVE"));
    }

    @Test
    void missingOrExcessiveTraceEvidenceIsRejectedBeforeGeneralization() {
        var valid = training().getFirst();
        assertThrows(IllegalArgumentException.class, () -> new TrainingTrace(valid.execution(),
            valid.selectedPathHash(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TrainingTrace(valid.execution(),
            valid.selectedPathHash(), Collections.nCopies(9, valid.evidence().getFirst())));
        assertThrows(IllegalArgumentException.class, () -> learner.learn(List.of(valid), LEARNING_LIMITS));
    }

    private static List<TrainingTrace> training() {
        return new ArrayList<>(List.of(chain("x", 6, 5), chain("y", 8, 12), chain("u", 8, 7)));
    }

    private static TrainingTrace chain(String variable, int linear, int constant) {
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

    private static TrainingTrace trace(List<VerifiedCandidateEvidence> evidence) {
        var execution = execute(evidence, total(evidence));
        return new TrainingTrace(execution, execution.candidates().getFirst().contentHash(), evidence);
    }

    private static long total(List<VerifiedCandidateEvidence> evidence) {
        return evidence.stream().mapToLong(e -> e.data().canonicalWork().totalWorkUnits()).reduce(0L, Math::addExact);
    }

    private static BudgetedRewriteProgramExecution execute(List<VerifiedCandidateEvidence> evidence, long budget) {
        List<RewriteProgram> steps = new ArrayList<>();
        for (int i = 0; i < evidence.size(); i++) {
            steps.add(budgetedSource("stage-" + i, new VerifiedFinitePolynomialCandidateSource(evidence.get(i))));
        }
        return new RewriteProgramInterpreter().executeBudgeted(new RewriteProgram.Sequence(
            RewriteProgram.NodeMetadata.named("verified-training-sequence"), steps),
            evidence.getFirst().data().sourceExpression(), new PathBudget(0, budget), PROGRAM_LIMITS);
    }

    private static BudgetedRewriteProgramExecution reuse(LearnedPlan learned, String source) {
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

    private static VerifiedCandidateEvidence prepare(String id, String source, String ansatz,
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
