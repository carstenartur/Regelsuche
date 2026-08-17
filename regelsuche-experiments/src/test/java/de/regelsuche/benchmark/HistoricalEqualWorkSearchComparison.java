package de.regelsuche.benchmark;

import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.AtlasReport;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.CaseResult;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.OracleEvidence;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Case;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.TargetRelation;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.json.JsonReader;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.search.strategy.StructuralDiversitySearchStrategy;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.AstRewriteTransformationEngines;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Target-blind scalar/diversity comparison at fixed admitted-work checkpoints.
 * Candidate lists are materialized before this diagnostic admits a deterministic
 * prefix, so the report equalizes admitted work rather than CPU or matcher work.
 */
public final class HistoricalEqualWorkSearchComparison {
    public static final String SCHEMA =
        "regelsuche.equal-work-search-comparison/v1";
    public static final String FILE_NAME = "equal-work-search-comparison.json";
    public static final List<Integer> CHECKPOINTS =
        List.of(1, 2, 4, 8, 16, 32);
    public static final String CLAIM_BOUNDARY =
        "Both target-blind searches receive the same frozen source, production "
            + "inventory and deterministic ceilings for engine calls and admitted "
            + "primitive rewrite steps. Candidate lists are materialized before "
            + "the deterministic prefix is admitted, so this does not equalize "
            + "matcher runtime, allocation or total CPU work. Oracle witnesses "
            + "and targets are inspected only after search completion. A retained "
            + "checkpoint advantage is not proof, autonomous rediscovery, novelty "
            + "or general superiority.";

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final SymPyEquivalenceService equivalence =
        new SymPyEquivalenceService();

    public static void main(String[] args) {
        if (args.length == 0
                || !"equal-work-search-comparison".equals(args[0])) {
            throw new IllegalArgumentException(
                "expected equal-work-search-comparison [output-directory]");
        }
        Path output = args.length > 1
            ? Path.of(args[1])
            : Path.of("build/reports/"
                + "historical-rediscovery-equal-work-search-comparison");
        Corpus corpus = HistoricalRediscoveryCorpus.load();
        AtlasReport atlas = new HistoricalRediscoveryAtlas().run(corpus);
        HistoricalEqualWorkSearchComparison comparison =
            new HistoricalEqualWorkSearchComparison();
        Report report = comparison.run(corpus, atlas);
        System.out.println("historicalEqualWorkSearchComparison="
            + comparison.write(output, report));
        System.out.println("historicalEqualWorkSearchComparisonHash="
            + report.contentHash());
    }

    Report run(Corpus corpus, AtlasReport atlas) {
        requireBinding(corpus, atlas);
        Map<String, CaseResult> byId = atlas.cases().stream()
            .collect(Collectors.toUnmodifiableMap(
                value -> value.benchmarkCase().id(), value -> value));
        List<EqualWorkCase> cases = corpus.cases().stream()
            .sorted(Comparator.comparing(Case::id))
            .map(value -> compare(
                value,
                Objects.requireNonNull(byId.get(value.id()))))
            .toList();
        return Report.create(corpus, atlas, cases);
    }

    Path write(Path directory, Report report) {
        Path output = directory.toAbsolutePath().normalize().resolve(FILE_NAME);
        String json = report.toCanonicalJson();
        try {
            Files.createDirectories(output.getParent());
            AtomicJsonFile.writeUtf8(output, json);
            if (!json.equals(Files.readString(
                    output, StandardCharsets.UTF_8))) {
                throw new IllegalStateException(
                    "equal-work report changed on write");
            }
            return output;
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write equal-work report", exception);
        }
    }

    private EqualWorkCase compare(Case benchmarkCase, CaseResult atlasCase) {
        OracleEvidence oracle = atlasCase.production().oracle();
        if (!oracle.reachable()) {
            return EqualWorkCase.skipped(
                benchmarkCase.id(),
                CaseStatus.NO_PRODUCTION_WITNESS,
                atlasCase.status().name(),
                oracle.status(),
                0);
        }
        if (atlasCase.production().scalar().reached()) {
            return EqualWorkCase.skipped(
                benchmarkCase.id(),
                CaseStatus.SCALAR_ALREADY_REACHED,
                atlasCase.status().name(),
                oracle.status(),
                oracle.witnessExpressions().size());
        }
        if (oracle.witnessExpressions().isEmpty()) {
            throw new IllegalStateException(
                "reachable oracle witness is empty for " + benchmarkCase.id());
        }
        List<Checkpoint> checkpoints = CHECKPOINTS.stream()
            .map(budget -> checkpoint(benchmarkCase, atlasCase, budget))
            .toList();
        return new EqualWorkCase(
            benchmarkCase.id(),
            CaseStatus.EXECUTED_ORACLE_WITNESS_SCALAR_MISS,
            atlasCase.status().name(),
            oracle.status(),
            oracle.witnessExpressions().size(),
            checkpoints);
    }

    private Checkpoint checkpoint(
        Case benchmarkCase,
        CaseResult atlasCase,
        int primitiveBudget
    ) {
        int callBudget = Math.max(
            1, Math.min(primitiveBudget, benchmarkCase.searchMaxVisitedStates()));
        List<String> witness = atlasCase.production().oracle()
            .witnessExpressions();
        String source = atlasCase.representation().formattedSource();
        String target = atlasCase.representation().formattedTarget();
        Policy scalar = runScalar(
            benchmarkCase, source, target, witness, primitiveBudget, callBudget);
        Policy diversity = runDiversity(
            benchmarkCase, source, target, witness, primitiveBudget, callBudget);
        return Checkpoint.create(
            primitiveBudget, callBudget, witness.size(), scalar, diversity);
    }

    private Policy runScalar(
        Case benchmarkCase,
        String source,
        String target,
        List<String> witness,
        int primitiveBudget,
        int callBudget
    ) {
        FixedWorkEngine engine = engine(
            benchmarkCase, primitiveBudget, callBudget);
        SearchProblem problem = problem(benchmarkCase, source, engine);
        requireTargetBlind(problem);
        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        String terminal = terminalStatus(
            benchmarkCase, result.states(), engine);
        return policy(
            benchmarkCase, target, witness, result.states(), engine, terminal);
    }

