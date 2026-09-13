package de.regelsuche.benchmark.polynomial;

import de.regelsuche.polynomial.ExactNestedFactorizationTransformationPipeline;
import de.regelsuche.polynomial.PolynomialWorkLedger;
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
    String reportEvidenceHash,
    ObservedExecution observedExecution
) {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-factorization-attempt/v1";
    public static final String OBSERVED_SCHEMA =
        "regelsuche.polynomial-theory-utility-factorization-attempt/v2";
    public static final String NO_SELECTION = "NONE";
    public static final String NO_TRANSITION = "NONE";
    private static final Pattern SHA_256 =
        Pattern.compile("sha256:[0-9a-f]{64}");

    /** Historical constructor; its v1 identity and payload remain unchanged. */
    public PolynomialTheoryUtilityFactorizationAttempt(String attemptId, int attemptIndex,
            String executionInputId, String backendId, String requestId, String requestEvidenceHash,
            List<String> candidateIds, String selectedCandidateId, String transitionId,
            String verifierOutcome, String reportEvidenceHash) {
        this(attemptId, attemptIndex, executionInputId, backendId, requestId, requestEvidenceHash,
            candidateIds, selectedCandidateId, transitionId, verifierOutcome, reportEvidenceHash, null);
    }

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
        String metadataIdentity = identity(
                attemptIndex,
                executionInputId,
                backendId,
                requestId,
                requestEvidenceHash,
                candidateIds,
                selectedCandidateId,
                transitionId,
                verifierOutcome,
                reportEvidenceHash);
        if (observedExecution != null && !metadataIdentity.equals(observedExecution
                .metadata(attemptIndex, executionInputId, transitionId).attemptId())) {
            throw new IllegalArgumentException("observed attempt metadata differs from its actual pipeline report");
        }
        if (!attemptId.equals(observedIdentity(metadataIdentity, observedExecution))) {
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

    /** Creates v2 evidence only from an issuer-owned, actually executed nested pipeline. */
    public static PolynomialTheoryUtilityFactorizationAttempt createObserved(int attemptIndex,
            String executionInputId, int occurrenceIndex,
            ExactNestedFactorizationTransformationPipeline.Result pipeline, String transitionId) {
        var execution = new ObservedExecution(occurrenceIndex, pipeline);
        var metadata = execution.metadata(attemptIndex, executionInputId, transitionId);
        return new PolynomialTheoryUtilityFactorizationAttempt(observedIdentity(metadata.attemptId(), execution),
            metadata.attemptIndex(), metadata.executionInputId(), metadata.backendId(), metadata.requestId(),
            metadata.requestEvidenceHash(), metadata.candidateIds(), metadata.selectedCandidateId(),
            metadata.transitionId(), metadata.verifierOutcome(), metadata.reportEvidenceHash(), execution);
    }

    public String schema() {
        return observedExecution == null ? SCHEMA : OBSERVED_SCHEMA;
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
        if (result.observations() == null && observedExecution != null) {
            throw new IllegalArgumentException("observed attempt requires an observed result");
        }
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

    private static String observedIdentity(String metadataIdentity, ObservedExecution execution) {
        if (execution == null) return metadataIdentity;
        StringBuilder material = new StringBuilder();
        append(material, OBSERVED_SCHEMA);
        append(material, metadataIdentity);
        append(material, execution.canonicalMaterial());
        return hash(material.toString());
    }

    /**
     * Immutable issuer-owned binding. Callers cannot supply a replacement path,
     * certificate or partial ledger: these are read from the actual pipeline.
     */
    public static final class ObservedExecution {
        private final int occurrenceIndex;
        private final ExactNestedFactorizationTransformationPipeline.Result pipeline;

        private ObservedExecution(int occurrenceIndex,
                ExactNestedFactorizationTransformationPipeline.Result pipeline) {
            if (occurrenceIndex < 0) throw new IllegalArgumentException("occurrenceIndex must be non-negative");
            this.occurrenceIndex = occurrenceIndex;
            this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
            if (pipeline.factorization().isEmpty() || !pipeline.factorization().orElseThrow().executed()) {
                throw new IllegalArgumentException("observed attempt requires an executed factorization pipeline");
            }
        }

        public int occurrenceIndex() { return occurrenceIndex; }
        public List<Integer> path() { return pipeline.position().path(); }
        public String pipelineEvidenceHash() { return pipeline.certificateHash(); }
        public String sourceRootEvidenceHash() {
            return pipeline.projection().orElseThrow().rootSourceHash().orElseThrow();
        }
        public PolynomialWorkLedger rawWork() {
            return pipeline.factorization().orElseThrow().totalWork();
        }

        public String canonicalMaterial() {
            StringBuilder material = new StringBuilder();
            append(material, Integer.toString(occurrenceIndex));
            append(material, Integer.toString(path().size()));
            path().forEach(value -> append(material, Integer.toString(value)));
            append(material, pipelineEvidenceHash());
            append(material, sourceRootEvidenceHash());
            append(material, rawWork().canonicalMaterial());
            return material.toString();
        }

        void validateAgainst(PolynomialTheoryUtilityCandidateResult result,
                PolynomialTheoryUtilityExecutionObservations.Occurrence occurrence) {
            String source = result.sourceRootExpression();
            // ExactParsedSubtermProjector frames one UTF-8 value before hashing.
            String rootHash = hash(source.getBytes(StandardCharsets.UTF_8).length + ":" + source + "\n");
            if (occurrenceIndex != occurrence.occurrenceIndex() || !path().equals(occurrence.path())
                    || !pipelineEvidenceHash().equals(occurrence.pipelineEvidenceHash())
                    || !sourceRootEvidenceHash().equals(rootHash)) {
                throw new IllegalArgumentException("observed attempt belongs to another source or occurrence pipeline");
            }
        }

        private PolynomialTheoryUtilityFactorizationAttempt metadata(int index, String inputId, String transitionId) {
            var factorization = pipeline.factorization().orElseThrow();
            var report = factorization.report().orElseThrow();
            String selected = pipeline.transformation().map(value -> value.candidateCertificateHash())
                .filter(value -> !value.isEmpty()).orElse(NO_SELECTION);
            return create(index, inputId, factorization.engineId(),
                hash(factorization.request().orElseThrow().canonicalMaterial()), factorization.certificateHash(),
                report.candidates().stream().map(value -> value.verificationCertificateHash()).toList(), selected,
                transitionId, selected.equals(NO_SELECTION) ? report.status().name() : "VERIFIED",
                report.verificationHash());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ObservedExecution execution
                && occurrenceIndex == execution.occurrenceIndex
                && pipelineEvidenceHash().equals(execution.pipelineEvidenceHash());
        }

        @Override
        public int hashCode() { return Objects.hash(occurrenceIndex, pipelineEvidenceHash()); }
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
