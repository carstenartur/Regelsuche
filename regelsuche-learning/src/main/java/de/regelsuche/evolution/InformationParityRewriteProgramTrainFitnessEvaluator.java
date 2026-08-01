package de.regelsuche.evolution;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainFitnessEvidence.CaseMeasurement;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainFitnessEvidence.PathCorrectness;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.math.algorithms.equivalence.RationalFunctionNormalFormEquivalenceService;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.PrimitiveWorkBestFirstSearchStrategy;
import de.regelsuche.search.strategy.PrimitiveWorkBestFirstSearchStrategy.Budget;
import de.regelsuche.search.strategy.PrimitiveWorkBestFirstSearchStrategy.Problem;
import de.regelsuche.search.strategy.PrimitiveWorkBestFirstSearchStrategy.Result;
import de.regelsuche.search.strategy.PrimitiveWorkBestFirstSearchStrategy.State;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngines;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Authoritative paired TRAIN evaluator for learned rewrite programs.
 *
 * <p>Both runs receive the ordinary rule inventory and every rule compiled from
 * the candidate genome. The candidate run differs only by the additional
 * compiled program. Both runs use the same primitive-step and total-work
 * budgets.</p>
 */
public final class InformationParityRewriteProgramTrainFitnessEvaluator {
    private static final Set<FitnessComponent> SUPPORTED_COMPONENTS = Set.of(
        FitnessComponent.TRAIN_CASES_NEWLY_SOLVED,
        FitnessComponent.TRAIN_PATH_LENGTH_REDUCTION,
        FitnessComponent.TRAIN_EXPLORED_STATE_REDUCTION,
        FitnessComponent.SUPPORT,
        FitnessComponent.ASSUMPTION_SIMPLICITY,
        FitnessComponent.CANDIDATE_COMPLEXITY,
        FitnessComponent.PROOF_COST_PROXY);

    private final EvolutionRewriteProgramTrainSuite suite;
    private final Set<FitnessComponent> requiredComponents;
    private final EvolutionGenomeCompiler genomeCompiler;
    private final EvolutionRewriteProgramCompiler programCompiler;
    private final RationalFunctionNormalFormEquivalenceService equivalence;
    private final WorkSearchRunner searchRunner;

    public InformationParityRewriteProgramTrainFitnessEvaluator(
        EvolutionRewriteProgramTrainSuite suite,
        Set<FitnessComponent> requiredComponents
    ) {
        this(
            suite,
            requiredComponents,
            new EvolutionGenomeCompiler(),
            new EvolutionRewriteProgramCompiler(),
            new RationalFunctionNormalFormEquivalenceService(),
            InformationParityRewriteProgramTrainFitnessEvaluator::search);
    }

    InformationParityRewriteProgramTrainFitnessEvaluator(
        EvolutionRewriteProgramTrainSuite suite,
        Set<FitnessComponent> requiredComponents,
        EvolutionGenomeCompiler genomeCompiler,
        EvolutionRewriteProgramCompiler programCompiler,
        RationalFunctionNormalFormEquivalenceService equivalence,
        WorkSearchRunner searchRunner
    ) {
        this.suite = Objects.requireNonNull(suite, "suite");
        Objects.requireNonNull(requiredComponents, "requiredComponents");
        if (requiredComponents.isEmpty()) {
            throw new IllegalArgumentException(
                "requiredComponents must not be empty");
        }
        this.requiredComponents = Set.copyOf(requiredComponents);
        this.genomeCompiler = Objects.requireNonNull(
            genomeCompiler, "genomeCompiler");
        this.programCompiler = Objects.requireNonNull(
            programCompiler, "programCompiler");
        this.equivalence = Objects.requireNonNull(equivalence, "equivalence");
        this.searchRunner = Objects.requireNonNull(searchRunner, "searchRunner");
    }

