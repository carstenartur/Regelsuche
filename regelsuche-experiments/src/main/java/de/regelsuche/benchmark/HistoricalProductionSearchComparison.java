package de.regelsuche.benchmark;

import de.regelsuche.benchmark.DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.AtlasReport;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.CaseResult;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.SearchEvidence;
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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compares target-blind scalar and structural-diversity production searches
 * against the same retained target-aware oracle witnesses.
 *
 * <p>Both policies receive the same frozen source, production inventory and
 * declared {@link SearchHeuristic} ceilings. Their actual consumed work remains
 * separate evidence and is never assumed equal merely because the ceilings are
 * equal.</p>
 */
public final class HistoricalProductionSearchComparison {
    public static final String SCHEMA =
        "regelsuche.production-search-comparison/v1";
    public static final String EVIDENCE_STATUS =
        "EXECUTED_MATCHED_DECLARED_BUDGET_COMPARISON";
    public static final String INFORMATION_BOUNDARY =
        "TARGET_BLIND_SEARCHES_ORACLE_POST_HOC_DIAGNOSTIC";
    public static final String CLAIM_BOUNDARY =
        "Scalar best-first and structural-diversity searches receive the same "
            + "frozen source, production inventory and declared heuristic "
            + "budgets without a target. Oracle witnesses are used only after "
            + "both searches finish. Equal declared budgets do not imply equal "
            + "consumed work, and a retained prefix difference is not proof, "
            + "autonomous rediscovery, novelty or general search superiority.";
    public static final String FILE_NAME =
        "production-search-comparison.json";

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final SymPyEquivalenceService equivalence =
        new SymPyEquivalenceService();

    /** Executes the comparison from the already retained in-memory evidence. */
    public Report run(
        Corpus corpus,
        AtlasReport atlas,
        List<HistoricalWitnessPruningDiagnostic.CaseDiagnostic> witnessCases
    ) {
        Objects.requireNonNull(corpus, "corpus");
        Objects.requireNonNull(atlas, "atlas");
        requireAtlasBinding(corpus, atlas);
        Map<String, CaseResult> atlasById = atlas.cases().stream()
            .collect(Collectors.toUnmodifiableMap(
                value -> value.benchmarkCase().id(),
                Function.identity()));
        Map<String, HistoricalWitnessPruningDiagnostic.CaseDiagnostic>
            witnessById = witnessCases.stream()
                .collect(Collectors.toUnmodifiableMap(
                    HistoricalWitnessPruningDiagnostic.CaseDiagnostic::id,
                    Function.identity()));
        requireCaseMembership(corpus, atlasById.keySet(), witnessById.keySet());

        List<CaseComparison> comparisons = corpus.cases().stream()
            .sorted(Comparator.comparing(Case::id))
            .map(benchmarkCase -> compare(
                benchmarkCase,
                atlasById.get(benchmarkCase.id()),
                witnessById.get(benchmarkCase.id())))
            .toList();
        return Report.create(
            corpus,
            atlas,
            HistoricalWitnessPruningDiagnostic.SCHEMA,
            new HistoricalWitnessPruningDiagnostic().contentHash(
                corpus,
                atlas,
                witnessCases),
            comparisons);
    }

    /** Writes and rereads the canonical report bytes. */
    public Path write(Path directory, Report report) {
        Path outputDirectory = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath().normalize();
        Report safeReport = Objects.requireNonNull(report, "report");
        Path output = outputDirectory.resolve(FILE_NAME);
        try {
            Files.createDirectories(outputDirectory);
            AtomicJsonFile.writeUtf8(output, safeReport.toCanonicalJson());
            String retained = Files.readString(output, StandardCharsets.UTF_8);
            if (!safeReport.toCanonicalJson().equals(retained)) {
                throw new IllegalStateException(
                    "written production search comparison differs from report");
            }
            return output;
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write production search comparison", exception);
        }
    }

