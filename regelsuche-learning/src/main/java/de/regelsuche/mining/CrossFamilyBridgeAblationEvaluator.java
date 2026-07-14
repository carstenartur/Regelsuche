package de.regelsuche.mining;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.CrossFamilyBridgeHypothesisBuilder.BridgeHypothesis;
import de.regelsuche.mining.CrossFamilyBridgeTransferEvaluator.FamilyRole;
import de.regelsuche.mining.CrossFamilyBridgeTransferEvaluator.TransferReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Computes paired utility evidence for an already validated cross-family bridge.
 *
 * <p>The evaluator never changes the bridge relation. It compares the same fresh
 * family tasks with and without the bridge and requires a material held-out-family
 * gain without regressions before reporting a beneficial ablation.</p>
 */
public final class CrossFamilyBridgeAblationEvaluator {
    public static final String SCHEMA = "regelsuche.cross-family-bridge-ablation/v1";

    public AblationReport evaluate(
        BridgeHypothesis hypothesis,
        TransferReport transfer,
        List<FamilyAblation> observations
    ) {
        Objects.requireNonNull(hypothesis, "hypothesis");
        Objects.requireNonNull(transfer, "transfer");
        List<FamilyAblation> ordered = orderedObservations(observations);
        ensureUniqueFamilies(ordered);

        List<String> blockers = new ArrayList<>(identityBlockers(hypothesis, transfer));
        Map<String, FamilyRole> expected = expectedFamilies(transfer, blockers);
        Map<String, FamilyAblation> supplied = suppliedByFamily(ordered, expected, blockers);
        List<FamilyAblationResult> results = expected.entrySet().stream()
            .map(entry -> resultFor(entry.getKey(), entry.getValue(), supplied.get(entry.getKey())))
            .sorted(Comparator.comparing(FamilyAblationResult::familyId))
            .toList();

        AblationStatus status = overallStatus(transfer, blockers, results);
        List<String> orderedBlockers = blockers.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .sorted()
            .toList();
        String contentHash = hash(canonicalMaterial(
            hypothesis, transfer, status, results, orderedBlockers));
        return new AblationReport(
            SCHEMA,
            hypothesis.hypothesisId(),
            hypothesis.formationHash(),
            transfer.contentHash(),
            transfer.trainingFamilies(),
            transfer.heldOutFamilies(),
            status,
            results,
            orderedBlockers,
            contentHash);
    }

    private static List<String> identityBlockers(
        BridgeHypothesis hypothesis,
        TransferReport transfer
    ) {
        List<String> blockers = new ArrayList<>();
        if (!hypothesis.hypothesisId().equals(transfer.hypothesisId())) {
            blockers.add("hypothesis/transfer identity mismatch");
        }
        if (!hypothesis.sourceClusterId().equals(transfer.sourceClusterId())) {
            blockers.add("source cluster mismatch");
        }
        if (!hypothesis.formationHash().equals(transfer.formationHash())) {
            blockers.add("formation hash mismatch");
        }
        if (!hypothesis.trainingFamilies().equals(transfer.trainingFamilies())) {
            blockers.add("training-family provenance mismatch");
        }
        if (!transfer.accepted()) {
            blockers.add("cross-family transfer is not accepted");
        }
        return List.copyOf(blockers);
    }

    private static Map<String, FamilyRole> expectedFamilies(
        TransferReport transfer,
        List<String> blockers
    ) {
        Map<String, FamilyRole> expected = new TreeMap<>();
        transfer.trainingFamilies().forEach(family ->
            expected.put(family, FamilyRole.FORMATION));
        for (String family : transfer.heldOutFamilies()) {
            FamilyRole previous = expected.putIfAbsent(family, FamilyRole.HELD_OUT);
            if (previous != null) {
                blockers.add("family occurs in both formation and held-out sets: " + family);
            }
        }
        if (transfer.heldOutFamilies().isEmpty()) {
            blockers.add("held-out family is missing");
        }
        return expected;
    }

    private static Map<String, FamilyAblation> suppliedByFamily(
        List<FamilyAblation> observations,
        Map<String, FamilyRole> expected,
        List<String> blockers
    ) {
        Map<String, FamilyAblation> supplied = new LinkedHashMap<>();
        for (FamilyAblation observation : observations) {
            if (!expected.containsKey(observation.familyId())) {
                blockers.add("unexpected ablation family: " + observation.familyId());
                continue;
            }
            supplied.put(observation.familyId(), observation);
        }
        return supplied;
    }

    private static FamilyAblationResult resultFor(
        String familyId,
        FamilyRole expectedRole,
        FamilyAblation observation
    ) {
        if (observation == null) {
            return FamilyAblationResult.incomplete(
                familyId, expectedRole, "paired family ablation is missing");
        }
        if (observation.role() != expectedRole) {
            return FamilyAblationResult.incomplete(
                familyId, expectedRole, "family role does not match transfer evidence");
        }
        FamilyAblationStatus status = classify(
            observation.withBridge(), observation.withoutBridge());
        return new FamilyAblationResult(
            familyId,
            expectedRole,
            status,
            observation.withBridge(),
            observation.withoutBridge(),
            delta(observation.withoutBridge().pathLength(),
                observation.withBridge().pathLength()),
            delta(observation.withoutBridge().statesExplored(),
                observation.withBridge().statesExplored()),
            explanation(status));
    }

