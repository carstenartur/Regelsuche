package de.regelsuche.evolution;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainFitnessEvidence.CaseMeasurement;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainFitnessEvidence.PathCorrectness;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.math.algorithms.equivalence.RationalFunctionNormalFormEquivalenceService;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Paired TRAIN fitness for one exact genome/program candidate. Baseline and
 * candidate use the same task, target relation, heuristic and primitive engine;
 * the candidate side differs only by the added executable program.
 */
public final class RewriteProgramTrainFitnessEvaluator {
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
    private final EvolutionRewriteProgramCompiler compiler;
    private final RationalFunctionNormalFormEquivalenceService equivalence;
    private final GoalSearchRunner searchRunner;

    public RewriteProgramTrainFitnessEvaluator(
        EvolutionRewriteProgramTrainSuite suite,
        Set<FitnessComponent> requiredComponents
    ) {
        this(
            suite,
            requiredComponents,
            new EvolutionRewriteProgramCompiler(),
            new RationalFunctionNormalFormEquivalenceService(),
            RewriteProgramTrainFitnessEvaluator::search);
    }

    RewriteProgramTrainFitnessEvaluator(
        EvolutionRewriteProgramTrainSuite suite,
        Set<FitnessComponent> requiredComponents,
        EvolutionRewriteProgramCompiler compiler,
        RationalFunctionNormalFormEquivalenceService equivalence,
        GoalSearchRunner searchRunner
    ) {
        this.suite = Objects.requireNonNull(suite, "suite");
        Objects.requireNonNull(requiredComponents, "requiredComponents");
        if (requiredComponents.isEmpty()) {
            throw new IllegalArgumentException(
                "requiredComponents must not be empty");
        }
        this.requiredComponents = Set.copyOf(requiredComponents);
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.equivalence = Objects.requireNonNull(equivalence, "equivalence");
        this.searchRunner = Objects.requireNonNull(searchRunner, "searchRunner");
    }

