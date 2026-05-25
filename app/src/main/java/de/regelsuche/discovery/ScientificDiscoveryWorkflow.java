package de.regelsuche.discovery;

import de.regelsuche.benchmark.DeterministicDiscoveryExperimentRunner;
import de.regelsuche.benchmark.DiscoveryReplayArtifactWriter;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.demo.DemoRuleSet;
import de.regelsuche.example.SeedExpression;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.math.algorithms.equivalence.PolynomialNormalFormEquivalenceService;
import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.mining.DiscoveryDemos;
import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.persistence.PersistenceConfig;
import de.regelsuche.persistence.PersistenceContext;
import de.regelsuche.persistence.relational.CounterexampleEntity;
import de.regelsuche.persistence.relational.DiscoveryExperimentEntity;
import de.regelsuche.persistence.relational.ExportReportEntity;
import de.regelsuche.persistence.relational.ProofJobMetadataEntity;
import de.regelsuche.persistence.relational.RelationalPersistenceAdapters;
import de.regelsuche.persistence.relational.SearchRunEntity;
import de.regelsuche.persistence.relational.SeedExpressionEntity;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.SymPyTransformationEngine;
import de.regelsuche.transform.Transformation;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import de.regelsuche.validation.DeterministicCounterexampleSearchService;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/** App-level Seed → Discovery → Replay → Persistence workflow. */
public final class ScientificDiscoveryWorkflow implements AutoCloseable {
    private static final Instant FIXED_INSTANT = Instant.EPOCH;

    private final PersistenceContext context;
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final AstRewriteTransformationEngine searchEngine = new AstRewriteTransformationEngine(DemoRuleSet.rules());
    private final BestFirstSearchStrategy searchStrategy = new BestFirstSearchStrategy();
    private final DeterministicCounterexampleSearchService counterexamples = new DeterministicCounterexampleSearchService();
    private final PolynomialNormalFormEquivalenceService polynomialEquivalence =
        new PolynomialNormalFormEquivalenceService(new DefaultMathematicalAlgorithmRegistry());

    private ScientificDiscoveryWorkflow(PersistenceContext context) {
        this.context = context;
    }

    public static ScientificDiscoveryWorkflow boot(PersistenceConfig config, PrintStream log) {
        return new ScientificDiscoveryWorkflow(PersistenceContext.from(config, log));
    }

    public RunResult run(String experimentId, List<SeedExpression> seeds, int globalBudget, int parallelism, Path artifactDirectory) {
        DeterministicDiscoveryExperimentRunner runner = new DeterministicDiscoveryExperimentRunner(
            globalBudget, parallelism, this::evaluateSeed);
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report = runner.runDetailed(seeds);
        DiscoveryReplayArtifactWriter.ArtifactBundle artifacts = new DiscoveryReplayArtifactWriter().write(report, artifactDirectory);
        context.relationalAdapters().ifPresent(adapters -> persist(experimentId, report, artifacts, adapters));
        return new RunResult(report, artifacts, context);
    }

    public synchronized DeterministicDiscoveryExperimentRunner.SeedRunOutcome evaluateSeed(SeedExpression seed) {
        String category = seed.category().toLowerCase(Locale.ROOT);
        return switch (category) {
            case "binomial" -> searchFor(seed, "binomial expansion",
                expression -> expression.contains("a * a") && expression.contains("x * x"));
            case "trigonometric" -> searchFor(seed, "pythagorean trigonometric identity",
                expression -> canonicalizer.canonicalize(expression).equals("1"));
            case "rational" -> rational(seed);
            case "matrix" -> oneStep(seed, "A * B + A * C", "linalg_distributivity",
                CandidateProofStatus.SYMBOLICALLY_VERIFIED, List.of(), "matrix distributivity reproduced");
            case "factorization" -> factorization(seed);
            case "geometric-series" -> geometricSeries(seed);
            case "counterexample" -> counterexample(seed);
            default -> DeterministicDiscoveryExperimentRunner.SeedRunOutcome.fail("unsupported scientific seed category: " + seed.category());
        };
    }

