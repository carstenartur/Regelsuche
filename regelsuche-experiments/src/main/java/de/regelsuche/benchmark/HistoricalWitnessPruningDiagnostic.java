package de.regelsuche.benchmark;

import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.AtlasReport;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.CaseResult;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.OracleEvidence;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.SearchEvidence;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Case;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Relation;
import de.regelsuche.benchmark.HistoricalWitnessPruningReport.CaseDiagnostic;
import de.regelsuche.benchmark.HistoricalWitnessPruningReport.CaseStatus;
import de.regelsuche.benchmark.HistoricalWitnessPruningReport.TargetBlindTerminalStatus;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchObserver;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Reruns the retained target-blind scalar search with telemetry and locates the
 * first lost prefix of each target-aware production-oracle witness.
 */
public final class HistoricalWitnessPruningDiagnostic {
    private final Function<Case, TransformationEngine> engineFactory;
    private final WitnessPrefixAnalyzer analyzer = new WitnessPrefixAnalyzer();

    public HistoricalWitnessPruningDiagnostic() {
        this(HistoricalWitnessPruningDiagnostic::productionEngine);
    }

    HistoricalWitnessPruningDiagnostic(
        Function<Case, TransformationEngine> engineFactory
    ) {
        this.engineFactory = Objects.requireNonNull(engineFactory, "engineFactory");
    }

    public HistoricalWitnessPruningReport run(
        Corpus corpus,
        AtlasReport atlas
    ) {
        Objects.requireNonNull(corpus, "corpus");
        Objects.requireNonNull(atlas, "atlas");
        requireAtlasBinding(corpus, atlas);
        Map<String, CaseResult> atlasCases = new LinkedHashMap<>();
        atlas.cases().forEach(result ->
            atlasCases.put(result.benchmarkCase().id(), result));
        List<CaseDiagnostic> cases = corpus.cases().stream()
            .sorted(Comparator.comparing(Case::id))
            .map(value -> diagnose(
                value,
                Objects.requireNonNull(
                    atlasCases.get(value.id()),
                    () -> "missing atlas case " + value.id())))
            .toList();
        return HistoricalWitnessPruningReport.create(corpus, atlas, cases);
    }

    public WrittenArtifact write(
        Path directory,
        HistoricalWitnessPruningReport report
    ) {
        Path outputDirectory = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath().normalize();
        Objects.requireNonNull(report, "report");
        Path output = outputDirectory.resolve(
            HistoricalWitnessPruningReport.FILE_NAME);
        try {
            Files.createDirectories(outputDirectory);
            AtomicJsonFile.writeUtf8(output, report.toCanonicalJson());
            String retained = Files.readString(output, StandardCharsets.UTF_8);
            if (!report.toCanonicalJson().equals(retained)) {
                throw new IllegalStateException(
                    "written witness-pruning diagnostic differs from report");
            }
            return new WrittenArtifact(
                output,
                report.contentHash(),
                Files.size(output));
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write witness-pruning diagnostic", exception);
        }
    }

    CaseDiagnostic diagnose(Case benchmarkCase, CaseResult atlasCase) {
        OracleEvidence oracle = atlasCase.production().oracle();
        SearchEvidence scalar = atlasCase.production().scalar();
        if (!oracle.reachable()) {
            CaseStatus status = oracle.inconclusive()
                ? CaseStatus.ORACLE_BUDGET_INCONCLUSIVE
                : oracle.completeClosureExhausted()
                    ? CaseStatus.ORACLE_COMPLETE_CLOSURE_WITHOUT_WITNESS
                    : CaseStatus.ORACLE_NOT_EVALUATED;
            return notApplicable(benchmarkCase, status, oracle, scalar);
        }
        if (benchmarkCase.relation() == Relation.NOT_EQUIVALENT) {
            return notApplicable(
                benchmarkCase,
                CaseStatus.CORRECTNESS_REGRESSION_WITNESS,
                oracle,
                scalar);
        }
        if (scalar.reached()) {
            return new CaseDiagnostic(
                benchmarkCase.id(),
                CaseStatus.SCALAR_ALREADY_FOUND,
                oracle.status(),
                oracle.witnessExpressions().size(),
                oracle.witnessExpressions().size(),
                TargetBlindTerminalStatus.SCALAR_FOUND,
                scalar.exploredStates(),
                scalar.engineCalls(),
                scalar.generatedTransformations(),
                null,
                "the retained target-blind scalar search already reached the relation");
        }
        validateWitness(benchmarkCase, oracle);

        CountingEngine counting = new CountingEngine(
            engineFactory.apply(benchmarkCase));
        TraceCollector trace = new TraceCollector();
        SearchProblem problem = searchProblem(
            benchmarkCase,
            atlasCase.representation().formattedSource(),
            counting).withObserver(trace);
        if (problem.target() != null) {
            throw new IllegalStateException(
                "witness comparison search must remain target-blind");
        }
        GoalSearchResult rerun = new BestFirstSearchStrategy()
            .searchWithDiagnostics(problem);
        requireSameScalarRun(scalar, rerun, counting);
        return analyzer.analyze(
            benchmarkCase,
            oracle,
            rerun,
            trace.events(),
            counting.calls(),
            counting.generated());
    }

    private CaseDiagnostic notApplicable(
        Case benchmarkCase,
        CaseStatus status,
        OracleEvidence oracle,
        SearchEvidence scalar
    ) {
        return CaseDiagnostic.notApplicable(
            benchmarkCase.id(),
            status,
            oracle.status(),
            scalar.exploredStates(),
            scalar.engineCalls(),
            scalar.generatedTransformations());
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
        CountingEngine counting
    ) {
        if (retained.exploredStates() != rerun.states().size()
                || retained.engineCalls() != counting.calls()
                || retained.generatedTransformations() != counting.generated()
                || !Objects.equals(retained.metrics(), rerun.metrics())) {
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
        if (!corpus.schema().equals(atlas.corpusSchema())
                || !corpus.contentSha256().equals(atlas.corpusSha256())
                || !corpus.inventoryRevision().equals(atlas.inventoryRevision())
                || !corpus.claimBoundary().equals(atlas.claimBoundary())
                || corpus.cases().size() != atlas.cases().size()) {
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

    public record WrittenArtifact(Path path, String contentHash, long byteLength) {
        public WrittenArtifact {
            path = Objects.requireNonNull(path, "path")
                .toAbsolutePath().normalize();
            if (contentHash == null
                    || !contentHash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "contentHash must be prefixed SHA-256");
            }
            if (byteLength < 1) {
                throw new IllegalArgumentException("byteLength must be positive");
            }
        }
    }

    private static final class TraceCollector implements SearchObserver {
        private final List<SearchEvent> events = new ArrayList<>();

        @Override
        public void onEvent(SearchEvent event) {
            events.add(Objects.requireNonNull(event, "event"));
        }

        private List<SearchEvent> events() {
            return List.copyOf(events);
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
