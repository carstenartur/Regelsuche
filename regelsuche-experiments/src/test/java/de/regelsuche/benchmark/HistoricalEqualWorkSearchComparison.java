package de.regelsuche.benchmark;

import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.AtlasReport;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.CaseResult;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.OracleEvidence;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Case;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.TargetRelation;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.equivalence.SymPyEquivalenceService;
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
import java.util.HexFormat;
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
            checkpoints = List.copyOf(
                Objects.requireNonNull(checkpoints, "checkpoints"));
            if (oracleWitnessStepCount < 0) {
                throw new IllegalArgumentException(
                    "oracleWitnessStepCount must not be negative");
            }
            boolean executed = status
                == CaseStatus.EXECUTED_ORACLE_WITNESS_SCALAR_MISS;
            boolean witnessRequired = status != CaseStatus.NO_PRODUCTION_WITNESS;
            if (executed != !checkpoints.isEmpty()
                    || executed && checkpoints.size() != CHECKPOINTS.size()
                    || witnessRequired && oracleWitnessStepCount < 1
                    || !witnessRequired && oracleWitnessStepCount != 0) {
                throw new IllegalArgumentException(
                    "case status and checkpoint evidence differ");
            }
            for (Checkpoint checkpoint : checkpoints) {
                Outcome expected = Checkpoint.outcome(
                    oracleWitnessStepCount,
                    checkpoint.scalar(),
                    checkpoint.diversity());
                if (checkpoint.outcome() != expected
                        || checkpoint.scalar().witnessPrefixLength()
                            > oracleWitnessStepCount
                        || checkpoint.diversity().witnessPrefixLength()
                            > oracleWitnessStepCount) {
                    throw new IllegalArgumentException(
                        "checkpoint outcome differs from witness evidence");
                }
            }
        }

        static EqualWorkCase skipped(
            String id,
            CaseStatus status,
            String atlasStatus,
            String oracleStatus,
            int oracleWitnessStepCount
        ) {
            return new EqualWorkCase(
                id, status, atlasStatus, oracleStatus,
                oracleWitnessStepCount, List.of());
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
            statusCounts = Map.copyOf(
                Objects.requireNonNull(statusCounts, "statusCounts"));
            if (caseCount < 1 || checkpointCount < 0
                    || equalConsumedWorkCount < 0
                    || equalWorkDiversityAdvantageCount < 0
                    || equalWorkDiversityCompleteWitnessCount < 0
                    || statusCounts.values().stream()
                        .mapToInt(Integer::intValue).sum() != caseCount
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
            Map<CaseStatus, Integer> counts = new EnumMap<>(CaseStatus.class);
            cases.forEach(value -> counts.merge(
                value.status(), 1, Integer::sum));
            List<Checkpoint> checkpoints = cases.stream()
                .flatMap(value -> value.checkpoints().stream()).toList();
            int equal = (int) checkpoints.stream()
                .filter(Checkpoint::equalConsumedWork).count();
            int advantage = (int) checkpoints.stream()
                .filter(Checkpoint::equalConsumedWork)
                .filter(value -> value.outcome()
                    == Outcome.DIVERSITY_ONLY_COMPLETE_WITNESS
                    || value.outcome() == Outcome.DIVERSITY_LONGER_PREFIX)
                .count();
            int complete = (int) checkpoints.stream()
                .filter(Checkpoint::equalConsumedWork)
                .filter(value -> value.outcome()
                    == Outcome.DIVERSITY_ONLY_COMPLETE_WITNESS)
                .count();
            return new Summary(
                cases.size(), Map.copyOf(counts), checkpoints.size(),
                equal, advantage, complete);
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
            cases = Objects.requireNonNull(cases, "cases").stream()
                .sorted(Comparator.comparing(EqualWorkCase::id)).toList();
            if (cases.isEmpty()
                    || new LinkedHashSet<>(cases.stream()
                        .map(EqualWorkCase::id).toList()).size()
                        != cases.size()
                    || !Summary.derive(cases).equals(
                        Objects.requireNonNull(summary, "summary"))) {
                throw new IllegalArgumentException(
                    "equal-work report case balance differs");
            }
            contentHash = requireSha256(
                contentHash, "contentHash", true);
            if (!hash(
                    corpusSha256, atlasSha256, inventoryRevision,
                    cases, summary).equals(contentHash)) {
                throw new IllegalArgumentException(
                    "equal-work report contentHash mismatch");
            }
        }

        static Report create(
            Corpus corpus,
            AtlasReport atlas,
            List<EqualWorkCase> cases
        ) {
            List<EqualWorkCase> sorted = cases.stream()
                .sorted(Comparator.comparing(EqualWorkCase::id)).toList();
            Summary summary = Summary.derive(sorted);
            String atlasSha = sha256(atlas.toJson());
            return new Report(
                corpus.contentSha256(),
                atlasSha,
                corpus.inventoryRevision(),
                sorted,
                summary,
                hash(
                    corpus.contentSha256(),
                    atlasSha,
                    corpus.inventoryRevision(),
                    sorted,
                    summary));
        }

        String toCanonicalJson() {
            return render(
                corpusSha256, atlasSha256, inventoryRevision,
                cases, summary, contentHash);
        }
    }

    private static String hash(
        String corpusSha256,
        String atlasSha256,
        String inventoryRevision,
        List<EqualWorkCase> cases,
        Summary summary
    ) {
        return sha256(render(
            corpusSha256, atlasSha256, inventoryRevision,
            cases, summary, null));
    }

    private static String render(
        String corpusSha256,
        String atlasSha256,
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
        writer.property("corpusSha256", corpusSha256);
        writer.property("atlasSchema", HistoricalRediscoveryAtlas.SCHEMA);
        writer.property("atlasSha256", atlasSha256);
        writer.property("inventoryRevision", inventoryRevision);
        writer.property(
            "workUnit",
            "ENGINE_CALLS_AND_ADMITTED_PRIMITIVE_REWRITE_STEPS");
        writer.property(
            "informationBoundary", "TARGET_BLIND_SEARCHES_ORACLE_POST_HOC");
        writer.property("claimBoundary", CLAIM_BOUNDARY);
        writer.array("primitiveCheckpoints", array ->
            CHECKPOINTS.forEach(array::numberValue));
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
        writer.array("checkpoints", array -> value.checkpoints().forEach(item ->
            array.objectValue(object -> writeCheckpoint(object, item))));
    }

    private static void writeCheckpoint(JsonWriter writer, Checkpoint value) {
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
        writer.property("witnessPrefixLength", value.witnessPrefixLength());
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
        return value.trim();
    }

    private static String requireSha256(
        String value,
        String label,
        boolean prefixed
    ) {
        String text = requireText(value, label);
        String pattern = prefixed
            ? "sha256:[0-9a-f]{64}" : "[0-9a-f]{64}";
        if (!text.matches(pattern)) {
            throw new IllegalArgumentException(label + " must be SHA-256");
        }
        return text;
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