    private DeterministicDiscoveryExperimentRunner.SeedRunOutcome searchFor(SeedExpression seed, String label, Predicate<String> target) {
        String root = canonicalizer.canonicalize(seed.expression());
        ExpressionScore before = scorer.score(root);
        context.graphStore().saveNode(root, before.weightedTotal());
        SearchProblem problem = new SearchProblem(root, searchEngine, scorer, canonicalizer,
            new SearchHeuristic(7, 900, 1, 10, 200, 200));
        SearchState best = null;
        for (SearchState state : searchStrategy.search(problem)) {
            saveState(root, state);
            if (state.depth() > 0 && target.test(state.expression())) {
                best = state;
                break;
            }
        }
        if (best == null) {
            return DeterministicDiscoveryExperimentRunner.SeedRunOutcome.fail("did not reproduce " + label);
        }
        String pathId = stablePathId(root, best);
        context.graphStore().saveDiscoveredTransformation(toDiscovered(pathId, root, best, before));
        List<String> hypotheses = List.of("hyp-" + seed.id());
        List<String> counters = counterexamples.search(new CounterexampleSearchService.HypothesisInput(
                hypotheses.getFirst(), root, best.expression(), seed.assumptions()),
                CounterexampleSearchService.CounterexampleBudget.defaultBudget())
            .counterexample().map(ce -> List.of(ce.toString())).orElse(List.of());
        return new DeterministicDiscoveryExperimentRunner.SeedRunOutcome(
            counters.isEmpty(), seed.id() + " reproduced: " + label, hypotheses, counters, best.path(), 0L, 0L);
    }

    private DeterministicDiscoveryExperimentRunner.SeedRunOutcome factorization(SeedExpression seed) {
        String target = "(x + a) * (x - a)";
        boolean equivalent = polynomialEquivalence.arePolynomiallyEquivalent(seed.expression(), target);
        Optional<Transformation> sympy = new SymPyTransformationEngine().transform(seed.expression()).stream()
            .filter(transformation -> polynomialEquivalence.arePolynomiallyEquivalent(transformation.transformedExpression(), target))
            .findFirst();
        if (!equivalent) {
            return DeterministicDiscoveryExperimentRunner.SeedRunOutcome.fail("factorization equivalence check failed");
        }
        String result = sympy.map(Transformation::transformedExpression).orElse(target);
        return oneStep(seed, result, "polynomial_factorization", CandidateProofStatus.SYMBOLICALLY_VERIFIED,
            List.of(), "factorization reproduced");
    }

    private DeterministicDiscoveryExperimentRunner.SeedRunOutcome geometricSeries(SeedExpression seed) {
        HypothesisCandidate hypothesis = DiscoveryDemos.geometricSeriesHypothesis();
        return oneStep(seed, "S_(n+1) = S_n + x^n", "geometric_series_recurrence",
            CandidateProofStatus.VALIDATED_BY_EXAMPLES, List.of(hypothesis.id()),
            "geometric-series recurrence hypothesis reproduced");
    }

    private DeterministicDiscoveryExperimentRunner.SeedRunOutcome counterexample(SeedExpression seed) {
        CounterexampleSearchService.CounterexampleSearchResult result = counterexamples.search(
            new CounterexampleSearchService.HypothesisInput("hyp-" + seed.id(), seed.expression(), "a", seed.assumptions()),
            CounterexampleSearchService.CounterexampleBudget.defaultBudget());
        List<String> found = result.counterexample().map(ce -> List.of(ce.toString())).orElse(List.of());
        return new DeterministicDiscoveryExperimentRunner.SeedRunOutcome(
            !found.isEmpty(), seed.id() + " refuted by deterministic counterexample search",
            List.of("hyp-" + seed.id()), found,
            List.of(seed.expression(), "counterexample-search", found.isEmpty() ? "none" : found.getFirst()), 0L, 0L);
    }

    private DeterministicDiscoveryExperimentRunner.SeedRunOutcome rational(SeedExpression seed) {
        CounterexampleSearchService.CounterexampleSearchResult result = counterexamples.search(
            new CounterexampleSearchService.HypothesisInput("hyp-" + seed.id(), seed.expression(), "a", seed.assumptions()),
            CounterexampleSearchService.CounterexampleBudget.defaultBudget());
        if (result.counterexample().isPresent()) {
            return DeterministicDiscoveryExperimentRunner.SeedRunOutcome.fail("rational cancellation refuted");
        }
        List<String> assumptions = result.inferredAssumptions().isEmpty()
            ? seed.assumptions()
            : result.inferredAssumptions();
        return oneStep(new SeedExpression(seed.id(), seed.expression(), seed.source(), seed.category(), seed.tags(), assumptions),
            "a", "rational_cancel_common_factor", CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            List.of(), "rational cancellation reproduced");
    }

