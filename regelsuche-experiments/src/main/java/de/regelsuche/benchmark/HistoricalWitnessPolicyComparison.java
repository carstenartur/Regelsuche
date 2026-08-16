package de.regelsuche.benchmark;

import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Case;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Relation;
import de.regelsuche.canonical.ExpressionCanonicalizer;
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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compares the first scalar oracle-witness loss with the existing target-blind
 * structural-diversity control under the same declared search heuristic.
 *
 * <p>The target-aware witness is used only after both target-blind searches
 * have completed. Configured budgets are identical, while actual explored and
 * generated work remains visible for each policy instead of being treated as
 * equal by assertion.</p>
 */
public final class HistoricalWitnessPolicyComparison {
    static final String SCHEMA =
        "regelsuche.witness-policy-comparison/v1";
    static final String EVIDENCE_STATUS =
        "EXECUTED_TARGET_BLIND_POLICY_COMPARISON";
    static final String SCALAR_POLICY =
        "SCALAR_BEST_FIRST_TARGET_BLIND";
    static final String DIVERSITY_POLICY =
        "STRUCTURAL_DIVERSITY_TARGET_BLIND";
    static final String BUDGET_POLICY =
        "SAME_DECLARED_SEARCH_HEURISTIC_ACTUAL_WORK_RETAINED_SEPARATELY";
    static final String FILE_NAME = "witness-policy-comparison.json";
    static final String CLAIM_BOUNDARY =
        "The target-aware oracle supplies a bounded diagnostic witness only after "
            + "both target-blind policies complete. Prefix recovery is not proof, "
            + "autonomous rediscovery, external novelty or general superiority.";

    private static final String NOT_APPLICABLE = "NOT_APPLICABLE";
    private static final String SCALAR_ALREADY_FOUND = "SCALAR_ALREADY_FOUND";
    private static final String DIVERSITY_REACHED_RELATION =
        "DIVERSITY_REACHED_RELATION";
    private static final String DIVERSITY_EXPLORED_FULL_WITNESS =
        "DIVERSITY_EXPLORED_FULL_WITNESS";
    private static final String DIVERSITY_EXTENDED_PREFIX =
        "DIVERSITY_EXTENDED_PREFIX";
    private static final String NO_PREFIX_GAIN = "NO_PREFIX_GAIN";
    private static final String DIVERSITY_SHORTER_PREFIX =
        "DIVERSITY_SHORTER_PREFIX";
    private static final Set<String> STATUSES = Set.of(
        NOT_APPLICABLE,
        SCALAR_ALREADY_FOUND,
        DIVERSITY_REACHED_RELATION,
        DIVERSITY_EXPLORED_FULL_WITNESS,
        DIVERSITY_EXTENDED_PREFIX,
        NO_PREFIX_GAIN,
        DIVERSITY_SHORTER_PREFIX);

    private final ExpressionParser parser = new ExpressionParser();