    private Policy runDiversity(
        Case benchmarkCase,
        String source,
        String target,
        List<String> witness,
        int primitiveBudget,
        int callBudget
    ) {
        FixedWorkEngine engine = engine(
            benchmarkCase, primitiveBudget, callBudget);
        SearchProblem problem = problem(benchmarkCase, source, engine);
        requireTargetBlind(problem);
        List<SearchState> states = new StructuralDiversitySearchStrategy()
            .search(problem);
        return policy(
            benchmarkCase, target, witness, states, engine,
            terminalStatus(benchmarkCase, states, engine));
    }

    private static String terminalStatus(
        Case benchmarkCase,
        List<SearchState> states,
        FixedWorkEngine engine
    ) {
        if (states.size() >= benchmarkCase.searchMaxVisitedStates()) {
            return "STATE_BUDGET";
        }
        return engine.budgetReached()
            ? "WORK_BUDGET" : "FRONTIER_EXHAUSTED";
    }

    private Policy policy(
        Case benchmarkCase,
        String target,
        List<String> witness,
        List<SearchState> states,
        FixedWorkEngine engine,
        String terminal
    ) {
        return new Policy(
            findMatch(benchmarkCase, target, states) != null,
            prefix(witness, states),
            states.size(),
            engine.calls(),
            engine.steps(),
            engine.primitiveBudgetReached(),
            engine.callBudgetReached(),
            terminal);
    }

    private SearchProblem problem(
        Case benchmarkCase,
        String source,
        TransformationEngine engine
    ) {
        return new SearchProblem(
            source,
            engine,
            scorer,
            canonicalizer,
            new SearchHeuristic(
                benchmarkCase.searchMaxDepth(),
                benchmarkCase.searchMaxVisitedStates(),
                1,
                benchmarkCase.maxExpandingSteps(),
                benchmarkCase.maxCandidatesPerState(),
                benchmarkCase.beamWidth()));
    }