    private static FamilyAblationStatus classify(
        RunEvidence withBridge,
        RunEvidence withoutBridge
    ) {
        if (!withBridge.successKnown() || !withoutBridge.successKnown()) {
            return FamilyAblationStatus.INCOMPLETE;
        }
        if (!withBridge.successful()) {
            return FamilyAblationStatus.REGRESSION;
        }
        if (!withoutBridge.successful()) {
            return FamilyAblationStatus.MATERIAL_GAIN;
        }
        boolean pathKnown = withBridge.pathLength() >= 0 && withoutBridge.pathLength() >= 0;
        boolean statesKnown = withBridge.statesExplored() >= 0
            && withoutBridge.statesExplored() >= 0;
        if (!pathKnown && !statesKnown) {
            return FamilyAblationStatus.INCOMPLETE;
        }
        boolean gain = pathKnown && withBridge.pathLength() < withoutBridge.pathLength()
            || statesKnown && withBridge.statesExplored() < withoutBridge.statesExplored();
        boolean loss = pathKnown && withBridge.pathLength() > withoutBridge.pathLength()
            || statesKnown && withBridge.statesExplored() > withoutBridge.statesExplored();
        if (gain && loss) {
            return FamilyAblationStatus.TRADE_OFF;
        }
        if (gain) {
            return FamilyAblationStatus.MATERIAL_GAIN;
        }
        if (loss) {
            return FamilyAblationStatus.REGRESSION;
        }
        return FamilyAblationStatus.UNCHANGED;
    }

    private static AblationStatus overallStatus(
        TransferReport transfer,
        List<String> blockers,
        List<FamilyAblationResult> results
    ) {
        if (!transfer.accepted()) {
            return AblationStatus.NOT_ELIGIBLE_TRANSFER;
        }
        if (!blockers.isEmpty()
                || results.stream().anyMatch(result ->
                    result.status() == FamilyAblationStatus.INCOMPLETE)) {
            return AblationStatus.INCOMPLETE_EVIDENCE;
        }
        if (results.stream().anyMatch(result ->
                result.status() == FamilyAblationStatus.REGRESSION)) {
            return AblationStatus.REGRESSION;
        }
        if (results.stream().anyMatch(result ->
                result.status() == FamilyAblationStatus.TRADE_OFF)) {
            return AblationStatus.TRADE_OFF;
        }
        boolean heldOutGain = results.stream().anyMatch(result ->
            result.role() == FamilyRole.HELD_OUT
                && result.status() == FamilyAblationStatus.MATERIAL_GAIN);
        return heldOutGain
            ? AblationStatus.BENEFICIAL_HELD_OUT
            : AblationStatus.NO_HELD_OUT_GAIN;
    }