    public static void main(String[] args) {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                "expected: <atlas-directory> <scalar-diagnostic-json> <output-directory>");
        }
        Execution execution = new HistoricalWitnessPolicyComparison().execute(
            Path.of(args[0]),
            Path.of(args[1]),
            Path.of(args[2]));
        System.out.println("historicalWitnessPolicyComparison=" + execution.path());
        System.out.println("historicalWitnessPolicyComparisonHash="
            + execution.contentHash());
        System.out.println("historicalWitnessPolicyComparisonCases="
            + execution.cases().size());
    }

    Execution execute(
        Path atlasDirectory,
        Path scalarDiagnosticPath,
        Path outputDirectory
    ) {
        return execute(
            HistoricalRediscoveryCorpus.load(),
            atlasDirectory,
            scalarDiagnosticPath,
            outputDirectory);
    }

    Execution execute(
        Corpus corpus,
        Path atlasDirectory,
        Path scalarDiagnosticPath,
        Path outputDirectory
    ) {
        Objects.requireNonNull(corpus, "corpus");
        HistoricalRediscoveryRunArtifact.VerifiedRun verified =
            HistoricalRediscoveryRunArtifact.verify(atlasDirectory);
        requireManifestBinding(corpus, verified.manifest());
        Path atlasPath = verified.directory().resolve(
            HistoricalRediscoveryRunArtifact.ArtifactRole.ATLAS_JSON.fileName());
        String atlasJson = readUtf8(atlasPath);
        String scalarJson = readUtf8(scalarDiagnosticPath);
        RetainedAtlas atlas = RetainedAtlas.parse(atlasJson, corpus);
        RetainedScalarDiagnostic scalar = RetainedScalarDiagnostic.parse(
            scalarJson,
            corpus,
            atlasJson);
        List<CaseComparison> cases = compare(corpus, atlas, scalar);
        String hash = reportHash(corpus, verified.manifest(), atlasJson,
            scalar.contentHash(), cases);
        String json = renderReport(corpus, verified.manifest(), atlasJson,
            scalar.contentHash(), cases, hash);
        Path directory = Objects.requireNonNull(outputDirectory, "outputDirectory")
            .toAbsolutePath().normalize();
        Path output = directory.resolve(FILE_NAME);
        try {
            Files.createDirectories(directory);
            AtomicJsonFile.writeUtf8(output, json);
            if (!json.equals(Files.readString(output, StandardCharsets.UTF_8))) {
                throw new IllegalStateException(
                    "written witness-policy comparison differs from report");
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write witness-policy comparison", exception);
        }
        return new Execution(output, hash, cases);
    }

    private List<CaseComparison> compare(
        Corpus corpus,
        RetainedAtlas atlas,
        RetainedScalarDiagnostic scalar
    ) {
        Map<String, AtlasCase> atlasCases = atlas.cases().stream()
            .collect(Collectors.toUnmodifiableMap(AtlasCase::id, Function.identity()));
        Map<String, ScalarCase> scalarCases = scalar.cases().stream()
            .collect(Collectors.toUnmodifiableMap(ScalarCase::id, Function.identity()));
        return corpus.cases().stream()
            .sorted(Comparator.comparing(Case::id))
            .map(benchmarkCase -> compareCase(
                benchmarkCase,
                requireCase(atlasCases, benchmarkCase.id(), "atlas"),
                requireCase(scalarCases, benchmarkCase.id(), "scalar diagnostic")))
            .toList();
    }

    private CaseComparison compareCase(
        Case benchmarkCase,
        AtlasCase atlasCase,
        ScalarCase scalarCase
    ) {
        if (!"REACHABLE".equals(atlasCase.oracleStatus())
                || atlasCase.witnessExpressions().isEmpty()
                || benchmarkCase.relation() == Relation.NOT_EQUIVALENT) {
            return CaseComparison.notApplicable(
                benchmarkCase,
                atlasCase,
                scalarCase,
                "no positive production-oracle witness is eligible");
        }
        if ("SCALAR_ALREADY_FOUND".equals(scalarCase.status())) {
            return CaseComparison.notCompared(
                benchmarkCase,
                atlasCase,
                scalarCase,
                SCALAR_ALREADY_FOUND,
                "the scalar policy already reached the relation");
        }
        if (atlasCase.witnessExpressions().size()
                != atlasCase.witnessRuleIds().size()
                || scalarCase.witnessStepCount()
                    != atlasCase.witnessExpressions().size()) {
            throw new IllegalArgumentException(
                "oracle witness binding differs for " + benchmarkCase.id());
        }

        CountingEngine counting = new CountingEngine(
            productionEngine(benchmarkCase));
        List<SearchState> states = new StructuralDiversitySearchStrategy()
            .search(searchProblem(
                benchmarkCase,
                format(benchmarkCase.source()),
                counting));
        requireSameDiversityRun(
            benchmarkCase,
            atlasCase.diversity(),
            states,
            counting.calls(),
            counting.generated());

        int diversityPrefix = prefixLength(
            atlasCase.witnessExpressions(), states);
        int gain = diversityPrefix - scalarCase.exploredPrefixLength();
        String status;
        String detail;
        if (atlasCase.diversity().reached()) {
            status = DIVERSITY_REACHED_RELATION;
            detail = "the diversity policy reaches the retained relation";
        } else if (diversityPrefix == atlasCase.witnessExpressions().size()) {
            status = DIVERSITY_EXPLORED_FULL_WITNESS;
            detail = "all oracle witness states are explored but relation "
                + "matching remains separate";
        } else if (gain > 0) {
            status = DIVERSITY_EXTENDED_PREFIX;
            detail = "the diversity policy preserves a longer oracle-witness prefix";
        } else if (gain == 0) {
            status = NO_PREFIX_GAIN;
            detail = "the diversity policy does not extend the scalar witness prefix";
        } else {
            status = DIVERSITY_SHORTER_PREFIX;
            detail = "the diversity policy explores a shorter oracle-witness prefix";
        }
        return new CaseComparison(
            benchmarkCase.id(),
            status,
            atlasCase.oracleStatus(),
            atlasCase.witnessExpressions().size(),
            budget(benchmarkCase),
            scalarCase.view(),
            new PolicyRun(
                atlasCase.diversity().reached(),
                diversityPrefix,
                states.size(),
                counting.calls(),
                counting.generated(),
                atlasCase.diversity().path(),
                atlasCase.diversity().ruleIds(),
                ""),
            gain,
            detail);
    }

    private int prefixLength(
        List<String> witnessExpressions,
        List<SearchState> states
    ) {
        Set<String> explored = states.stream()
            .map(SearchState::expression)
            .map(this::expressionKey)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        int length = 0;
        for (String expression : witnessExpressions) {
            if (!explored.contains(expressionKey(expression))) {
                break;
            }
            length++;
        }
        return length;
    }

    private static void requireSameDiversityRun(
        Case benchmarkCase,
        RetainedSearch retained,
        List<SearchState> states,
        int engineCalls,
        long generatedTransformations
    ) {
        List<Object> expected = List.of(
            retained.exploredStates(),
            retained.engineCalls(),
            retained.generatedTransformations());
        List<Object> actual = List.of(
            states.size(), engineCalls, generatedTransformations);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                "diversity rerun differs from retained atlas evidence for "
                    + benchmarkCase.id());
        }
        if (retained.reached()) {
            boolean retainedPathExists = states.stream().anyMatch(state ->
                state.path().equals(retained.path())
                    && state.appliedRuleIds().equals(retained.ruleIds()));
            if (!retainedPathExists) {
                throw new IllegalStateException(
                    "retained diversity witness path is absent for "
                        + benchmarkCase.id());
            }
        }
    }

    private static SearchProblem searchProblem(
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

    private static DeclaredBudget budget(Case value) {
        return new DeclaredBudget(
            value.searchMaxDepth(),
            value.searchMaxVisitedStates(),
            value.maxCandidatesPerState(),
            value.maxExpandingSteps(),
            value.beamWidth());
    }

    private static <T> T requireCase(
        Map<String, T> values,
        String id,
        String source
    ) {
        T value = values.get(id);
        if (value == null) {
            throw new IllegalArgumentException(
                "missing " + source + " case " + id);
        }
        return value;
    }

    private static void requireManifestBinding(
        Corpus corpus,
        HistoricalRediscoveryRunArtifact.Manifest manifest
    ) {
        if (!corpus.schema().equals(manifest.corpusSchema())
                || !corpus.contentSha256().equals(manifest.corpusSha256())
                || !corpus.inventoryRevision().equals(manifest.inventoryRevision())
                || !corpus.claimBoundary().equals(manifest.claimBoundary())
                || corpus.cases().size() != manifest.caseCount()) {
            throw new IllegalArgumentException(
                "historical run manifest does not bind the frozen corpus");
        }
    }

    private static String reportHash(
        Corpus corpus,
        HistoricalRediscoveryRunArtifact.Manifest manifest,
        String atlasJson,
        String scalarDiagnosticHash,
        List<CaseComparison> cases
    ) {
        return sha256(renderReport(
            corpus, manifest, atlasJson, scalarDiagnosticHash, cases, null));
    }

    private static String renderReport(
        Corpus corpus,
        HistoricalRediscoveryRunArtifact.Manifest manifest,
        String atlasJson,
        String scalarDiagnosticHash,
        List<CaseComparison> cases,
        String contentHash
    ) {
        Map<String, Integer> counts = new TreeMap<>();
        cases.forEach(value -> counts.merge(value.status(), 1, Integer::sum));
        int compared = (int) cases.stream()
            .filter(value -> !NOT_APPLICABLE.equals(value.status())
                && !SCALAR_ALREADY_FOUND.equals(value.status()))
            .count();
        int positiveGain = (int) cases.stream()
            .filter(value -> value.prefixGain() > 0)
            .count();
        int reached = (int) cases.stream()
            .filter(value -> DIVERSITY_REACHED_RELATION.equals(value.status()))
            .count();
        int regressions = (int) cases.stream()
            .filter(value -> DIVERSITY_SHORTER_PREFIX.equals(value.status()))
            .count();

        JsonWriter writer = new JsonWriter().beginObject();
        writer.property("schema", SCHEMA);
        writer.property("evidenceStatus", EVIDENCE_STATUS);
        writer.property("corpusSchema", corpus.schema());
        writer.property("corpusSha256", corpus.contentSha256());
        writer.property("atlasRunHash", manifest.contentHash());
        writer.property("atlasSha256", sha256(atlasJson));
        writer.property("scalarDiagnosticHash", scalarDiagnosticHash);
        writer.property("inventoryRevision", corpus.inventoryRevision());
        writer.property("scalarPolicy", SCALAR_POLICY);
        writer.property("diversityPolicy", DIVERSITY_POLICY);
        writer.property("budgetPolicy", BUDGET_POLICY);
        writer.property("claimBoundary", CLAIM_BOUNDARY);
        writer.array("cases", array -> cases.forEach(value ->
            array.objectValue(object -> writeCase(object, value))));
        writer.object("summary", summary -> {
            summary.property("caseCount", cases.size());
            summary.property("comparedCaseCount", compared);
            summary.property("positivePrefixGainCount", positiveGain);
            summary.property("diversityReachedCount", reached);
            summary.property("regressionCount", regressions);
            summary.object("statusCounts", statusCounts -> counts.forEach(
                statusCounts::property));
        });
        if (contentHash != null) {
            writer.property("contentHash", contentHash);
        }
        return writer.endObject().toString();
    }

    private static void writeCase(JsonWriter writer, CaseComparison value) {
        writer.property("id", value.id());
        writer.property("status", value.status());
        writer.property("oracleStatus", value.oracleStatus());
        writer.property("witnessStepCount", value.witnessStepCount());
        writer.object("declaredBudget", object -> writeBudget(
            object, value.declaredBudget()));
        writer.object("scalar", object -> writePolicyRun(
            object, value.scalar()));
        writer.object("diversity", object -> writePolicyRun(
            object, value.diversity()));
        writer.property("prefixGain", value.prefixGain());
        writer.property("detail", value.detail());
    }

    private static void writeBudget(JsonWriter writer, DeclaredBudget value) {
        writer.property("maxDepth", value.maxDepth());
        writer.property("maxVisitedStates", value.maxVisitedStates());
        writer.property("maxCandidatesPerState", value.maxCandidatesPerState());
        writer.property("maxExpandingSteps", value.maxExpandingSteps());
        writer.property("beamWidth", value.beamWidth());
    }

    private static void writePolicyRun(JsonWriter writer, PolicyRun value) {
        writer.property("reached", value.reached());
        writer.property("exploredPrefixLength", value.exploredPrefixLength());
        writer.property("exploredStates", value.exploredStates());
        writer.property("engineCalls", value.engineCalls());
        writer.property("generatedTransformations", value.generatedTransformations());
        writer.stringArray("path", value.path());
        writer.stringArray("ruleIds", value.ruleIds());
        writer.property("detail", value.detail());
    }

    record Execution(Path path, String contentHash, List<CaseComparison> cases) {
        Execution {
            path = Objects.requireNonNull(path, "path")
                .toAbsolutePath().normalize();
            contentHash = requirePrefixedSha256(contentHash, "contentHash");
            cases = List.copyOf(cases);
        }
    }

    record DeclaredBudget(
        int maxDepth,
        int maxVisitedStates,
        int maxCandidatesPerState,
        int maxExpandingSteps,
        int beamWidth
    ) {
    }

    record PolicyRun(
        boolean reached,
        int exploredPrefixLength,
        int exploredStates,
        int engineCalls,
        long generatedTransformations,
        List<String> path,
        List<String> ruleIds,
        String detail
    ) {
        PolicyRun {
            if (exploredPrefixLength < 0 || exploredStates < 0
                    || engineCalls < 0 || generatedTransformations < 0) {
                throw new IllegalArgumentException(
                    "policy-run counters must not be negative");
            }
            path = List.copyOf(path);
            ruleIds = List.copyOf(ruleIds);
            detail = detail == null ? "" : detail;
        }
    }

    record CaseComparison(
        String id,
        String status,
        String oracleStatus,
        int witnessStepCount,
        DeclaredBudget declaredBudget,
        PolicyRun scalar,
        PolicyRun diversity,
        int prefixGain,
        String detail
    ) {
        CaseComparison {
            id = requireText(id, "id");
            status = requireMember(status, STATUSES, "status");
            oracleStatus = requireText(oracleStatus, "oracleStatus");
            Objects.requireNonNull(declaredBudget, "declaredBudget");
            Objects.requireNonNull(scalar, "scalar");
            Objects.requireNonNull(diversity, "diversity");
            detail = detail == null ? "" : detail;
            if (witnessStepCount < 0
                    || scalar.exploredPrefixLength() > witnessStepCount
                    || diversity.exploredPrefixLength() > witnessStepCount
                    || prefixGain != diversity.exploredPrefixLength()
                        - scalar.exploredPrefixLength()) {
                throw new IllegalArgumentException(
                    "case comparison counters are inconsistent");
            }
        }

        static CaseComparison notApplicable(
            Case benchmarkCase,
            AtlasCase atlasCase,
            ScalarCase scalarCase,
            String detail
        ) {
            return notCompared(
                benchmarkCase, atlasCase, scalarCase, NOT_APPLICABLE, detail);
        }

        static CaseComparison notCompared(
            Case benchmarkCase,
            AtlasCase atlasCase,
            ScalarCase scalarCase,
            String status,
            String detail
        ) {
            PolicyRun scalar = scalarCase.view();
            PolicyRun diversity = new PolicyRun(
                atlasCase.diversity().reached(),
                0,
                atlasCase.diversity().exploredStates(),
                atlasCase.diversity().engineCalls(),
                atlasCase.diversity().generatedTransformations(),
                atlasCase.diversity().path(),
                atlasCase.diversity().ruleIds(),
                "");
            return new CaseComparison(
                benchmarkCase.id(),
                status,
                atlasCase.oracleStatus(),
                atlasCase.witnessExpressions().size(),
                budget(benchmarkCase),
                scalar,
                diversity,
                -scalar.exploredPrefixLength(),
                detail);
        }
    }

    private record RetainedAtlas(List<AtlasCase> cases) {
        static RetainedAtlas parse(String json, Corpus corpus) {
            Map<String, Object> root = new JsonReader(json).readObject();
            requireEqual(HistoricalRediscoveryAtlas.SCHEMA,
                string(root, "schema"), "atlas schema");
            requireEqual(corpus.schema(), string(root, "corpusSchema"),
                "atlas corpus schema");
            requireEqual(corpus.contentSha256(), string(root, "corpusSha256"),
                "atlas corpus hash");
            requireEqual(corpus.inventoryRevision(),
                string(root, "inventoryRevision"), "atlas inventory revision");
            List<AtlasCase> cases = list(root, "cases").stream()
                .map(RetainedAtlas::parseCase)
                .sorted(Comparator.comparing(AtlasCase::id))
                .toList();
            requireCaseIds(corpus, cases.stream().map(AtlasCase::id).toList(),
                "atlas");
            return new RetainedAtlas(cases);
        }

        private static AtlasCase parseCase(Object raw) {
            Map<String, Object> values = object(raw, "atlas case");
            Map<String, Object> production = object(values, "production");
            Map<String, Object> oracle = object(production, "oracle");
            return new AtlasCase(
                string(values, "id"),
                string(values, "primaryStatus"),
                string(oracle, "status"),
                stringList(oracle, "witnessExpressions"),
                stringList(oracle, "witnessRuleIds"),
                parseSearch(object(production, "scalar")),
                parseSearch(object(production, "diversity")));
        }

        private static RetainedSearch parseSearch(Map<String, Object> values) {
            return new RetainedSearch(
                bool(values, "reached"),
                integer(values, "exploredStates"),
                integer(values, "engineCalls"),
                longValue(values, "generatedTransformations"),
                stringList(values, "path"),
                stringList(values, "ruleIds"));
        }
    }

    private record AtlasCase(
        String id,
        String primaryStatus,
        String oracleStatus,
        List<String> witnessExpressions,
        List<String> witnessRuleIds,
        RetainedSearch scalar,
        RetainedSearch diversity
    ) {
        AtlasCase {
            witnessExpressions = List.copyOf(witnessExpressions);
            witnessRuleIds = List.copyOf(witnessRuleIds);
        }
    }

    private record RetainedSearch(
        boolean reached,
        int exploredStates,
        int engineCalls,
        long generatedTransformations,
        List<String> path,
        List<String> ruleIds
    ) {
        RetainedSearch {
            path = List.copyOf(path);
            ruleIds = List.copyOf(ruleIds);
        }
    }

    private record RetainedScalarDiagnostic(
        String contentHash,
        List<ScalarCase> cases
    ) {
        static RetainedScalarDiagnostic parse(
            String json,
            Corpus corpus,
            String atlasJson
        ) {
            Map<String, Object> root = new JsonReader(json).readObject();
            requireEqual(
                DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic.SCHEMA,
                string(root, "schema"),
                "scalar diagnostic schema");
            requireEqual(corpus.schema(), string(root, "corpusSchema"),
                "scalar corpus schema");
            requireEqual(corpus.contentSha256(), string(root, "corpusSha256"),
                "scalar corpus hash");
            requireEqual(sha256(atlasJson), string(root, "atlasSha256"),
                "scalar atlas hash");
            requireEqual(corpus.inventoryRevision(),
                string(root, "inventoryRevision"),
                "scalar inventory revision");
            requireEqual(SCALAR_POLICY, string(root, "searchPolicy"),
                "scalar policy");
            String contentHash = requirePrefixedSha256(
                string(root, "contentHash"), "scalar diagnostic contentHash");
            int marker = json.lastIndexOf(",\"contentHash\":");
            if (marker < 0 || !json.endsWith("}")) {
                throw new IllegalArgumentException(
                    "scalar diagnostic contentHash must be the final property");
            }
            String withoutHash = json.substring(0, marker) + "}";
            requireEqual(contentHash, sha256(withoutHash),
                "scalar diagnostic content hash");
            List<ScalarCase> cases = list(root, "cases").stream()
                .map(RetainedScalarDiagnostic::parseCase)
                .sorted(Comparator.comparing(ScalarCase::id))
                .toList();
            requireCaseIds(corpus, cases.stream().map(ScalarCase::id).toList(),
                "scalar diagnostic");
            return new RetainedScalarDiagnostic(contentHash, cases);
        }

        private static ScalarCase parseCase(Object raw) {
            Map<String, Object> values = object(raw, "scalar case");
            Object rawLoss = values.get("firstLoss");
            String reason = "";
            if (rawLoss instanceof Map<?, ?>) {
                reason = string(object(rawLoss, "firstLoss"), "reason");
            } else if (rawLoss != null) {
                throw new IllegalArgumentException(
                    "firstLoss must be an object or null");
            }
            return new ScalarCase(
                string(values, "id"),
                string(values, "status"),
                integer(values, "witnessStepCount"),
                integer(values, "exploredPrefixLength"),
                integer(values, "searchExploredStates"),
                integer(values, "engineCalls"),
                longValue(values, "generatedTransformations"),
                reason);
        }
    }

    private record ScalarCase(
        String id,
        String status,
        int witnessStepCount,
        int exploredPrefixLength,
        int exploredStates,
        int engineCalls,
        long generatedTransformations,
        String firstLossReason
    ) {
        PolicyRun view() {
            return new PolicyRun(
                "SCALAR_ALREADY_FOUND".equals(status),
                exploredPrefixLength,
                exploredStates,
                engineCalls,
                generatedTransformations,
                List.of(),
                List.of(),
                firstLossReason);
        }
    }

    private static void requireCaseIds(
        Corpus corpus,
        List<String> ids,
        String source
    ) {
        List<String> expected = corpus.cases().stream()
            .map(Case::id)
            .sorted()
            .toList();
        if (!ids.equals(expected)
                || new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException(
                source + " case membership differs from corpus");
        }
    }

    private static Map<String, Object> object(Object raw, String label) {
        if (!(raw instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException(
                    label + " key must be a string");
            }
            if (result.put(text, value) != null) {
                throw new IllegalArgumentException(
                    label + " contains duplicate key " + text);
            }
        });
        return result;
    }

    private static Map<String, Object> object(
        Map<String, Object> values,
        String key
    ) {
        return object(values.get(key), key);
    }

    private static List<?> list(Map<String, Object> values, String key) {
        Object raw = values.get(key);
        if (!(raw instanceof List<?> items)) {
            throw new IllegalArgumentException(key + " must be an array");
        }
        return items;
    }

    private static List<String> stringList(
        Map<String, Object> values,
        String key
    ) {
        return list(values, key).stream()
            .map(value -> {
                if (!(value instanceof String text)) {
                    throw new IllegalArgumentException(
                        key + " must contain strings");
                }
                return text;
            })
            .toList();
    }

    private static String string(Map<String, Object> values, String key) {
        Object raw = values.get(key);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " must be non-blank text");
        }
        return text;
    }

    private static boolean bool(Map<String, Object> values, String key) {
        Object raw = values.get(key);
        if (!(raw instanceof Boolean value)) {
            throw new IllegalArgumentException(key + " must be boolean");
        }
        return value;
    }

    private static int integer(Map<String, Object> values, String key) {
        long value = longValue(values, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " is outside int range");
        }
        return (int) value;
    }

    private static long longValue(Map<String, Object> values, String key) {
        Object raw = values.get(key);
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be numeric");
        }
        double decimal = number.doubleValue();
        long integer = number.longValue();
        if (!Double.isFinite(decimal) || decimal != integer) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return integer;
    }

    private static String readUtf8(Path path) {
        try {
            return Files.readString(
                Objects.requireNonNull(path, "path")
                    .toAbsolutePath().normalize(),
                StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read " + path, exception);
        }
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

    private static String requirePrefixedSha256(String value, String label) {
        String text = requireText(value, label);
        if (!text.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                label + " must be prefixed SHA-256");
        }
        return text;
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

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static final class CountingEngine implements TransformationEngine {
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
