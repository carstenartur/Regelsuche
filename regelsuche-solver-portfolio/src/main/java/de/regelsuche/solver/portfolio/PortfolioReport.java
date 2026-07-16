package de.regelsuche.solver.portfolio;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.solver.ir.SolverIr;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Canonical aggregate trace. It is execution telemetry, never mathematical evidence itself. */
public record PortfolioReport(
    String schema,
    String requestHash,
    String obligationHash,
    SolverObjective objective,
    PortfolioPolicy policy,
    PortfolioOutcome outcome,
    List<PortfolioAttempt> attempts,
    String selectedExecutionHash,
    String selectedBackendId,
    List<BackendRole> selectedRoles,
    List<String> conflictExecutionHashes,
    long consumedCostUnits,
    int executedInvocations,
    boolean proofAuthorized,
    boolean promotionBlocked,
    String telemetryNotice,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.solver-portfolio-report/v1";
    public static final String TELEMETRY_NOTICE =
        "EXECUTION_TELEMETRY_NOT_MATHEMATICAL_EVIDENCE";

    public PortfolioReport {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported portfolio report schema");
        }
        requireSha(requestHash, "requestHash");
        requireSha(obligationHash, "obligationHash");
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(outcome, "outcome");
        attempts = attempts == null ? List.of() : attempts.stream()
            .sorted(Comparator.comparingInt(PortfolioAttempt::sequence))
            .toList();
        if (new HashSet<>(attempts.stream().map(PortfolioAttempt::sequence).toList()).size()
                != attempts.size()) {
            throw new IllegalArgumentException("portfolio attempt sequences must be unique");
        }
        selectedExecutionHash = selectedExecutionHash == null ? "" : selectedExecutionHash;
        if (!selectedExecutionHash.isEmpty()) {
            requireSha(selectedExecutionHash, "selectedExecutionHash");
        }
        selectedBackendId = selectedBackendId == null ? "" : selectedBackendId;
        selectedRoles = selectedRoles == null ? List.of() : selectedRoles.stream()
            .filter(Objects::nonNull)
            .distinct()
            .sorted(Comparator.comparing(Enum::name))
            .toList();
        conflictExecutionHashes = conflictExecutionHashes == null ? List.of()
            : conflictExecutionHashes.stream().distinct().sorted().toList();
        conflictExecutionHashes.forEach(value -> requireSha(value, "conflictExecutionHash"));
        if (consumedCostUnits < 0L || executedInvocations < 0) {
            throw new IllegalArgumentException("portfolio usage must not be negative");
        }
        if (!TELEMETRY_NOTICE.equals(telemetryNotice)) {
            throw new IllegalArgumentException("invalid telemetry notice");
        }
        if (proofAuthorized && (!objective.proofObjective()
                || outcome != PortfolioOutcome.CONFIRMED)) {
            throw new IllegalArgumentException(
                "proofAuthorized requires a confirmed proof objective");
        }
        if ((outcome == PortfolioOutcome.CONFIRMED
                || outcome == PortfolioOutcome.REFUTED)
                && selectedExecutionHash.isEmpty()) {
            throw new IllegalArgumentException(
                "decisive portfolio outcome requires selected execution");
        }
        requireSha(contentHash, "contentHash");
        String expected = hash(
            requestHash, obligationHash, objective, policy, outcome, attempts,
            selectedExecutionHash, selectedBackendId, selectedRoles,
            conflictExecutionHashes, consumedCostUnits, executedInvocations,
            proofAuthorized, promotionBlocked);
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException("portfolio report hash does not match fields");
        }
    }

    public static PortfolioReport create(
        PortfolioRequest request,
        PortfolioOutcome outcome,
        List<PortfolioAttempt> attempts,
        String selectedExecutionHash,
        String selectedBackendId,
        List<BackendRole> selectedRoles,
        List<String> conflictExecutionHashes,
        long consumedCostUnits,
        int executedInvocations,
        boolean proofAuthorized
    ) {
        boolean promotionBlocked = outcome == PortfolioOutcome.CONFLICT
            || (request.objective().proofObjective() && !proofAuthorized);
        List<PortfolioAttempt> orderedAttempts = attempts == null ? List.of()
            : attempts.stream().sorted(Comparator.comparingInt(PortfolioAttempt::sequence)).toList();
        List<BackendRole> orderedRoles = selectedRoles == null ? List.of()
            : selectedRoles.stream().filter(Objects::nonNull).distinct()
                .sorted(Comparator.comparing(Enum::name)).toList();
        List<String> conflicts = conflictExecutionHashes == null ? List.of()
            : conflictExecutionHashes.stream().distinct().sorted().toList();
        String selectedHash = selectedExecutionHash == null ? "" : selectedExecutionHash;
        String backendId = selectedBackendId == null ? "" : selectedBackendId;
        return new PortfolioReport(
            SCHEMA, request.contentHash(), request.obligation().contentHash(),
            request.objective(), request.policy(), outcome, orderedAttempts,
            selectedHash, backendId, orderedRoles, conflicts, consumedCostUnits,
            executedInvocations, proofAuthorized, promotionBlocked, TELEMETRY_NOTICE,
            hash(request.contentHash(), request.obligation().contentHash(),
                request.objective(), request.policy(), outcome, orderedAttempts,
                selectedHash, backendId, orderedRoles, conflicts,
                consumedCostUnits, executedInvocations, proofAuthorized,
                promotionBlocked));
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("schema", schema)
            .property("requestHash", requestHash)
            .property("obligationHash", obligationHash)
            .property("objective", objective.name())
            .property("policy", policy.name())
            .property("outcome", outcome.name())
            .array("attempts", array -> attempts.forEach(attempt ->
                array.objectValue(attempt::writeTo)))
            .property("selectedExecutionHash", selectedExecutionHash)
            .property("selectedBackendId", selectedBackendId)
            .stringArray("selectedRoles", selectedRoles.stream().map(Enum::name).toList())
            .stringArray("conflictExecutionHashes", conflictExecutionHashes)
            .property("consumedCostUnits", consumedCostUnits)
            .property("executedInvocations", executedInvocations)
            .property("proofAuthorized", proofAuthorized)
            .property("promotionBlocked", promotionBlocked)
            .property("telemetryNotice", telemetryNotice)
            .property("contentHash", contentHash)
            .endObject()
            .toString();
    }

    private static String hash(
        String requestHash,
        String obligationHash,
        SolverObjective objective,
        PortfolioPolicy policy,
        PortfolioOutcome outcome,
        List<PortfolioAttempt> attempts,
        String selectedExecutionHash,
        String selectedBackendId,
        List<BackendRole> selectedRoles,
        List<String> conflictExecutionHashes,
        long consumedCostUnits,
        int executedInvocations,
        boolean proofAuthorized,
        boolean promotionBlocked
    ) {
        return SolverIr.sha256(
            SCHEMA
                + "\nrequest=" + requestHash
                + "\nobligation=" + obligationHash
                + "\nobjective=" + objective.name()
                + "\npolicy=" + policy.name()
                + "\noutcome=" + outcome.name()
                + "\nattempts=" + attempts.stream()
                    .map(PortfolioAttempt::contentHash).toList()
                + "\nselectedExecution=" + selectedExecutionHash
                + "\nselectedBackend=" + selectedBackendId
                + "\nselectedRoles=" + selectedRoles
                + "\nconflicts=" + conflictExecutionHashes
                + "\nconsumedCost=" + consumedCostUnits
                + "\nexecutedInvocations=" + executedInvocations
                + "\nproofAuthorized=" + proofAuthorized
                + "\npromotionBlocked=" + promotionBlocked
                + "\nnotice=" + TELEMETRY_NOTICE);
    }

    private static void requireSha(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }
}