    private CaseComparison compare(
        Case benchmarkCase,
        CaseResult atlasCase,
        HistoricalWitnessPruningDiagnostic.CaseDiagnostic witnessCase
    ) {
        SearchEvidence scalarRetained = atlasCase.production().scalar();
        SearchEvidence diversityRetained = atlasCase.production().diversity();
        List<String> witnessExpressions =
            atlasCase.production().oracle().witnessExpressions();
        if (witnessExpressions.size() != witnessCase.witnessStepCount()) {
            throw new IllegalArgumentException(
                "oracle witness length differs from witness diagnostic: "
                    + benchmarkCase.id());
        }
        requireScalarBinding(scalarRetained, witnessCase);

        CountingEngine counting = new CountingEngine(
            productionEngine(benchmarkCase));
        List<SearchState> diversityStates =
            new StructuralDiversitySearchStrategy().search(searchProblem(
                benchmarkCase,
                format(benchmarkCase.source()),
                counting));
        Optional<SearchState> diversityMatch = findMatch(
            benchmarkCase,
            format(benchmarkCase.target()),
            diversityStates);
        requireDiversityBinding(
            diversityRetained,
            diversityStates,
            diversityMatch,
            counting);

        int witnessSteps = witnessExpressions.size();
        int diversityPrefix = witnessSteps == 0
            ? 0
            : exploredPrefixLength(witnessExpressions, diversityStates);
        SearchComparisonEvidence scalar = new SearchComparisonEvidence(
            SearchPolicy.SCALAR_BEST_FIRST_TARGET_BLIND,
            scalarRetained.reached(),
            witnessCase.exploredPrefixLength(),
            scalarRetained.exploredStates(),
            scalarRetained.engineCalls(),
            scalarRetained.generatedTransformations(),
            witnessCase.searchTerminalStatus());
        SearchComparisonEvidence diversity = new SearchComparisonEvidence(
            SearchPolicy.STRUCTURAL_DIVERSITY_TARGET_BLIND,
            diversityMatch.isPresent(),
            diversityPrefix,
            diversityStates.size(),
            counting.calls(),
            counting.generated(),
            diversityStates.isEmpty()
                ? "NO_STATES"
                : "COMPLETED_BOUNDED_SEARCH");
        ComparisonStatus status = classify(witnessSteps, scalar, diversity);
        return new CaseComparison(
            benchmarkCase.id(),
            status,
            witnessSteps,
            DeclaredBudget.from(benchmarkCase),
            scalar,
            diversity,
            diversityPrefix - scalar.exploredPrefixLength(),
            detail(status));
    }

    private static ComparisonStatus classify(
        int witnessSteps,
        SearchComparisonEvidence scalar,
        SearchComparisonEvidence diversity
    ) {
        if (witnessSteps == 0) {
            return ComparisonStatus.NOT_APPLICABLE_NO_PRODUCTION_WITNESS;
        }
        if (scalar.reachedRelation()) {
            return ComparisonStatus.SCALAR_ALREADY_FOUND;
        }
        if (diversity.reachedRelation()) {
            return diversity.exploredPrefixLength() == witnessSteps
                ? ComparisonStatus.DIVERSITY_RECOVERS_COMPLETE_WITNESS
                : ComparisonStatus.DIVERSITY_FINDS_RELATION_VIA_ALTERNATE_PATH;
        }
        if (diversity.exploredPrefixLength()
                > scalar.exploredPrefixLength()) {
            return ComparisonStatus.DIVERSITY_EXTENDS_SCALAR_PREFIX;
        }
        if (diversity.exploredPrefixLength()
                == scalar.exploredPrefixLength()) {
            return ComparisonStatus.DIVERSITY_MATCHES_SCALAR_PREFIX;
        }
        return ComparisonStatus.DIVERSITY_RETAINS_SHORTER_PREFIX;
    }

    private static String detail(ComparisonStatus status) {
        return switch (status) {
            case NOT_APPLICABLE_NO_PRODUCTION_WITNESS ->
                "the production oracle retained no witness for prefix comparison";
            case SCALAR_ALREADY_FOUND ->
                "the target-blind scalar search already reached the relation";
            case DIVERSITY_RECOVERS_COMPLETE_WITNESS ->
                "structural diversity retained every state of the oracle witness";
            case DIVERSITY_FINDS_RELATION_VIA_ALTERNATE_PATH ->
                "structural diversity reached the relation without retaining the "
                    + "complete selected oracle witness";
            case DIVERSITY_EXTENDS_SCALAR_PREFIX ->
                "structural diversity retained a longer oracle witness prefix";
            case DIVERSITY_MATCHES_SCALAR_PREFIX ->
                "both target-blind policies retained the same oracle prefix length";
            case DIVERSITY_RETAINS_SHORTER_PREFIX ->
                "structural diversity retained a shorter selected oracle prefix";
        };
    }