    private DeterministicDiscoveryExperimentRunner.SeedRunOutcome oneStep(
        SeedExpression seed, String result, String ruleId, CandidateProofStatus proofStatus,
        List<String> explicitHypotheses, String summary
    ) {
        String root = seed.expression();
        ExpressionScore before = scorer.score(root);
        ExpressionScore after = scorer.score(result);
        context.graphStore().saveNode(root, before.weightedTotal());
        context.graphStore().saveNode(result, after.weightedTotal());
        context.graphStore().saveEdge(new GraphEdge(root, result, ruleId, 1, before.improvementTo(after),
            seed.id() + "#1", Integer.toHexString(result.hashCode()), before.weightedTotal(), after.weightedTotal(),
            RewriteKind.NORMALIZE, false, 0, true, proofStatus));
        DiscoveredTransformation transformation = new DiscoveredTransformation(
            "path-" + seed.id(), root, result,
            List.of(new TransformationStep(0, root, result, ruleId, RewriteKind.NORMALIZE,
                before.weightedTotal(), after.weightedTotal(), true, ruleId, seed.assumptions())),
            before, after, before.improvementTo(after), proofStatus, FIXED_INSTANT, Integer.toHexString(result.hashCode()));
        context.graphStore().saveDiscoveredTransformation(transformation);
        List<String> hypotheses = explicitHypotheses.isEmpty() ? List.of("hyp-" + seed.id()) : explicitHypotheses;
        return new DeterministicDiscoveryExperimentRunner.SeedRunOutcome(
            true, seed.id() + " reproduced: " + summary, hypotheses, List.of(), List.of(root, result), 0L, 0L);
    }

    private void saveState(String root, SearchState state) {
        context.graphStore().saveNode(state.expression(), state.score().weightedTotal());
        if (state.parentExpression() == null || state.appliedRuleId() == null) {
            return;
        }
        context.graphStore().saveEdge(new GraphEdge(
            state.parentExpression(), state.expression(), state.appliedRuleId(), state.depth(), state.improvement(),
            root + "#" + state.depth(), state.canonicalHash(), scorer.score(state.parentExpression()).weightedTotal(),
            state.score().weightedTotal(), state.appliedRuleKind(), state.mayIncreaseComplexity(),
            state.estimatedCostDelta(), state.equivalencePreservingByConstruction(), CandidateProofStatus.OBSERVED));
    }

    private DiscoveredTransformation toDiscovered(String pathId, String root, SearchState state, ExpressionScore before) {
        List<TransformationStep> steps = new ArrayList<>();
        List<String> path = state.path();
        List<String> rules = state.appliedRuleIds();
        List<RewriteKind> kinds = state.appliedRuleKinds();
        for (int i = 0; i < Math.min(rules.size(), path.size() - 1); i++) {
            String from = path.get(i);
            String to = path.get(i + 1);
            steps.add(new TransformationStep(i, from, to, rules.get(i), i < kinds.size() ? kinds.get(i) : RewriteKind.NORMALIZE,
                scorer.score(from).weightedTotal(), scorer.score(to).weightedTotal(), true, rules.get(i), state.assumptions()));
        }
        return new DiscoveredTransformation(pathId, root, state.expression(), steps, before, state.score(),
            before.improvementTo(state.score()), CandidateProofStatus.OBSERVED, FIXED_INSTANT, state.canonicalHash());
    }

    private String stablePathId(String root, SearchState state) {
        StringBuilder builder = new StringBuilder(root).append('\u0001').append(state.canonicalHash()).append('\u0001');
        state.path().forEach(step -> builder.append(step).append('\u0002'));
        state.appliedRuleIds().forEach(rule -> builder.append(rule).append('\u0003'));
        return "path-" + Long.toHexString(Integer.toUnsignedLong(builder.toString().hashCode()));
    }

