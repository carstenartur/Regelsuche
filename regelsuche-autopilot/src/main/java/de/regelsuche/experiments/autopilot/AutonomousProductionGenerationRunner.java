package de.regelsuche.experiments.autopilot;

import de.regelsuche.benchmark.DeterministicDiscoveryExperimentRunner;
import de.regelsuche.benchmark.DeterministicDiscoveryExperimentRunner.DiscoveryReport;
import de.regelsuche.benchmark.DeterministicDiscoveryExperimentRunner.SeedRunOutcome;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.example.SeedExpression;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.ObservationBranch;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalMetrics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Executes the pinned issue-348 generation slice through the real untargeted
 * best-first search and deterministic experiment runner.
 */
public final class AutonomousProductionGenerationRunner {
    public static final String SEED_CATALOG_SCHEMA =
        "regelsuche.autonomous-production-seed-catalog/v1";
    public static final String OBSERVATION_BUNDLE_SCHEMA =
        "regelsuche.autonomous-production-observations/v2";
    public static final String GENERATION_RECEIPT_SCHEMA =
        "regelsuche.autonomous-generation-receipt/v2";
    public static final String GENERATION_RUN_SCHEMA =
        "regelsuche.autonomous-production-generation/v2";

    public GenerationRun runPinned(int parallelism) {
        return run(
            PinnedAutonomousProductionCampaign.brief(),
            PinnedAutonomousProductionCampaign.seeds(),
            parallelism);
    }