    public EvolutionRewriteProgramTrainFitnessEvidence evaluate(
        EvolutionRewriteProgramCandidate candidate
    ) {
        Objects.requireNonNull(candidate, "candidate");
        EvolutionGenome genome = candidate.genome();
        List<String> blockers = new ArrayList<>();
        if (genome.trainingScope().sourceSplit()
                != EvolutionGenome.SourceSplit.TRAIN) {
            blockers.add("FITNESS_SOURCE_IS_NOT_TRAIN");
        }
        if (suite.evaluatorProfile()
                != EvolutionRewriteProgramTrainSuite.EvaluatorProfile
                    .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS) {
            blockers.add("UNSUPPORTED_EVALUATOR_PROFILE:"
                + suite.evaluatorProfile());
        }
        if (suite.primitiveWorkBudget().maxCandidatesPerState()
                < genome.budget().maxCandidatesPerState()) {
            blockers.add(
                "SUITE_CANDIDATE_BOUND_NARROWER_THAN_GENOME_AND_PROGRAM_SOURCES");
        }

        EvolutionGenomeCompiler.CompiledProgram flatGenome;
        EvolutionRewriteProgramCompiler.CompiledRewriteProgram compiledProgram;
        try {
            flatGenome = genomeCompiler.compile(genome);
            compiledProgram = programCompiler.compile(genome, candidate.plan());
        } catch (RuntimeException exception) {
            blockers.add("CANDIDATE_COMPILATION_FAILED:"
                + stableFailure(exception));
            return EvolutionRewriteProgramTrainFitnessEvidence.create(
                suite, candidate, List.of(), zeroComponents(), blockers);
        }

        int sourceCandidateLimit = Math.min(
            suite.primitiveWorkBudget().maxCandidatesPerState(),
            genome.budget().maxCandidatesPerState());
        MeasuredTransformationEngine ordinary =
            MeasuredTransformationEngines.counting(
                new AstRewriteTransformationEngine(
                    AstRewriteTransformationEngine.defaultRules(),
                    genome.budget().maxAstGrowthPerStep(),
                    sourceCandidateLimit));
        MeasuredTransformationEngine flatGenomeRules =
            MeasuredTransformationEngines.counting(
                new AstRewriteTransformationEngine(
                    flatGenome.rules(),
                    genome.budget().maxAstGrowthPerStep(),
                    sourceCandidateLimit));
        MeasuredTransformationEngine baseline =
            MeasuredTransformationEngines.union(
                ordinary,
                flatGenomeRules);
        MeasuredTransformationEngine candidateEngine =
            MeasuredTransformationEngines.union(
                ordinary,
                flatGenomeRules,
                compiledProgram.engine());
        Budget budget = effectiveBudget(genome);

        List<CaseMeasurement> measurements = new ArrayList<>();
        for (EvolutionRewriteProgramTrainSuite.TrainCase trainCase
                : suite.cases()) {
            try {
                measurements.add(evaluateCase(
                    trainCase, budget, baseline, candidateEngine));
            } catch (RuntimeException exception) {
                blockers.add("TRAIN_CASE_EVALUATION_FAILED:"
                    + trainCase.caseId() + ":" + stableFailure(exception));
                measurements.add(failedMeasurement(trainCase));
            }
        }
        addCaseBlockers(measurements, blockers);

        Map<FitnessComponent, Integer> components = components(
            candidate, measurements);
        requiredComponents.stream()
            .filter(component -> !SUPPORTED_COMPONENTS.contains(component))
            .sorted(Comparator.comparing(Enum::name))
            .forEach(component -> {
                components.put(component, 0);
                blockers.add("UNSUPPORTED_PROGRAM_TRAIN_FITNESS_COMPONENT:"
                    + component);
            });
        components.keySet().removeIf(
            component -> !requiredComponents.contains(component));

        return EvolutionRewriteProgramTrainFitnessEvidence.create(
            suite, candidate, measurements, components, blockers);
    }