    private static List<FamilyAblation> orderedObservations(
        List<FamilyAblation> observations
    ) {
        return observations == null
            ? List.of()
            : observations.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(FamilyAblation::familyId))
                .toList();
    }

    private static void ensureUniqueFamilies(List<FamilyAblation> observations) {
        Set<String> seen = new TreeSet<>();
        for (FamilyAblation observation : observations) {
            if (!seen.add(observation.familyId())) {
                throw new IllegalArgumentException(
                    "duplicate family ablation: " + observation.familyId());
            }
        }
    }

    private static int delta(int withoutBridge, int withBridge) {
        return withoutBridge < 0 || withBridge < 0 ? 0 : withoutBridge - withBridge;
    }

    private static long delta(long withoutBridge, long withBridge) {
        return withoutBridge < 0L || withBridge < 0L ? 0L : withoutBridge - withBridge;
    }

    private static String explanation(FamilyAblationStatus status) {
        return switch (status) {
            case MATERIAL_GAIN -> "bridge improves reachability, path length, or explored states";
            case UNCHANGED -> "bridge does not materially change the paired run";
            case REGRESSION -> "bridge makes the paired run fail or increases measured cost";
            case TRADE_OFF -> "bridge improves one measured cost while worsening another";
            case INCOMPLETE -> "paired success or comparable cost evidence is incomplete";
        };
    }

    private static String canonicalMaterial(
        BridgeHypothesis hypothesis,
        TransferReport transfer,
        AblationStatus status,
        List<FamilyAblationResult> results,
        List<String> blockers
    ) {
        StringBuilder material = new StringBuilder(SCHEMA)
            .append("\nhypothesis=").append(hypothesis.hypothesisId())
            .append("\nformationHash=").append(hypothesis.formationHash())
            .append("\ntransferHash=").append(transfer.contentHash())
            .append("\nstatus=").append(status.name())
            .append("\nblockers=").append(blockers);
        results.forEach(result -> material.append("\nfamily=")
            .append(result.canonicalMaterial()));
        return material.toString();
    }

    private static String hash(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public enum FamilyAblationStatus {
        MATERIAL_GAIN,
        UNCHANGED,
        REGRESSION,
        TRADE_OFF,
        INCOMPLETE
    }

    public enum AblationStatus {
        BENEFICIAL_HELD_OUT,
        NO_HELD_OUT_GAIN,
        REGRESSION,
        TRADE_OFF,
        INCOMPLETE_EVIDENCE,
        NOT_ELIGIBLE_TRANSFER
    }

    public record RunEvidence(Boolean success, int pathLength, long statesExplored) {
        public RunEvidence {
            if (pathLength < -1) {
                throw new IllegalArgumentException("pathLength must be >= -1");
            }
            if (statesExplored < -1L) {
                throw new IllegalArgumentException("statesExplored must be >= -1");
            }
        }

        public boolean successKnown() {
            return success != null;
        }

        public boolean successful() {
            return Boolean.TRUE.equals(success);
        }

        String canonicalMaterial() {
            return (success == null ? "unknown" : success.toString())
                + ',' + pathLength + ',' + statesExplored;
        }
    }

    public record FamilyAblation(
        String familyId,
        FamilyRole role,
        RunEvidence withBridge,
        RunEvidence withoutBridge
    ) {
        public FamilyAblation {
            requireText(familyId, "familyId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(withBridge, "withBridge");
            Objects.requireNonNull(withoutBridge, "withoutBridge");
        }
    }

    public record FamilyAblationResult(
        String familyId,
        FamilyRole role,
        FamilyAblationStatus status,
        RunEvidence withBridge,
        RunEvidence withoutBridge,
        int pathLengthGain,
        long statesExploredGain,
        String explanation
    ) {
        public FamilyAblationResult {
            requireText(familyId, "familyId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(withBridge, "withBridge");
            Objects.requireNonNull(withoutBridge, "withoutBridge");
            explanation = explanation == null ? "" : explanation;
        }

        static FamilyAblationResult incomplete(
            String familyId,
            FamilyRole role,
            String explanation
        ) {
            return new FamilyAblationResult(
                familyId,
                role,
                FamilyAblationStatus.INCOMPLETE,
                new RunEvidence(null, -1, -1L),
                new RunEvidence(null, -1, -1L),
                0,
                0L,
                explanation);
        }

        String canonicalMaterial() {
            return familyId + '|'
                + role.name() + '|'
                + status.name() + '|'
                + withBridge.canonicalMaterial() + '|'
                + withoutBridge.canonicalMaterial() + '|'
                + pathLengthGain + '|'
                + statesExploredGain + '|'
                + explanation;
        }
    }

    public record AblationReport(
        String schema,
        String hypothesisId,
        String formationHash,
        String transferContentHash,
        List<String> trainingFamilies,
        List<String> heldOutFamilies,
        AblationStatus status,
        List<FamilyAblationResult> familyResults,
        List<String> blockers,
        String contentHash
    ) {
        public AblationReport {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported bridge-ablation schema");
            }
            requireText(hypothesisId, "hypothesisId");
            requireSha256(formationHash, "formationHash");
            requireSha256(transferContentHash, "transferContentHash");
            trainingFamilies = sortedDistinct(trainingFamilies);
            heldOutFamilies = sortedDistinct(heldOutFamilies);
            Objects.requireNonNull(status, "status");
            familyResults = familyResults == null
                ? List.of()
                : familyResults.stream()
                    .sorted(Comparator.comparing(FamilyAblationResult::familyId))
                    .toList();
            blockers = sortedDistinct(blockers);
            requireSha256(contentHash, "contentHash");
        }

        public boolean beneficial() {
            return status == AblationStatus.BENEFICIAL_HELD_OUT;
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("hypothesisId", hypothesisId)
                .property("formationHash", formationHash)
                .property("transferContentHash", transferContentHash)
                .stringArray("trainingFamilies", trainingFamilies)
                .stringArray("heldOutFamilies", heldOutFamilies)
                .property("status", status.name())
                .stringArray("blockers", blockers)
                .array("familyResults", array -> familyResults.forEach(result ->
                    array.objectValue(object -> object
                        .property("familyId", result.familyId())
                        .property("role", result.role().name())
                        .property("status", result.status().name())
                        .object("withBridge", run -> writeRun(run, result.withBridge()))
                        .object("withoutBridge", run -> writeRun(run, result.withoutBridge()))
                        .property("pathLengthGain", result.pathLengthGain())
                        .property("statesExploredGain", result.statesExploredGain())
                        .property("explanation", result.explanation()))))
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }

        public void write(Path output) {
            try {
                Path parent = output.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(output, toCanonicalJson(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private static void writeRun(JsonWriter json, RunEvidence run) {
            json.property("success", run.success() == null ? "unknown" : run.success().toString())
                .property("pathLength", run.pathLength())
                .property("statesExplored", run.statesExplored());
        }
    }

    private static List<String> sortedDistinct(List<String> values) {
        return values == null
            ? List.of()
            : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hash");
        }
    }
}