    private int exploredPrefixLength(
        List<String> witnessExpressions,
        List<SearchState> states
    ) {
        Set<String> explored = states.stream()
            .map(SearchState::expression)
            .map(this::expressionKey)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        int prefix = 0;
        for (String expression : witnessExpressions) {
            if (!explored.contains(expressionKey(expression))) {
                break;
            }
            prefix++;
        }
        return prefix;
    }

    private Optional<SearchState> findMatch(
        Case benchmarkCase,
        String target,
        List<SearchState> states
    ) {
        return states.stream()
            .filter(state -> matches(
                benchmarkCase,
                state.expression(),
                target))
            .min(Comparator
                .comparingInt(SearchState::depth)
                .thenComparing(SearchState::expression)
                .thenComparing(state ->
                    String.join("->", state.appliedRuleIds())));
    }

    private boolean matches(
        Case benchmarkCase,
        String expression,
        String target
    ) {
        if (benchmarkCase.targetRelation() == TargetRelation.SYNTAX_EXACT) {
            try {
                return format(expression).equals(target);
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
        return equivalence.areEquivalent(expression, target);
    }

    private static void requireScalarBinding(
        SearchEvidence retained,
        HistoricalWitnessPruningDiagnostic.CaseDiagnostic witness
    ) {
        List<Object> atlasWork = List.of(
            retained.reached(),
            retained.exploredStates(),
            retained.engineCalls(),
            retained.generatedTransformations());
        List<Object> witnessWork = List.of(
            HistoricalWitnessPruningDiagnostic.SCALAR_ALREADY_FOUND
                .equals(witness.status()),
            witness.searchExploredStates(),
            witness.engineCalls(),
            witness.generatedTransformations());
        if (!atlasWork.equals(witnessWork)) {
            throw new IllegalArgumentException(
                "witness diagnostic does not bind retained scalar evidence");
        }
    }

    private static void requireDiversityBinding(
        SearchEvidence retained,
        List<SearchState> states,
        Optional<SearchState> match,
        CountingEngine counting
    ) {
        String terminal = states.isEmpty()
            ? "NO_STATES"
            : "COMPLETED_BOUNDED_SEARCH";
        List<String> path = match.map(SearchState::path).orElse(List.of());
        List<String> ruleIds = match
            .map(SearchState::appliedRuleIds)
            .orElse(List.of());
        List<Object> expected = List.of(
            retained.reached(),
            retained.terminalStatus(),
            retained.exploredStates(),
            retained.engineCalls(),
            retained.generatedTransformations(),
            retained.path(),
            retained.ruleIds());
        List<Object> actual = List.of(
            match.isPresent(),
            terminal,
            states.size(),
            counting.calls(),
            counting.generated(),
            path,
            ruleIds);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                "diversity rerun differs from retained atlas evidence");
        }
    }

    private SearchProblem searchProblem(
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

    private static TransformationEngine productionEngine(Case benchmarkCase) {
        return AstRewriteTransformationEngines.production(
            AstRewriteTransformationEngine.defaultRules(),
            128,
            Math.max(200, benchmarkCase.maxCandidatesPerState() * 2));
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

    private static void requireAtlasBinding(Corpus corpus, AtlasReport atlas) {
        List<Object> expected = List.of(
            corpus.schema(),
            corpus.contentSha256(),
            corpus.inventoryRevision(),
            corpus.claimBoundary(),
            corpus.cases().stream().map(Case::id).sorted().toList());
        List<Object> actual = List.of(
            atlas.corpusSchema(),
            atlas.corpusSha256(),
            atlas.inventoryRevision(),
            atlas.claimBoundary(),
            atlas.cases().stream()
                .map(value -> value.benchmarkCase().id())
                .sorted()
                .toList());
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                "production comparison atlas does not bind the corpus");
        }
    }

    private static void requireCaseMembership(
        Corpus corpus,
        Set<String> atlasIds,
        Set<String> witnessIds
    ) {
        Set<String> corpusIds = corpus.cases().stream()
            .map(Case::id)
            .collect(Collectors.toSet());
        if (!corpusIds.equals(atlasIds) || !corpusIds.equals(witnessIds)) {
            throw new IllegalArgumentException(
                "production comparison case membership differs");
        }
    }

