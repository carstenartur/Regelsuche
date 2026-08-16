package de.regelsuche.benchmark;

import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.AtlasReport;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.CaseResult;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.OracleEvidence;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.SearchEvidence;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Case;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Relation;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalMetrics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchEventType;
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
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Locates the first lost prefix of a target-aware oracle witness in the
 * unchanged target-blind scalar production search.
 */
public final class HistoricalWitnessPruningDiagnostic {
    public static final String SCHEMA =
        "regelsuche.witness-pruning-diagnostic/v1";
    public static final String EVIDENCE_STATUS =
        "EXECUTED_TARGET_AWARE_ORACLE_DIAGNOSTIC";
    public static final String SEARCH_POLICY =
        "SCALAR_BEST_FIRST_TARGET_BLIND";
    public static final String FILE_NAME =
        "witness-pruning-diagnostic.json";
    public static final String CLAIM_BOUNDARY =
        "The target-aware oracle supplies a bounded witness only for diagnosis. "
            + "The compared scalar search remains target-blind. A lost prefix "
            + "is not global unreachability, and an explored witness is not "
            + "autonomous rediscovery, proof or mathematical novelty.";

    static final String ORACLE_NOT_EVALUATED = "ORACLE_NOT_EVALUATED";
    static final String ORACLE_COMPLETE_CLOSURE_WITHOUT_WITNESS =
        "ORACLE_COMPLETE_CLOSURE_WITHOUT_WITNESS";
    static final String ORACLE_BUDGET_INCONCLUSIVE =
        "ORACLE_BUDGET_INCONCLUSIVE";
    static final String SCALAR_ALREADY_FOUND = "SCALAR_ALREADY_FOUND";
    static final String WITNESS_PREFIX_LOST = "WITNESS_PREFIX_LOST";
    static final String WITNESS_COMPLETELY_EXPLORED =
        "WITNESS_COMPLETELY_EXPLORED";
    static final String CORRECTNESS_REGRESSION_WITNESS =
        "CORRECTNESS_REGRESSION_WITNESS";

    static final String TRANSFORMATION_SKIPPED = "TRANSFORMATION_SKIPPED";
    static final String STATE_PRUNED_DUPLICATE = "STATE_PRUNED_DUPLICATE";
    static final String STATE_ENQUEUED_BUT_NOT_EXPLORED =
        "STATE_ENQUEUED_BUT_NOT_EXPLORED";
    static final String TRANSFORMATION_GENERATED_NOT_ENQUEUED =
        "TRANSFORMATION_GENERATED_NOT_ENQUEUED";
    static final String CANDIDATE_BUDGET_BEFORE_WITNESS_EDGE =
        "CANDIDATE_BUDGET_BEFORE_WITNESS_EDGE";
    static final String PARENT_DEPTH_LIMIT = "PARENT_DEPTH_LIMIT";
    static final String PARENT_PRUNED_TRANSPOSITION =
        "PARENT_PRUNED_TRANSPOSITION";
    static final String PARENT_PRUNED_DUPLICATE =
        "PARENT_PRUNED_DUPLICATE";
    static final String TRANSFORMATION_NOT_GENERATED =
        "TRANSFORMATION_NOT_GENERATED";
    static final String PARENT_ENQUEUED_BUT_NOT_EXPLORED =
        "PARENT_ENQUEUED_BUT_NOT_EXPLORED";
    static final String PARENT_NOT_REACHED = "PARENT_NOT_REACHED";

