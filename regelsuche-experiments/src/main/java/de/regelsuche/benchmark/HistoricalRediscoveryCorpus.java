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
import de.regelsuche.json.JsonReader;
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
import java.io.InputStream;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Strict loader for the frozen historical rediscovery diagnostic corpus. */
public final class HistoricalRediscoveryCorpus {
    public static final String RESOURCE =
        "/de/regelsuche/benchmark/historical-rediscovery-corpus.json";
    public static final String SCHEMA =
        "regelsuche.historical-rediscovery-corpus/v1";

    private static final Set<String> ROOT_KEYS = Set.of(
        "schema",
        "evidenceStatus",
        "inventoryRevision",
        "claimBoundary",
        "cases"
    );
    private static final Set<String> CASE_KEYS = Set.of(
        "id",
        "family",
        "source",
        "target",
        "relation",
        "role",
        "diagnosticPurpose",
        "provenance",
        "targetRelation",
        "oracleMaxDepth",
        "oracleMaxVisitedStates",
        "searchMaxDepth",
        "searchMaxVisitedStates",
        "maxCandidatesPerState",
        "maxExpandingSteps",
        "beamWidth"
    );

    private HistoricalRediscoveryCorpus() {
    }

    public static Corpus load() {
        try (InputStream input = HistoricalRediscoveryCorpus.class
                .getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing corpus resource " + RESOURCE);
            }
            byte[] bytes = input.readAllBytes();
            String json = new String(bytes, StandardCharsets.UTF_8);
            return parse(json, sha256(bytes));
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read corpus " + RESOURCE, exception);
        }
    }

    static Corpus parse(String json, String contentSha256) {
        Map<String, Object> root = new JsonReader(
            requireText(json, "json")).readObject();
        requireKeys(root, ROOT_KEYS, "corpus root");
        String schema = string(root, "schema");
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported corpus schema " + schema);
        }
        String evidenceStatus = string(root, "evidenceStatus");
        if (!"FROZEN_DIAGNOSTIC_CORPUS".equals(evidenceStatus)) {
            throw new IllegalArgumentException(
                "unexpected corpus evidenceStatus " + evidenceStatus);
        }
        String inventoryRevision = string(root, "inventoryRevision");
        String claimBoundary = string(root, "claimBoundary");
        Object rawCases = root.get("cases");
        if (!(rawCases instanceof List<?> values)) {
            throw new IllegalArgumentException("cases must be a JSON array");
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("corpus must contain at least one case");
        }

        List<Case> cases = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Object rawCase : values) {
            if (!(rawCase instanceof Map<?, ?> rawMap)) {
                throw new IllegalArgumentException(
                    "each corpus case must be a JSON object");
            }
            Map<String, Object> caseValues = stringKeyed(rawMap);
            requireKeys(caseValues, CASE_KEYS, "corpus case");
            Case benchmarkCase = new Case(
                string(caseValues, "id"),
                string(caseValues, "family"),
                string(caseValues, "source"),
                string(caseValues, "target"),
                enumValue(Relation.class, caseValues, "relation"),
                enumValue(Role.class, caseValues, "role"),
                string(caseValues, "diagnosticPurpose"),
                string(caseValues, "provenance"),
                enumValue(TargetRelation.class, caseValues, "targetRelation"),
                nonNegativeInt(caseValues, "oracleMaxDepth"),
                positiveInt(caseValues, "oracleMaxVisitedStates"),
                nonNegativeInt(caseValues, "searchMaxDepth"),
                positiveInt(caseValues, "searchMaxVisitedStates"),
                positiveInt(caseValues, "maxCandidatesPerState"),
                nonNegativeInt(caseValues, "maxExpandingSteps"),
                positiveInt(caseValues, "beamWidth")
            );
            if (!ids.add(benchmarkCase.id())) {
                throw new IllegalArgumentException(
                    "duplicate corpus case id " + benchmarkCase.id());
            }
            if (benchmarkCase.role() == Role.NEGATIVE_CONTROL
                    && benchmarkCase.relation() != Relation.NOT_EQUIVALENT) {
                throw new IllegalArgumentException(
                    "negative control must declare NOT_EQUIVALENT: "
                        + benchmarkCase.id());
            }
            cases.add(benchmarkCase);
        }
        return new Corpus(
            schema,
            evidenceStatus,
            inventoryRevision,
            claimBoundary,
            requireSha256(contentSha256),
            cases
        );
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("JSON object key must be a string");
            }
            result.put(text, value);
        });
        return result;
    }

    private static void requireKeys(
        Map<String, Object> values,
        Set<String> expected,
        String label
    ) {
        if (!values.keySet().equals(expected)) {
            throw new IllegalArgumentException(
                label + " keys differ: expected=" + expected
                    + ", actual=" + values.keySet());
        }
    }

    private static String string(Map<String, Object> values, String key) {
        Object raw = values.get(key);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(
                "missing, non-string or blank " + key + " in " + values);
        }
        return text.trim();
    }

    private static int positiveInt(Map<String, Object> values, String key) {
        int value = integer(values, key);
        if (value < 1) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }

    private static int nonNegativeInt(Map<String, Object> values, String key) {
        int value = integer(values, key);
        if (value < 0) {
            throw new IllegalArgumentException(key + " must not be negative");
        }
        return value;
    }

    private static int integer(Map<String, Object> values, String key) {
        Object raw = values.get(key);
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be numeric");
        }
        double decimal = number.doubleValue();
        int integer = number.intValue();
        if (!Double.isFinite(decimal) || decimal != integer) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return integer;
    }

    private static <E extends Enum<E>> E enumValue(
        Class<E> type,
        Map<String, Object> values,
        String key
    ) {
        String value = string(values, key);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "unsupported " + key + " value " + value,
                exception);
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static String requireSha256(String value) {
        String text = requireText(value, "contentSha256").trim();
        if (!text.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                "contentSha256 must be lowercase hexadecimal SHA-256");
        }
        return text;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public enum Relation {
        EQUIVALENT,
        NOT_EQUIVALENT
    }

    public enum Role {
        HISTORICAL_POSITIVE,
        NEGATIVE_CONTROL,
        SEARCH_POLICY_CONTROL
    }

    public enum TargetRelation {
        SYNTAX_EXACT,
        VALUE_EQUIVALENT
    }

    public record Corpus(
        String schema,
        String evidenceStatus,
        String inventoryRevision,
        String claimBoundary,
        String contentSha256,
        List<Case> cases
    ) {
        public Corpus {
            requireText(schema, "schema");
            requireText(evidenceStatus, "evidenceStatus");
            requireText(inventoryRevision, "inventoryRevision");
            requireText(claimBoundary, "claimBoundary");
            contentSha256 = requireSha256(contentSha256);
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
            if (cases.isEmpty()) {
                throw new IllegalArgumentException("cases must not be empty");
            }
        }
    }

    public record Case(
        String id,
        String family,
        String source,
        String target,
        Relation relation,
        Role role,
        String diagnosticPurpose,
        String provenance,
        TargetRelation targetRelation,
        int oracleMaxDepth,
        int oracleMaxVisitedStates,
        int searchMaxDepth,
        int searchMaxVisitedStates,
        int maxCandidatesPerState,
        int maxExpandingSteps,
        int beamWidth
    ) {
        public Case {
            id = requireText(id, "id").trim();
            family = requireText(family, "family").trim();
            source = requireText(source, "source").trim();
            target = requireText(target, "target").trim();
            Objects.requireNonNull(relation, "relation");
            Objects.requireNonNull(role, "role");
            diagnosticPurpose = requireText(
                diagnosticPurpose,
                "diagnosticPurpose").trim();
            provenance = requireText(provenance, "provenance").trim();
            Objects.requireNonNull(targetRelation, "targetRelation");
            if (oracleMaxDepth < 0 || searchMaxDepth < 0
                    || oracleMaxVisitedStates < 1
                    || searchMaxVisitedStates < 1
                    || maxCandidatesPerState < 1
                    || maxExpandingSteps < 0
                    || beamWidth < 1) {
                throw new IllegalArgumentException(
                    "case budgets are outside their declared finite ranges: " + id);
            }
        }
    }
}