    public enum ComparisonStatus {
        NOT_APPLICABLE_NO_PRODUCTION_WITNESS,
        SCALAR_ALREADY_FOUND,
        DIVERSITY_RECOVERS_COMPLETE_WITNESS,
        DIVERSITY_FINDS_RELATION_VIA_ALTERNATE_PATH,
        DIVERSITY_EXTENDS_SCALAR_PREFIX,
        DIVERSITY_MATCHES_SCALAR_PREFIX,
        DIVERSITY_RETAINS_SHORTER_PREFIX
    }

    public enum SearchPolicy {
        SCALAR_BEST_FIRST_TARGET_BLIND,
        STRUCTURAL_DIVERSITY_TARGET_BLIND
    }

    public record DeclaredBudget(
        int maxDepth,
        int maxVisitedStates,
        int maxCandidatesPerState,
        int maxExpandingSteps,
        int beamWidth
    ) {
        public DeclaredBudget {
            if (maxDepth < 0
                    || maxVisitedStates < 1
                    || maxCandidatesPerState < 1
                    || maxExpandingSteps < 0
                    || beamWidth < 1) {
                throw new IllegalArgumentException(
                    "declared comparison budget is outside its finite range");
            }
        }

        private static DeclaredBudget from(Case benchmarkCase) {
            return new DeclaredBudget(
                benchmarkCase.searchMaxDepth(),
                benchmarkCase.searchMaxVisitedStates(),
                benchmarkCase.maxCandidatesPerState(),
                benchmarkCase.maxExpandingSteps(),
                benchmarkCase.beamWidth());
        }
    }

    public record SearchComparisonEvidence(
        SearchPolicy policy,
        boolean reachedRelation,
        int exploredPrefixLength,
        int exploredStates,
        int engineCalls,
        long generatedTransformations,
        String terminalStatus
    ) {
        public SearchComparisonEvidence {
            Objects.requireNonNull(policy, "policy");
            terminalStatus = requireText(terminalStatus, "terminalStatus");
            if (exploredPrefixLength < 0
                    || exploredStates < 0
                    || engineCalls < 0
                    || generatedTransformations < 0) {
                throw new IllegalArgumentException(
                    "search comparison counters must not be negative");
            }
        }
    }