    private static final Set<String> CASE_STATUSES = Set.of(
        ORACLE_NOT_EVALUATED,
        ORACLE_COMPLETE_CLOSURE_WITHOUT_WITNESS,
        ORACLE_BUDGET_INCONCLUSIVE,
        SCALAR_ALREADY_FOUND,
        WITNESS_PREFIX_LOST,
        WITNESS_COMPLETELY_EXPLORED,
        CORRECTNESS_REGRESSION_WITNESS);
    private static final Set<String> LOSS_REASONS = Set.of(
        TRANSFORMATION_SKIPPED,
        STATE_PRUNED_DUPLICATE,
        STATE_ENQUEUED_BUT_NOT_EXPLORED,
        TRANSFORMATION_GENERATED_NOT_ENQUEUED,
        CANDIDATE_BUDGET_BEFORE_WITNESS_EDGE,
        PARENT_DEPTH_LIMIT,
        PARENT_PRUNED_TRANSPOSITION,
        PARENT_PRUNED_DUPLICATE,
        TRANSFORMATION_NOT_GENERATED,
        PARENT_ENQUEUED_BUT_NOT_EXPLORED,
        PARENT_NOT_REACHED);
    private static final Set<String> TERMINAL_STATUSES = Set.of(
        "NOT_EVALUATED",
        "SCALAR_FOUND",
        "STATE_BUDGET",
        "CANDIDATE_BUDGET",
        "DEPTH_BUDGET",
        "NO_TRANSFORMATIONS",
        "FRONTIER_EXHAUSTED");
    private static final SearchEventType[] PARENT_EVENT_TYPES = {
        SearchEventType.STATE_PRUNED_BUDGET,
        SearchEventType.STATE_PRUNED_DEPTH,
        SearchEventType.STATE_PRUNED_TRANSPOSITION,
        SearchEventType.STATE_PRUNED_DUPLICATE,
        SearchEventType.STATE_EXPANDED,
        SearchEventType.STATE_ENQUEUED
    };
    private static final String[] PARENT_REASONS = {
        CANDIDATE_BUDGET_BEFORE_WITNESS_EDGE,
        PARENT_DEPTH_LIMIT,
        PARENT_PRUNED_TRANSPOSITION,
        PARENT_PRUNED_DUPLICATE,
        TRANSFORMATION_NOT_GENERATED,
        PARENT_ENQUEUED_BUT_NOT_EXPLORED
    };
    private static final String[] PARENT_DETAILS = {
        "per-state candidate ceiling stopped before the witness edge",
        "witness parent reached the configured depth ceiling",
        "witness parent was rejected by transposition memory",
        "witness parent was rejected as a duplicate",
        "production engine did not emit the oracle witness edge",
        "witness parent remained in the frontier"
    };

    private final ExpressionParser parser = new ExpressionParser();

