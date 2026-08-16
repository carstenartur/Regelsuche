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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compares target-blind scalar and structural-diversity production searches
 * against the same retained target-aware oracle witnesses.
 *
 * <p>Both policies receive the same frozen source, production inventory and
 * declared search ceilings. Actual consumed work remains separate evidence.</p>
 */
public final class HistoricalProductionSearchComparison {
    static final String SCHEMA =
        "regelsuche.production-search-comparison/v1";
    static final String EVIDENCE_STATUS =
        "EXECUTED_MATCHED_DECLARED_BUDGET_COMPARISON";
    static final String SCALAR_POLICY =
        "SCALAR_BEST_FIRST_TARGET_BLIND";
    static final String DIVERSITY_POLICY =
        "STRUCTURAL_DIVERSITY_TARGET_BLIND";
    static final String INFORMATION_BOUNDARY =
        "TARGET_BLIND_SEARCHES_ORACLE_POST_HOC_DIAGNOSTIC";
    static final String CLAIM_BOUNDARY =
        "Scalar best-first and structural-diversity searches receive the same "
            + "frozen source, production inventory and declared heuristic "
            + "budgets without a target. Oracle witnesses are used only after "
            + "both searches finish. Equal declared budgets do not imply equal "
            + "consumed work, and a retained prefix difference is not proof, "
            + "autonomous rediscovery, novelty or general search superiority.";
    static final String FILE_NAME =
        "production-search-comparison.json";

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final SymPyEquivalenceService equivalence =
        new SymPyEquivalenceService();