    public record CaseComparison(
        String id,
        ComparisonStatus status,
        int oracleWitnessStepCount,
        DeclaredBudget declaredBudget,
        SearchComparisonEvidence scalar,
        SearchComparisonEvidence diversity,
        int prefixDelta,
        String detail
    ) {
        public CaseComparison {
            id = requireText(id, "id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(declaredBudget, "declaredBudget");
            Objects.requireNonNull(scalar, "scalar");
            Objects.requireNonNull(diversity, "diversity");
            detail = detail == null ? "" : detail;
            if (oracleWitnessStepCount < 0
                    || scalar.exploredPrefixLength()
                        > oracleWitnessStepCount
                    || diversity.exploredPrefixLength()
                        > oracleWitnessStepCount
                    || prefixDelta != diversity.exploredPrefixLength()
                        - scalar.exploredPrefixLength()) {
                throw new IllegalArgumentException(
                    "case comparison prefix accounting is inconsistent");
            }
        }
    }

    public record Summary(
        int caseCount,
        Map<ComparisonStatus, Integer> statusCounts,
        int comparableScalarMissCount,
        int diversityRecoveredCompleteWitnessCount,
        int diversityExtendedPrefixCount,
        int diversityReachedRelationCount
    ) {
        public Summary {
            statusCounts = Map.copyOf(
                Objects.requireNonNull(statusCounts, "statusCounts"));
            if (caseCount < 1
                    || statusCounts.values().stream()
                        .mapToInt(Integer::intValue).sum() != caseCount
                    || comparableScalarMissCount < 0
                    || diversityRecoveredCompleteWitnessCount < 0
                    || diversityExtendedPrefixCount < 0
                    || diversityReachedRelationCount < 0) {
                throw new IllegalArgumentException(
                    "production comparison summary is inconsistent");
            }
        }

        private static Summary derive(List<CaseComparison> cases) {
            Map<ComparisonStatus, Integer> counts =
                new EnumMap<>(ComparisonStatus.class);
            cases.forEach(value ->
                counts.merge(value.status(), 1, Integer::sum));
            int comparable = (int) cases.stream()
                .filter(value -> value.oracleWitnessStepCount() > 0)
                .filter(value -> !value.scalar().reachedRelation())
                .count();
            int complete = counts.getOrDefault(
                ComparisonStatus.DIVERSITY_RECOVERS_COMPLETE_WITNESS,
                0);
            int extended = (int) cases.stream()
                .filter(value -> value.prefixDelta() > 0)
                .count();
            int reached = (int) cases.stream()
                .filter(value -> value.diversity().reachedRelation())
                .count();
            return new Summary(
                cases.size(),
                counts,
                comparable,
                complete,
                extended,
                reached);
        }
    }

    public record Report(
        String schema,
        String evidenceStatus,
        String corpusSchema,
        String corpusSha256,
        String atlasSchema,
        String atlasSha256,
        String witnessDiagnosticSchema,
        String witnessDiagnosticSha256,
        String inventoryRevision,
        String informationBoundary,
        String claimBoundary,
        List<CaseComparison> cases,
        Summary summary,
        String contentHash
    ) {
        public Report {
            if (!SCHEMA.equals(schema)
                    || !EVIDENCE_STATUS.equals(evidenceStatus)
                    || !HistoricalRediscoveryCorpus.SCHEMA
                        .equals(corpusSchema)
                    || !HistoricalRediscoveryAtlas.SCHEMA
                        .equals(atlasSchema)
                    || !HistoricalWitnessPruningDiagnostic.SCHEMA
                        .equals(witnessDiagnosticSchema)
                    || !INFORMATION_BOUNDARY.equals(informationBoundary)
                    || !CLAIM_BOUNDARY.equals(claimBoundary)) {
                throw new IllegalArgumentException(
                    "production comparison contract identity differs");
            }
            corpusSha256 = requireRawSha256(
                corpusSha256,
                "corpusSha256");
            atlasSha256 = requirePrefixedSha256(
                atlasSha256,
                "atlasSha256");
            witnessDiagnosticSha256 = requirePrefixedSha256(
                witnessDiagnosticSha256,
                "witnessDiagnosticSha256");
            inventoryRevision = requireText(
                inventoryRevision,
                "inventoryRevision");
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
            Objects.requireNonNull(summary, "summary");
            contentHash = requirePrefixedSha256(
                contentHash,
                "contentHash");
            String expected = reportHash(
                corpusSchema,
                corpusSha256,
                atlasSchema,
                atlasSha256,
                witnessDiagnosticSchema,
                witnessDiagnosticSha256,
                inventoryRevision,
                cases,
                summary);
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "production comparison contentHash mismatch");
            }
        }

        private static Report create(
            Corpus corpus,
            AtlasReport atlas,
            String witnessSchema,
            String witnessSha256,
            List<CaseComparison> cases
        ) {
            List<CaseComparison> retained = List.copyOf(cases);
            Summary summary = Summary.derive(retained);
            String atlasSha256 = sha256(atlas.toJson());
            String hash = reportHash(
                corpus.schema(),
                corpus.contentSha256(),
                atlas.schema(),
                atlasSha256,
                witnessSchema,
                witnessSha256,
                corpus.inventoryRevision(),
                retained,
                summary);
            return new Report(
                SCHEMA,
                EVIDENCE_STATUS,
                corpus.schema(),
                corpus.contentSha256(),
                atlas.schema(),
                atlasSha256,
                witnessSchema,
                witnessSha256,
                corpus.inventoryRevision(),
                INFORMATION_BOUNDARY,
                CLAIM_BOUNDARY,
                retained,
                summary,
                hash);
        }