    private CaseMeasurement evaluateCase(
        EvolutionRewriteProgramTrainSuite.TrainCase trainCase,
        Budget budget,
        MeasuredTransformationEngine baselineEngine,
        MeasuredTransformationEngine candidateEngine
    ) {
        Result baseline = searchRunner.search(
            baselineEngine,
            trainCase.inputExpression(),
            trainCase.targetExpression(),
            budget);
        Result candidate = searchRunner.search(
            candidateEngine,
            trainCase.inputExpression(),
            trainCase.targetExpression(),
            budget);
        PathAudit baselineAudit = auditPath(baseline, trainCase.assumptions());
        PathAudit candidateAudit = auditPath(candidate, trainCase.assumptions());
        boolean programUsed = candidate.reached()
            && candidate.reachedState().programUsed();
        boolean newlySolved = !baseline.reached()
            && candidate.reached()
            && programUsed
            && candidateAudit.correctness() == PathCorrectness.CONFIRMED;
        boolean reachabilityRegression = baseline.reached() && !candidate.reached();
        boolean correctnessFailure = candidate.reached()
            && (candidateAudit.correctness() == PathCorrectness.REFUTED
                || candidateAudit.correctness()
                    == PathCorrectness.MISSING_ASSUMPTION);
        boolean correctnessRegression = baseline.reached()
            && baselineAudit.correctness() == PathCorrectness.CONFIRMED
            && correctnessFailure;
        return new CaseMeasurement(
            trainCase.caseId(),
            trainCase.familyId(),
            baseline.status().name(),
            candidate.status().name(),
            baseline.reached(),
            candidate.reached(),
            baselineAudit.correctness(),
            candidateAudit.correctness(),
            edgeDepth(baseline),
            edgeDepth(candidate),
            baselineAudit.primitiveSteps(),
            candidateAudit.primitiveSteps(),
            baseline.metrics().exploredStates(),
            candidate.metrics().exploredStates(),
            baseline.metrics().generatedTransformations(),
            candidate.metrics().generatedTransformations(),
            baseline.metrics().transformationWork(),
            candidate.metrics().transformationWork(),
            programUsed,
            newlySolved,
            reachabilityRegression,
            correctnessFailure,
            correctnessRegression);
    }

    private PathAudit auditPath(
        Result result,
        List<String> declaredAssumptions
    ) {
        if (!result.reached()) {
            return new PathAudit(PathCorrectness.NOT_EVALUATED, 0);
        }
        State reached = result.reachedState();
        Set<String> declared = Set.copyOf(
            AssumptionSignature.ofExpressions(declaredAssumptions)
                .normalizedAssumptions());
        if (!declared.containsAll(reached.assumptions())) {
            return new PathAudit(
                PathCorrectness.MISSING_ASSUMPTION,
                reached.primitiveDepth());
        }
        List<String> path = reached.path();
        if (path.isEmpty()) {
            return new PathAudit(PathCorrectness.UNSUPPORTED, 0);
        }
        for (int index = 0; index + 1 < path.size(); index++) {
            var evaluation = equivalence.evaluate(
                path.get(index), path.get(index + 1), declaredAssumptions);
            PathCorrectness correctness = switch (evaluation.status()) {
                case CONFIRMED -> PathCorrectness.CONFIRMED;
                case REFUTED -> PathCorrectness.REFUTED;
                case MISSING_ASSUMPTION -> PathCorrectness.MISSING_ASSUMPTION;
                case UNSUPPORTED -> PathCorrectness.UNSUPPORTED;
            };
            if (correctness != PathCorrectness.CONFIRMED) {
                return new PathAudit(correctness, reached.primitiveDepth());
            }
        }
        return new PathAudit(
            PathCorrectness.CONFIRMED,
            reached.primitiveDepth());
    }

