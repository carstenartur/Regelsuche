package de.regelsuche.benchmark.polynomial;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One typed factorization request/report observation for a candidate result.
 */
public record PolynomialTheoryUtilityFactorizationAttempt(
    String attemptId,
    int attemptIndex,
    String executionInputId,
    String backendId,
    String requestId,
    String requestEvidenceHash,
    List<String> candidateIds,
    String selectedCandidateId,
    String transitionId,
    String verifierOutcome,
    String reportEvidenceHash
) {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-factorization-attempt/v1";
    public static final String NO_SELECTION = "NONE";
    public static final String NO_TRANSITION = "NONE";
    private static final Pattern SHA_256 =
        Pattern.compile("sha256:[0-9a-f]{64}");

    public PolynomialTheoryUtilityFactorizationAttempt {
        attemptId = requireHash(attemptId, "attemptId");
        if (attemptIndex < 0) {
            throw new IllegalArgumentException(
                "attemptIndex must be non-negative"
            );
        }
        executionInputId = requireHash(
            executionInputId,
            "executionInputId"
        );
        backendId = requireText(backendId, "backendId");
        requestId = requireHash(requestId, "requestId");
        requestEvidenceHash = requireHash(
            requestEvidenceHash,
            "requestEvidenceHash"
        );
        candidateIds = immutableCandidateIds(candidateIds);
        selectedCandidateId = requireSelection(
            selectedCandidateId,
            candidateIds
        );
        transitionId = requireOptionalHash(
            transitionId,
            "transitionId"
        );
        verifierOutcome = requireText(
            verifierOutcome,
            "verifierOutcome"
        );
        reportEvidenceHash = requireHash(
            reportEvidenceHash,
            "reportEvidenceHash"
        );
        if (!NO_SELECTION.equals(selectedCandidateId)
                && !"VERIFIED".equals(verifierOutcome)) {
            throw new IllegalArgumentException(
                "selected candidate lacks a verified outcome"
            );
        }
        if (!NO_TRANSITION.equals(transitionId)
                && NO_SELECTION.equals(selectedCandidateId)) {
            throw new IllegalArgumentException(
                "transition lineage lacks a selected candidate"
            );
        }
        if (!attemptId.equals(identity(
                attemptIndex,
                executionInputId,
                backendId,
                requestId,
                requestEvidenceHash,
                candidateIds,
                selectedCandidateId,
                transitionId,
                verifierOutcome,
                reportEvidenceHash))) {
            throw new IllegalArgumentException(
                "factorization attempt identity differs from its fields"
            );
        }
    }

    public static PolynomialTheoryUtilityFactorizationAttempt create(
        int attemptIndex,
        String executionInputId,
        String backendId,
        String requestId,
        String requestEvidenceHash,
        List<String> candidateIds,
        String selectedCandidateId,
        String transitionId,
        String verifierOutcome,
        String reportEvidenceHash
    ) {
        List<String> candidates = immutableCandidateIds(candidateIds);
        String selected = requireSelection(
            selectedCandidateId,
            candidates
        );
        String retainedTransition = requireOptionalHash(
            transitionId,
            "transitionId"
        );
        return new PolynomialTheoryUtilityFactorizationAttempt(
            identity(
                attemptIndex,
                executionInputId,
                backendId,
                requestId,
                requestEvidenceHash,
                candidates,
                selected,
                retainedTransition,
                verifierOutcome,
                reportEvidenceHash
            ),
            attemptIndex,
            executionInputId,
            backendId,
            requestId,
            requestEvidenceHash,
            candidates,
            selected,
            retainedTransition,
            verifierOutcome,
            reportEvidenceHash
        );
    }

    public String schema() {
        return SCHEMA;
    }

    public int candidateCount() {
        return candidateIds.size();
    }

    public boolean selectedCandidate() {
        return !NO_SELECTION.equals(selectedCandidateId);
    }

    public boolean producedTransition() {
        return !NO_TRANSITION.equals(transitionId);
    }

    public void validateAgainst(
        int expectedIndex,
        PolynomialTheoryUtilityCandidateResult result,
        PolynomialTheoryUtilityExecutionProfile profile
    ) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(profile, "profile");
        var frozenProfile = PolynomialTheoryUtilityExecutionInputs.profile(
            result.input().profileId()
        );
        if (attemptIndex != expectedIndex
                || !profile.equals(frozenProfile)
                || !executionInputId.equals(
                    result.input().inputId()
                )
                || !backendId.equals(profile.engineId())
                || "DISABLED".equals(profile.factorizationMode())
                || "NONE".equals(profile.engineId())) {
            throw new IllegalArgumentException(
                "factorization attempt differs from its result profile"
            );
        }
        if (producedTransition()) {
            var transition = result.transitions().stream()
                .filter(value -> transitionId.equals(value.transitionId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                    "factorization attempt refers to another result transition"
                ));
            if (!backendId.equals(transition.backendId())) {
                throw new IllegalArgumentException(
                    "factorization attempt backend differs from its transition"
                );
            }
        }
    }

    private static String identity(
        int attemptIndex,
        String executionInputId,
        String backendId,
        String requestId,
        String requestEvidenceHash,
        List<String> candidateIds,
        String selectedCandidateId,
        String transitionId,
        String verifierOutcome,
        String reportEvidenceHash
    ) {
        StringBuilder material = new StringBuilder();
        append(material, SCHEMA);
        append(material, Integer.toString(attemptIndex));
        append(
            material,
            requireHash(executionInputId, "executionInputId")
        );
        append(material, requireText(backendId, "backendId"));
        append(material, requireHash(requestId, "requestId"));
        append(
            material,
            requireHash(
                requestEvidenceHash,
                "requestEvidenceHash"
            )
        );
        append(material, Integer.toString(candidateIds.size()));
        candidateIds.forEach(value -> append(
            material,
            requireHash(value, "candidateId")
        ));
        append(
            material,
            requireSelection(selectedCandidateId, candidateIds)
        );
        append(
            material,
            requireOptionalHash(transitionId, "transitionId")
        );
        append(
            material,
            requireText(verifierOutcome, "verifierOutcome")
        );
        append(
            material,
            requireHash(reportEvidenceHash, "reportEvidenceHash")
        );
        return hash(material.toString());
    }

    private static List<String> immutableCandidateIds(
        List<String> values
    ) {
        List<String> retained = List.copyOf(
            Objects.requireNonNull(values, "candidateIds")
        );
        Set<String> unique = new HashSet<>();
        for (String value : retained) {
            String candidateId = requireHash(value, "candidateId");
            if (!unique.add(candidateId)) {
                throw new IllegalArgumentException(
                    "factorization report repeats a candidate identity"
                );
            }
        }
        return retained;
    }

    private static String requireSelection(
        String selectedCandidateId,
        List<String> candidateIds
    ) {
        String value = requireText(
            selectedCandidateId,
            "selectedCandidateId"
        );
        if (!NO_SELECTION.equals(value)) {
            value = requireHash(value, "selectedCandidateId");
            if (!candidateIds.contains(value)) {
                throw new IllegalArgumentException(
                    "selected candidate is absent from the report"
                );
            }
        }
        return value;
    }

    private static String requireOptionalHash(String value, String name) {
        String text = requireText(value, name);
        return NO_TRANSITION.equals(text)
            ? text
            : requireHash(text, name);
    }

    private static String hash(String material) {
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            material.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String requireHash(String value, String name) {
        String text = requireText(value, name);
        if (!SHA_256.matcher(text).matches()) {
            throw new IllegalArgumentException(name + " is not SHA-256");
        }
        return text;
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(
                name + " must not be blank"
            );
        }
        return text;
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }
}