    Report run(
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
                value -> value));
        Map<String, HistoricalWitnessPruningDiagnostic.CaseDiagnostic>
            witnessById = witnessCases.stream()
                .collect(Collectors.toUnmodifiableMap(
                    HistoricalWitnessPruningDiagnostic.CaseDiagnostic::id,
                    value -> value));
        requireCaseMembership(corpus, atlasById.keySet(), witnessById.keySet());
        List<CaseComparison> cases = corpus.cases().stream()
            .sorted(Comparator.comparing(Case::id))
            .map(value -> compare(
                value,
                atlasById.get(value.id()),
                witnessById.get(value.id())))
            .toList();
        return Report.create(
            corpus,
            atlas,
            HistoricalWitnessPruningDiagnostic.SCHEMA,
            new HistoricalWitnessPruningDiagnostic().contentHash(
                corpus, atlas, witnessCases),
            cases);
    }

    Path write(Path directory, Report report) {
        Path output = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath().normalize().resolve(FILE_NAME);
        String json = Objects.requireNonNull(report, "report").toCanonicalJson();
        try {
            Files.createDirectories(output.getParent());
            AtomicJsonFile.writeUtf8(output, json);
            if (!json.equals(Files.readString(output, StandardCharsets.UTF_8))) {
                throw new IllegalStateException(
                    "written production comparison differs from report");
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
        List<String> witness = atlasCase.production().oracle().witnessExpressions();
        if (witness.size() != witnessCase.witnessStepCount()) {
            throw new IllegalArgumentException(
                "oracle witness differs from witness diagnostic: "
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
        SearchState match = findMatch(
            benchmarkCase,
            format(benchmarkCase.target()),
            diversityStates);
        requireDiversityBinding(
            diversityRetained, diversityStates, match, counting);

        int witnessSteps = witness.size();
        SearchComparisonEvidence scalar = new SearchComparisonEvidence(
            scalarRetained.reached(),
            witnessCase.exploredPrefixLength(),
            scalarRetained.exploredStates(),
            scalarRetained.engineCalls(),
            scalarRetained.generatedTransformations(),
            witnessCase.searchTerminalStatus());
        SearchComparisonEvidence diversity = new SearchComparisonEvidence(
            match != null,
            witnessSteps == 0
                ? 0
                : exploredPrefixLength(witness, diversityStates),
            diversityStates.size(),
            counting.calls(),
            counting.generated(),
            diversityStates.isEmpty()
                ? "NO_STATES"
                : "COMPLETED_BOUNDED_SEARCH");
        return new CaseComparison(
            benchmarkCase.id(),
            classify(witnessSteps, scalar, diversity),
            witnessSteps,
            DeclaredBudget.from(benchmarkCase),
            scalar,
            diversity,
            diversity.exploredPrefixLength() - scalar.exploredPrefixLength());
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

    private int exploredPrefixLength(
        List<String> witness,
        List<SearchState> states
    ) {
        Set<String> explored = states.stream()
            .map(SearchState::expression)
            .map(this::expressionKey)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        int prefix = 0;
        for (String expression : witness) {
            if (!explored.contains(expressionKey(expression))) {
                break;
            }
            prefix++;
        }
        return prefix;
    }

    private SearchState findMatch(
        Case benchmarkCase,
        String target,
        List<SearchState> states
    ) {
        return states.stream()
            .filter(state -> matches(
                benchmarkCase, state.expression(), target))
            .min(Comparator
                .comparingInt(SearchState::depth)
                .thenComparing(SearchState::expression)
                .thenComparing(state ->
                    String.join("->", state.appliedRuleIds())))
            .orElse(null);
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
        boolean witnessReached =
            HistoricalWitnessPruningDiagnostic.SCALAR_ALREADY_FOUND
                .equals(witness.status());
        if (retained.reached() != witnessReached
                || retained.exploredStates() != witness.searchExploredStates()
                || retained.engineCalls() != witness.engineCalls()
                || retained.generatedTransformations()
                    != witness.generatedTransformations()) {
            throw new IllegalArgumentException(
                "witness diagnostic does not bind scalar evidence");
        }
    }

    private static void requireDiversityBinding(
        SearchEvidence retained,
        List<SearchState> states,
        SearchState match,
        CountingEngine counting
    ) {
        String terminal = states.isEmpty()
            ? "NO_STATES"
            : "COMPLETED_BOUNDED_SEARCH";
        List<String> path = match == null ? List.of() : match.path();
        List<String> ruleIds = match == null
            ? List.of()
            : match.appliedRuleIds();
        if (retained.reached() != (match != null)
                || !retained.terminalStatus().equals(terminal)
                || retained.exploredStates() != states.size()
                || retained.engineCalls() != counting.calls()
                || retained.generatedTransformations() != counting.generated()
                || !retained.path().equals(path)
                || !retained.ruleIds().equals(ruleIds)) {
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
        Set<String> corpusIds = corpus.cases().stream()
            .map(Case::id)
            .collect(Collectors.toSet());
        Set<String> atlasIds = atlas.cases().stream()
            .map(value -> value.benchmarkCase().id())
            .collect(Collectors.toSet());
        if (!corpus.schema().equals(atlas.corpusSchema())
                || !corpus.contentSha256().equals(atlas.corpusSha256())
                || !corpus.inventoryRevision().equals(atlas.inventoryRevision())
                || !corpus.claimBoundary().equals(atlas.claimBoundary())
                || corpus.cases().size() != atlas.cases().size()
                || !corpusIds.equals(atlasIds)) {
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

    enum ComparisonStatus {
        NOT_APPLICABLE_NO_PRODUCTION_WITNESS,
        SCALAR_ALREADY_FOUND,
        DIVERSITY_RECOVERS_COMPLETE_WITNESS,
        DIVERSITY_FINDS_RELATION_VIA_ALTERNATE_PATH,
        DIVERSITY_EXTENDS_SCALAR_PREFIX,
        DIVERSITY_MATCHES_SCALAR_PREFIX,
        DIVERSITY_RETAINS_SHORTER_PREFIX
    }

    record DeclaredBudget(
        int maxDepth,
        int maxVisitedStates,
        int maxCandidatesPerState,
        int maxExpandingSteps,
        int beamWidth
    ) {
        DeclaredBudget {
            if (maxDepth < 0
                    || maxVisitedStates < 1
                    || maxCandidatesPerState < 1
                    || maxExpandingSteps < 0
                    || beamWidth < 1) {
                throw new IllegalArgumentException(
                    "declared budget is outside its finite range");
            }
        }

        static DeclaredBudget from(Case value) {
            return new DeclaredBudget(
                value.searchMaxDepth(),
                value.searchMaxVisitedStates(),
                value.maxCandidatesPerState(),
                value.maxExpandingSteps(),
                value.beamWidth());
        }
    }

    record SearchComparisonEvidence(
        boolean reachedRelation,
        int exploredPrefixLength,
        int exploredStates,
        int engineCalls,
        long generatedTransformations,
        String terminalStatus
    ) {
        SearchComparisonEvidence {
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

    record CaseComparison(
        String id,
        ComparisonStatus status,
        int oracleWitnessStepCount,
        DeclaredBudget declaredBudget,
        SearchComparisonEvidence scalar,
        SearchComparisonEvidence diversity,
        int prefixDelta
    ) {
        CaseComparison {
            id = requireText(id, "id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(declaredBudget, "declaredBudget");
            Objects.requireNonNull(scalar, "scalar");
            Objects.requireNonNull(diversity, "diversity");
            if (oracleWitnessStepCount < 0
                    || scalar.exploredPrefixLength()
                        > oracleWitnessStepCount
                    || diversity.exploredPrefixLength()
                        > oracleWitnessStepCount
                    || prefixDelta != diversity.exploredPrefixLength()
                        - scalar.exploredPrefixLength()
                    || status != classify(
                        oracleWitnessStepCount, scalar, diversity)) {
                throw new IllegalArgumentException(
                    "case comparison evidence is inconsistent");
            }
        }
    }

    record Summary(
        int caseCount,
        Map<ComparisonStatus, Integer> statusCounts,
        int comparableScalarMissCount,
        int diversityRecoveredCompleteWitnessCount,
        int diversityExtendedPrefixCount,
        int diversityReachedRelationCount
    ) {
        Summary {
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

        static Summary derive(List<CaseComparison> cases) {
            Map<ComparisonStatus, Integer> counts =
                new EnumMap<>(ComparisonStatus.class);
            cases.forEach(value ->
                counts.merge(value.status(), 1, Integer::sum));
            return new Summary(
                cases.size(),
                counts,
                (int) cases.stream()
                    .filter(value -> value.oracleWitnessStepCount() > 0)
                    .filter(value -> !value.scalar().reachedRelation())
                    .count(),
                counts.getOrDefault(
                    ComparisonStatus.DIVERSITY_RECOVERS_COMPLETE_WITNESS,
                    0),
                (int) cases.stream()
                    .filter(value -> value.prefixDelta() > 0)
                    .count(),
                (int) cases.stream()
                    .filter(value -> value.diversity().reachedRelation())
                    .count());
        }
    }

    record Report(
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
        Report {
            if (!SCHEMA.equals(schema)
                    || !EVIDENCE_STATUS.equals(evidenceStatus)
                    || !HistoricalRediscoveryCorpus.SCHEMA.equals(corpusSchema)
                    || !HistoricalRediscoveryAtlas.SCHEMA.equals(atlasSchema)
                    || !HistoricalWitnessPruningDiagnostic.SCHEMA
                        .equals(witnessDiagnosticSchema)
                    || !INFORMATION_BOUNDARY.equals(informationBoundary)
                    || !CLAIM_BOUNDARY.equals(claimBoundary)) {
                throw new IllegalArgumentException(
                    "production comparison identity differs");
            }
            corpusSha256 = requireRawSha256(
                corpusSha256, "corpusSha256");
            atlasSha256 = requirePrefixedSha256(
                atlasSha256, "atlasSha256");
            witnessDiagnosticSha256 = requirePrefixedSha256(
                witnessDiagnosticSha256, "witnessDiagnosticSha256");
            inventoryRevision = requireText(
                inventoryRevision, "inventoryRevision");
            cases = Objects.requireNonNull(cases, "cases").stream()
                .sorted(Comparator.comparing(CaseComparison::id))
                .toList();
            if (cases.isEmpty()
                    || new LinkedHashSet<>(cases.stream()
                        .map(CaseComparison::id).toList()).size()
                        != cases.size()
                    || !Summary.derive(cases).equals(summary)) {
                throw new IllegalArgumentException(
                    "production comparison case balance differs");
            }
            contentHash = requirePrefixedSha256(
                contentHash, "contentHash");
            if (!reportHash(
                    corpusSchema,
                    corpusSha256,
                    atlasSchema,
                    atlasSha256,
                    witnessDiagnosticSchema,
                    witnessDiagnosticSha256,
                    inventoryRevision,
                    cases,
                    summary).equals(contentHash)) {
                throw new IllegalArgumentException(
                    "production comparison contentHash mismatch");
            }
        }

        static Report create(
            Corpus corpus,
            AtlasReport atlas,
            String witnessSchema,
            String witnessSha256,
            List<CaseComparison> cases
        ) {
            List<CaseComparison> retained = cases.stream()
                .sorted(Comparator.comparing(CaseComparison::id))
                .toList();
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

        String toCanonicalJson() {
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
        writer.property("scalarPolicy", SCALAR_POLICY);
        writer.property("diversityPolicy", DIVERSITY_POLICY);
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
            "oracleWitnessStepCount", value.oracleWitnessStepCount());
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
        writer.object("scalar", object -> writeSearch(object, value.scalar()));
        writer.object(
            "diversity", object -> writeSearch(object, value.diversity()));
        writer.property("prefixDelta", value.prefixDelta());
    }

    private static void writeSearch(
        JsonWriter writer,
        SearchComparisonEvidence value
    ) {
        writer.property("reachedRelation", value.reachedRelation());
        writer.property(
            "exploredPrefixLength", value.exploredPrefixLength());
        writer.property("exploredStates", value.exploredStates());
        writer.property("engineCalls", value.engineCalls());
        writer.property(
            "generatedTransformations", value.generatedTransformations());
        writer.property("terminalStatus", value.terminalStatus());
    }

    private static void writeSummary(JsonWriter writer, Summary value) {
        writer.property("caseCount", value.caseCount());
        writer.object("statusCounts", object -> value.statusCounts().entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> object.property(
                entry.getKey().name(), entry.getValue())));
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

        int calls() {
            return calls;
        }

        long generated() {
            return generated;
        }
    }
}