/** Compares two target-blind production policies against retained oracle witnesses. */
final class HistoricalProductionSearchComparison {
    static final String SCHEMA = "regelsuche.production-search-comparison/v1";
    static final String EVIDENCE_STATUS =
        "EXECUTED_MATCHED_DECLARED_BUDGET_COMPARISON";
    static final String SCALAR_POLICY = "SCALAR_BEST_FIRST_TARGET_BLIND";
    static final String DIVERSITY_POLICY = "STRUCTURAL_DIVERSITY_TARGET_BLIND";
    static final String INFORMATION_BOUNDARY =
        "TARGET_BLIND_SEARCHES_ORACLE_POST_HOC_DIAGNOSTIC";
    static final String CLAIM_BOUNDARY =
        "Scalar best-first and structural-diversity searches receive the same "
            + "frozen source, production inventory and declared heuristic "
            + "budgets without a target. Oracle witnesses are used only after "
            + "both searches finish. Equal declared budgets do not imply equal "
            + "consumed work, and a retained prefix difference is not proof, "
            + "autonomous rediscovery, novelty or general search superiority.";
    static final String FILE_NAME = "production-search-comparison.json";

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
        Objects.requireNonNull(witnessCases, "witnessCases");
        requireAtlasBinding(corpus, atlas);
        Map<String, CaseResult> atlasById = atlas.cases().stream()
            .collect(Collectors.toUnmodifiableMap(
                value -> value.benchmarkCase().id(), value -> value));
        Map<String, HistoricalWitnessPruningDiagnostic.CaseDiagnostic>
            witnessById = witnessCases.stream().collect(
                Collectors.toUnmodifiableMap(
                    HistoricalWitnessPruningDiagnostic.CaseDiagnostic::id,
                    value -> value));
        requireCaseMembership(corpus, atlasById.keySet(), witnessById.keySet());
        List<CaseComparison> cases = corpus.cases().stream()
            .sorted(Comparator.comparing(Case::id))
            .map(value -> compare(
                value, atlasById.get(value.id()), witnessById.get(value.id())))
            .toList();
        String witnessHash = new HistoricalWitnessPruningDiagnostic()
            .contentHash(corpus, atlas, witnessCases);
        return Report.create(corpus, atlas, witnessHash, cases);
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

