package de.regelsuche.benchmark;

import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Case;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Relation;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.search.strategy.StructuralDiversitySearchStrategy;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.AstRewriteTransformationEngines;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Compares scalar and structural-diversity oracle-witness prefix retention. */
public final class HistoricalWitnessPolicyComparison {
    public static final String SCHEMA =
        "regelsuche.witness-policy-comparison/v1";
    public static final String FILE_NAME = "witness-policy-comparison.json";
    public static final String BUDGET_POLICY =
        "SAME_DECLARED_SEARCH_HEURISTIC_ACTUAL_WORK_RETAINED_SEPARATELY";
    public static final String CLAIM_BOUNDARY =
        "The target-aware oracle is inspected only after both target-blind "
            + "policies complete. Prefix recovery is not proof, autonomous "
            + "rediscovery, external novelty or general superiority.";

    private static final Set<String> NON_COMPARED = Set.of(
        "NOT_APPLICABLE", "SCALAR_ALREADY_FOUND");
    private final ExpressionParser parser = new ExpressionParser();

    public static void main(String[] args) {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                "expected: <atlas-directory> <pruning-json> <output-directory>");
        }
        HistoricalWitnessPolicyCodec.Result result =
            new HistoricalWitnessPolicyComparison().execute(
                HistoricalRediscoveryCorpus.load(),
                Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        System.out.println("historicalWitnessPolicyComparison=" + result.path());
        System.out.println("historicalWitnessPolicyComparisonHash="
            + result.contentHash());
    }

    HistoricalWitnessPolicyCodec.Result execute(
        Corpus corpus,
        Path atlasDirectory,
        Path pruningPath,
        Path outputDirectory
    ) {
        HistoricalWitnessPolicyCodec.Inputs inputs =
            HistoricalWitnessPolicyCodec.load(
                corpus, atlasDirectory, pruningPath);
        List<Comparison> results = corpus.cases().stream()
            .sorted(Comparator.comparing(Case::id))
            .map(value -> compare(
                value,
                inputs.atlasCases().get(value.id()),
                inputs.pruningCases().get(value.id())))
            .toList();
        return HistoricalWitnessPolicyCodec.write(
            corpus, inputs, results, outputDirectory);
    }

    private Comparison compare(
        Case benchmarkCase,
        Map<String, Object> atlasCase,
        Map<String, Object> pruningCase
    ) {
        Map<String, Object> production = object(atlasCase, "production");
        Map<String, Object> oracle = object(production, "oracle");
        Map<String, Object> diversity = object(production, "diversity");
        String oracleStatus = text(oracle, "status");
        List<String> witness = strings(oracle, "witnessExpressions");
        int scalarPrefix = integer(pruningCase, "exploredPrefixLength");
        Work scalarWork = new Work(
            integer(pruningCase, "searchExploredStates"),
            integer(pruningCase, "engineCalls"),
            number(pruningCase, "generatedTransformations"));
        Work retainedDiversityWork = work(diversity);
        String pruningStatus = text(pruningCase, "status");
        String lossReason = lossReason(pruningCase);

        if (!"REACHABLE".equals(oracleStatus) || witness.isEmpty()
                || benchmarkCase.relation() == Relation.NOT_EQUIVALENT) {
            return new Comparison(
                benchmarkCase.id(), "NOT_APPLICABLE", oracleStatus,
                witness.size(), scalarPrefix, null, null, lossReason,
                bool(diversity, "reached"), scalarWork,
                retainedDiversityWork, budget(benchmarkCase));
        }
        if (DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic
                .SCALAR_ALREADY_FOUND.equals(pruningStatus)) {
            return new Comparison(
                benchmarkCase.id(), "SCALAR_ALREADY_FOUND", oracleStatus,
                witness.size(), scalarPrefix, null, null, lossReason,
                bool(diversity, "reached"), scalarWork,
                retainedDiversityWork, budget(benchmarkCase));
        }
        if (integer(pruningCase, "witnessStepCount") != witness.size()) {
            throw new IllegalArgumentException(
                "witness size differs for " + benchmarkCase.id());
        }

        CountingEngine counting = new CountingEngine(engine(benchmarkCase));
        List<SearchState> states = new StructuralDiversitySearchStrategy()
            .search(problem(benchmarkCase, format(benchmarkCase.source()),
                counting));
        Work actualWork = new Work(
            states.size(), counting.calls(), counting.generated());
        if (!retainedDiversityWork.equals(actualWork)) {
            throw new IllegalStateException(
                "diversity rerun differs for " + benchmarkCase.id());
        }
        int diversityPrefix = prefix(witness, states);
        int gain = diversityPrefix - scalarPrefix;
        boolean reached = bool(diversity, "reached");
        String status = reached
            ? "DIVERSITY_REACHED_RELATION"
            : diversityPrefix == witness.size()
                ? "DIVERSITY_EXPLORED_FULL_WITNESS"
                : gain > 0
                    ? "DIVERSITY_EXTENDED_PREFIX"
                    : gain == 0
                        ? "NO_PREFIX_GAIN"
                        : "DIVERSITY_SHORTER_PREFIX";
        return new Comparison(
            benchmarkCase.id(), status, oracleStatus, witness.size(),
            scalarPrefix, diversityPrefix, gain, lossReason, reached,
            scalarWork, actualWork, budget(benchmarkCase));
    }

    private int prefix(List<String> witness, List<SearchState> states) {
        Set<String> explored = states.stream()
            .map(SearchState::expression)
            .map(this::key)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        int result = 0;
        for (String expression : witness) {
            if (!explored.contains(key(expression))) {
                break;
            }
            result++;
        }
        return result;
    }

    private static SearchProblem problem(
        Case value,
        String source,
        TransformationEngine engine
    ) {
        return new SearchProblem(
            source, engine, new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(
                value.searchMaxDepth(), value.searchMaxVisitedStates(), 1,
                value.maxExpandingSteps(), value.maxCandidatesPerState(),
                value.beamWidth()));
    }

    private static TransformationEngine engine(Case value) {
        return AstRewriteTransformationEngines.production(
            AstRewriteTransformationEngine.defaultRules(), 128,
            Math.max(200, value.maxCandidatesPerState() * 2));
    }

    private String key(String expression) {
        try {
            return format(expression);
        } catch (IllegalArgumentException exception) {
            return expression.trim().replaceAll("\\s+", " ");
        }
    }

    private String format(String expression) {
        return ExpressionFormatter.format(parser.parseTerm(expression));
    }

    private static Map<String, Integer> budget(Case value) {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("maxDepth", value.searchMaxDepth());
        result.put("maxVisitedStates", value.searchMaxVisitedStates());
        result.put("maxCandidatesPerState", value.maxCandidatesPerState());
        result.put("maxExpandingSteps", value.maxExpandingSteps());
        result.put("beamWidth", value.beamWidth());
        return Map.copyOf(result);
    }

    private static Work work(Map<String, Object> value) {
        return new Work(
            integer(value, "exploredStates"),
            integer(value, "engineCalls"),
            number(value, "generatedTransformations"));
    }

    private static String lossReason(Map<String, Object> value) {
        Object raw = value.get("firstLoss");
        return raw instanceof Map<?, ?>
            ? text(object(raw, "firstLoss"), "reason") : "";
    }

    private static Map<String, Object> object(
        Map<String, Object> value,
        String key
    ) {
        return object(value.get(key), key);
    }

    private static Map<String, Object> object(Object raw, String label) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put((String) key, value));
        return result;
    }

    private static List<?> list(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof List<?> result)) {
            throw new IllegalArgumentException(key + " must be an array");
        }
        return result;
    }

    private static List<String> strings(
        Map<String, Object> value,
        String key
    ) {
        return list(value, key).stream().map(String.class::cast).toList();
    }

    private static String text(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof String result) || result.isBlank()) {
            throw new IllegalArgumentException(key + " must be text");
        }
        return result;
    }

    private static boolean bool(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof Boolean result)) {
            throw new IllegalArgumentException(key + " must be boolean");
        }
        return result;
    }

    private static int integer(Map<String, Object> value, String key) {
        long result = number(value, key);
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " is outside int range");
        }
        return (int) result;
    }

    private static long number(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof Number number)
                || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.longValue()) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return number.longValue();
    }

    record Work(
        int exploredStates,
        int engineCalls,
        long generatedTransformations
    ) {
    }

    record Comparison(
        String id,
        String status,
        String oracleStatus,
        int witnessStepCount,
        int scalarPrefixLength,
        Integer diversityPrefixLength,
        Integer prefixGain,
        String scalarFirstLossReason,
        boolean diversityReached,
        Work scalarWork,
        Work diversityWork,
        Map<String, Integer> declaredBudget
    ) {
        Comparison {
            if (witnessStepCount < 0 || scalarPrefixLength < 0
                    || scalarPrefixLength > witnessStepCount) {
                throw new IllegalArgumentException("invalid prefix counts");
            }
            boolean compared = !NON_COMPARED.contains(status);
            if (compared != (diversityPrefixLength != null)
                    || compared != (prefixGain != null)) {
                throw new IllegalArgumentException(
                    "comparison outcome is not balanced");
            }
            if (compared && prefixGain
                    != diversityPrefixLength - scalarPrefixLength) {
                throw new IllegalArgumentException("prefix gain differs");
            }
            declaredBudget = Map.copyOf(declaredBudget);
        }

        boolean compared() {
            return !NON_COMPARED.contains(status);
        }
    }

    private static final class CountingEngine implements TransformationEngine {
        private final TransformationEngine delegate;
        private int calls;
        private long generated;

        private CountingEngine(TransformationEngine delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Transformation> transform(String expression) {
            calls++;
            List<Transformation> result = delegate.transform(expression);
            generated += result.size();
            return result;
        }

        private int calls() {
            return calls;
        }

        private long generated() {
            return generated;
        }
    }
}
