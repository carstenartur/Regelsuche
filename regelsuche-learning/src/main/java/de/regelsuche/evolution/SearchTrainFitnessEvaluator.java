package de.regelsuche.evolution;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.evolution.EvolutionPopulationEngine.TrainFitness;
import de.regelsuche.evolution.EvolutionPopulationEngine.TrainFitnessEvaluator;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Paired, deterministic TRAIN fitness measured by the real search engine. */
public final class SearchTrainFitnessEvaluator implements TrainFitnessEvaluator {
    private static final Set<FitnessComponent> SUPPORTED_COMPONENTS = Set.of(
        FitnessComponent.TRAIN_CASES_NEWLY_SOLVED,
        FitnessComponent.TRAIN_PATH_LENGTH_REDUCTION,
        FitnessComponent.TRAIN_EXPLORED_STATE_REDUCTION,
        FitnessComponent.SUPPORT,
        FitnessComponent.ASSUMPTION_SIMPLICITY,
        FitnessComponent.CANDIDATE_COMPLEXITY,
        FitnessComponent.PROOF_COST_PROXY);

    private final EvolutionTrainSearchSuite suite;
    private final Set<FitnessComponent> requiredComponents;
    private final EvolutionGenomeCompiler compiler;

    public SearchTrainFitnessEvaluator(
        EvolutionTrainSearchSuite suite,
        Set<FitnessComponent> requiredComponents
    ) {
        this(suite, requiredComponents, new EvolutionGenomeCompiler());
    }