    public EvolutionRewriteProgramTrainFitnessEvidence evaluate(
        EvolutionRewriteProgramCandidate candidate
    ) {
        Objects.requireNonNull(candidate, "candidate");
        List<String> blockers = new ArrayList<>();
        EvolutionGenome genome = candidate.genome();
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
        if (suite.heuristic().maxCandidatesPerState()
                < genome.budget().maxCandidatesPerState()) {
            blockers.add(
                "SUITE_CANDIDATE_BOUND_NARROWER_THAN_PROGRAM_SOURCE_BOUND");
        }

        EvolutionRewriteProgramCompiler.CompiledRewriteProgram compiled;
        try {
            compiled = compiler.compile(genome, candidate.plan());
        } catch (RuntimeException exception) {
            blockers.add("PROGRAM_COMPILATION_FAILED:" + stableFailure(exception));
            return EvolutionRewriteProgramTrainFitnessEvidence.create(
                suite, candidate, List.of(), zeroComponents(), blockers);
        }

        SearchHeuristic heuristic = effectiveHeuristic(genome);
        AstRewriteTransformationEngine baselineEngine =
            new AstRewriteTransformationEngine(
                AstRewriteTransformationEngine.defaultRules(),
                genome.budget().maxAstGrowthPerStep(),
                heuristic.maxCandidatesPerState());
        TransformationEngine candidateEngine = union(
            baselineEngine,
            compiled.engine());

        List<CaseMeasurement> measurements = new ArrayList<>();
        for (EvolutionRewriteProgramTrainSuite.TrainCase trainCase
                : suite.cases()) {
            try {
                measurements.add(evaluateCase(
                    trainCase,
                    heuristic,
                    baselineEngine,
                    candidateEngine));
            } catch (RuntimeException exception) {
                blockers.add("TRAIN_CASE_EVALUATION_FAILED:"
                    + trainCase.caseId() + ":" + stableFailure(exception));
                measurements.add(failedMeasurement(trainCase));
            }
        }

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
        SearchHeuristic heuristic,
        TransformationEngine baselineEngine,
        TransformationEngine candidateEngine
    ) {
        GoalSearchResult baseline = searchRunner.search(
            baselineEngine,
            trainCase.inputExpression(),
            trainCase.targetExpression(),
            heuristic);
        GoalSearchResult candidate = searchRunner.search(
            candidateEngine,
            trainCase.inputExpression(),
            trainCase.targetExpression(),
            heuristic);

        PathAudit baselineAudit = auditPath(baseline, trainCase.assumptions());
        PathAudit candidateAudit = auditPath(candidate, trainCase.assumptions());
        boolean programUsed = candidate.reached()
            && candidate.reachedState().appliedRuleIds().stream()
                .anyMatch(rule -> rule.startsWith("program:"));
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
            depth(baseline),
            depth(candidate),
            baselineAudit.primitiveSteps(),
            candidateAudit.primitiveSteps(),
            baseline.metrics().exploredStates(),
            candidate.metrics().exploredStates(),
            baseline.metrics().generatedTransformations(),
            candidate.metrics().generatedTransformations(),
            programUsed,
            newlySolved,
            reachabilityRegression,
            correctnessFailure,
            correctnessRegression);
    }

    private PathAudit auditPath(
        GoalSearchResult result,
        List<String> declaredAssumptions
    ) {
        if (!result.reached()) {
            return new PathAudit(PathCorrectness.NOT_EVALUATED, 0);
        }
        SearchState reached = result.reachedState();
        Set<String> declared = Set.copyOf(
            AssumptionSignature.ofExpressions(declaredAssumptions)
                .normalizedAssumptions());
        if (!declared.containsAll(reached.assumptions())) {
            return new PathAudit(
                PathCorrectness.MISSING_ASSUMPTION,
                primitiveStepCount(reached.appliedRuleIds()));
        }
        List<String> path = reached.path();
        if (path.size() < 1) {
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
                return new PathAudit(
                    correctness,
                    primitiveStepCount(reached.appliedRuleIds()));
            }
        }
        return new PathAudit(
            PathCorrectness.CONFIRMED,
            primitiveStepCount(reached.appliedRuleIds()));
    }

    private static TransformationEngine union(
        TransformationEngine baseline,
        TransformationEngine program
    ) {
        return expression -> {
            LinkedHashMap<String, Transformation> distinct = new LinkedHashMap<>();
            List<Transformation> combined = new ArrayList<>();
            combined.addAll(baseline.transform(expression));
            combined.addAll(program.transform(expression));
            combined.stream()
                .sorted(Comparator
                    .comparing(Transformation::rule)
                    .thenComparing(Transformation::transformedExpression)
                    .thenComparing(Transformation::applicationKey))
                .forEach(transformation -> distinct.putIfAbsent(
                    transformation.rule() + "\u0000"
                        + transformation.transformedExpression() + "\u0000"
                        + transformation.applicationKey(),
                    transformation));
            return List.copyOf(distinct.values());
        };
    }

    private SearchHeuristic effectiveHeuristic(EvolutionGenome genome) {
        SearchHeuristic configured = suite.heuristic();
        return new SearchHeuristic(
            Math.min(configured.maxDepth(),
                genome.budget().maxApplicationsPerPath()),
            configured.maxVisitedExpressions(),
            configured.significantImprovementThreshold(),
            Math.min(configured.maxExpandingSteps(),
                genome.budget().maxApplicationsPerPath()),
            Math.min(configured.maxCandidatesPerState(),
                genome.budget().maxCandidatesPerState()),
            configured.beamWidth());
    }

    private Map<FitnessComponent, Integer> components(
        EvolutionRewriteProgramCandidate candidate,
        List<CaseMeasurement> measurements
    ) {
        EnumMap<FitnessComponent, Integer> result =
            new EnumMap<>(FitnessComponent.class);
        int caseCount = suite.cases().size();
        int confirmedReached = (int) measurements.stream()
            .filter(CaseMeasurement::candidateReached)
            .filter(item -> item.candidatePathCorrectness()
                == PathCorrectness.CONFIRMED)
            .count();
        int newlySolved = (int) measurements.stream()
            .filter(CaseMeasurement::newlySolved)
            .count();
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
            if (item.baselineReached()
                    && item.candidateReached()
                    && item.baselinePathCorrectness() == PathCorrectness.CONFIRMED
                    && item.candidatePathCorrectness()
                        == PathCorrectness.CONFIRMED) {
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
            if (item.baselineReached()
                    && item.candidateReached()
                    && item.baselinePathCorrectness() == PathCorrectness.CONFIRMED
                    && item.candidatePathCorrectness()
                        == PathCorrectness.CONFIRMED) {
                baseline += Math.max(1L, item.baselineExploredStates());
                delta += item.baselineExploredStates()
                    - item.candidateExploredStates();
            }
        }
        return normalizedDelta(delta, baseline);
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
            false,
            false,
            false,
            false,
            false);
    }

    private static int primitiveStepCount(List<String> ruleIds) {
        int result = 0;
        for (String ruleId : ruleIds) {
            if (!ruleId.startsWith("program:")
                    || !ruleId.contains("[")
                    || !ruleId.endsWith("]")) {
                result++;
                continue;
            }
            String body = ruleId.substring(
                ruleId.indexOf('[') + 1, ruleId.length() - 1);
            result += body.isBlank() ? 0 : body.split(" -> ", -1).length;
        }
        return result;
    }

    private static GoalSearchResult search(
        TransformationEngine engine,
        String input,
        String target,
        SearchHeuristic heuristic
    ) {
        SearchProblem problem = new SearchProblem(
            input,
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            heuristic)
            .withTarget(SearchTarget.syntaxExact(target));
        return new BestFirstSearchStrategy().searchWithDiagnostics(problem);
    }

    private static int depth(GoalSearchResult result) {
        return result.reachedState() == null ? -1 : result.reachedState().depth();
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
    interface GoalSearchRunner {
        GoalSearchResult search(
            TransformationEngine engine,
            String input,
            String target,
            SearchHeuristic heuristic);
    }
}