    private static void addCaseBlockers(
        List<CaseMeasurement> measurements,
        List<String> blockers
    ) {
        measurements.stream()
            .filter(CaseMeasurement::reachabilityRegression)
            .map(item -> "TRAIN_REACHABILITY_REGRESSION:" + item.caseId())
            .forEach(blockers::add);
        measurements.stream()
            .filter(CaseMeasurement::correctnessFailure)
            .map(item -> "TRAIN_CORRECTNESS_FAILURE:" + item.caseId()
                + ":" + item.candidatePathCorrectness())
            .forEach(blockers::add);
        measurements.stream()
            .filter(item -> item.candidateReached()
                && item.candidatePathCorrectness() == PathCorrectness.UNSUPPORTED)
            .map(item -> "TRAIN_CORRECTNESS_UNSUPPORTED:" + item.caseId())
            .forEach(blockers::add);
    }

    private Budget effectiveBudget(EvolutionGenome genome) {
        EvolutionRewriteProgramTrainSuite.PrimitiveWorkBudget configured =
            suite.primitiveWorkBudget();
        return new Budget(
            Math.min(
                configured.maxPrimitiveSteps(),
                genome.budget().maxApplicationsPerPath()),
            configured.maxExploredStates(),
            Math.min(
                configured.maxCandidatesPerState(),
                genome.budget().maxCandidatesPerState()),
            Math.min(
                configured.maxExpandingSteps(),
                genome.budget().maxApplicationsPerPath()),
            configured.maxWorkUnits());
    }

    private Map<FitnessComponent, Integer> components(
        EvolutionRewriteProgramCandidate candidate,
        List<CaseMeasurement> measurements
    ) {
        EnumMap<FitnessComponent, Integer> result =
            new EnumMap<>(FitnessComponent.class);
        int caseCount = suite.cases().size();
        int confirmedReached = Math.toIntExact(measurements.stream()
            .filter(CaseMeasurement::candidateReached)
            .filter(item -> item.candidatePathCorrectness()
                == PathCorrectness.CONFIRMED)
            .count());
        int newlySolved = Math.toIntExact(measurements.stream()
            .filter(CaseMeasurement::newlySolved)
            .count());
        result.put(FitnessComponent.SUPPORT,
            permille(confirmedReached, caseCount));
        result.put(FitnessComponent.TRAIN_CASES_NEWLY_SOLVED,
            permille(newlySolved, caseCount));
        result.put(FitnessComponent.TRAIN_PATH_LENGTH_REDUCTION,
            pairedPrimitiveStepReduction(measurements));
        result.put(FitnessComponent.TRAIN_EXPLORED_STATE_REDUCTION,
            pairedStateReduction(measurements));
        result.put(FitnessComponent.ASSUMPTION_SIMPLICITY,
            assumptionSimplicity(candidate.genome()));
        result.put(FitnessComponent.CANDIDATE_COMPLEXITY,
            candidateSimplicity(candidate));
        result.put(FitnessComponent.PROOF_COST_PROXY,
            proofCostProxy(candidate));
        return result;
    }

    private Map<FitnessComponent, Integer> zeroComponents() {
        EnumMap<FitnessComponent, Integer> result =
            new EnumMap<>(FitnessComponent.class);
        requiredComponents.forEach(component -> result.put(component, 0));
        return result;
    }

    private static int pairedPrimitiveStepReduction(
        List<CaseMeasurement> measurements
    ) {
        long baseline = 0;
        long delta = 0;
        for (CaseMeasurement item : measurements) {
            if (resourceComparable(item)) {
                baseline += Math.max(1, item.baselinePrimitiveSteps());
                delta += (long) item.baselinePrimitiveSteps()
                    - item.candidatePrimitiveSteps();
            }
        }
        return normalizedDelta(delta, baseline);
    }

    private static int pairedStateReduction(List<CaseMeasurement> measurements) {
        long baseline = 0;
        long delta = 0;
        for (CaseMeasurement item : measurements) {
            if (resourceComparable(item)) {
                baseline += Math.max(1L, item.baselineExploredStates());
                delta += item.baselineExploredStates()
                    - item.candidateExploredStates();
            }
        }
        return normalizedDelta(delta, baseline);
    }

    private static boolean resourceComparable(CaseMeasurement item) {
        return item.programUsed()
            && item.baselineReached()
            && item.candidateReached()
            && item.baselinePathCorrectness() == PathCorrectness.CONFIRMED
            && item.candidatePathCorrectness() == PathCorrectness.CONFIRMED
            && item.candidateTotalWorkUnits()
                <= item.baselineTotalWorkUnits();
    }

