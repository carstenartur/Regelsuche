package de.regelsuche.mining;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.OpenTargetConjectureMiner.ConvergenceEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.MiningReport;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureMiner.PathEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.RejectedCluster;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Canonical, target-free evidence envelope for open-target conjecture mining. */
public final class OpenTargetConjectureEvidence {
    private final CampaignContext context;
    private final MiningReport report;

    public OpenTargetConjectureEvidence(CampaignContext context, MiningReport report) {
        this.context = Objects.requireNonNull(context, "context");
        this.report = Objects.requireNonNull(report, "report");
        validate();
    }

    public CampaignContext context() {
        return context;
    }

    public MiningReport report() {
        return report;
    }

    /** Hash of the canonical payload before the hash property itself is added. */
    public String contentHash() {
        return "sha256:" + sha256(render(null));
    }

    public String toJson() {
        return render(contentHash());
    }

    public Path write(Path output) {
        Objects.requireNonNull(output, "output");
        try {
            Path parent = output.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output, toJson(), StandardCharsets.UTF_8);
            return output;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void validate() {
        if (!OpenTargetConjectureMiner.SCHEMA.equals(report.schema())) {
            throw new IllegalArgumentException("incompatible open-target mining schema");
        }
        if (report.targetProvided()) {
            throw new IllegalArgumentException("open-target evidence must not contain a target");
        }
        Set<String> provenanceIds = context.seeds().stream()
            .map(SeedProvenance::observationId)
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> reportIds = reportObservationIds();
        if (!provenanceIds.equals(reportIds)) {
            throw new IllegalArgumentException(
                "seed provenance does not match report observations: "
                    + provenanceIds + " != " + reportIds);
        }
        for (OpenTargetConjecture conjecture : report.conjectures()) {
            if (conjecture.supportCount() != conjecture.evidence().size()) {
                throw new IllegalArgumentException("conjecture support count is inconsistent");
            }
            Set<String> evidenceIds = conjecture.evidence().stream()
                .map(ConvergenceEvidence::observationId)
                .collect(Collectors.toCollection(TreeSet::new));
            if (!evidenceIds.equals(new TreeSet<>(conjecture.supportingObservationIds()))) {
                throw new IllegalArgumentException("conjecture observation IDs are inconsistent");
            }
            long distinctAlpha = conjecture.evidence().stream()
                .map(ConvergenceEvidence::alphaPairFingerprint)
                .distinct()
                .count();
            if (distinctAlpha != conjecture.distinctAlphaSupport()) {
                throw new IllegalArgumentException("conjecture alpha-support count is inconsistent");
            }
            if (conjecture.evidence().stream()
                    .anyMatch(item -> item.searchStatus() != GoalStatus.UNTARGETED)) {
                throw new IllegalArgumentException("targeted evidence entered an open-target conjecture");
            }
        }
    }

    private Set<String> reportObservationIds() {
        Set<String> result = new TreeSet<>();
        report.conjectures().stream()
            .flatMap(conjecture -> conjecture.evidence().stream())
            .map(ConvergenceEvidence::observationId)
            .forEach(result::add);
        report.rejectedClusters().stream()
            .flatMap(cluster -> cluster.observationIds().stream())
            .forEach(result::add);
        return result;
    }

    private String render(String contentHash) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", OpenTargetConjectureMiner.SCHEMA)
            .property("targetProvided", false);
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        json.object("campaign", this::writeCampaign)
            .object("summary", summary -> summary
                .property("observations", context.seeds().size())
                .property("conjectures", report.conjectures().size())
                .property("rejectedClusters", report.rejectedClusters().size())
                .property("supportingObservations", report.conjectures().stream()
                    .mapToInt(OpenTargetConjecture::supportCount).sum())
                .property("postHocFamilies", report.conjectures().stream()
                    .flatMap(item -> item.postHocFamilies().stream()).distinct().count()))
            .array("conjectures", array -> report.conjectures().forEach(conjecture ->
                array.objectValue(object -> writeConjecture(object, conjecture))))
            .array("rejectedClusters", array -> report.rejectedClusters().forEach(cluster ->
                array.objectValue(object -> writeRejected(object, cluster))))
            .endObject();
        return json.toString();
    }

    private void writeCampaign(JsonWriter json) {
        json.property("campaignId", context.campaignId())
            .property("producerVersion", context.producerVersion())
            .property("softwareRevision", context.softwareRevision())
            .property("ruleInventoryHash", context.ruleInventoryHash())
            .object("budget", budget -> writeBudget(budget, context.budget()))
            .array("seeds", array -> context.seeds().forEach(seed ->
                array.objectValue(object -> writeSeed(object, seed))));
    }