        long[] work = new long[2];
        TransformationEngine counting = countingEngine(
            productionEngine(benchmarkCase), work);
        List<SearchState> diversityStates =
            new StructuralDiversitySearchStrategy().search(searchProblem(
                benchmarkCase, format(benchmarkCase.source()), counting));
        SearchState match = findMatch(
            benchmarkCase, format(benchmarkCase.target()), diversityStates);
        int engineCalls = Math.toIntExact(work[0]);
        requireDiversityBinding(
            diversityRetained, diversityStates, match, engineCalls, work[1]);

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
            witnessSteps == 0 ? 0 : exploredPrefixLength(witness, diversityStates),
            diversityStates.size(),
            engineCalls,
            work[1],
            diversityStates.isEmpty() ? "NO_STATES" : "COMPLETED_BOUNDED_SEARCH");
        return new CaseComparison(
            benchmarkCase.id(),
            classify(witnessSteps, scalar, diversity),
            witnessSteps,
            benchmarkCase.searchMaxDepth(),
            benchmarkCase.searchMaxVisitedStates(),
            benchmarkCase.maxCandidatesPerState(),
            benchmarkCase.maxExpandingSteps(),
            benchmarkCase.beamWidth(),
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
        if (diversity.exploredPrefixLength() > scalar.exploredPrefixLength()) {
            return ComparisonStatus.DIVERSITY_EXTENDS_SCALAR_PREFIX;
        }
        if (diversity.exploredPrefixLength() == scalar.exploredPrefixLength()) {
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
            .filter(state -> matches(benchmarkCase, state.expression(), target))
            .min(Comparator.comparingInt(SearchState::depth)
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
        int engineCalls,
        long generatedTransformations
    ) {
        String terminal = states.isEmpty()
            ? "NO_STATES" : "COMPLETED_BOUNDED_SEARCH";
        List<String> path = match == null ? List.of() : match.path();
        List<String> ruleIds = match == null
            ? List.of() : match.appliedRuleIds();
        if (retained.reached() != (match != null)
                || !retained.terminalStatus().equals(terminal)
                || retained.exploredStates() != states.size()
                || retained.engineCalls() != engineCalls
                || retained.generatedTransformations() != generatedTransformations
                || !retained.path().equals(path)
                || !retained.ruleIds().equals(ruleIds)) {
            throw new IllegalStateException(
                "diversity rerun differs from retained atlas evidence");
        }
    }

    private static TransformationEngine countingEngine(
        TransformationEngine delegate,
        long[] work
    ) {
        Objects.requireNonNull(delegate, "delegate");
        return expression -> {
            work[0]++;
            List<Transformation> result = delegate.transform(expression);
            work[1] += result.size();
            return result;
        };
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
            .map(Case::id).collect(Collectors.toSet());
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
            .map(Case::id).collect(Collectors.toSet());
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
        int maxDepth,
        int maxVisitedStates,
        int maxCandidatesPerState,
        int maxExpandingSteps,
        int beamWidth,
        SearchComparisonEvidence scalar,
        SearchComparisonEvidence diversity,
        int prefixDelta
    ) {
        CaseComparison {
            id = requireText(id, "id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(scalar, "scalar");
            Objects.requireNonNull(diversity, "diversity");
            if (oracleWitnessStepCount < 0
                    || maxDepth < 0
                    || maxVisitedStates < 1
                    || maxCandidatesPerState < 1
                    || maxExpandingSteps < 0
                    || beamWidth < 1
                    || scalar.exploredPrefixLength() > oracleWitnessStepCount
                    || diversity.exploredPrefixLength() > oracleWitnessStepCount
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
            cases.forEach(value -> counts.merge(
                value.status(), 1, Integer::sum));
            return new Summary(
                cases.size(),
                counts,
                (int) cases.stream()
                    .filter(value -> value.oracleWitnessStepCount() > 0)
                    .filter(value -> !value.scalar().reachedRelation())
                    .count(),
                counts.getOrDefault(
                    ComparisonStatus.DIVERSITY_RECOVERS_COMPLETE_WITNESS, 0),
                (int) cases.stream()
                    .filter(value -> value.prefixDelta() > 0).count(),
                (int) cases.stream()
                    .filter(value -> value.diversity().reachedRelation()).count());
        }
    }

    record Report(
        String corpusSha256,
        String atlasSha256,
        String witnessDiagnosticSha256,
        String inventoryRevision,
        List<CaseComparison> cases,
        Summary summary,
        String contentHash
    ) {
        Report {
            corpusSha256 = requireSha256(corpusSha256, "corpusSha256", false);
            atlasSha256 = requireSha256(atlasSha256, "atlasSha256", true);
            witnessDiagnosticSha256 = requireSha256(
                witnessDiagnosticSha256, "witnessDiagnosticSha256", true);
            inventoryRevision = requireText(
                inventoryRevision, "inventoryRevision");
            cases = Objects.requireNonNull(cases, "cases").stream()
                .sorted(Comparator.comparing(CaseComparison::id)).toList();
            Summary expectedSummary = Summary.derive(cases);
            if (cases.isEmpty()
                    || new LinkedHashSet<>(cases.stream()
                        .map(CaseComparison::id).toList()).size() != cases.size()
                    || !expectedSummary.equals(summary)) {
                throw new IllegalArgumentException(
                    "production comparison case balance differs");
            }
            contentHash = requireSha256(contentHash, "contentHash", true);
            if (!reportHash(
                    corpusSha256,
                    atlasSha256,
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
            String witnessSha256,
            List<CaseComparison> cases
        ) {
            List<CaseComparison> retained = cases.stream()
                .sorted(Comparator.comparing(CaseComparison::id)).toList();
            Summary summary = Summary.derive(retained);
            String atlasSha256 = sha256(atlas.toJson());
            String hash = reportHash(
                corpus.contentSha256(),
                atlasSha256,
                witnessSha256,
                corpus.inventoryRevision(),
                retained,
                summary);
            return new Report(
                corpus.contentSha256(),
                atlasSha256,
                witnessSha256,
                corpus.inventoryRevision(),
                retained,
                summary,
                hash);
        }

        String toCanonicalJson() {
            return renderReport(
                corpusSha256,
                atlasSha256,
                witnessDiagnosticSha256,
                inventoryRevision,
                cases,
                summary,
                contentHash);
        }
    }

    private static String reportHash(
        String corpusSha256,
        String atlasSha256,
        String witnessSha256,
        String inventoryRevision,
        List<CaseComparison> cases,
        Summary summary
    ) {
        return sha256(renderReport(
            corpusSha256,
            atlasSha256,
            witnessSha256,
            inventoryRevision,
            cases,
            summary,
            null));
    }

    private static String renderReport(
        String corpusSha256,
        String atlasSha256,
        String witnessSha256,
        String inventoryRevision,
        List<CaseComparison> cases,
        Summary summary,
        String contentHash
    ) {
        JsonWriter writer = new JsonWriter().beginObject();
        writer.property("schema", SCHEMA);
        writer.property("evidenceStatus", EVIDENCE_STATUS);
        writer.property("corpusSchema", HistoricalRediscoveryCorpus.SCHEMA);
        writer.property("corpusSha256", corpusSha256);
        writer.property("atlasSchema", HistoricalRediscoveryAtlas.SCHEMA);
        writer.property("atlasSha256", atlasSha256);
        writer.property(
            "witnessDiagnosticSchema", HistoricalWitnessPruningDiagnostic.SCHEMA);
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
        writer.property("oracleWitnessStepCount", value.oracleWitnessStepCount());
        writer.object("declaredBudget", object -> {
            object.property("maxDepth", value.maxDepth());
            object.property("maxVisitedStates", value.maxVisitedStates());
            object.property(
                "maxCandidatesPerState", value.maxCandidatesPerState());
            object.property("maxExpandingSteps", value.maxExpandingSteps());
            object.property("beamWidth", value.beamWidth());
        });
        writer.object("scalar", object -> writeSearch(object, value.scalar()));
        writer.object("diversity", object -> writeSearch(object, value.diversity()));
        writer.property("prefixDelta", value.prefixDelta());
    }

    private static void writeSearch(
        JsonWriter writer,
        SearchComparisonEvidence value
    ) {
        writer.property("reachedRelation", value.reachedRelation());
        writer.property("exploredPrefixLength", value.exploredPrefixLength());
        writer.property("exploredStates", value.exploredStates());
        writer.property("engineCalls", value.engineCalls());
        writer.property(
            "generatedTransformations", value.generatedTransformations());
        writer.property("terminalStatus", value.terminalStatus());
    }

    private static void writeSummary(JsonWriter writer, Summary value) {
        writer.property("caseCount", value.caseCount());
        writer.object("statusCounts", object -> value.statusCounts().entrySet()
            .stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> object.property(
                entry.getKey().name(), entry.getValue())));
        writer.property(
            "comparableScalarMissCount", value.comparableScalarMissCount());
        writer.property(
            "diversityRecoveredCompleteWitnessCount",
            value.diversityRecoveredCompleteWitnessCount());
        writer.property(
            "diversityExtendedPrefixCount", value.diversityExtendedPrefixCount());
        writer.property(
            "diversityReachedRelationCount", value.diversityReachedRelationCount());
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
        String text = requireText(value, label);
        String pattern = prefixed ? "sha256:[0-9a-f]{64}" : "[0-9a-f]{64}";
        if (!text.matches(pattern)) {
            throw new IllegalArgumentException(label + " must be a SHA-256");
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


}