    SearchTrainFitnessEvaluator(
        EvolutionTrainSearchSuite suite,
        Set<FitnessComponent> requiredComponents,
        EvolutionGenomeCompiler compiler
    ) {
        this.suite = Objects.requireNonNull(suite, "suite");
        Objects.requireNonNull(requiredComponents, "requiredComponents");
        if (requiredComponents.isEmpty()) {
            throw new IllegalArgumentException("requiredComponents must not be empty");
        }
        this.requiredComponents = Set.copyOf(requiredComponents);
        this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    public static SearchTrainFitnessEvaluator forPlan(
        EvolutionStudyPlan plan,
        EvolutionTrainSearchSuite suite
    ) {
        Objects.requireNonNull(plan, "plan");
        Set<FitnessComponent> components = plan.fitnessWeights().stream()
            .map(EvolutionStudyPlan.FitnessWeight::component)
            .collect(java.util.stream.Collectors.toSet());
        return new SearchTrainFitnessEvaluator(suite, components);
    }

    @Override
    public TrainFitness evaluate(EvolutionGenome genome) {
        EvolutionTrainFitnessEvidence evidence = evaluateWithEvidence(genome);
        return new TrainFitness(evidence.rawComponents(), evidence.blockers());
    }

    /** Returns complete paired evidence before scalar population selection. */
    public EvolutionTrainFitnessEvidence evaluateWithEvidence(
        EvolutionGenome genome
    ) {
        Objects.requireNonNull(genome, "genome");
        List<String> blockers = new ArrayList<>();
        if (genome.trainingScope().sourceSplit()
                != EvolutionGenome.SourceSplit.TRAIN) {
            blockers.add("FITNESS_SOURCE_IS_NOT_TRAIN");
        }

        EvolutionGenomeCompiler.CompiledProgram program;
        try {
            program = compiler.compile(genome);
        } catch (RuntimeException exception) {
            blockers.add("GENOME_COMPILATION_FAILED:" + stableFailure(exception));
            return EvolutionTrainFitnessEvidence.create(
                suite.contentHash(), genome, List.of(), zeroComponents(), blockers);
        }

        List<EvolutionTrainFitnessEvidence.CaseMeasurement> measurements =
            new ArrayList<>();
        for (EvolutionTrainSearchSuite.TrainCase trainCase : suite.cases()) {
            try {
                measurements.add(evaluateCase(genome, program, trainCase));
            } catch (RuntimeException exception) {
                blockers.add("TRAIN_CASE_EVALUATION_FAILED:"
                    + trainCase.caseId() + ":" + stableFailure(exception));
            }
        }

        measurements.stream()
            .filter(EvolutionTrainFitnessEvidence.CaseMeasurement::correctnessRegression)
            .map(item -> "TRAIN_CORRECTNESS_REGRESSION:" + item.caseId())
            .forEach(blockers::add);

        Map<FitnessComponent, Integer> components = components(genome, measurements);
        requiredComponents.stream()
            .filter(component -> !SUPPORTED_COMPONENTS.contains(component))
            .sorted(Comparator.comparing(Enum::name))
            .forEach(component -> {
                components.put(component, 0);
                blockers.add("UNSUPPORTED_TRAIN_FITNESS_COMPONENT:" + component);
            });
        components.keySet().removeIf(
            component -> !requiredComponents.contains(component));

        return EvolutionTrainFitnessEvidence.create(
            suite.contentHash(), genome, measurements, components, blockers);
    }

    private EvolutionTrainFitnessEvidence.CaseMeasurement evaluateCase(
        EvolutionGenome genome,
        EvolutionGenomeCompiler.CompiledProgram program,
        EvolutionTrainSearchSuite.TrainCase trainCase
    ) {
        List<RewriteRule> baselineRules = AstRewriteTransformationEngine.defaultRules();
        List<RewriteRule> candidateRules = new ArrayList<>(baselineRules);
        candidateRules.addAll(program.rules());

        AstRewriteTransformationEngine baselineEngine =
            new AstRewriteTransformationEngine(
                baselineRules,
                genome.budget().maxAstGrowthPerStep(),
                genome.budget().maxCandidatesPerState());
        AstRewriteTransformationEngine candidateEngine =
            new AstRewriteTransformationEngine(
                candidateRules,
                genome.budget().maxAstGrowthPerStep(),
                genome.budget().maxCandidatesPerState());
        SearchHeuristic heuristic = effectiveHeuristic(genome);

        GoalSearchResult baseline = search(
            baselineEngine, trainCase.inputExpression(),
            trainCase.targetExpression(), heuristic);
        GoalSearchResult candidate = search(
            candidateEngine, trainCase.inputExpression(),
            trainCase.targetExpression(), heuristic);
        boolean newlySolved = !baseline.reached() && candidate.reached();
        boolean correctnessRegression = baseline.reached() && !candidate.reached();
        return new EvolutionTrainFitnessEvidence.CaseMeasurement(
            trainCase.caseId(),
            trainCase.familyId(),
            baseline.status().name(),
            candidate.status().name(),
            baseline.reached(),
            candidate.reached(),
            depth(baseline),
            depth(candidate),
            baseline.metrics().exploredStates(),
            candidate.metrics().exploredStates(),
            newlySolved,
            correctnessRegression);
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

    private static GoalSearchResult search(
        AstRewriteTransformationEngine engine,
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

    private Map<FitnessComponent, Integer> components(
        EvolutionGenome genome,
        List<EvolutionTrainFitnessEvidence.CaseMeasurement> measurements
    ) {
        EnumMap<FitnessComponent, Integer> result =
            new EnumMap<>(FitnessComponent.class);
        int caseCount = suite.cases().size();
        int candidateReached = (int) measurements.stream()
            .filter(EvolutionTrainFitnessEvidence.CaseMeasurement::candidateReached)
            .count();
        int newlySolved = (int) measurements.stream()
            .filter(EvolutionTrainFitnessEvidence.CaseMeasurement::newlySolved)
            .count();
        result.put(FitnessComponent.SUPPORT, permille(candidateReached, caseCount));
        result.put(FitnessComponent.TRAIN_CASES_NEWLY_SOLVED,
            permille(newlySolved, caseCount));
        result.put(FitnessComponent.TRAIN_PATH_LENGTH_REDUCTION,
            pairedDepthReduction(measurements));
        result.put(FitnessComponent.TRAIN_EXPLORED_STATE_REDUCTION,
            pairedStateReduction(measurements));
        result.put(FitnessComponent.ASSUMPTION_SIMPLICITY,
            assumptionSimplicity(genome));
        result.put(FitnessComponent.CANDIDATE_COMPLEXITY,
            candidateSimplicity(genome));
        result.put(FitnessComponent.PROOF_COST_PROXY,
            proofCostProxy(genome));
        return result;
    }

    private Map<FitnessComponent, Integer> zeroComponents() {
        EnumMap<FitnessComponent, Integer> result =
            new EnumMap<>(FitnessComponent.class);
        requiredComponents.forEach(component -> result.put(component, 0));
        return result;
    }

    private static int pairedDepthReduction(
        List<EvolutionTrainFitnessEvidence.CaseMeasurement> measurements
    ) {
        long baseline = 0;
        long delta = 0;
        for (EvolutionTrainFitnessEvidence.CaseMeasurement item : measurements) {
            if (item.baselineReached() && item.candidateReached()) {
                baseline += Math.max(1, item.baselinePathLength());
                delta += (long) item.baselinePathLength()
                    - item.candidatePathLength();
            }
        }
        return normalizedDelta(delta, baseline);
    }

    private static int pairedStateReduction(
        List<EvolutionTrainFitnessEvidence.CaseMeasurement> measurements
    ) {
        long baseline = 0;
        long delta = 0;
        for (EvolutionTrainFitnessEvidence.CaseMeasurement item : measurements) {
            if (item.baselineReached() && item.candidateReached()) {
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
            assumptions, genome.budget().maxProgramLength());
    }

    private static int candidateSimplicity(EvolutionGenome genome) {
        int nodes = genome.rewrites().stream()
            .mapToInt(gene ->
                EvolutionGenomeCompiler.nodeCount(
                    EvolutionGenomeCompiler.parsePattern(gene.sourcePattern()))
                + EvolutionGenomeCompiler.nodeCount(
                    EvolutionGenomeCompiler.parsePattern(gene.targetPattern())))
            .sum();
        return inverseBudgetScore(nodes, genome.budget().maxAstNodes());
    }

    private static int proofCostProxy(EvolutionGenome genome) {
        int obligations = genome.rewrites().stream()
            .mapToInt(gene -> gene.evidenceObligations().size())
            .sum();
        int denominator = Math.max(1,
            genome.budget().maxProgramLength() * 5);
        return inverseBudgetScore(obligations, denominator);
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

    private static int depth(GoalSearchResult result) {
        return result.reachedState() == null ? -1 : result.reachedState().depth();
    }

    private static String stableFailure(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
            + (message == null || message.isBlank()
                ? ""
                : ":" + message.replaceAll("\\s+", " ").trim());
    }
}
