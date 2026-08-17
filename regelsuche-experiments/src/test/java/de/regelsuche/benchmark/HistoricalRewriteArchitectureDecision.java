package de.regelsuche.benchmark;

import de.regelsuche.json.JsonReader;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Derives a fail-closed architecture decision from retained #620 evidence. */
public final class HistoricalRewriteArchitectureDecision {
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