    private FixedWorkEngine engine(Case value, int steps, int calls) {
        TransformationEngine delegate = AstRewriteTransformationEngines.production(
            AstRewriteTransformationEngine.defaultRules(),
            128,
            Math.max(200, value.maxCandidatesPerState() * 2));
        return new FixedWorkEngine(delegate, steps, calls);
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

    private SearchState findMatch(
        Case benchmarkCase,
        String target,
        List<SearchState> states
    ) {
        return states.stream()
            .filter(state -> matches(benchmarkCase, state.expression(), target))
            .min(Comparator.comparingInt(SearchState::depth)
                .thenComparing(SearchState::expression)
                .thenComparing(state -> String.join("->", state.appliedRuleIds())))
            .orElse(null);
    }

    private boolean matches(Case value, String expression, String target) {
        if (value.targetRelation() == TargetRelation.SYNTAX_EXACT) {
            try {
                return format(expression).equals(target);
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
        return equivalence.areEquivalent(expression, target);
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

    private static void requireTargetBlind(SearchProblem problem) {
        if (problem.target() != null) {
            throw new IllegalStateException(
                "equal-work search must be target-blind");
        }
    }

    private static void requireBinding(Corpus corpus, AtlasReport atlas) {
        Set<String> corpusIds = corpus.cases().stream()
            .map(Case::id).collect(Collectors.toSet());
        Set<String> atlasIds = atlas.cases().stream()
            .map(value -> value.benchmarkCase().id()).collect(Collectors.toSet());
        if (!corpus.schema().equals(atlas.corpusSchema())
                || !corpus.contentSha256().equals(atlas.corpusSha256())
                || !corpus.inventoryRevision().equals(atlas.inventoryRevision())
                || !corpus.claimBoundary().equals(atlas.claimBoundary())
                || !corpusIds.equals(atlasIds)) {
            throw new IllegalArgumentException(
                "atlas does not bind equal-work corpus");
        }
    }

    public enum CaseStatus {
        NO_PRODUCTION_WITNESS,
        SCALAR_ALREADY_REACHED,
        EXECUTED_ORACLE_WITNESS_SCALAR_MISS
    }

    public enum Outcome {
        BOTH_COMPLETE_WITNESS,
        DIVERSITY_ONLY_COMPLETE_WITNESS,
        SCALAR_ONLY_COMPLETE_WITNESS,
        DIVERSITY_LONGER_PREFIX,
        SCALAR_LONGER_PREFIX,
        EQUAL_PREFIX
    }

    public record Policy(
        boolean reachedRelation,
        int witnessPrefixLength,
        int exploredStates,
        int engineCalls,
        int admittedPrimitiveSteps,
        boolean primitiveBudgetReached,
        boolean engineCallBudgetReached,
        String terminalStatus
    ) {
        public Policy {
            terminalStatus = requireText(terminalStatus, "terminalStatus");
            if (witnessPrefixLength < 0 || exploredStates < 0
                    || engineCalls < 0 || admittedPrimitiveSteps < 0) {
                throw new IllegalArgumentException(
                    "policy counters must not be negative");
            }
        }
    }

    public record Checkpoint(
        int primitiveBudget,
        int engineCallBudget,
        Policy scalar,
        Policy diversity,
        boolean equalConsumedWork,
        Outcome outcome
    ) {
        public Checkpoint {
            if (!CHECKPOINTS.contains(primitiveBudget)
                    || engineCallBudget < 1
                    || engineCallBudget > primitiveBudget) {
                throw new IllegalArgumentException(
                    "checkpoint budgets are outside the preregistration");
            }
            Objects.requireNonNull(scalar, "scalar");
            Objects.requireNonNull(diversity, "diversity");
            Objects.requireNonNull(outcome, "outcome");
            requireWithinBudget(
                scalar, primitiveBudget, engineCallBudget, "scalar");
            requireWithinBudget(
                diversity, primitiveBudget, engineCallBudget, "diversity");
            boolean expectedEqual = scalar.engineCalls()
                    == diversity.engineCalls()
                && scalar.admittedPrimitiveSteps()
                    == diversity.admittedPrimitiveSteps();
            if (equalConsumedWork != expectedEqual) {
                throw new IllegalArgumentException(
                    "equalConsumedWork differs from the policy ledgers");
            }
        }

        private static void requireWithinBudget(
            Policy policy,
            int primitiveBudget,
            int callBudget,
            String label
        ) {
            if (policy.engineCalls() > callBudget
                    || policy.admittedPrimitiveSteps() > primitiveBudget
                    || policy.primitiveBudgetReached()
                        != (policy.admittedPrimitiveSteps() >= primitiveBudget)
                    || policy.engineCallBudgetReached()
                        != (policy.engineCalls() >= callBudget)) {
                throw new IllegalArgumentException(
                    label + " policy differs from the checkpoint budget");
            }
        }

        static Checkpoint create(
            int primitiveBudget,
            int callBudget,
            int witnessSteps,
            Policy scalar,
            Policy diversity
        ) {
            boolean equal = scalar.engineCalls() == diversity.engineCalls()
                && scalar.admittedPrimitiveSteps()
                    == diversity.admittedPrimitiveSteps();
            return new Checkpoint(
                primitiveBudget,
                callBudget,
                scalar,
                diversity,
                equal,
                outcome(witnessSteps, scalar, diversity));
        }

        private static Outcome outcome(
            int witnessSteps,
            Policy scalar,
            Policy diversity
        ) {
            boolean scalarComplete =
                scalar.witnessPrefixLength() == witnessSteps;
            boolean diversityComplete =
                diversity.witnessPrefixLength() == witnessSteps;
            if (scalarComplete && diversityComplete) {
                return Outcome.BOTH_COMPLETE_WITNESS;
            }
            if (diversityComplete) {
                return Outcome.DIVERSITY_ONLY_COMPLETE_WITNESS;
            }
            if (scalarComplete) {
                return Outcome.SCALAR_ONLY_COMPLETE_WITNESS;
            }
            if (diversity.witnessPrefixLength()
                    > scalar.witnessPrefixLength()) {
                return Outcome.DIVERSITY_LONGER_PREFIX;
            }
            if (scalar.witnessPrefixLength()
                    > diversity.witnessPrefixLength()) {
                return Outcome.SCALAR_LONGER_PREFIX;
            }
            return Outcome.EQUAL_PREFIX;
        }
    }

    public record EqualWorkCase(
        String id,
        CaseStatus status,
        String atlasStatus,
        String oracleStatus,
        int oracleWitnessStepCount,
        List<Checkpoint> checkpoints
    ) {
        public EqualWorkCase {
            id = requireText(id, "id");
            Objects.requireNonNull(status, "status");
            atlasStatus = requireText(atlasStatus, "atlasStatus");
            oracleStatus = requireText(oracleStatus, "oracleStatus");
            checkpoints = List.copyOf(checkpoints);
            boolean executed = status
                == CaseStatus.EXECUTED_ORACLE_WITNESS_SCALAR_MISS;
            if (oracleWitnessStepCount < 0
                    || executed != !checkpoints.isEmpty()
                    || (executed && checkpoints.size() != CHECKPOINTS.size())) {
                throw new IllegalArgumentException(
                    "equal-work case evidence is inconsistent");
            }
        }

        static EqualWorkCase skipped(
            String id,
            CaseStatus status,
            String atlasStatus,
            String oracleStatus,
            int witnessSteps
        ) {
            return new EqualWorkCase(
                id, status, atlasStatus, oracleStatus, witnessSteps, List.of());
        }
    }

    public record Summary(
        int caseCount,
        Map<CaseStatus, Integer> statusCounts,
        int checkpointCount,
        int equalConsumedWorkCount,
        int equalWorkDiversityAdvantageCount,
        int equalWorkDiversityCompleteWitnessCount
    ) {
        public Summary {
            statusCounts = Map.copyOf(statusCounts);
            int statuses = statusCounts.values().stream()
                .mapToInt(Integer::intValue).sum();
            if (caseCount < 1 || statuses != caseCount
                    || checkpointCount < 0
                    || equalConsumedWorkCount < 0
                    || equalWorkDiversityAdvantageCount < 0
                    || equalWorkDiversityCompleteWitnessCount < 0
                    || equalConsumedWorkCount > checkpointCount
                    || equalWorkDiversityAdvantageCount
                        > equalConsumedWorkCount
                    || equalWorkDiversityCompleteWitnessCount
                        > equalWorkDiversityAdvantageCount) {
                throw new IllegalArgumentException(
                    "equal-work summary is inconsistent");
            }
        }

        static Summary derive(List<EqualWorkCase> cases) {
            Map<CaseStatus, Integer> statuses =
                new EnumMap<>(CaseStatus.class);
            cases.forEach(value -> statuses.merge(
                value.status(), 1, Integer::sum));
            List<Checkpoint> checkpoints = cases.stream()
                .flatMap(value -> value.checkpoints().stream())
                .toList();
            List<Checkpoint> equal = checkpoints.stream()
                .filter(Checkpoint::equalConsumedWork)
                .toList();
            int advantages = (int) equal.stream()
                .filter(value -> value.diversity().witnessPrefixLength()
                    > value.scalar().witnessPrefixLength())
                .count();
            int complete = (int) equal.stream()
                .filter(value -> value.outcome()
                    == Outcome.DIVERSITY_ONLY_COMPLETE_WITNESS)
                .count();
            return new Summary(
                cases.size(),
                statuses,
                checkpoints.size(),
                equal.size(),
                advantages,
                complete);
        }
    }

    public record Report(
        String corpusSha256,
        String atlasSha256,
        String inventoryRevision,
        List<EqualWorkCase> cases,
        Summary summary,
        String contentHash
    ) {
        public Report {
            corpusSha256 = requireSha256(
                corpusSha256, "corpusSha256", false);
            atlasSha256 = requireSha256(
                atlasSha256, "atlasSha256", true);
            inventoryRevision = requireText(
                inventoryRevision, "inventoryRevision");
            cases = cases.stream()
                .sorted(Comparator.comparing(EqualWorkCase::id))
                .toList();
            if (cases.isEmpty()
                    || new LinkedHashSet<>(cases.stream()
                        .map(EqualWorkCase::id).toList()).size() != cases.size()
                    || !Summary.derive(cases).equals(summary)) {
                throw new IllegalArgumentException(
                    "equal-work report case balance differs");
            }
            contentHash = requireSha256(
                contentHash, "contentHash", true);
            if (!hash(corpusSha256, atlasSha256, inventoryRevision,
                    cases, summary).equals(contentHash)) {
                throw new IllegalArgumentException(
                    "equal-work contentHash mismatch");
            }
        }

        static Report create(
            Corpus corpus,
            AtlasReport atlas,
            List<EqualWorkCase> cases
        ) {
            List<EqualWorkCase> sorted = cases.stream()
                .sorted(Comparator.comparing(EqualWorkCase::id))
                .toList();
            Summary summary = Summary.derive(sorted);
            String atlasHash = sha256(atlas.toJson());
            return new Report(
                corpus.contentSha256(),
                atlasHash,
                corpus.inventoryRevision(),
                sorted,
                summary,
                hash(
                    corpus.contentSha256(), atlasHash,
                    corpus.inventoryRevision(), sorted, summary));
        }

        String toCanonicalJson() {
            return render(
                corpusSha256,
                atlasSha256,
                inventoryRevision,
                cases,
                summary,
                contentHash);
        }
    }

    private static String hash(
        String corpusHash,
        String atlasHash,
        String inventoryRevision,
        List<EqualWorkCase> cases,
        Summary summary
    ) {
        return sha256(render(
            corpusHash,
            atlasHash,
            inventoryRevision,
            cases,
            summary,
            null));
    }

    private static String render(
        String corpusHash,
        String atlasHash,
        String inventoryRevision,
        List<EqualWorkCase> cases,
        Summary summary,
        String contentHash
    ) {
        JsonWriter writer = new JsonWriter().beginObject();
        writer.property("schema", SCHEMA);
        writer.property(
            "evidenceStatus",
            "EXECUTED_TARGET_BLIND_FIXED_ADMITTED_WORK_CHECKPOINTS");
        writer.property("corpusSchema", HistoricalRediscoveryCorpus.SCHEMA);
        writer.property("corpusSha256", corpusHash);
        writer.property("atlasSchema", HistoricalRediscoveryAtlas.SCHEMA);
        writer.property("atlasSha256", atlasHash);
        writer.property("inventoryRevision", inventoryRevision);
        writer.property("scalarPolicy", "SCALAR_BEST_FIRST_TARGET_BLIND");
        writer.property(
            "diversityPolicy", "STRUCTURAL_DIVERSITY_TARGET_BLIND");
        writer.property(
            "informationBoundary",
            "TARGETS_AND_ORACLE_WITNESSES_POST_HOC_ONLY");
        writer.property(
            "workUnit",
            "ENGINE_CALLS_AND_ADMITTED_PRIMITIVE_REWRITE_STEPS");
        writer.array("checkpoints", array -> CHECKPOINTS.forEach(array::value));
        writer.property("claimBoundary", CLAIM_BOUNDARY);
        writer.array("cases", array -> cases.forEach(value ->
            array.objectValue(object -> writeCase(object, value))));
        writer.object("summary", object -> writeSummary(object, summary));
        if (contentHash != null) {
            writer.property("contentHash", contentHash);
        }
        return writer.endObject().toString();
    }

    private static void writeCase(JsonWriter writer, EqualWorkCase value) {
        writer.property("id", value.id());
        writer.property("status", value.status().name());
        writer.property("atlasStatus", value.atlasStatus());
        writer.property("oracleStatus", value.oracleStatus());
        writer.property(
            "oracleWitnessStepCount", value.oracleWitnessStepCount());
        writer.array("checkpointResults", array ->
            value.checkpoints().forEach(checkpoint ->
                array.objectValue(object -> writeCheckpoint(object, checkpoint))));
    }

    private static void writeCheckpoint(
        JsonWriter writer,
        Checkpoint value
    ) {
        writer.property("primitiveBudget", value.primitiveBudget());
        writer.property("engineCallBudget", value.engineCallBudget());
        writer.object("scalar", object -> writePolicy(object, value.scalar()));
        writer.object(
            "diversity", object -> writePolicy(object, value.diversity()));
        writer.property("equalConsumedWork", value.equalConsumedWork());
        writer.property("outcome", value.outcome().name());
    }

    private static void writePolicy(JsonWriter writer, Policy value) {
        writer.property("reachedRelation", value.reachedRelation());
        writer.property(
            "witnessPrefixLength", value.witnessPrefixLength());
        writer.property("exploredStates", value.exploredStates());
        writer.property("engineCalls", value.engineCalls());
        writer.property(
            "admittedPrimitiveSteps", value.admittedPrimitiveSteps());
        writer.property(
            "primitiveBudgetReached", value.primitiveBudgetReached());
        writer.property(
            "engineCallBudgetReached", value.engineCallBudgetReached());
        writer.property("terminalStatus", value.terminalStatus());
    }

    private static void writeSummary(JsonWriter writer, Summary value) {
        writer.property("caseCount", value.caseCount());
        writer.object("statusCounts", object -> value.statusCounts().entrySet()
            .stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> object.property(
                entry.getKey().name(), entry.getValue())));
        writer.property("checkpointCount", value.checkpointCount());
        writer.property(
            "equalConsumedWorkCount", value.equalConsumedWorkCount());
        writer.property(
            "equalWorkDiversityAdvantageCount",
            value.equalWorkDiversityAdvantageCount());
        writer.property(
            "equalWorkDiversityCompleteWitnessCount",
            value.equalWorkDiversityCompleteWitnessCount());
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static String requireSha256(
        String value,
        String label,
        boolean prefixed
    ) {
        String result = requireText(value, label);
        String pattern = prefixed
            ? "sha256:[0-9a-f]{64}" : "[0-9a-f]{64}";
        if (!result.matches(pattern)) {
            throw new IllegalArgumentException(label + " must be SHA-256");
        }
        return result;
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static final class FixedWorkEngine
            implements TransformationEngine {
        private static final Comparator<Transformation> ORDER = Comparator
            .comparing(Transformation::rule)
            .thenComparing(Transformation::transformedExpression)
            .thenComparing(Transformation::applicationKey);

        private final TransformationEngine delegate;
        private final int primitiveBudget;
        private final int callBudget;
        private int calls;
        private int steps;

        private FixedWorkEngine(
            TransformationEngine delegate,
            int primitiveBudget,
            int callBudget
        ) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            if (!CHECKPOINTS.contains(primitiveBudget)
                    || callBudget < 1 || callBudget > primitiveBudget) {
                throw new IllegalArgumentException(
                    "fixed-work budgets are outside the preregistration");
            }
            this.primitiveBudget = primitiveBudget;
            this.callBudget = callBudget;
        }

        @Override
        public List<Transformation> transform(String expression) {
            if (budgetReached()) {
                return List.of();
            }
            calls++;
            List<Transformation> candidates = new ArrayList<>(
                delegate.transform(expression));
            candidates.sort(ORDER);
            List<Transformation> admitted = new ArrayList<>();
            for (Transformation candidate : candidates) {
                int cost = candidate.primitiveStepCount();
                if (steps + cost > primitiveBudget) {
                    break;
                }
                admitted.add(candidate);
                steps += cost;
                if (primitiveBudgetReached()) {
                    break;
                }
            }
            return List.copyOf(admitted);
        }

        int calls() {
            return calls;
        }

        int steps() {
            return steps;
        }

        boolean primitiveBudgetReached() {
            return steps >= primitiveBudget;
        }

        boolean callBudgetReached() {
            return calls >= callBudget;
        }

        boolean budgetReached() {
            return primitiveBudgetReached() || callBudgetReached();
        }
    }
}

/** Derives a fail-closed architecture decision from retained #620 evidence. */
final class HistoricalRewriteArchitectureDecision {
    static final String SCHEMA =
        "regelsuche.rewrite-architecture-decision/v1";
    static final String FILE_NAME = "rewrite-architecture-decision.json";
    static final String EVIDENCE_STATUS =
        "DERIVED_FROM_EXECUTED_HISTORICAL_DIAGNOSTICS";
    static final String CLAIM_BOUNDARY =
        "This artifact is a reversible implementation-priority decision derived "
            + "from the frozen historical diagnostic corpus and its retained "
            + "search evidence. It is not mathematical proof, autonomous "
            + "rediscovery, external novelty, a complete causal model or a claim "
            + "of general search or architecture superiority.";

    private static final long MAX_INPUT_BYTES = 32L * 1024L * 1024L;
    private static final String CORPUS_SCHEMA =
        "regelsuche.historical-rediscovery-corpus/v1";
    private static final String ATLAS_SCHEMA =
        "regelsuche.historical-rediscovery-atlas/v1";

    public static void main(String[] args) {
        if (args.length != 7
                || !"rewrite-architecture-decision".equals(args[0])) {
            throw new IllegalArgumentException(
                "expected rewrite-architecture-decision <atlas-run> <atlas> "
                    + "<witness-diagnostic> <production-comparison> "
                    + "<equal-work-comparison> <output-directory>");
        }
        Report report = derive(
            Path.of(args[1]), Path.of(args[2]), Path.of(args[3]),
            Path.of(args[4]), Path.of(args[5]));
        Path output = write(Path.of(args[6]), report);
        System.out.println("historicalRewriteArchitectureDecision=" + output);
        System.out.println("historicalRewriteArchitectureDecisionHash="
            + report.contentHash());
    }

    static Report derive(
        Path runPath,
        Path atlasPath,
        Path witnessPath,
        Path productionPath,
        Path equalWorkPath
    ) {
        Document run = Document.hashed(
            runPath,
            "atlas run",
            "regelsuche.historical-rediscovery-run/v1",
            "EXECUTED_DIAGNOSTIC");
        Document atlas = Document.plain(
            atlasPath,
            "atlas",
            ATLAS_SCHEMA);
        Document witness = Document.hashed(
            witnessPath,
            "witness diagnostic",
            "regelsuche.witness-pruning-diagnostic/v1",
            "EXECUTED_TARGET_AWARE_ORACLE_DIAGNOSTIC");
        Document production = Document.hashed(
            productionPath,
            "production comparison",
            "regelsuche.production-search-comparison/v1",
            "EXECUTED_MATCHED_DECLARED_BUDGET_COMPARISON");
        Document equalWork = Document.hashed(
            equalWorkPath,
            "equal-work comparison",
            "regelsuche.equal-work-search-comparison/v1",
            "EXECUTED_TARGET_BLIND_FIXED_ADMITTED_WORK_CHECKPOINTS");

        String corpusHash = same(
            "corpusSha256", run, atlas, witness, production, equalWork);
        rawSha(corpusHash, "corpusSha256");
        String inventory = same(
            "inventoryRevision", run, atlas, witness, production, equalWork);
        requireEqual(
            "SCALAR_BEST_FIRST_TARGET_BLIND",
            production.text("scalarPolicy"),
            "production scalar policy");
        requireEqual(
            "STRUCTURAL_DIVERSITY_TARGET_BLIND",
            production.text("diversityPolicy"),
            "production diversity policy");
        requireEqual(
            "ENGINE_CALLS_AND_ADMITTED_PRIMITIVE_REWRITE_STEPS",
            equalWork.text("workUnit"),
            "equal-work work unit");

        String atlasHash = sha256(atlas.raw());
        requireEqual(
            atlasHash,
            artifactHash(run, "ATLAS_JSON"),
            "atlas-run payload identity");
        requireEqual(
            run.text("assessmentDecision"),
            atlas.object("assessment").text("decision"),
            "atlas assessment identity");
        requireEqual(atlasHash, witness.text("atlasSha256"), "witness atlas");
        requireEqual(
            atlasHash, production.text("atlasSha256"), "production atlas");
        requireEqual(
            atlasHash, equalWork.text("atlasSha256"), "equal-work atlas");
        requireEqual(
            witness.contentHash(),
            production.text("witnessDiagnosticSha256"),
            "production witness identity");

        View witnessSummary = witness.object("summary");
        View productionSummary = production.object("summary");
        View equalSummary = equalWork.object("summary");
        View equalStatuses = equalSummary.object("statusCounts");
        View witnessStatuses = witnessSummary.object("statusCounts");
        int caseCount = equalSummary.integer("caseCount");
        requireEqual(caseCount, atlas.array("cases").size(), "atlas cases");
        requireEqual(caseCount, run.integer("caseCount"), "run cases");
        requireEqual(
            caseCount, witnessSummary.integer("caseCount"), "witness cases");
        requireEqual(
            caseCount, productionSummary.integer("caseCount"),
            "production cases");

        Distribution distribution = new Distribution(
            caseCount,
            equalStatuses.optionalInteger("NO_PRODUCTION_WITNESS"),
            equalStatuses.optionalInteger("SCALAR_ALREADY_REACHED"),
            equalStatuses.optionalInteger(
                "EXECUTED_ORACLE_WITNESS_SCALAR_MISS"),
            witnessStatuses.optionalInteger("WITNESS_PREFIX_LOST"),
            productionSummary.integer(
                "diversityRecoveredCompleteWitnessCount"),
            equalSummary.integer("checkpointCount"),
            equalSummary.integer("equalConsumedWorkCount"),
            equalSummary.integer("equalWorkDiversityAdvantageCount"),
            equalSummary.integer(
                "equalWorkDiversityCompleteWitnessCount"));
        requireDecisionEvidence(atlas.object("assessment"), distribution);

        Sources sources = new Sources(
            run.contentHash(), atlasHash, witness.contentHash(),
            production.contentHash(), equalWork.contentHash());
        List<Decision> decisions = decisions();
        String hash = reportHash(
            corpusHash, inventory, sources, distribution, decisions);
        return new Report(
            corpusHash, inventory, sources, distribution, decisions, hash);
    }

    static Path write(Path directory, Report report) {
        Path output = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath().normalize().resolve(FILE_NAME);
        String json = Objects.requireNonNull(report, "report").json();
        try {
            Files.createDirectories(output.getParent());
            AtomicJsonFile.writeUtf8(output, json);
            requireEqual(
                json,
                Files.readString(output, StandardCharsets.UTF_8),
                "written architecture decision");
            return output;
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write rewrite architecture decision", exception);
        }
    }

    private static void requireDecisionEvidence(
        View assessment,
        Distribution distribution
    ) {
        if (!assessment.bool("representationLayerWorks")
                || !assessment.bool("missingInventoryLayerIdentified")
                || !assessment.bool("searchPolicyDifferenceIdentified")
                || !assessment.bool("negativeControlPassed")
                || distribution.noProductionWitnessCount() < 1
                || distribution.oracleWitnessScalarMissCount() < 1
                || distribution.witnessPrefixLostCount() < 1
                || distribution.diversityRecoveredCompleteWitnessCount() < 1
                || distribution.equalWorkDiversityAdvantageCount() < 1
                || distribution.equalWorkDiversityCompleteWitnessCount() < 1) {
            throw new IllegalArgumentException(
                "historical evidence does not support decision revision v1");
        }
    }

    private static String artifactHash(Document run, String role) {
        for (Object raw : run.array("artifacts")) {
            View artifact = View.of(raw, "artifact");
            if (role.equals(artifact.text("role"))) {
                return prefixedSha(artifact.text("byteHash"), role + " hash");
            }
        }
        throw new IllegalArgumentException(
            "atlas run is missing artifact role " + role);
    }

    private static String same(String key, Document first, Document... rest) {
        String expected = first.text(key);
        for (Document value : rest) {
            requireEqual(expected, value.text(key), key);
        }
        return expected;
    }

    private static List<Decision> decisions() {
        return List.of(
            decision(
                "DIRECTED_INVENTORY_DIRECTIONALITY_DIAGNOSIS",
                Disposition.SELECTED_NEXT_REVERSIBLE_TRANCHE,
                "#620",
                List.of(
                    "NO_PRODUCTION_WITNESS_COUNT_POSITIVE",
                    "MISSING_INVENTORY_LAYER_IDENTIFIED"),
                "Cases without a directed production witness cannot be repaired "
                    + "by survivor ranking or broader runtime budgets."),
            decision(
                "PARETO_COMPLEXITY_DEBT_SEARCH_CONTROL",
                Disposition.SELECTED_NEXT_REVERSIBLE_TRANCHE,
                "#620",
                List.of(
                    "WITNESS_PREFIX_LOSS_RETAINED",
                    "MATCHED_ADMITTED_WORK_DIVERSITY_ADVANTAGE"),
                "A target-blind diversity policy retains a complete witness at "
                    + "a checkpoint where the admitted-work ledgers match, so a "
                    + "small non-scalar policy control is justified."),
            decision(
                "TARGET_FREE_REPRESENTATION_DISCOVERY",
                Disposition.REQUIRED_NEXT_EVALUATION,
                "#663",
                List.of(
                    "HISTORICAL_ENDPOINT_CONTROL_IS_NARROW",
                    "PRIMARY_DISCOVERY_TARGET_IS_REPRESENTATION_GAIN"),
                "The historical endpoint matrix is diagnostic; the next central "
                    + "evaluation must test target-free compression and concrete "
                    + "known-structure capability unlocks."),
            decision(
                "EXACT_VALUE_ARENA_SEARCH_QUOTIENT",
                Disposition.DEFERRED_NO_CAUSAL_EVIDENCE,
                "#661",
                List.of("NO_VALUE_IDENTITY_CAUSAL_LOSS_RETAINED"),
                "The retained control does not show that exact scalar identity, "
                    + "value interning or history-bearing quotienting caused the "
                    + "observed witness loss."),
            decision(
                "NATIVE_AC_CONDITIONAL_PROOF_EGRAPH",
                Disposition.DEFERRED_NO_CAUSAL_EVIDENCE,
                "#662",
                List.of("NO_MATCHER_GUARD_EGRAPH_CAUSAL_LOSS_RETAINED"),
                "The retained control does not establish an AC matcher, guard, "
                    + "assumption or e-graph semantic failure."),
            decision(
                "BROAD_RUNTIME_OPTIMIZATION",
                Disposition.DEFERRED_PENDING_LAYER_PROFILE,
                "#620",
                List.of("NO_LAYER_SEPARATED_PROFILE_RETAINED"),
                "Runtime work should be selected only after profiling shows the "
                    + "dominant layer and an optimization increases reachable "
                    + "depth, coverage or proof strength."));
    }

    private static Decision decision(
        String track,
        Disposition disposition,
        String issue,
        List<String> evidence,
        String reason
    ) {
        return new Decision(track, disposition, issue, evidence, reason);
    }

    private static String reportHash(
        String corpusHash,
        String inventory,
        Sources sources,
        Distribution distribution,
        List<Decision> decisions
    ) {
        return sha256(render(
            corpusHash, inventory, sources, distribution, decisions, null));
    }

    private static String render(
        String corpusHash,
        String inventory,
        Sources sources,
        Distribution distribution,
        List<Decision> decisions,
        String contentHash
    ) {
        JsonWriter writer = new JsonWriter().beginObject();
        writer.property("schema", SCHEMA);
        writer.property("evidenceStatus", EVIDENCE_STATUS);
        writer.property("corpusSchema", CORPUS_SCHEMA);
        writer.property("corpusSha256", corpusHash);
        writer.property("inventoryRevision", inventory);
        writer.object("sourceIdentities", object -> write(object, sources));
        writer.object("measuredDistribution", object ->
            write(object, distribution));
        writer.array("decisions", array -> decisions.forEach(value ->
            array.objectValue(object -> write(object, value))));
        writer.property("claimBoundary", CLAIM_BOUNDARY);
        if (contentHash != null) {
            writer.property("contentHash", contentHash);
        }
        return writer.endObject().toString();
    }

    private static void write(JsonWriter writer, Sources value) {
        writer.property("atlasRunContentHash", value.atlasRunContentHash());
        writer.property("atlasSha256", value.atlasSha256());
        writer.property(
            "witnessDiagnosticContentHash",
            value.witnessDiagnosticContentHash());
        writer.property(
            "productionComparisonContentHash",
            value.productionComparisonContentHash());
        writer.property(
            "equalWorkComparisonContentHash",
            value.equalWorkComparisonContentHash());
    }

    private static void write(JsonWriter writer, Distribution value) {
        writer.property("caseCount", value.caseCount());
        writer.property(
            "noProductionWitnessCount", value.noProductionWitnessCount());
        writer.property(
            "scalarAlreadyReachedCount", value.scalarAlreadyReachedCount());
        writer.property(
            "oracleWitnessScalarMissCount",
            value.oracleWitnessScalarMissCount());
        writer.property(
            "witnessPrefixLostCount", value.witnessPrefixLostCount());
        writer.property(
            "diversityRecoveredCompleteWitnessCount",
            value.diversityRecoveredCompleteWitnessCount());
        writer.property("checkpointCount", value.checkpointCount());
        writer.property(
            "equalConsumedWorkCheckpointCount",
            value.equalConsumedWorkCheckpointCount());
        writer.property(
            "equalWorkDiversityAdvantageCount",
            value.equalWorkDiversityAdvantageCount());
        writer.property(
            "equalWorkDiversityCompleteWitnessCount",
            value.equalWorkDiversityCompleteWitnessCount());
    }

    private static void write(JsonWriter writer, Decision value) {
        writer.property("track", value.track());
        writer.property("disposition", value.disposition().name());
        writer.property("relatedIssue", value.relatedIssue());
        writer.array("evidenceCodes", array ->
            value.evidenceCodes().forEach(array::value));
        writer.property("reason", value.reason());
    }

    private static void requireEqual(
        Object expected,
        Object actual,
        String label
    ) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException(
                label + " differs: expected=" + expected + ", actual=" + actual);
        }
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }

    private static String rawSha(String value, String label) {
        String result = text(value, label);
        if (!result.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " must be raw SHA-256");
        }
        return result;
    }

    private static String prefixedSha(String value, String label) {
        String result = text(value, label);
        if (!result.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                label + " must be prefixed SHA-256");
        }
        return result;
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    enum Disposition {
        SELECTED_NEXT_REVERSIBLE_TRANCHE,
        REQUIRED_NEXT_EVALUATION,
        DEFERRED_NO_CAUSAL_EVIDENCE,
        DEFERRED_PENDING_LAYER_PROFILE
    }

    record Sources(
        String atlasRunContentHash,
        String atlasSha256,
        String witnessDiagnosticContentHash,
        String productionComparisonContentHash,
        String equalWorkComparisonContentHash
    ) {
        Sources {
            atlasRunContentHash = prefixedSha(
                atlasRunContentHash, "atlasRunContentHash");
            atlasSha256 = prefixedSha(atlasSha256, "atlasSha256");
            witnessDiagnosticContentHash = prefixedSha(
                witnessDiagnosticContentHash,
                "witnessDiagnosticContentHash");
            productionComparisonContentHash = prefixedSha(
                productionComparisonContentHash,
                "productionComparisonContentHash");
            equalWorkComparisonContentHash = prefixedSha(
                equalWorkComparisonContentHash,
                "equalWorkComparisonContentHash");
        }
    }

    record Distribution(
        int caseCount,
        int noProductionWitnessCount,
        int scalarAlreadyReachedCount,
        int oracleWitnessScalarMissCount,
        int witnessPrefixLostCount,
        int diversityRecoveredCompleteWitnessCount,
        int checkpointCount,
        int equalConsumedWorkCheckpointCount,
        int equalWorkDiversityAdvantageCount,
        int equalWorkDiversityCompleteWitnessCount
    ) {
        Distribution {
            int[] values = {
                caseCount,
                noProductionWitnessCount,
                scalarAlreadyReachedCount,
                oracleWitnessScalarMissCount,
                witnessPrefixLostCount,
                diversityRecoveredCompleteWitnessCount,
                checkpointCount,
                equalConsumedWorkCheckpointCount,
                equalWorkDiversityAdvantageCount,
                equalWorkDiversityCompleteWitnessCount
            };
            for (int value : values) {
                if (value < 0) {
                    throw new IllegalArgumentException(
                        "distribution counters must not be negative");
                }
            }
            if (caseCount < 1
                    || noProductionWitnessCount + scalarAlreadyReachedCount
                        + oracleWitnessScalarMissCount != caseCount
                    || witnessPrefixLostCount < oracleWitnessScalarMissCount
                    || diversityRecoveredCompleteWitnessCount
                        < oracleWitnessScalarMissCount
                    || equalConsumedWorkCheckpointCount > checkpointCount
                    || equalWorkDiversityAdvantageCount
                        > equalConsumedWorkCheckpointCount
                    || equalWorkDiversityCompleteWitnessCount
                        > equalWorkDiversityAdvantageCount) {
                throw new IllegalArgumentException(
                    "distribution is internally inconsistent");
            }
        }
    }

    record Decision(
        String track,
        Disposition disposition,
        String relatedIssue,
        List<String> evidenceCodes,
        String reason
    ) {
        Decision {
            track = text(track, "track");
            Objects.requireNonNull(disposition, "disposition");
            relatedIssue = text(relatedIssue, "relatedIssue");
            if (!relatedIssue.matches("#[0-9]+")) {
                throw new IllegalArgumentException(
                    "relatedIssue must be an issue reference");
            }
            evidenceCodes = List.copyOf(
                Objects.requireNonNull(evidenceCodes, "evidenceCodes"));
            if (evidenceCodes.isEmpty()
                    || new LinkedHashSet<>(evidenceCodes).size()
                        != evidenceCodes.size()) {
                throw new IllegalArgumentException(
                    "evidenceCodes must be non-empty and unique");
            }
            reason = text(reason, "reason");
        }
    }

    record Report(
        String corpusSha256,
        String inventoryRevision,
        Sources sources,
        Distribution distribution,
        List<Decision> decisions,
        String contentHash
    ) {
        Report {
            corpusSha256 = rawSha(corpusSha256, "corpusSha256");
            inventoryRevision = text(inventoryRevision, "inventoryRevision");
            Objects.requireNonNull(sources, "sources");
            Objects.requireNonNull(distribution, "distribution");
            decisions = List.copyOf(
                Objects.requireNonNull(decisions, "decisions"));
            Set<Disposition> dispositions = EnumSet.noneOf(Disposition.class);
            decisions.forEach(value -> dispositions.add(value.disposition()));
            if (decisions.size() != 6
                    || new LinkedHashSet<>(decisions.stream()
                        .map(Decision::track).toList()).size() != 6
                    || !dispositions.equals(EnumSet.allOf(Disposition.class))) {
                throw new IllegalArgumentException(
                    "decision matrix differs from revision v1");
            }
            contentHash = prefixedSha(contentHash, "contentHash");
            requireEqual(
                reportHash(
                    corpusSha256, inventoryRevision, sources,
                    distribution, decisions),
                contentHash,
                "decision contentHash");
        }

        String json() {
            return render(
                corpusSha256, inventoryRevision, sources,
                distribution, decisions, contentHash);
        }
    }

    private record Document(String raw, View root, String contentHash) {
        static Document plain(Path path, String label, String schema) {
            String raw = read(path, label);
            View root = View.parse(raw, label);
            requireEqual(schema, root.text("schema"), label + " schema");
            return new Document(raw, root, "");
        }

        static Document hashed(
            Path path,
            String label,
            String schema,
            String status
        ) {
            String raw = read(path, label);
            View root = View.parse(raw, label);
            requireEqual(schema, root.text("schema"), label + " schema");
            requireEqual(
                status, root.text("evidenceStatus"), label + " status");
            String hash = prefixedSha(
                root.text("contentHash"), label + " contentHash");
            String suffix = ",\"contentHash\":\"" + hash + "\"}";
            if (!raw.endsWith(suffix)) {
                throw new IllegalArgumentException(
                    label + " contentHash is not the final canonical field");
            }
            String withoutHash = raw.substring(
                0, raw.length() - suffix.length()) + "}";
            requireEqual(hash, sha256(withoutHash), label + " contentHash");
            return new Document(raw, root, hash);
        }

        String text(String key) {
            return root.text(key);
        }

        int integer(String key) {
            return root.integer(key);
        }

        View object(String key) {
            return root.object(key);
        }

        List<?> array(String key) {
            return root.array(key);
        }
    }

    private record View(Map<String, Object> values) {
        static View parse(String json, String label) {
            try {
                return new View(new JsonReader(json).readObject());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                    label + " is not strict JSON", exception);
            }
        }

        static View of(Object raw, String label) {
            if (!(raw instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(label + " must be an object");
            }
            Map<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (!(key instanceof String text)) {
                    throw new IllegalArgumentException(
                        label + " contains a non-string key");
                }
                values.put(text, value);
            });
            return new View(Map.copyOf(values));
        }