        public String toCanonicalJson() {
            return renderReport(
                corpusSchema,
                corpusSha256,
                atlasSchema,
                atlasSha256,
                witnessDiagnosticSchema,
                witnessDiagnosticSha256,
                inventoryRevision,
                cases,
                summary,
                contentHash);
        }
    }

    private static String reportHash(
        String corpusSchema,
        String corpusSha256,
        String atlasSchema,
        String atlasSha256,
        String witnessSchema,
        String witnessSha256,
        String inventoryRevision,
        List<CaseComparison> cases,
        Summary summary
    ) {
        return sha256(renderReport(
            corpusSchema,
            corpusSha256,
            atlasSchema,
            atlasSha256,
            witnessSchema,
            witnessSha256,
            inventoryRevision,
            cases,
            summary,
            null));
    }

    private static String renderReport(
        String corpusSchema,
        String corpusSha256,
        String atlasSchema,
        String atlasSha256,
        String witnessSchema,
        String witnessSha256,
        String inventoryRevision,
        List<CaseComparison> cases,
        Summary summary,
        String contentHash
    ) {
        JsonWriter writer = new JsonWriter().beginObject();
        writer.property("schema", SCHEMA);
        writer.property("evidenceStatus", EVIDENCE_STATUS);
        writer.property("corpusSchema", corpusSchema);
        writer.property("corpusSha256", corpusSha256);
        writer.property("atlasSchema", atlasSchema);
        writer.property("atlasSha256", atlasSha256);
        writer.property("witnessDiagnosticSchema", witnessSchema);
        writer.property("witnessDiagnosticSha256", witnessSha256);
        writer.property("inventoryRevision", inventoryRevision);
        writer.property("informationBoundary", INFORMATION_BOUNDARY);
        writer.property("claimBoundary", CLAIM_BOUNDARY);
        writer.array("cases", array -> cases.forEach(value ->
            array.objectValue(object -> writeCase(object, value))));
        writer.object("summary", object -> writeSummary(object, summary));
        if (contentHash != null) {
            writer.property("contentHash", contentHash);
        }
        return writer.endObject().toString();
    }

    private static void writeCase(JsonWriter writer, CaseComparison value) {
        writer.property("id", value.id());
        writer.property("status", value.status().name());
        writer.property(
            "oracleWitnessStepCount",
            value.oracleWitnessStepCount());
        writer.object("declaredBudget", object -> {
            object.property("maxDepth", value.declaredBudget().maxDepth());
            object.property(
                "maxVisitedStates",
                value.declaredBudget().maxVisitedStates());
            object.property(
                "maxCandidatesPerState",
                value.declaredBudget().maxCandidatesPerState());
            object.property(
                "maxExpandingSteps",
                value.declaredBudget().maxExpandingSteps());
            object.property("beamWidth", value.declaredBudget().beamWidth());
        });
        writer.object("scalar", object ->
            writeSearch(object, value.scalar()));
        writer.object("diversity", object ->
            writeSearch(object, value.diversity()));
        writer.property("prefixDelta", value.prefixDelta());
        writer.property("detail", value.detail());
    }

    private static void writeSearch(
        JsonWriter writer,
        SearchComparisonEvidence value
    ) {
        writer.property("policy", value.policy().name());
        writer.property("reachedRelation", value.reachedRelation());
        writer.property(
            "exploredPrefixLength", value.exploredPrefixLength());
        writer.property("exploredStates", value.exploredStates());
        writer.property("engineCalls", value.engineCalls());
        writer.property(
            "generatedTransformations",
            value.generatedTransformations());
        writer.property("terminalStatus", value.terminalStatus());
    }

    private static void writeSummary(JsonWriter writer, Summary value) {
        writer.property("caseCount", value.caseCount());
        writer.object("statusCounts", object -> value.statusCounts().entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> object.property(
                entry.getKey().name(),
                entry.getValue())));
        writer.property(
            "comparableScalarMissCount",
            value.comparableScalarMissCount());
        writer.property(
            "diversityRecoveredCompleteWitnessCount",
            value.diversityRecoveredCompleteWitnessCount());
        writer.property(
            "diversityExtendedPrefixCount",
            value.diversityExtendedPrefixCount());
        writer.property(
            "diversityReachedRelationCount",
            value.diversityReachedRelationCount());
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static String requireRawSha256(String value, String label) {
        String text = requireText(value, label);
        if (!text.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                label + " must be lowercase hexadecimal SHA-256");
        }
        return text;
    }

    private static String requirePrefixedSha256(
        String value,
        String label
    ) {
        String text = requireText(value, label);
        if (!text.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                label + " must be prefixed SHA-256");
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

    private static final class CountingEngine
            implements TransformationEngine {
        private final TransformationEngine delegate;
        private int calls;
        private long generated;

        private CountingEngine(TransformationEngine delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
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