    public Report run(Corpus corpus, AtlasReport atlas) {
        Objects.requireNonNull(corpus, "corpus");
        Objects.requireNonNull(atlas, "atlas");
        requireAtlasBinding(corpus, atlas);
        Map<String, CaseResult> atlasCases = atlas.cases().stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(
                value -> value.benchmarkCase().id(),
                value -> value));
        List<CaseDiagnostic> cases = corpus.cases().stream()
            .sorted(Comparator.comparing(Case::id))
            .map(value -> diagnose(
                value,
                Objects.requireNonNull(
                    atlasCases.get(value.id()),
                    () -> "missing atlas case " + value.id())))
            .toList();
        return Report.create(corpus, atlas, cases);
    }

    public Path write(Path directory, Report report) {
        Path outputDirectory = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath().normalize();
        Objects.requireNonNull(report, "report");
        Path output = outputDirectory.resolve(FILE_NAME);
        try {
            Files.createDirectories(outputDirectory);
            AtomicJsonFile.writeUtf8(output, report.toCanonicalJson());
            String retained = Files.readString(output, StandardCharsets.UTF_8);
            if (!report.toCanonicalJson().equals(retained)) {
                throw new IllegalStateException(
                    "written witness-pruning diagnostic differs from report");
            }
            return output;
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write witness-pruning diagnostic", exception);
        }
    }

    private CaseDiagnostic diagnose(Case benchmarkCase, CaseResult atlasCase) {
        OracleEvidence oracle = atlasCase.production().oracle();
        SearchEvidence scalar = atlasCase.production().scalar();
        if (!oracle.reachable()) {
            return CaseDiagnostic.notApplicable(
                benchmarkCase.id(),
                unavailableStatus(oracle),
                oracle.status(),
                scalar);
        }
        if (benchmarkCase.relation() == Relation.NOT_EQUIVALENT) {
            return new CaseDiagnostic(
                benchmarkCase.id(),
                CORRECTNESS_REGRESSION_WITNESS,
                oracle.status(),
                oracle.witnessExpressions().size(),
                0,
                "NOT_EVALUATED",
                scalar.exploredStates(),
                scalar.engineCalls(),
                scalar.generatedTransformations(),
                null,
                "production oracle reached a target declared non-equivalent");
        }
        if (scalar.reached()) {
            return new CaseDiagnostic(
                benchmarkCase.id(),
                SCALAR_ALREADY_FOUND,
                oracle.status(),
                oracle.witnessExpressions().size(),
                0,
                "SCALAR_FOUND",
                scalar.exploredStates(),
                scalar.engineCalls(),
                scalar.generatedTransformations(),
                null,
                "the retained scalar search reached the relation; witness-prefix "
                    + "comparison was not required");
        }
        validateWitness(benchmarkCase, oracle);

        TransformationEngine production = productionEngine(benchmarkCase);
        int[] calls = {0};
        long[] generated = {0};
        TransformationEngine counting = expression -> {
            calls[0]++;
            List<Transformation> result = production.transform(expression);
            generated[0] += result.size();
            return result;
        };
        List<SearchEvent> events = new ArrayList<>();
        SearchProblem problem = searchProblem(
            benchmarkCase,
            atlasCase.representation().formattedSource(),
            counting).withObserver(events::add);
        if (problem.target() != null) {
            throw new IllegalStateException(
                "witness comparison search must remain target-blind");
        }
        GoalSearchResult rerun = new BestFirstSearchStrategy()
            .searchWithDiagnostics(problem);
        requireSameScalarRun(scalar, rerun, calls[0], generated[0]);
        return analyzeWitness(
            benchmarkCase,
            oracle,
            rerun,
            events,
            calls[0],
            generated[0]);
    }

    CaseDiagnostic analyzeWitness(
        Case benchmarkCase,
        OracleEvidence oracle,
        GoalSearchResult search,
        List<SearchEvent> events,
        int engineCalls,
        long generatedTransformations
    ) {
        Set<String> explored = search.states().stream()
            .map(SearchState::expression)
            .map(this::expressionKey)
            .collect(java.util.stream.Collectors.toCollection(
                LinkedHashSet::new));
        String before = format(benchmarkCase.source());
        int prefixLength = 0;
        for (int index = 0; index < oracle.witnessExpressions().size(); index++) {
            String after = format(oracle.witnessExpressions().get(index));
            String rule = oracle.witnessRuleIds().get(index);
            if (explored.contains(expressionKey(after))) {
                prefixLength++;
                before = after;
                continue;
            }
            return new CaseDiagnostic(
                benchmarkCase.id(),
                WITNESS_PREFIX_LOST,
                oracle.status(),
                oracle.witnessExpressions().size(),
                prefixLength,
                terminalStatus(benchmarkCase, search.metrics()),
                search.states().size(),
                engineCalls,
                generatedTransformations,
                diagnoseLoss(index, before, after, rule, events),
                "first target-aware oracle witness edge absent from the "
                    + "target-blind explored prefix");
        }
        return new CaseDiagnostic(
            benchmarkCase.id(),
            WITNESS_COMPLETELY_EXPLORED,
            oracle.status(),
            oracle.witnessExpressions().size(),
            prefixLength,
            terminalStatus(benchmarkCase, search.metrics()),
            search.states().size(),
            engineCalls,
            generatedTransformations,
            null,
            "all oracle witness states were explored; relation matching must "
                + "be diagnosed separately");
    }

    private LostStep diagnoseLoss(
        int index,
        String before,
        String after,
        String rule,
        List<SearchEvent> events
    ) {
        Optional<SearchEvent> transition = firstTransition(
            events, before, after, rule);
        if (transition.isEmpty()) {
            return parentLoss(index, before, after, rule, events);
        }
        SearchEvent generated = transition.orElseThrow();
        if (!generated.pruningReason().isBlank()) {
            return loss(
                index,
                before,
                after,
                rule,
                TRANSFORMATION_SKIPPED,
                generated,
                "witness transformation was offered but rejected");
        }
        Optional<SearchEvent> duplicate = firstEdgeEvent(
            events,
            SearchEventType.STATE_PRUNED_DUPLICATE,
            before,
            after,
            rule,
            generated.sequence());
        if (duplicate.isPresent()) {
            return loss(
                index,
                before,
                after,
                rule,
                STATE_PRUNED_DUPLICATE,
                duplicate.orElseThrow(),
                "witness state was removed by visited-state identity");
        }
        Optional<SearchEvent> enqueued = firstEdgeEvent(
            events,
            SearchEventType.STATE_ENQUEUED,
            before,
            after,
            rule,
            generated.sequence());
        if (enqueued.isPresent()) {
            return loss(
                index,
                before,
                after,
                rule,
                STATE_ENQUEUED_BUT_NOT_EXPLORED,
                enqueued.orElseThrow(),
                "witness state remained outside the explored prefix");
        }
        return loss(
            index,
            before,
            after,
            rule,
            TRANSFORMATION_GENERATED_NOT_ENQUEUED,
            generated,
            "generated witness edge has no enqueue or prune event");
    }

    private LostStep parentLoss(
        int index,
        String before,
        String after,
        String rule,
        List<SearchEvent> events
    ) {
        for (int position = 0; position < PARENT_EVENT_TYPES.length; position++) {
            Optional<SearchEvent> event = firstStateEvent(
                events, PARENT_EVENT_TYPES[position], before);
            if (event.isPresent()) {
                return loss(
                    index,
                    before,
                    after,
                    rule,
                    PARENT_REASONS[position],
                    event.orElseThrow(),
                    PARENT_DETAILS[position]);
            }
        }
        return loss(
            index,
            before,
            after,
            rule,
            PARENT_NOT_REACHED,
            null,
            "target-blind search never reached the witness parent");
    }

    private LostStep loss(
        int index,
        String before,
        String after,
        String rule,
        String reason,
        SearchEvent event,
        String detail
    ) {
        return new LostStep(
            index, before, after, rule, reason, event, detail);
    }

    private Optional<SearchEvent> firstTransition(
        List<SearchEvent> events,
        String before,
        String after,
        String rule
    ) {
        return events.stream()
            .filter(event -> event.type() == SearchEventType.TRANSFORMATION_GENERATED)
            .filter(event -> sameExpression(event.parentExpression(), before))
            .filter(event -> sameExpression(event.expression(), after))
            .filter(event -> event.ruleId().equals(rule))
            .min(Comparator.comparingLong(SearchEvent::sequence));
    }

    private Optional<SearchEvent> firstEdgeEvent(
        List<SearchEvent> events,
        SearchEventType type,
        String before,
        String after,
        String rule,
        long afterSequence
    ) {
        return events.stream()
            .filter(event -> event.sequence() > afterSequence)
            .filter(event -> event.type() == type)
            .filter(event -> sameExpression(event.parentExpression(), before))
            .filter(event -> sameExpression(event.expression(), after))
            .filter(event -> event.ruleId().equals(rule))
            .min(Comparator.comparingLong(SearchEvent::sequence));
    }

    private Optional<SearchEvent> firstStateEvent(
        List<SearchEvent> events,
        SearchEventType type,
        String expression
    ) {
        return events.stream()
            .filter(event -> event.type() == type)
            .filter(event -> sameExpression(event.expression(), expression))
            .min(Comparator.comparingLong(SearchEvent::sequence));
    }

    private static String unavailableStatus(OracleEvidence oracle) {
        if (oracle.inconclusive()) {
            return ORACLE_BUDGET_INCONCLUSIVE;
        }
        if (oracle.completeClosureExhausted()) {
            return ORACLE_COMPLETE_CLOSURE_WITHOUT_WITNESS;
        }
        return ORACLE_NOT_EVALUATED;
    }

    private static String terminalStatus(Case benchmarkCase, GoalMetrics metrics) {
        if (metrics.exploredStates() >= benchmarkCase.searchMaxVisitedStates()) {
            return "STATE_BUDGET";
        }
        if (metrics.candidateBudgetPrunes() > 0) {
            return "CANDIDATE_BUDGET";
        }
        if (metrics.depthPrunes() > 0) {
            return "DEPTH_BUDGET";
        }
        if (metrics.expandedStates() > 0
                && metrics.generatedTransformations() == 0) {
            return "NO_TRANSFORMATIONS";
        }
        return "FRONTIER_EXHAUSTED";
    }

    private SearchProblem searchProblem(
        Case benchmarkCase,
        String source,
        TransformationEngine engine
    ) {
        return new SearchProblem(
            source,
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(
                benchmarkCase.searchMaxDepth(),
                benchmarkCase.searchMaxVisitedStates(),
                1,
                benchmarkCase.maxExpandingSteps(),
                benchmarkCase.maxCandidatesPerState(),
                benchmarkCase.beamWidth()));
    }

    private static TransformationEngine productionEngine(Case benchmarkCase) {
        return AstRewriteTransformationEngines.production(
            AstRewriteTransformationEngine.defaultRules(),
            128,
            Math.max(200, benchmarkCase.maxCandidatesPerState() * 2));
    }

    private static void requireSameScalarRun(
        SearchEvidence retained,
        GoalSearchResult rerun,
        int engineCalls,
        long generatedTransformations
    ) {
        List<Object> retainedWork = List.of(
            retained.exploredStates(),
            retained.engineCalls(),
            retained.generatedTransformations(),
            Objects.requireNonNull(retained.metrics(), "retained.metrics"));
        List<Object> rerunWork = List.of(
            rerun.states().size(),
            engineCalls,
            generatedTransformations,
            rerun.metrics());
        if (!retainedWork.equals(rerunWork)) {
            throw new IllegalStateException(
                "observer rerun differs from retained target-blind scalar evidence");
        }
    }

    private static void validateWitness(Case benchmarkCase, OracleEvidence oracle) {
        if (oracle.witnessExpressions().isEmpty()
                || oracle.witnessExpressions().size()
                    != oracle.witnessRuleIds().size()) {
            throw new IllegalStateException(
                "reachable oracle witness is incomplete for " + benchmarkCase.id());
        }
    }

    private static void requireAtlasBinding(Corpus corpus, AtlasReport atlas) {
        List<Object> corpusBinding = List.of(
            corpus.schema(),
            corpus.contentSha256(),
            corpus.inventoryRevision(),
            corpus.claimBoundary(),
            corpus.cases().size());
        List<Object> atlasBinding = List.of(
            atlas.corpusSchema(),
            atlas.corpusSha256(),
            atlas.inventoryRevision(),
            atlas.claimBoundary(),
            atlas.cases().size());
        if (!corpusBinding.equals(atlasBinding)) {
            throw new IllegalArgumentException(
                "witness-pruning diagnostic requires the matching atlas");
        }
        Set<String> corpusIds = corpus.cases().stream()
            .map(Case::id)
            .collect(java.util.stream.Collectors.toSet());
        Set<String> atlasIds = atlas.cases().stream()
            .map(result -> result.benchmarkCase().id())
            .collect(java.util.stream.Collectors.toSet());
        if (!corpusIds.equals(atlasIds)) {
            throw new IllegalArgumentException(
                "witness-pruning atlas case membership differs from corpus");
        }
    }

    private boolean sameExpression(String left, String right) {
        return expressionKey(left).equals(expressionKey(right));
    }

    private String expressionKey(String expression) {
        try {
            return format(expression);
        } catch (IllegalArgumentException exception) {
            return Objects.requireNonNull(expression, "expression")
                .trim().replaceAll("\\s+", " ");
        }
    }

    private String format(String expression) {
        return ExpressionFormatter.format(parser.parseTerm(expression));
    }

    public record Report(
        String corpusSha256,
        String atlasSha256,
        String inventoryRevision,
        List<CaseDiagnostic> cases,
        Map<String, Integer> statusCounts,
        Map<String, Integer> firstLossCounts,
        String contentHash
    ) {
        public Report {
            requireRawSha256(corpusSha256, "corpusSha256");
            requirePrefixedSha256(atlasSha256, "atlasSha256");
            inventoryRevision = requireText(
                inventoryRevision, "inventoryRevision");
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
            statusCounts = Map.copyOf(
                Objects.requireNonNull(statusCounts, "statusCounts"));
            firstLossCounts = Map.copyOf(
                Objects.requireNonNull(firstLossCounts, "firstLossCounts"));
            validateCases(cases, statusCounts, firstLossCounts);
            requirePrefixedSha256(contentHash, "contentHash");
            String expected = reportHash(
                corpusSha256,
                atlasSha256,
                inventoryRevision,
                cases,
                statusCounts,
                firstLossCounts);
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "witness-pruning contentHash mismatch");
            }
        }

        static Report create(
            Corpus corpus,
            AtlasReport atlas,
            List<CaseDiagnostic> cases
        ) {
            Map<String, Integer> statuses = countValues(
                cases.stream().map(CaseDiagnostic::status).toList());
            Map<String, Integer> losses = countValues(cases.stream()
                .map(CaseDiagnostic::firstLoss)
                .filter(Objects::nonNull)
                .map(LostStep::reason)
                .toList());
            String atlasHash = sha256(atlas.toJson());
            String hash = reportHash(
                corpus.contentSha256(),
                atlasHash,
                corpus.inventoryRevision(),
                cases,
                statuses,
                losses);
            return new Report(
                corpus.contentSha256(),
                atlasHash,
                corpus.inventoryRevision(),
                cases,
                statuses,
                losses,
                hash);
        }

        public String schema() {
            return SCHEMA;
        }

        public String toCanonicalJson() {
            return renderReport(
                corpusSha256,
                atlasSha256,
                inventoryRevision,
                cases,
                statusCounts,
                firstLossCounts,
                contentHash);
        }
    }

    public record CaseDiagnostic(
        String id,
        String status,
        String oracleStatus,
        int witnessStepCount,
        int exploredPrefixLength,
        String searchTerminalStatus,
        int searchExploredStates,
        int engineCalls,
        long generatedTransformations,
        LostStep firstLoss,
        String detail
    ) {
        public CaseDiagnostic {
            id = requireText(id, "id");
            status = requireMember(status, CASE_STATUSES, "status");
            oracleStatus = requireText(oracleStatus, "oracleStatus");
            searchTerminalStatus = requireMember(
                searchTerminalStatus,
                TERMINAL_STATUSES,
                "searchTerminalStatus");
            detail = detail == null ? "" : detail;
            if (witnessStepCount < 0 || exploredPrefixLength < 0
                    || exploredPrefixLength > witnessStepCount
                    || searchExploredStates < 0 || engineCalls < 0
                    || generatedTransformations < 0) {
                throw new IllegalArgumentException(
                    "case diagnostic counters are outside their ranges");
            }
            if (WITNESS_PREFIX_LOST.equals(status) != (firstLoss != null)) {
                throw new IllegalArgumentException(
                    "first loss must exist exactly for WITNESS_PREFIX_LOST");
            }
        }

        static CaseDiagnostic notApplicable(
            String id,
            String status,
            String oracleStatus,
            SearchEvidence scalar
        ) {
            return new CaseDiagnostic(
                id,
                status,
                oracleStatus,
                0,
                0,
                "NOT_EVALUATED",
                scalar.exploredStates(),
                scalar.engineCalls(),
                scalar.generatedTransformations(),
                null,
                "no target-aware production witness is available for prefix diagnosis");
        }
    }

    public record LostStep(
        int index,
        String expressionBefore,
        String expressionAfter,
        String ruleId,
        String reason,
        SearchEvent event,
        String detail
    ) {
        public LostStep {
            if (index < 0) {
                throw new IllegalArgumentException(
                    "loss index must not be negative");
            }
            expressionBefore = requireText(
                expressionBefore, "expressionBefore");
            expressionAfter = requireText(expressionAfter, "expressionAfter");
            ruleId = requireText(ruleId, "ruleId");
            reason = requireMember(reason, LOSS_REASONS, "reason");
            detail = detail == null ? "" : detail;
        }
    }

    private static void validateCases(
        List<CaseDiagnostic> cases,
        Map<String, Integer> statusCounts,
        Map<String, Integer> firstLossCounts
    ) {
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("report cases must not be empty");
        }
        List<String> ids = cases.stream().map(CaseDiagnostic::id).toList();
        if (!ids.equals(ids.stream().sorted().toList())
                || new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException(
                "report cases must have unique canonical ordering");
        }
        Map<String, Integer> expectedStatuses = countValues(
            cases.stream().map(CaseDiagnostic::status).toList());
        Map<String, Integer> expectedLosses = countValues(cases.stream()
            .map(CaseDiagnostic::firstLoss)
            .filter(Objects::nonNull)
            .map(LostStep::reason)
            .toList());
        if (!expectedStatuses.equals(statusCounts)
                || !expectedLosses.equals(firstLossCounts)) {
            throw new IllegalArgumentException(
                "report summary differs from retained cases");
        }
    }

    private static Map<String, Integer> countValues(List<String> values) {
        Map<String, Integer> counts = new TreeMap<>();
        values.forEach(value -> counts.merge(value, 1, Integer::sum));
        return Map.copyOf(counts);
    }

    private static String reportHash(
        String corpusSha256,
        String atlasSha256,
        String inventoryRevision,
        List<CaseDiagnostic> cases,
        Map<String, Integer> statusCounts,
        Map<String, Integer> firstLossCounts
    ) {
        return sha256(renderReport(
            corpusSha256,
            atlasSha256,
            inventoryRevision,
            cases,
            statusCounts,
            firstLossCounts,
            null));
    }

    private static String renderReport(
        String corpusSha256,
        String atlasSha256,
        String inventoryRevision,
        List<CaseDiagnostic> cases,
        Map<String, Integer> statusCounts,
        Map<String, Integer> firstLossCounts,
        String contentHash
    ) {
        JsonWriter writer = new JsonWriter().beginObject();
        writer.property("schema", SCHEMA);
        writer.property("evidenceStatus", EVIDENCE_STATUS);
        writer.property("corpusSchema", HistoricalRediscoveryCorpus.SCHEMA);
        writer.property("corpusSha256", corpusSha256);
        writer.property("atlasSchema", HistoricalRediscoveryAtlas.SCHEMA);
        writer.property("atlasSha256", atlasSha256);
        writer.property("inventoryRevision", inventoryRevision);
        writer.property("searchPolicy", SEARCH_POLICY);
        writer.property("claimBoundary", CLAIM_BOUNDARY);
        writer.array("cases", array -> cases.forEach(value ->
            array.objectValue(object -> writeCase(object, value))));
        writer.object("summary", summary -> {
            summary.property("caseCount", cases.size());
            summary.object("statusCounts", counts ->
                writeCounts(counts, statusCounts));
            summary.object("firstLossCounts", counts ->
                writeCounts(counts, firstLossCounts));
        });
        if (contentHash != null) {
            writer.property("contentHash", contentHash);
        }
        return writer.endObject().toString();
    }

    private static void writeCase(JsonWriter writer, CaseDiagnostic value) {
        writer.property("id", value.id());
        writer.property("status", value.status());
        writer.property("oracleStatus", value.oracleStatus());
        writer.property("witnessStepCount", value.witnessStepCount());
        writer.property("exploredPrefixLength", value.exploredPrefixLength());
        writer.property("searchTerminalStatus", value.searchTerminalStatus());
        writer.property("searchExploredStates", value.searchExploredStates());
        writer.property("engineCalls", value.engineCalls());
        writer.property(
            "generatedTransformations", value.generatedTransformations());
        if (value.firstLoss() == null) {
            writer.nullProperty("firstLoss");
        } else {
            writer.object("firstLoss", object ->
                writeLoss(object, value.firstLoss()));
        }
        writer.property("detail", value.detail());
    }

    private static void writeLoss(JsonWriter writer, LostStep value) {
        writer.property("index", value.index());
        writer.property("expressionBefore", value.expressionBefore());
        writer.property("expressionAfter", value.expressionAfter());
        writer.property("ruleId", value.ruleId());
        writer.property("reason", value.reason());
        if (value.event() == null) {
            writer.nullProperty("event");
        } else {
            writer.object("event", object -> writeEvent(object, value.event()));
        }
        writer.property("detail", value.detail());
    }

    private static void writeEvent(JsonWriter writer, SearchEvent value) {
        writer.property("type", value.type().name());
        writer.property("sequence", value.sequence());
        writer.property("depth", value.depth());
        writer.property("score", value.score());
        writer.property("frontierSize", value.frontierSize());
        writer.property("visitedCount", value.visitedCount());
        writer.property("generatedCount", value.generatedCount());
        writer.property("pruningReason", value.pruningReason());
    }

    private static void writeCounts(
        JsonWriter writer,
        Map<String, Integer> counts
    ) {
        counts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> writer.property(
                entry.getKey(), entry.getValue()));
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static String requireMember(
        String value,
        Set<String> allowed,
        String label
    ) {
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(label + " is unsupported");
        }
        return value;
    }

    private static void requireRawSha256(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                label + " must be lowercase hexadecimal SHA-256");
        }
    }

    private static void requirePrefixedSha256(String value, String label) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                label + " must be prefixed SHA-256");
        }
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
}