        String text(String key) {
            Object raw = values.get(key);
            if (!(raw instanceof String value)) {
                throw new IllegalArgumentException(key + " must be text");
            }
            return HistoricalRewriteArchitectureDecision.text(value, key);
        }

        int integer(String key) {
            Object raw = values.get(key);
            if (!(raw instanceof Number number)) {
                throw new IllegalArgumentException(key + " must be numeric");
            }
            double decimal = number.doubleValue();
            int value = number.intValue();
            if (!Double.isFinite(decimal) || decimal != value) {
                throw new IllegalArgumentException(key + " must be an integer");
            }
            return value;
        }

        int optionalInteger(String key) {
            return values.containsKey(key) ? integer(key) : 0;
        }

        boolean bool(String key) {
            Object raw = values.get(key);
            if (!(raw instanceof Boolean value)) {
                throw new IllegalArgumentException(key + " must be boolean");
            }
            return value;
        }

        View object(String key) {
            return of(values.get(key), key);
        }

        List<?> array(String key) {
            Object raw = values.get(key);
            if (!(raw instanceof List<?> list)) {
                throw new IllegalArgumentException(key + " must be an array");
            }
            return List.copyOf(list);
        }
    }

    private static String read(Path path, String label) {
        Path normalized = Objects.requireNonNull(path, label + " path")
            .toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(normalized)
                    || Files.size(normalized) > MAX_INPUT_BYTES) {
                throw new IllegalArgumentException(
                    label + " must be a bounded regular file");
            }
            return Files.readString(normalized, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read " + label, exception);
        }
    }
}
