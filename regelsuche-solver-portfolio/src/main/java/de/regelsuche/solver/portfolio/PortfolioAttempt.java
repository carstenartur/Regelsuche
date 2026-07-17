package de.regelsuche.solver.portfolio;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.solver.ir.SolverIr;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One deterministic trace entry, including filtered and skipped backends. */
public record PortfolioAttempt(
    int sequence,
    String backendId,
    String backendVersion,
    String profileHash,
    List<BackendRole> roles,
    AttemptDisposition disposition,
    String resultStatus,
    String executionHash,
    List<String> issues,
    long chargedCostUnits,
    String attemptConfigurationHash,
    String contentHash
) {
    private static final Set<String> RESULT_STATES = Set.of(
        "NOT_RUN", "CONFIRMED", "REFUTED", "UNKNOWN", "TIMEOUT",
        "UNSUPPORTED", "ERROR");

    public PortfolioAttempt {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        requireText(backendId, "backendId");
        requireText(backendVersion, "backendVersion");
        requireSha(profileHash, "profileHash");
        roles = roles == null ? List.of() : roles.stream()
            .filter(Objects::nonNull)
            .distinct()
            .sorted(Comparator.comparing(Enum::name))
            .toList();
        Objects.requireNonNull(disposition, "disposition");
        if (!RESULT_STATES.contains(resultStatus)) {
            throw new IllegalArgumentException("unsupported resultStatus: " + resultStatus);
        }
        executionHash = executionHash == null ? "" : executionHash;
        if (!executionHash.isEmpty()) {
            requireSha(executionHash, "executionHash");
        }
        issues = issues == null ? List.of() : issues.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .sorted()
            .toList();
        if (chargedCostUnits < 0L) {
            throw new IllegalArgumentException("chargedCostUnits must not be negative");
        }
        requireSha(attemptConfigurationHash, "attemptConfigurationHash");
        requireSha(contentHash, "contentHash");
        String expected = hash(
            sequence, backendId, backendVersion, profileHash, roles, disposition,
            resultStatus, executionHash, issues, chargedCostUnits,
            attemptConfigurationHash);
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException("portfolio attempt hash does not match fields");
        }
    }

    public static PortfolioAttempt create(
        int sequence,
        BackendCapabilityProfile profile,
        AttemptDisposition disposition,
        String resultStatus,
        String executionHash,
        List<String> issues,
        long chargedCostUnits,
        String attemptConfigurationHash
    ) {
        return new PortfolioAttempt(
            sequence, profile.backendId(), profile.backendVersion(),
            profile.semanticHash(), profile.roles(), disposition, resultStatus,
            executionHash, issues, chargedCostUnits, attemptConfigurationHash,
            hash(sequence, profile.backendId(), profile.backendVersion(),
                profile.semanticHash(), profile.roles(), disposition, resultStatus,
                executionHash == null ? "" : executionHash,
                issues == null ? List.of() : issues.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim).distinct().sorted().toList(),
                chargedCostUnits, attemptConfigurationHash));
    }

    void writeTo(JsonWriter object) {
        object.property("sequence", sequence)
            .property("backendId", backendId)
            .property("backendVersion", backendVersion)
            .property("profileHash", profileHash)
            .stringArray("roles", roles.stream().map(Enum::name).toList())
            .property("disposition", disposition.name())
            .property("resultStatus", resultStatus)
            .property("executionHash", executionHash)
            .stringArray("issues", issues)
            .property("chargedCostUnits", chargedCostUnits)
            .property("attemptConfigurationHash", attemptConfigurationHash)
            .property("contentHash", contentHash);
    }

    private static String hash(
        int sequence,
        String backendId,
        String backendVersion,
        String profileHash,
        List<BackendRole> roles,
        AttemptDisposition disposition,
        String resultStatus,
        String executionHash,
        List<String> issues,
        long chargedCostUnits,
        String attemptConfigurationHash
    ) {
        return SolverIr.sha256(
            "sequence=" + sequence
                + "\nbackend=" + backendId + '@' + backendVersion
                + "\nprofile=" + profileHash
                + "\nroles=" + roles
                + "\ndisposition=" + disposition.name()
                + "\nresult=" + resultStatus
                + "\nexecution=" + (executionHash == null ? "" : executionHash)
                + "\nissues=" + issues
                + "\ncost=" + chargedCostUnits
                + "\nconfiguration=" + attemptConfigurationHash);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireSha(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }
}
