package de.regelsuche.release;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2;
import de.regelsuche.experiments.autopilot.PinnedAutonomousProductionCampaign;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.DynamicOperatorCompiler;
import de.regelsuche.mining.DynamicPatternOperator;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.TransformationEngine;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Paired search utility with and without the exact retained candidate. */
public final class ProductionCandidateUtilityEvaluator {
    public static final String SCHEMA =
        "regelsuche.autonomous-candidate-qualified-utility/v1";

    public UtilityReport evaluate(OpenTargetConjecture conjecture) {
        Objects.requireNonNull(conjecture, "conjecture");
        DynamicPatternOperator operator = new DynamicOperatorCompiler().compile(
            conjecture.conjectureId(),
            ProductionCandidateQualificationCatalog.REVISION,
            conjecture.leftPattern(),
            conjecture.rightPattern()).operator().orElseThrow(() ->
                new IllegalStateException("retained candidate could not be compiled"));
        List<UtilityCase> cases = ProductionCandidateQualificationCatalog.positives()
            .stream().map(item -> evaluateCase(item, operator))
            .sorted(Comparator.comparing(UtilityCase::id)).toList();
        int regressions = (int) cases.stream().filter(UtilityCase::regression).count();
        int gains = (int) cases.stream().filter(UtilityCase::materialGain).count();
        int gainPermille = cases.isEmpty() ? 0 : gains * 1000 / cases.size();
        String contentHash = AutonomousResearchBriefV2.hash(
            SCHEMA
                + "\nconjecture=" + conjecture.conjectureId()
                + "\nrevision=" + ProductionCandidateQualificationCatalog.REVISION
                + "\ncases=" + cases.stream().map(UtilityCase::canonicalMaterial).toList()
                + "\nregressions=" + regressions
                + "\ngains=" + gains
                + "\ngainPermille=" + gainPermille);
        return new UtilityReport(
            SCHEMA,
            conjecture.conjectureId(),
            ProductionCandidateQualificationCatalog.REVISION,
            cases,
            regressions,
            gains,
            gainPermille,
            true,
            regressions == 0 && gains > 0,
            contentHash);
    }

    private static UtilityCase evaluateCase(
        ProductionCandidateQualificationCatalog.PositiveCase item,
        DynamicPatternOperator operator
    ) {
        TransformationEngine baselineEngine = new AstRewriteTransformationEngine();
        TransformationEngine candidateEngine = new HypothesisTransformationEngine(
            new AstRewriteTransformationEngine(), List.of(operator), 4);
        String target = ExpressionFormatter.format(
            new ExpressionParser().parseTerm(item.targetExpression()));
        GoalSearchResult baseline = search(
            baselineEngine, item.inputExpression(), target);
        GoalSearchResult candidate = search(
            candidateEngine, item.inputExpression(), target);
        int baselineDepth = depth(baseline);
        int candidateDepth = depth(candidate);
        long baselineStates = baseline.metrics().exploredStates();
        long candidateStates = candidate.metrics().exploredStates();
        boolean regression = !candidate.reached()
            || baseline.reached() && (candidateDepth > baselineDepth
                || candidateStates > baselineStates);
        boolean materialGain = candidate.reached() && !regression
            && (!baseline.reached()
                || candidateDepth < baselineDepth
                || candidateStates < baselineStates);
        return new UtilityCase(
            item.id(),
            baseline.status().name(),
            candidate.status().name(),
            baseline.reached(),
            candidate.reached(),
            baselineDepth,
            candidateDepth,
            baselineStates,
            candidateStates,
            materialGain,
            regression);
    }

    private static GoalSearchResult search(
        TransformationEngine engine,
        String input,
        String target
    ) {
        SearchProblem problem = new SearchProblem(
            input,
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            PinnedAutonomousProductionCampaign.searchHeuristic())
            .withTarget(SearchTarget.syntaxExact(target));
        return new BestFirstSearchStrategy().searchWithDiagnostics(problem);
    }

    private static int depth(GoalSearchResult result) {
        return result.reachedState() == null ? -1 : result.reachedState().depth();
    }

    public record UtilityCase(
        String id,
        String baselineStatus,
        String candidateStatus,
        boolean baselineReached,
        boolean candidateReached,
        int baselinePathLength,
        int candidatePathLength,
        long baselineExploredStates,
        long candidateExploredStates,
        boolean materialGain,
        boolean regression
    ) {
        public UtilityCase {
            if (id == null || id.isBlank()
                    || baselinePathLength < -1 || candidatePathLength < -1
                    || baselineExploredStates < 0 || candidateExploredStates < 0) {
                throw new IllegalArgumentException("invalid paired utility case");
            }
        }

        String canonicalMaterial() {
            return id + '|' + baselineStatus + '|' + candidateStatus
                + '|' + baselineReached + '|' + candidateReached
                + '|' + baselinePathLength + '|' + candidatePathLength
                + '|' + baselineExploredStates + '|' + candidateExploredStates
                + '|' + materialGain + '|' + regression;
        }
    }

    public record UtilityReport(
        String schema,
        String conjectureId,
        String suiteRevision,
        List<UtilityCase> cases,
        int correctnessRegressionCount,
        int materialGainCount,
        int gainPermille,
        boolean pairedUtilityEvaluated,
        boolean beneficial,
        String contentHash
    ) {
        public UtilityReport {
            cases = cases == null ? List.of() : cases.stream()
                .sorted(Comparator.comparing(UtilityCase::id)).toList();
            if (!SCHEMA.equals(schema) || cases.size() != 12
                    || correctnessRegressionCount < 0 || materialGainCount < 0
                    || gainPermille < 0 || gainPermille > 1000
                    || beneficial != (pairedUtilityEvaluated
                        && correctnessRegressionCount == 0
                        && materialGainCount > 0)) {
                throw new IllegalArgumentException("invalid paired utility report");
            }
            requireSha(contentHash);
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("conjectureId", conjectureId)
                .property("suiteRevision", suiteRevision)
                .array("cases", array -> cases.forEach(item ->
                    array.objectValue(object -> object
                        .property("id", item.id())
                        .property("baselineStatus", item.baselineStatus())
                        .property("candidateStatus", item.candidateStatus())
                        .property("baselineReached", item.baselineReached())
                        .property("candidateReached", item.candidateReached())
                        .property("baselinePathLength", item.baselinePathLength())
                        .property("candidatePathLength", item.candidatePathLength())
                        .property("baselineExploredStates", item.baselineExploredStates())
                        .property("candidateExploredStates", item.candidateExploredStates())
                        .property("materialGain", item.materialGain())
                        .property("regression", item.regression()))))
                .property("correctnessRegressionCount", correctnessRegressionCount)
                .property("materialGainCount", materialGainCount)
                .property("gainPermille", gainPermille)
                .property("pairedUtilityEvaluated", pairedUtilityEvaluated)
                .property("beneficial", beneficial)
                .property("contentHash", contentHash)
                .endObject().toString();
        }
    }

    private static void requireSha(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentHash must be SHA-256");
        }
    }
}