    public GenerationRun run(
        AutonomousResearchBriefV2 brief,
        List<SeedExpression> suppliedSeeds,
        int parallelism
    ) {
        Objects.requireNonNull(brief, "brief");
        List<SeedExpression> seeds = validateAndOrderSeeds(brief, suppliedSeeds);
        SeedCatalog catalog = SeedCatalog.create(brief.contentHash(), seeds);
        ConcurrentMap<String, GoalSearchResult> searchResults =
            new ConcurrentHashMap<>();

        DeterministicDiscoveryExperimentRunner runner =
            new DeterministicDiscoveryExperimentRunner(
                seeds.size(),
                Math.max(1, parallelism),
                seed -> evaluate(seed, searchResults));
        DiscoveryReport report = runner.runDetailed(seeds);

        List<GeneratedObservation> observations = report.rows().stream()
            .map(row -> generatedObservation(
                brief,
                row.seed(),
                requireSearchResult(searchResults, row.seed())))
            .sorted(Comparator.comparing(item -> item.seed().stableKey()))
            .toList();
        if (observations.size() != seeds.size()) {
            throw new IllegalStateException(
                "every production seed must produce one immutable observation branch");
        }

        ObservationBundle bundle = ObservationBundle.create(
            brief.contentHash(), observations.stream()
                .map(GeneratedObservation::snapshot)
                .toList());
        GenerationReceipt receipt = generationReceipt(brief, observations);
        String reportHash = AutonomousResearchBrief.hash(
            report.renderDeterministicJson());
        String contentHash = AutonomousResearchBrief.hash(
            GENERATION_RUN_SCHEMA
                + "\nbrief=" + brief.contentHash()
                + "\nseedCatalog=" + catalog.contentHash()
                + "\nobservations=" + bundle.contentHash()
                + "\nreceipt=" + receipt.contentHash()
                + "\ndiscoveryReport=" + reportHash);
        return new GenerationRun(
            GENERATION_RUN_SCHEMA,
            brief,
            catalog,
            observations,
            bundle,
            receipt,
            report,
            reportHash,
            false,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    public void write(Path outputDirectory, GenerationRun run) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(run, "run");
        try {
            Files.createDirectories(outputDirectory);
            write(outputDirectory.resolve("brief-v2.json"),
                run.brief().toCanonicalJson());
            write(outputDirectory.resolve("seeds.json"),
                run.seedCatalog().toCanonicalJson());
            write(outputDirectory.resolve("observations.json"),
                run.observationBundle().toCanonicalJson());
            write(outputDirectory.resolve("generation-receipt.json"),
                run.receipt().toCanonicalJson());
            write(outputDirectory.resolve("discovery-report.json"),
                run.discoveryReport().renderDeterministicJson());
            write(outputDirectory.resolve("generation-run.json"),
                run.toCanonicalJson());
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write production generation evidence", exception);
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static SeedRunOutcome evaluate(
        SeedExpression seed,
        ConcurrentMap<String, GoalSearchResult> searchResults
    ) {
        GoalSearchResult result = search(seed);
        if (result.status() != GoalStatus.UNTARGETED) {
            throw new IllegalStateException(
                "production generation unexpectedly used a search target");
        }
        if (searchResults.putIfAbsent(seed.stableKey(), result) != null) {
            throw new IllegalStateException(
                "duplicate production seed execution: " + seed.stableKey());
        }
        SearchState selected = selectedState(result);
        boolean transformed = selected.depth() > 0;
        return new SeedRunOutcome(
            transformed,
            "untargeted production search explored "
                + result.metrics().exploredStates() + " states",
            transformed ? List.of(selected.expression()) : List.of(),
            List.of(),
            selected.path(),
            0L,
            0L);
    }

    private static GoalSearchResult search(SeedExpression seed) {
        SearchProblem problem = new SearchProblem(
            seed.expression(),
            new AstRewriteTransformationEngine(),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            PinnedAutonomousProductionCampaign.searchHeuristic());
        if (problem.target() != null) {
            throw new IllegalStateException(
                "production generation problem must not have a target");
        }
        return new BestFirstSearchStrategy().searchWithDiagnostics(problem);
    }

    private static GoalSearchResult requireSearchResult(
        Map<String, GoalSearchResult> results,
        SeedExpression seed
    ) {
        GoalSearchResult result = results.get(seed.stableKey());
        if (result == null) {
            throw new IllegalStateException(
                "missing production search result for " + seed.stableKey());
        }
        return result;
    }

    private static GeneratedObservation generatedObservation(
        AutonomousResearchBriefV2 brief,
        SeedExpression seed,
        GoalSearchResult result
    ) {
        if (result.status() != GoalStatus.UNTARGETED) {
            throw new IllegalArgumentException(
                "only UNTARGETED search results may become observation branches");
        }
        List<StateSnapshot> states = result.states().stream()
            .map(StateSnapshot::from)
            .sorted(StateSnapshot.ORDER)
            .toList();
        if (states.isEmpty()) {
            throw new IllegalStateException(
                "production search must retain at least the root state");
        }
        SearchState selected = selectedState(result);
        SearchMetricsSnapshot metrics = SearchMetricsSnapshot.from(result.metrics());
        String observationId = "observation-" + seed.stableKey();
        String branchId = "autopilot/observation/" + seed.stableKey();
        String snapshotHash = AutonomousResearchBrief.hash(
            "regelsuche.autonomous-observation-snapshot/v2"
                + "\nbrief=" + brief.contentHash()
                + "\nseed=" + seedMaterial(seed)
                + "\nstatus=" + result.status().name()
                + "\nmetrics=" + metrics.canonicalMaterial()
                + "\nstates=" + states.stream()
                    .map(StateSnapshot::canonicalMaterial).toList());
        String evidenceHash = AutonomousResearchBrief.hash(
            "regelsuche.autonomous-observation-evidence/v2"
                + "\nobservation=" + observationId
                + "\nseed=" + seedMaterial(seed)
                + "\nsnapshot=" + snapshotHash
                + "\nselected=" + selected.expression()
                + "\npath=" + selected.path()
                + "\nrules=" + selected.appliedRuleIds());
        ObservationBranch branch = ObservationBranch.create(
            branchId,
            seed.source(),
            observationId,
            snapshotHash,
            evidenceHash);
        ObservationSnapshot snapshot = new ObservationSnapshot(
            observationId,
            branchId,
            seed.source(),
            seed.id(),
            seed.expression(),
            seed.source(),
            seed.category(),
            sorted(seed.tags()),
            sorted(seed.assumptions()),
            result.status().name(),
            metrics,
            states,
            selected.expression(),
            selected.path(),
            selected.appliedRuleIds(),
            snapshotHash,
            evidenceHash,
            branch.contentHash());
        return new GeneratedObservation(seed, branch, snapshot, result);
    }

    private static SearchState selectedState(GoalSearchResult result) {
        return result.states().stream()
            .filter(state -> state.depth() > 0)
            .min(Comparator
                .comparingInt(SearchState::depth)
                .thenComparing(SearchState::expression)
                .thenComparing(state -> String.join("->", state.appliedRuleIds())))
            .orElseGet(() -> result.states().getFirst());
    }

    private static GenerationReceipt generationReceipt(
        AutonomousResearchBriefV2 brief,
        List<GeneratedObservation> observations
    ) {
        Map<ResourceKind, Long> configured = brief.budget(EvidenceStage.GENERATION)
            .resources();
        long generatedStates = observations.stream()
            .map(GeneratedObservation::searchResult)
            .map(GoalSearchResult::metrics)
            .mapToLong(GoalMetrics::generatedTransformations)
            .sum();
        long exploredStates = observations.stream()
            .map(GeneratedObservation::searchResult)
            .map(GoalSearchResult::metrics)
            .mapToLong(GoalMetrics::exploredStates)
            .sum();
        Map<ResourceKind, Long> executed = immutableResources(Map.of(
            ResourceKind.GENERATED_STATES, generatedStates,
            ResourceKind.EXPLORED_STATES, exploredStates,
            ResourceKind.OBSERVATIONS, (long) observations.size()));
        Map<ResourceKind, Long> skipped = Map.of();
        Map<ResourceKind, Long> remaining = remaining(configured, executed, skipped);
        String contentHash = AutonomousResearchBrief.hash(
            GENERATION_RECEIPT_SCHEMA
                + "\nbrief=" + brief.contentHash()
                + "\nconfigured=" + configured
                + "\nexecuted=" + executed
                + "\nskipped=" + skipped
                + "\nremaining=" + remaining
                + "\nobservationBranches=" + observations.stream()
                    .map(item -> item.branch().contentHash()).toList());
        return new GenerationReceipt(
            GENERATION_RECEIPT_SCHEMA,
            brief.contentHash(),
            EvidenceStage.GENERATION,
            "COMPLETED",
            configured,
            executed,
            skipped,
            remaining,
            observations.stream().map(item -> item.branch().branchId()).toList(),
            false,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    private static Map<ResourceKind, Long> remaining(
        Map<ResourceKind, Long> configured,
        Map<ResourceKind, Long> executed,
        Map<ResourceKind, Long> skipped
    ) {
        EnumMap<ResourceKind, Long> result = new EnumMap<>(ResourceKind.class);
        for (Map.Entry<ResourceKind, Long> entry : configured.entrySet()) {
            long consumed = Math.addExact(
                executed.getOrDefault(entry.getKey(), 0L),
                skipped.getOrDefault(entry.getKey(), 0L));
            if (consumed > entry.getValue()) {
                throw new IllegalArgumentException(
                    "production generation exceeds configured resource "
                        + entry.getKey());
            }
            long amount = entry.getValue() - consumed;
            if (amount > 0L) {
                result.put(entry.getKey(), amount);
            }
        }
        Set<ResourceKind> unexpected = new HashSet<>(executed.keySet());
        unexpected.addAll(skipped.keySet());
        unexpected.removeAll(configured.keySet());
        if (!unexpected.isEmpty()) {
            throw new IllegalArgumentException(
                "production generation reported unconfigured resources: " + unexpected);
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<SeedExpression> validateAndOrderSeeds(
        AutonomousResearchBriefV2 brief,
        List<SeedExpression> suppliedSeeds
    ) {
        Objects.requireNonNull(suppliedSeeds, "seeds");
        List<SeedExpression> seeds = suppliedSeeds.stream()
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(SeedExpression::stableKey))
            .toList();
        if (seeds.size() < PinnedAutonomousProductionCampaign.REQUIRED_OBSERVATIONS) {
            throw new IllegalArgumentException(
                "production generation requires at least twelve seeds");
        }
        if (seeds.stream().map(SeedExpression::stableKey).distinct().count()
                != seeds.size()) {
            throw new IllegalArgumentException(
                "production seed stable keys must be unique");
        }
        Set<String> allowedGenerators = Set.copyOf(brief.seedGenerators());
        Set<String> allowedDomains = Set.copyOf(brief.allowedDomains());
        for (SeedExpression seed : seeds) {
            if (seed.id().isBlank()
                    || seed.expression().isBlank()
                    || !allowedGenerators.contains(seed.source())
                    || !allowedDomains.contains(seed.category())) {
                throw new IllegalArgumentException(
                    "production seed requires a non-blank ID and must be inside the pinned target-free brief: "
                        + seed.stableKey());
            }
        }
        long families = seeds.stream().map(SeedExpression::source).distinct().count();
        if (families < brief.minimumDistinctFamilies()) {
            throw new IllegalArgumentException(
                "production seed catalog lacks independent generator families");
        }
        if (brief.budget(EvidenceStage.GENERATION)
                .configured(ResourceKind.OBSERVATIONS) < seeds.size()) {
            throw new IllegalArgumentException(
                "generation observation budget is smaller than the seed catalog");
        }
        if (!brief.inventoryHash().equals(
                PinnedAutonomousProductionCampaign.inventoryHash())) {
            throw new IllegalArgumentException(
                "production brief does not bind the active AST rule inventory");
        }
        return List.copyOf(seeds);
    }

    private static Map<ResourceKind, Long> immutableResources(
        Map<ResourceKind, Long> supplied
    ) {
        EnumMap<ResourceKind, Long> result = new EnumMap<>(ResourceKind.class);
        supplied.forEach((resource, amount) -> {
            Objects.requireNonNull(resource, "resource");
            if (amount == null || amount < 0L) {
                throw new IllegalArgumentException(
                    "resource amounts must be non-negative");
            }
            if (amount > 0L) {
                result.put(resource, amount);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static String seedMaterial(SeedExpression seed) {
        return seed.stableKey() + '|' + seed.expression() + '|' + seed.source()
            + '|' + seed.category() + '|' + sorted(seed.tags()) + '|'
            + sorted(seed.assumptions());
    }

    private static List<String> sorted(List<String> values) {
        return values == null ? List.of() : values.stream().distinct().sorted().toList();
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }

    private static void requireNotEvaluated(String value, String name) {
        if (!"NOT_EVALUATED".equals(value)) {
            throw new IllegalArgumentException(name + " must be NOT_EVALUATED");
        }
    }

    public record GenerationRun(
        String schema,
        AutonomousResearchBriefV2 brief,
        SeedCatalog seedCatalog,
        List<GeneratedObservation> observations,
        ObservationBundle observationBundle,
        GenerationReceipt receipt,
        DiscoveryReport discoveryReport,
        String discoveryReportHash,
        boolean targetProvided,
        boolean generationIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public GenerationRun {
            if (!GENERATION_RUN_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported production generation schema");
            }
            brief = Objects.requireNonNull(brief, "brief");
            seedCatalog = Objects.requireNonNull(seedCatalog, "seedCatalog");
            observations = observations == null ? List.of() : List.copyOf(observations);
            observationBundle = Objects.requireNonNull(
                observationBundle, "observationBundle");
            receipt = Objects.requireNonNull(receipt, "receipt");
            discoveryReport = Objects.requireNonNull(
                discoveryReport, "discoveryReport");
            requireSha256(discoveryReportHash, "discoveryReportHash");
            if (targetProvided || generationIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "generation metadata must remain target-free and non-mathematical");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public List<ObservationBranch> observationBranches() {
            return observations.stream().map(GeneratedObservation::branch).toList();
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("briefHash", brief.contentHash())
                .property("seedCatalogHash", seedCatalog.contentHash())
                .property("observationBundleHash", observationBundle.contentHash())
                .property("generationReceiptHash", receipt.contentHash())
                .property("discoveryReportHash", discoveryReportHash)
                .property("seedCount", seedCatalog.seeds().size())
                .property("observationCount", observations.size())
                .property("targetProvided", targetProvided)
                .property("generationIsMathematicalEvidence",
                    generationIsMathematicalEvidence)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    public record GeneratedObservation(
        SeedExpression seed,
        ObservationBranch branch,
        ObservationSnapshot snapshot,
        GoalSearchResult searchResult
    ) {
        public GeneratedObservation {
            seed = Objects.requireNonNull(seed, "seed");
            branch = Objects.requireNonNull(branch, "branch");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            searchResult = Objects.requireNonNull(searchResult, "searchResult");
            if (!branch.observationId().equals(snapshot.observationId())
                    || !branch.snapshotHash().equals(snapshot.snapshotHash())
                    || !branch.evidenceHash().equals(snapshot.evidenceHash())) {
                throw new IllegalArgumentException(
                    "generated observation snapshot does not match its branch");
            }
        }
    }

    public record SeedCatalog(
        String schema,
        String briefHash,
        List<SeedExpression> seeds,
        boolean targetProvided,
        String contentHash
    ) {
        public SeedCatalog {
            if (!SEED_CATALOG_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported production seed catalog schema");
            }
            requireSha256(briefHash, "briefHash");
            seeds = seeds == null ? List.of() : seeds.stream()
                .sorted(Comparator.comparing(SeedExpression::stableKey))
                .toList();
            if (targetProvided) {
                throw new IllegalArgumentException(
                    "production seed catalog cannot provide a target");
            }
            requireSha256(contentHash, "contentHash");
        }

        static SeedCatalog create(String briefHash, List<SeedExpression> seeds) {
            List<SeedExpression> ordered = seeds.stream()
                .sorted(Comparator.comparing(SeedExpression::stableKey))
                .toList();
            String contentHash = AutonomousResearchBrief.hash(
                SEED_CATALOG_SCHEMA + "\nbrief=" + briefHash + "\nseeds="
                    + ordered.stream()
                        .map(AutonomousProductionGenerationRunner::seedMaterial)
                        .toList());
            return new SeedCatalog(
                SEED_CATALOG_SCHEMA, briefHash, ordered, false, contentHash);
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("briefHash", briefHash)
                .property("targetProvided", targetProvided)
                .array("seeds", array -> seeds.forEach(seed ->
                    array.objectValue(object -> object
                        .property("seedId", seed.stableKey())
                        .property("expression", seed.expression())
                        .property("generator", seed.source())
                        .property("domain", seed.category())
                        .stringArray("tags", sorted(seed.tags()))
                        .stringArray("assumptions", sorted(seed.assumptions())))))
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    public record ObservationBundle(
        String schema,
        String briefHash,
        List<ObservationSnapshot> observations,
        boolean targetProvided,
        String contentHash
    ) {
        public ObservationBundle {
            if (!OBSERVATION_BUNDLE_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported production observation bundle schema");
            }
            requireSha256(briefHash, "briefHash");
            observations = observations == null ? List.of() : observations.stream()
                .sorted(Comparator.comparing(ObservationSnapshot::observationId))
                .toList();
            if (targetProvided || observations.stream()
                    .anyMatch(item -> !GoalStatus.UNTARGETED.name()
                        .equals(item.searchStatus()))) {
                throw new IllegalArgumentException(
                    "production observation bundle must remain target-free");
            }
            requireSha256(contentHash, "contentHash");
        }

        static ObservationBundle create(
            String briefHash,
            List<ObservationSnapshot> observations
        ) {
            List<ObservationSnapshot> ordered = observations.stream()
                .sorted(Comparator.comparing(ObservationSnapshot::observationId))
                .toList();
            String contentHash = AutonomousResearchBrief.hash(
                OBSERVATION_BUNDLE_SCHEMA + "\nbrief=" + briefHash
                    + "\nobservations=" + ordered.stream()
                        .map(ObservationSnapshot::canonicalMaterial).toList());
            return new ObservationBundle(
                OBSERVATION_BUNDLE_SCHEMA,
                briefHash,
                ordered,
                false,
                contentHash);
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("briefHash", briefHash)
                .property("targetProvided", targetProvided)
                .array("observations", array -> observations.forEach(item ->
                    array.objectValue(object -> object
                        .property("observationId", item.observationId())
                        .property("branchId", item.branchId())
                        .property("familyId", item.familyId())
                        .property("seedId", item.seedId())
                        .property("seedExpression", item.seedExpression())
                        .property("generator", item.generator())
                        .property("domain", item.domain())
                        .stringArray("tags", item.tags())
                        .stringArray("assumptions", item.assumptions())
                        .property("searchStatus", item.searchStatus())
                        .object("metrics", metrics -> metrics
                            .property("exploredStates",
                                item.metrics().exploredStates())
                            .property("expandedStates",
                                item.metrics().expandedStates())
                            .property("generatedTransformations",
                                item.metrics().generatedTransformations())
                            .property("enqueuedStates",
                                item.metrics().enqueuedStates()))
                        .array("states", states -> item.states().forEach(state ->
                            states.objectValue(value -> value
                                .property("expression", state.expression())
                                .property("depth", state.depth())
                                .property("canonicalHash", state.canonicalHash())
                                .stringArray("path", state.path())
                                .stringArray("appliedRuleIds",
                                    state.appliedRuleIds())
                                .stringArray("assumptions", state.assumptions()))))
                        .property("selectedExpression", item.selectedExpression())
                        .stringArray("selectedPath", item.selectedPath())
                        .stringArray("selectedRuleIds", item.selectedRuleIds())
                        .property("snapshotHash", item.snapshotHash())
                        .property("evidenceHash", item.evidenceHash())
                        .property("branchHash", item.branchHash()))))
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    public record ObservationSnapshot(
        String observationId,
        String branchId,
        String familyId,
        String seedId,
        String seedExpression,
        String generator,
        String domain,
        List<String> tags,
        List<String> assumptions,
        String searchStatus,
        SearchMetricsSnapshot metrics,
        List<StateSnapshot> states,
        String selectedExpression,
        List<String> selectedPath,
        List<String> selectedRuleIds,
        String snapshotHash,
        String evidenceHash,
        String branchHash
    ) {
        public ObservationSnapshot {
            tags = sorted(tags);
            assumptions = sorted(assumptions);
            metrics = Objects.requireNonNull(metrics, "metrics");
            states = states == null ? List.of() : states.stream()
                .sorted(StateSnapshot.ORDER)
                .toList();
            selectedPath = selectedPath == null ? List.of() : List.copyOf(selectedPath);
            selectedRuleIds = selectedRuleIds == null
                ? List.of()
                : List.copyOf(selectedRuleIds);
            requireSha256(snapshotHash, "snapshotHash");
            requireSha256(evidenceHash, "evidenceHash");
            requireSha256(branchHash, "branchHash");
        }

        String canonicalMaterial() {
            return observationId + '|' + branchId + '|' + familyId + '|'
                + seedId + '|' + seedExpression + '|' + generator + '|'
                + domain + '|' + tags + '|' + assumptions + '|' + searchStatus
                + '|' + metrics.canonicalMaterial() + '|'
                + states.stream().map(StateSnapshot::canonicalMaterial).toList()
                + '|' + selectedExpression + '|' + selectedPath + '|'
                + selectedRuleIds + '|' + snapshotHash + '|' + evidenceHash
                + '|' + branchHash;
        }
    }

    public record StateSnapshot(
        String expression,
        int depth,
        String canonicalHash,
        List<String> path,
        List<String> appliedRuleIds,
        List<String> assumptions
    ) {
        static final Comparator<StateSnapshot> ORDER = Comparator
            .comparingInt(StateSnapshot::depth)
            .thenComparing(StateSnapshot::expression)
            .thenComparing(item -> String.join("->", item.appliedRuleIds()))
            .thenComparing(item -> String.join("->", item.path()));

        public StateSnapshot {
            path = path == null ? List.of() : List.copyOf(path);
            appliedRuleIds = appliedRuleIds == null
                ? List.of()
                : List.copyOf(appliedRuleIds);
            assumptions = sorted(assumptions);
        }

        static StateSnapshot from(SearchState state) {
            return new StateSnapshot(
                state.expression(),
                state.depth(),
                state.canonicalHash(),
                state.path(),
                state.appliedRuleIds(),
                state.assumptions());
        }

        String canonicalMaterial() {
            return expression + '|' + depth + '|' + canonicalHash + '|'
                + path + '|' + appliedRuleIds + '|' + assumptions;
        }
    }

    public record SearchMetricsSnapshot(
        int exploredStates,
        int expandedStates,
        int generatedTransformations,
        int enqueuedStates
    ) {
        static SearchMetricsSnapshot from(GoalMetrics metrics) {
            return new SearchMetricsSnapshot(
                metrics.exploredStates(),
                metrics.expandedStates(),
                metrics.generatedTransformations(),
                metrics.enqueuedStates());
        }

        String canonicalMaterial() {
            return exploredStates + "|" + expandedStates + "|"
                + generatedTransformations + "|" + enqueuedStates;
        }
    }

    public record GenerationReceipt(
        String schema,
        String briefHash,
        EvidenceStage stage,
        String disposition,
        Map<ResourceKind, Long> configuredResources,
        Map<ResourceKind, Long> executedResources,
        Map<ResourceKind, Long> skippedResources,
        Map<ResourceKind, Long> remainingResources,
        List<String> observationBranchIds,
        boolean targetProvided,
        boolean receiptIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public GenerationReceipt {
            if (!GENERATION_RECEIPT_SCHEMA.equals(schema)
                    || stage != EvidenceStage.GENERATION
                    || !"COMPLETED".equals(disposition)) {
                throw new IllegalArgumentException(
                    "unsupported or incomplete production generation receipt");
            }
            requireSha256(briefHash, "briefHash");
            configuredResources = immutableResources(configuredResources);
            executedResources = immutableResources(executedResources);
            skippedResources = immutableResources(skippedResources);
            remainingResources = immutableResources(remainingResources);
            observationBranchIds = observationBranchIds == null
                ? List.of()
                : observationBranchIds.stream().distinct().sorted().toList();
            validateBalance(
                configuredResources,
                executedResources,
                skippedResources,
                remainingResources);
            if (executedResources.getOrDefault(ResourceKind.OBSERVATIONS, 0L)
                    != observationBranchIds.size()) {
                throw new IllegalArgumentException(
                    "observation execution count must match retained branches");
            }
            if (targetProvided || receiptIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "generation receipt must remain target-free and non-mathematical");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public long executed(ResourceKind resource) {
            return executedResources.getOrDefault(resource, 0L);
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("briefHash", briefHash)
                .property("stage", stage.name())
                .property("disposition", disposition)
                .array("resources", array -> configuredResources.keySet().stream()
                    .sorted()
                    .forEach(resource -> array.objectValue(object -> object
                        .property("resource", resource.name())
                        .property("configured",
                            configuredResources.getOrDefault(resource, 0L))
                        .property("executed",
                            executedResources.getOrDefault(resource, 0L))
                        .property("skipped",
                            skippedResources.getOrDefault(resource, 0L))
                        .property("remaining",
                            remainingResources.getOrDefault(resource, 0L)))))
                .stringArray("observationBranchIds", observationBranchIds)
                .property("targetProvided", targetProvided)
                .property("receiptIsMathematicalEvidence",
                    receiptIsMathematicalEvidence)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }

        private static void validateBalance(
            Map<ResourceKind, Long> configured,
            Map<ResourceKind, Long> executed,
            Map<ResourceKind, Long> skipped,
            Map<ResourceKind, Long> remaining
        ) {
            Set<ResourceKind> resources = new TreeSet<>();
            resources.addAll(configured.keySet());
            resources.addAll(executed.keySet());
            resources.addAll(skipped.keySet());
            resources.addAll(remaining.keySet());
            for (ResourceKind resource : resources) {
                long accounted = Math.addExact(
                    Math.addExact(
                        executed.getOrDefault(resource, 0L),
                        skipped.getOrDefault(resource, 0L)),
                    remaining.getOrDefault(resource, 0L));
                if (configured.getOrDefault(resource, 0L) != accounted) {
                    throw new IllegalArgumentException(
                        "unbalanced production generation resource " + resource);
                }
            }
        }
    }
}
