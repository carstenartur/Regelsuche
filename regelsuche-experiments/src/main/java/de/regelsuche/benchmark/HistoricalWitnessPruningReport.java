package de.regelsuche.benchmark;

import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.AtlasReport;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import de.regelsuche.json.JsonWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Content-addressed result of the historical witness-prefix diagnosis. */
public record HistoricalWitnessPruningReport(
    String corpusSha256,
    String atlasSha256,
    String inventoryRevision,
    List<CaseDiagnostic> cases,
    Summary summary,
    String contentHash
) {
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

    public HistoricalWitnessPruningReport {
        requireRawSha256(corpusSha256, "corpusSha256");
        requirePrefixedSha256(atlasSha256, "atlasSha256");
        inventoryRevision = requireText(inventoryRevision, "inventoryRevision");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        Objects.requireNonNull(summary, "summary");
        if (cases.isEmpty() || summary.caseCount() != cases.size()) {
            throw new IllegalArgumentException("report case accounting differs");
        }
        List<String> ids = cases.stream().map(CaseDiagnostic::id).toList();
        if (!ids.equals(ids.stream().sorted().toList())
                || new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException(
                "report cases must have unique canonical ordering");
        }
        requirePrefixedSha256(contentHash, "contentHash");
        String expected = reportHash(
            corpusSha256, atlasSha256, inventoryRevision, cases, summary);
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "witness-pruning contentHash mismatch");
        }
    }

    static HistoricalWitnessPruningReport create(
        Corpus corpus,
        AtlasReport atlas,
        List<CaseDiagnostic> cases
    ) {
        Summary summary = Summary.derive(cases);
        String atlasHash = sha256(atlas.toJson());
        String hash = reportHash(
            corpus.contentSha256(),
            atlasHash,
            corpus.inventoryRevision(),
            cases,
            summary);
        return new HistoricalWitnessPruningReport(
            corpus.contentSha256(),
            atlasHash,
            corpus.inventoryRevision(),
            cases,
            summary,
            hash);
    }

    public String schema() {
        return SCHEMA;
    }

    public String toCanonicalJson() {
        return render(
            corpusSha256,
            atlasSha256,
            inventoryRevision,
            cases,
            summary,
            contentHash);
    }

    private static String reportHash(
        String corpusSha256,
        String atlasSha256,
        String inventoryRevision,
        List<CaseDiagnostic> cases,
        Summary summary
    ) {
        return sha256(render(
            corpusSha256,
            atlasSha256,
            inventoryRevision,
            cases,
            summary,
            null));
    }

    private static String render(
        String corpusSha256,
        String atlasSha256,
        String inventoryRevision,
        List<CaseDiagnostic> cases,
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
        writer.property("inventoryRevision", inventoryRevision);
        writer.property("searchPolicy", SEARCH_POLICY);
        writer.property("claimBoundary", CLAIM_BOUNDARY);
        writer.array("cases", array -> cases.forEach(value ->
            array.objectValue(object -> writeCase(object, value))));
        writer.object("summary", object -> writeSummary(object, summary));
        if (contentHash != null) {
            writer.property("contentHash", contentHash);
        }
        return writer.endObject().toString();
    }

    private static void writeCase(JsonWriter writer, CaseDiagnostic value) {
        writer.property("id", value.id());
        writer.property("status", value.status().name());
        writer.property("oracleStatus", value.oracleStatus());
        writer.property("witnessStepCount", value.witnessStepCount());
        writer.property("exploredPrefixLength", value.exploredPrefixLength());
        writer.property(
            "searchTerminalStatus", value.searchTerminalStatus().name());
        writer.property("searchExploredStates", value.searchExploredStates());
        writer.property("engineCalls", value.engineCalls());
        writer.property(
            "generatedTransformations", value.generatedTransformations());
        if (value.firstLoss() == null) {
            writer.nullProperty("firstLoss");
        } else {
            writer.object("firstLoss", object -> writeLoss(object, value.firstLoss()));
        }
        writer.property("detail", value.detail());
    }

    private static void writeLoss(JsonWriter writer, LostStep value) {
        writer.property("index", value.index());
        writer.property("expressionBefore", value.expressionBefore());
        writer.property("expressionAfter", value.expressionAfter());
        writer.property("ruleId", value.ruleId());
        writer.property("reason", value.reason().name());
        if (value.event() == null) {
            writer.nullProperty("event");
        } else {
            writer.object("event", object -> writeEvent(object, value.event()));
        }
        writer.property("detail", value.detail());
    }

    private static void writeEvent(JsonWriter writer, EventSnapshot value) {
        writer.property("type", value.type());
        writer.property("sequence", value.sequence());
        writer.property("depth", value.depth());
        writer.property("score", value.score());
        writer.property("frontierSize", value.frontierSize());
        writer.property("visitedCount", value.visitedCount());
        writer.property("generatedCount", value.generatedCount());
        writer.property("pruningReason", value.pruningReason());
    }

    private static void writeSummary(JsonWriter writer, Summary value) {
        writer.property("caseCount", value.caseCount());
        writer.object("statusCounts", counts -> writeCounts(
            counts, value.statusCounts()));
        writer.object("firstLossCounts", counts -> writeCounts(
            counts, value.firstLossCounts()));
    }

    private static <E extends Enum<E>> void writeCounts(
        JsonWriter writer,
        Map<E, Integer> counts
    ) {
        counts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> writer.property(
                entry.getKey().name(), entry.getValue()));
    }

    public enum CaseStatus {
        ORACLE_NOT_EVALUATED,
        ORACLE_COMPLETE_CLOSURE_WITHOUT_WITNESS,
        ORACLE_BUDGET_INCONCLUSIVE,
        SCALAR_ALREADY_FOUND,
        WITNESS_PREFIX_LOST,
        WITNESS_COMPLETELY_EXPLORED,
        CORRECTNESS_REGRESSION_WITNESS
    }

    public enum LossReason {
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
        PARENT_NOT_REACHED
    }

    public enum TargetBlindTerminalStatus {
        NOT_EVALUATED,
        SCALAR_FOUND,
        STATE_BUDGET,
        CANDIDATE_BUDGET,
        DEPTH_BUDGET,
        NO_TRANSFORMATIONS,
        FRONTIER_EXHAUSTED
    }

    public record EventSnapshot(
        String type,
        long sequence,
        int depth,
        int score,
        int frontierSize,
        int visitedCount,
        int generatedCount,
        String pruningReason
    ) {
        public EventSnapshot {
            type = type == null ? "" : type;
            pruningReason = pruningReason == null ? "" : pruningReason;
        }
    }

    public record LostStep(
        int index,
        String expressionBefore,
        String expressionAfter,
        String ruleId,
        LossReason reason,
        EventSnapshot event,
        String detail
    ) {
        public LostStep {
            if (index < 0) {
                throw new IllegalArgumentException("loss index must not be negative");
            }
            expressionBefore = requireText(expressionBefore, "expressionBefore");
            expressionAfter = requireText(expressionAfter, "expressionAfter");
            ruleId = requireText(ruleId, "ruleId");
            Objects.requireNonNull(reason, "reason");
            detail = detail == null ? "" : detail;
        }
    }

    public record CaseDiagnostic(
        String id,
        CaseStatus status,
        String oracleStatus,
        int witnessStepCount,
        int exploredPrefixLength,
        TargetBlindTerminalStatus searchTerminalStatus,
        int searchExploredStates,
        int engineCalls,
        long generatedTransformations,
        LostStep firstLoss,
        String detail
    ) {
        public CaseDiagnostic {
            id = requireText(id, "id");
            Objects.requireNonNull(status, "status");
            oracleStatus = requireText(oracleStatus, "oracleStatus");
            Objects.requireNonNull(searchTerminalStatus, "searchTerminalStatus");
            detail = detail == null ? "" : detail;
            if (witnessStepCount < 0 || exploredPrefixLength < 0
                    || exploredPrefixLength > witnessStepCount
                    || searchExploredStates < 0 || engineCalls < 0
                    || generatedTransformations < 0) {
                throw new IllegalArgumentException(
                    "case diagnostic counters are outside their ranges");
            }
            if ((status == CaseStatus.WITNESS_PREFIX_LOST) != (firstLoss != null)) {
                throw new IllegalArgumentException(
                    "first loss must exist exactly for WITNESS_PREFIX_LOST");
            }
        }

        static CaseDiagnostic notApplicable(
            String id,
            CaseStatus status,
            String oracleStatus,
            int exploredStates,
            int engineCalls,
            long generatedTransformations
        ) {
            return new CaseDiagnostic(
                id,
                status,
                oracleStatus,
                0,
                0,
                TargetBlindTerminalStatus.NOT_EVALUATED,
                exploredStates,
                engineCalls,
                generatedTransformations,
                null,
                "no target-aware production witness is available for prefix diagnosis");
        }
    }

    public record Summary(
        int caseCount,
        Map<CaseStatus, Integer> statusCounts,
        Map<LossReason, Integer> firstLossCounts
    ) {
        public Summary {
            if (caseCount < 1) {
                throw new IllegalArgumentException("caseCount must be positive");
            }
            statusCounts = Map.copyOf(
                Objects.requireNonNull(statusCounts, "statusCounts"));
            firstLossCounts = Map.copyOf(
                Objects.requireNonNull(firstLossCounts, "firstLossCounts"));
            if (statusCounts.values().stream()
                    .mapToInt(Integer::intValue).sum() != caseCount) {
                throw new IllegalArgumentException(
                    "witness-pruning status accounting is unbalanced");
            }
        }

        private static Summary derive(List<CaseDiagnostic> cases) {
            EnumMap<CaseStatus, Integer> statuses =
                new EnumMap<>(CaseStatus.class);
            EnumMap<LossReason, Integer> losses =
                new EnumMap<>(LossReason.class);
            for (CaseDiagnostic value : cases) {
                statuses.merge(value.status(), 1, Integer::sum);
                if (value.firstLoss() != null) {
                    losses.merge(value.firstLoss().reason(), 1, Integer::sum);
                }
            }
            return new Summary(cases.size(), statuses, losses);
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
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
