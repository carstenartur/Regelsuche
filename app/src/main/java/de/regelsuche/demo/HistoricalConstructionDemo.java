package de.regelsuche.demo;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.evolution.CheckedSchemaTaskSolver;
import de.regelsuche.evolution.CumulativeTaskKnowledge;
import de.regelsuche.evolution.StructuralTaskObjective;
import de.regelsuche.evolution.TaskConstructionProfile;
import de.regelsuche.evolution.TraceStrategyTransferExample;
import de.regelsuche.graph.ExpressionGraphStore;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.MoveVerifier;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.validation.CandidateProofStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Connects the source-only historical factorization task to the ordinary Workbench replay. */
public final class HistoricalConstructionDemo {
    static final String ID = "historical-sophie-germain";
    static final TaskConstructionProfile PROFILE = TaskConstructionProfile.STRUCTURAL_EXPANSION_V1;
    private static final StructuralTaskObjective GOAL = StructuralTaskObjective.FACTORIZATION;
    private static final MoveSearch.Budget BUDGET = new MoveSearch.Budget(32, 8, 10_000_000, 512, 10_000_000);

    private HistoricalConstructionDemo() {}

    public record Summary(StructuralTaskObjective goal, TaskConstructionProfile profile, long totalWork,
            MoveSearch.Budget budget, List<Step> steps) {
        public Summary {
            steps = List.copyOf(steps);
        }
    }

    public record Step(int stepIndex, String sourceExpression, String targetExpression, String ruleId,
            SearchMove.SourceKind sourceKind, String provenanceHash, MoveVerifier.Verification verification) {}

    static DemoService.DemoRunResult run(DemoCatalog.Demo demo, ExpressionGraphStore graphStore,
            ExpressionScorer scorer, ExpressionCanonicalizer canonicalizer) {
        long started = System.nanoTime();
        var source = new ExpressionParser().parseExactTerm(demo.expression()).expression();
        String sourceExpression = ExpressionFormatter.format(source);
        var task = new CheckedSchemaTaskSolver.Task(source, List.of(), GOAL.goal(), BUDGET);
        var knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(), PROFILE);
        var run = knowledge.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);

        var sourceScore = scorer.score(sourceExpression);
        var steps = new ArrayList<Step>();
        var pathSteps = new ArrayList<TransformationStep>();
        DiscoveredTransformation path = null;
        if (run.solved()) {
            var evidence = run.solution().orElseThrow().evidence();
            var witness = evidence.witness();
            var cursor = source;
            var score = sourceScore;
            for (var edge : witness) {
                if (!cursor.equals(edge.source().expression()) || !edge.verification().accepted()) {
                    throw new IllegalStateException("Historical construction witness is not continuous and verified");
                }
                String before = ExpressionFormatter.format(cursor);
                cursor = edge.target().expression();
                String after = ExpressionFormatter.format(cursor);
                var afterScore = scorer.score(after);
                var move = edge.move();
                int index = steps.size();
                steps.add(new Step(index, before, after, move.ruleId(), move.sourceKind(),
                    move.provenance().contentHash(), edge.verification()));
                // The Workbench displays formatted math. The typed execution's provenance is bound
                // to serialized ASTs and is carried by the summary, not by a legacy replay edge.
                pathSteps.add(new TransformationStep(index, before, after, move.ruleId(),
                    move.transformation().kind(), score.weightedTotal(), afterScore.weightedTotal(),
                    move.transformation().equivalencePreservingByConstruction(), edge.verification().reason(),
                    move.assumptions()));
                score = afterScore;
            }
            if (steps.isEmpty() || !cursor.equals(evidence.incumbent().expression())) {
                throw new IllegalStateException("Historical construction witness lacks its verified endpoint");
            }
            String improved = ExpressionFormatter.format(cursor);
            String lineage = steps.stream().map(Step::provenanceHash).collect(Collectors.joining("\n"));
            String pathId = "demo-" + ID + "-" + UUID.nameUUIDFromBytes(lineage.getBytes(StandardCharsets.UTF_8));
            path = new DiscoveredTransformation(pathId, sourceExpression, improved, pathSteps,
                sourceScore, score, sourceScore.improvementTo(score), CandidateProofStatus.SYMBOLICALLY_VERIFIED,
                Instant.now(), canonicalizer.stableHash(improved));
            retainPath(graphStore, path, witness, canonicalizer);
        } else {
            graphStore.saveNode(sourceExpression, sourceScore.weightedTotal());
        }

        var summary = new Summary(GOAL, PROFILE, run.totalWork(), BUDGET, steps);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        return new DemoService.DemoRunResult(demo, sourceExpression, null, steps.size() + 1, steps.size(),
            path == null ? 0 : 1, path, path, path, path != null,
            steps.stream().map(Step::ruleId).distinct().toList(), List.of(), elapsedMillis,
            null, false, "", "", null, summary);
    }

    private static void retainPath(ExpressionGraphStore graphStore, DiscoveredTransformation path,
            List<TypedMoveSearch.WitnessStep> witness, ExpressionCanonicalizer canonicalizer) {
        synchronized (graphStore) {
            if (graphStore.discoveredTransformations().stream().anyMatch(found -> found.id().equals(path.id()))) {
                return;
            }
            graphStore.saveNode(path.originalExpression(), path.originalScore().weightedTotal());
            for (var step : path.steps()) {
                var edge = witness.get(step.index());
                var move = edge.move().transformation();
                graphStore.saveNode(step.afterExpression(), step.scoreAfter());
                graphStore.saveEdge(new GraphEdge(step.beforeExpression(), step.afterExpression(), step.ruleId(),
                    edge.target().searchDepth(), step.scoreBefore() - step.scoreAfter(), path.id(),
                    canonicalizer.stableHash(step.afterExpression()), step.scoreBefore(), step.scoreAfter(),
                    step.ruleKind(), move.mayIncreaseComplexity(), move.estimatedCostDelta(),
                    step.equivalencePreserving(), path.validationStatus()));
            }
            graphStore.saveDiscoveredTransformation(path);
        }
    }
}