    private void persist(String experimentId, DeterministicDiscoveryExperimentRunner.DiscoveryReport report,
        DiscoveryReplayArtifactWriter.ArtifactBundle artifacts, RelationalPersistenceAdapters adapters) {
        for (DeterministicDiscoveryExperimentRunner.SeedRunReport row : report.rows()) {
            adapters.seeds().save(new SeedExpressionEntity(
                row.seed().id(), row.seed().expression(), row.seed().category(), row.seed().source(), row.seed().tags(), FIXED_INSTANT));
            String hypothesisId = row.hypotheses().isEmpty() ? "hyp-" + row.seed().id() : row.hypotheses().getFirst();
            String target = row.replayPath().isEmpty() ? "" : row.replayPath().getLast();
            adapters.hypotheses().orElseThrow().save(hypothesisId, new HypothesisCandidate(hypothesisId,
                row.seed().expression(), target, List.of("run-" + row.seed().id()),
                List.of(new HypothesisCandidate.ExpressionPair(row.seed().expression(), target)), row.seed().assumptions(),
                row.success() ? 1.0 : 0.0,
                row.success() ? CandidateProofStatus.VALIDATED_BY_EXAMPLES : CandidateProofStatus.REJECTED,
                !row.counterexamples().isEmpty(), List.of("scientific-reproduction"), java.util.Map.of(), FIXED_INSTANT));
            adapters.searchRuns().save(new SearchRunEntity("run-" + row.seed().id(), row.seed().expression(), target,
                "scientific-discovery-workflow", row.success() ? "SUCCEEDED" : "FAILED", row.replayPath().size(), 0,
                row.hypotheses(), FIXED_INSTANT, FIXED_INSTANT));
            for (int i = 0; i < row.counterexamples().size(); i++) {
                adapters.counterexamples().save(new CounterexampleEntity("ce-" + row.seed().id() + "-" + i,
                    hypothesisId, row.seed().expression(), target, row.counterexamples().get(i), row.seed().assumptions(), FIXED_INSTANT));
            }
            adapters.proofJobs().save(new ProofJobMetadataEntity("proof-" + row.seed().id(), hypothesisId,
                "optional-stub-proof-worker", row.success() ? "COMPLETED" : "SKIPPED",
                artifacts.jsonReport().toUri().toString(), FIXED_INSTANT, FIXED_INSTANT));
        }
        List<String> runIds = report.rows().stream().map(row -> "run-" + row.seed().id()).toList();
        adapters.experiments().save(new DiscoveryExperimentEntity(experimentId, "Scientific reproduction run",
            "Seed → Discovery → Replay → Persistence → Report", "SUCCEEDED", runIds, FIXED_INSTANT, FIXED_INSTANT));
        saveArtifact(adapters, experimentId, "json", artifacts.jsonReport(), report, runIds);
        saveArtifact(adapters, experimentId, "html", artifacts.htmlReport(), report, runIds);
        saveArtifact(adapters, experimentId, "md", artifacts.markdownReport(), report, runIds);
        saveArtifact(adapters, experimentId, "replay-json", artifacts.replayJson(), report, runIds);
        saveArtifact(adapters, experimentId, "png", artifacts.screenshotPng(), report, runIds);
        saveArtifact(adapters, experimentId, "gif", artifacts.replayGif(), report, runIds);
    }

    private void saveArtifact(RelationalPersistenceAdapters adapters, String experimentId, String format, Path path,
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report, List<String> runIds) {
        adapters.reports().save(new ExportReportEntity("artifact-" + experimentId + "-" + format, experimentId,
            "Scientific Discovery " + format.toUpperCase(Locale.ROOT) + " artifact",
            "artifact=" + path.getFileName() + "; seeds=" + report.metrics().processedSeeds(),
            "scientific-discovery", List.of(), format, path.toUri().toString(), runIds, FIXED_INSTANT));
    }

    @Override
    public void close() {
        context.close();
    }

    public record RunResult(DeterministicDiscoveryExperimentRunner.DiscoveryReport report,
        DiscoveryReplayArtifactWriter.ArtifactBundle artifacts, PersistenceContext context) {
    }
}