    private static void writeBudget(JsonWriter json, SearchHeuristic budget) {
        json.property("maxDepth", budget.maxDepth())
            .property("maxVisitedExpressions", budget.maxVisitedExpressions())
            .property("significantImprovementThreshold", budget.significantImprovementThreshold())
            .property("maxExpandingSteps", budget.maxExpandingSteps())
            .property("maxCandidatesPerState", budget.maxCandidatesPerState())
            .property("beamWidth", budget.beamWidth());
    }

    private static void writeSeed(JsonWriter json, SeedProvenance seed) {
        json.property("observationId", seed.observationId())
            .property("seedId", seed.seedId())
            .property("generatorId", seed.generatorId())
            .object("generatorParameters", parameters ->
                seed.generatorParameters().forEach(parameters::property));
    }

    private static void writeConjecture(JsonWriter json, OpenTargetConjecture conjecture) {
        json.property("conjectureId", conjecture.conjectureId())
            .property("leftPattern", conjecture.leftPattern())
            .property("rightPattern", conjecture.rightPattern())
            .property("supportCount", conjecture.supportCount())
            .property("distinctAlphaSupport", conjecture.distinctAlphaSupport())
            .property("candidateStatus", conjecture.candidateStatus())
            .property("evidenceStatus", conjecture.evidenceStatus())
            .property("validationStatus", "NOT_EVALUATED")
            .property("proofStatus", "NOT_EVALUATED")
            .property("noveltyStatus", "NOT_EVALUATED")
            .stringArray("postHocFamilies", conjecture.postHocFamilies())
            .stringArray("supportingObservationIds", conjecture.supportingObservationIds())
            .stringArray("parameterRelations", conjecture.parameterRelations())
            .object("expressionPlaceholderValues", values ->
                new TreeMap<>(conjecture.expressionPlaceholderValues())
                    .forEach(values::stringArray))
            .array("evidence", array -> conjecture.evidence().forEach(item ->
                array.objectValue(object -> writeEvidence(object, item))));
    }

    private static void writeEvidence(JsonWriter json, ConvergenceEvidence evidence) {
        json.property("observationId", evidence.observationId())
            .property("postHocFamily", evidence.family())
            .property("searchStatus", evidence.searchStatus().name())
            .property("inputExpression", evidence.inputExpression())
            .property("outputExpression", evidence.outputExpression())
            .property("canonicalOutputHash", evidence.canonicalOutputHash())
            .property("scoreImprovement", evidence.scoreImprovement())
            .property("alphaPairFingerprint", evidence.alphaPairFingerprint())
            .property("valuePairFingerprint", evidence.valuePairFingerprint())
            .property("pathCompetitionSignature", evidence.pathCompetitionSignature())
            .array("paths", array -> evidence.paths().forEach(path ->
                array.objectValue(object -> writePath(object, path))));
    }

    private static void writePath(JsonWriter json, PathEvidence path) {
        json.property("pathId", path.pathId())
            .property("depth", path.depth())
            .property("finalScore", path.finalScore())
            .stringArray("expressions", path.expressions())
            .stringArray("ruleIds", path.ruleIds())
            .stringArray("assumptions", path.assumptions());
    }

    private static void writeRejected(JsonWriter json, RejectedCluster cluster) {
        json.property("clusterSignature", cluster.clusterSignature())
            .property("supportCount", cluster.supportCount())
            .property("distinctAlphaSupport", cluster.distinctAlphaSupport())
            .property("reason", cluster.reason())
            .stringArray("observationIds", cluster.observationIds());
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record CampaignContext(
        String campaignId,
        String producerVersion,
        String softwareRevision,
        String ruleInventoryHash,
        SearchHeuristic budget,
        List<SeedProvenance> seeds
    ) {
        public CampaignContext {
            requireText(campaignId, "campaignId");
            requireText(producerVersion, "producerVersion");
            requireText(softwareRevision, "softwareRevision");
            requireText(ruleInventoryHash, "ruleInventoryHash");
            Objects.requireNonNull(budget, "budget");
            seeds = seeds == null ? List.of() : seeds.stream()
                .sorted(java.util.Comparator.comparing(SeedProvenance::observationId))
                .toList();
            if (seeds.isEmpty()) {
                throw new IllegalArgumentException("at least one seed provenance entry is required");
            }
            long distinct = seeds.stream().map(SeedProvenance::observationId).distinct().count();
            if (distinct != seeds.size()) {
                throw new IllegalArgumentException("observation IDs must be unique");
            }
        }
    }

    public record SeedProvenance(
        String observationId,
        String seedId,
        String generatorId,
        Map<String, String> generatorParameters
    ) {
        public SeedProvenance {
            requireText(observationId, "observationId");
            requireText(seedId, "seedId");
            requireText(generatorId, "generatorId");
            TreeMap<String, String> sorted = new TreeMap<>();
            if (generatorParameters != null) {
                generatorParameters.forEach((key, value) -> {
                    requireText(key, "generator parameter name");
                    requireText(value, "generator parameter value");
                    sorted.put(key, value);
                });
            }
            generatorParameters = Collections.unmodifiableMap(sorted);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