    private static int assumptionSimplicity(EvolutionGenome genome) {
        int assumptions = genome.rewrites().stream()
            .mapToInt(gene -> gene.assumptions().size())
            .sum();
        return inverseBudgetScore(
            assumptions,
            Math.max(1, genome.budget().maxProgramLength()));
    }

    private static int candidateSimplicity(
        EvolutionRewriteProgramCandidate candidate
    ) {
        EvolutionGenome genome = candidate.genome();
        int rewriteNodes = genome.rewrites().stream()
            .mapToInt(gene ->
                EvolutionGenomeCompiler.nodeCount(
                    EvolutionGenomeCompiler.parsePattern(gene.sourcePattern()))
                + EvolutionGenomeCompiler.nodeCount(
                    EvolutionGenomeCompiler.parsePattern(gene.targetPattern())))
            .sum();
        int used = rewriteNodes + candidate.plan().nodeCount();
        int budget = Math.max(1,
            genome.budget().maxAstNodes() + candidate.plan().maxNodes());
        return inverseBudgetScore(used, budget);
    }

    private static int proofCostProxy(
        EvolutionRewriteProgramCandidate candidate
    ) {
        int obligations = candidate.genome().rewrites().stream()
            .mapToInt(gene -> gene.evidenceObligations().size())
            .sum();
        int used = obligations + candidate.plan().nodeCount();
        int budget = Math.max(1,
            candidate.genome().budget().maxProgramLength() * 5
                + candidate.plan().maxNodes());
        return inverseBudgetScore(used, budget);
    }

    private static CaseMeasurement failedMeasurement(
        EvolutionRewriteProgramTrainSuite.TrainCase trainCase
    ) {
        return new CaseMeasurement(
            trainCase.caseId(),
            trainCase.familyId(),
            "EVALUATION_FAILED",
            "NOT_RUN",
            false,
            false,
            PathCorrectness.NOT_EVALUATED,
            PathCorrectness.NOT_EVALUATED,
            -1,
            -1,
            0,
            0,
            0,
            0,
            0,
            0,
            TransformationWorkMetrics.ZERO,
            TransformationWorkMetrics.ZERO,
            false,
            false,
            false,
            false,
            false);
    }

    private static Result search(
        MeasuredTransformationEngine engine,
        String input,
        String target,
        Budget budget
    ) {
        return new PrimitiveWorkBestFirstSearchStrategy().search(
            new Problem(
                input,
                target,
                engine,
                new ExpressionScorer(),
                new ExpressionCanonicalizer(),
                budget));
    }

    private static int edgeDepth(Result result) {
        return result.reachedState() == null
            ? -1
            : result.reachedState().edgeDepth();
    }

    private static int inverseBudgetScore(long used, long budget) {
        if (budget <= 0) {
            return 0;
        }
        long penalty = Math.min(1000L, used * 1000L / budget);
        return Math.toIntExact(1000L - penalty);
    }

    private static int permille(long numerator, long denominator) {
        return denominator <= 0
            ? 0
            : clamp(Math.toIntExact(numerator * 1000L / denominator));
    }

    private static int normalizedDelta(long delta, long baseline) {
        return baseline <= 0
            ? 0
            : clamp(Math.toIntExact(delta * 1000L / baseline));
    }

    private static int clamp(int value) {
        return Math.max(-1000, Math.min(1000, value));
    }

    private static String stableFailure(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
            + (message == null || message.isBlank()
                ? ""
                : ":" + message.replaceAll("\\s+", " ").trim());
    }

    private record PathAudit(
        PathCorrectness correctness,
        int primitiveSteps
    ) {
    }

    @FunctionalInterface
    interface WorkSearchRunner {
        Result search(
            MeasuredTransformationEngine engine,
            String input,
            String target,
            Budget budget);
    }
}
