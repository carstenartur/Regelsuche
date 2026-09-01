package de.regelsuche.benchmark.polynomial;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Target-blind run-scoped execution boundary for frozen study profiles. */
public interface PolynomialTheoryUtilityProfileAdapter {
    String profileId();

    String adapterId();

    Run openRun(RunDescriptor descriptor);

    interface Run extends AutoCloseable {
        CandidateResult execute(
            PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
        );

        @Override
        void close();
    }

    record RunDescriptor(
        String runId,
        String profileId,
        String checkpointId,
        String adapterId,
        int expectedCaseCount
    ) {
        public RunDescriptor {
            runId = requireText(runId, "runId");
            profileId = requireText(profileId, "profileId");
            checkpointId = requireText(checkpointId, "checkpointId");
            adapterId = requireText(adapterId, "adapterId");
            if (expectedCaseCount < 1) {
                throw new IllegalArgumentException(
                    "expectedCaseCount must be positive"
                );
            }
        }
    }

    /** One immutable terminal result for an exact frozen execution input. */
    record CandidateResult(
        String resultId,
        PolynomialTheoryUtilityExecutionInput input,
        TerminalStatus terminalStatus,
        String detailCode,
        long primitiveWorkConsumed,
        long mechanicalWorkConsumed,
        long factorizationWorkConsumed,
        int generatedTransitions,
        String verifierOutcome,
        String transitionEvidenceHash
    ) {
        public static final String SCHEMA =
            "regelsuche.polynomial-theory-utility-candidate-result/v1";
        public static final String NO_TRANSITION_EVIDENCE = "NONE";
        private static final Pattern SHA_256 =
            Pattern.compile("sha256:[0-9a-f]{64}");

        public CandidateResult {
            resultId = requireHash(resultId, "resultId");
            input = Objects.requireNonNull(input, "input");
            terminalStatus = Objects.requireNonNull(
                terminalStatus,
                "terminalStatus"
            );
            detailCode = requireText(detailCode, "detailCode");
            verifierOutcome = requireText(verifierOutcome, "verifierOutcome");
            transitionEvidenceHash = requireText(
                transitionEvidenceHash,
                "transitionEvidenceHash"
            );
            requireWorkWithinAuthority(
                input,
                primitiveWorkConsumed,
                mechanicalWorkConsumed,
                factorizationWorkConsumed,
                generatedTransitions
            );
            requireEvidence(
                terminalStatus,
                generatedTransitions,
                verifierOutcome,
                transitionEvidenceHash
            );
            if (!resultId.equals(identity(
                    input,
                    terminalStatus,
                    detailCode,
                    primitiveWorkConsumed,
                    mechanicalWorkConsumed,
                    factorizationWorkConsumed,
                    generatedTransitions,
                    verifierOutcome,
                    transitionEvidenceHash))) {
                throw new IllegalArgumentException(
                    "candidate result identity differs from its fields"
                );
            }
        }

        static CandidateResult noTransition(
            PolynomialTheoryUtilityExecutionInput input,
            String detailCode
        ) {
            return create(
                input,
                TerminalStatus.NO_TRANSITION,
                detailCode,
                0L,
                0L,
                0L,
                0,
                "NOT_REQUESTED",
                NO_TRANSITION_EVIDENCE
            );
        }

        static CandidateResult create(
            PolynomialTheoryUtilityExecutionInput input,
            TerminalStatus terminalStatus,
            String detailCode,
            long primitiveWorkConsumed,
            long mechanicalWorkConsumed,
            long factorizationWorkConsumed,
            int generatedTransitions,
            String verifierOutcome,
            String transitionEvidenceHash
        ) {
            Objects.requireNonNull(input, "input");
            return new CandidateResult(
                identity(
                    input,
                    terminalStatus,
                    detailCode,
                    primitiveWorkConsumed,
                    mechanicalWorkConsumed,
                    factorizationWorkConsumed,
                    generatedTransitions,
                    verifierOutcome,
                    transitionEvidenceHash
                ),
                input,
                terminalStatus,
                detailCode,
                primitiveWorkConsumed,
                mechanicalWorkConsumed,
                factorizationWorkConsumed,
                generatedTransitions,
                verifierOutcome,
                transitionEvidenceHash
            );
        }

        void validateAgainst(PolynomialTheoryUtilityExecutionInput expected) {
            if (!input.equals(Objects.requireNonNull(expected, "expected"))) {
                throw new IllegalArgumentException(
                    "candidate result refers to another frozen execution input"
                );
            }
        }

        private static void requireWorkWithinAuthority(
            PolynomialTheoryUtilityExecutionInput input,
            long primitive,
            long mechanical,
            long factorization,
            int transitions
        ) {
            if (primitive < 0
                    || mechanical < 0
                    || factorization < 0
                    || transitions < 0
                    || primitive > input.admittedPrimitiveWork()
                    || mechanical > input.totalMechanicalWork()
                    || factorization > input.factorizationWork()
                    || factorization > mechanical) {
                throw new IllegalArgumentException(
                    "candidate result work differs from frozen authority"
                );
            }
        }

        private static void requireEvidence(
            TerminalStatus status,
            int transitions,
            String verifier,
            String evidence
        ) {
            boolean validated = status == TerminalStatus.VALIDATED_TRANSITION;
            if (validated) {
                if (transitions < 1
                        || !"VERIFIED".equals(verifier)
                        || !SHA_256.matcher(evidence).matches()) {
                    throw new IllegalArgumentException(
                        "validated transition lacks verifier-bound evidence"
                    );
                }
            } else if (transitions != 0
                    || !NO_TRANSITION_EVIDENCE.equals(evidence)) {
                throw new IllegalArgumentException(
                    "non-transition result retains transition evidence"
                );
            }
        }

        private static String identity(
            PolynomialTheoryUtilityExecutionInput input,
            TerminalStatus status,
            String detail,
            long primitive,
            long mechanical,
            long factorization,
            int transitions,
            String verifier,
            String evidence
        ) {
            StringBuilder material = new StringBuilder();
            append(material, SCHEMA);
            append(material, PolynomialTheoryUtilityPreregistration.STUDY_ID);
            append(material, Objects.requireNonNull(input, "input").inputId());
            append(material, Objects.requireNonNull(status, "status").name());
            append(material, requireText(detail, "detailCode"));
            append(material, Long.toString(primitive));
            append(material, Long.toString(mechanical));
            append(material, Long.toString(factorization));
            append(material, Integer.toString(transitions));
            append(material, requireText(verifier, "verifierOutcome"));
            append(material, requireText(evidence, "transitionEvidenceHash"));
            return PolynomialTheoryUtilityExecutionIdentity.sha256(
                material.toString().getBytes(StandardCharsets.UTF_8)
            );
        }

        private static void append(StringBuilder target, String value) {
            target.append(value.length()).append(':').append(value);
        }

        private static String requireHash(String value, String name) {
            if (value == null || !SHA_256.matcher(value).matches()) {
                throw new IllegalArgumentException(name + " is not SHA-256");
            }
            return value;
        }

        public enum TerminalStatus {
            VALIDATED_TRANSITION,
            NO_TRANSITION,
            UNSUPPORTED,
            BUDGET_INCONCLUSIVE,
            TECHNICAL_FAILURE
        }
    }

    /** Frozen control adapter that deliberately performs no factorization. */
    final class NoFactorizationAdapter
            implements PolynomialTheoryUtilityProfileAdapter {
        public static final String PROFILE_ID = "NO_FACTORIZATION";
        public static final String ADAPTER_ID =
            "regelsuche.polynomial-theory-utility.no-factorization/v1";
        public static final String DETAIL_CODE =
            "FACTORIZATION_DISABLED_BY_FROZEN_PROFILE";

        @Override
        public String profileId() {
            return PROFILE_ID;
        }

        @Override
        public String adapterId() {
            return ADAPTER_ID;
        }

        @Override
        public Run openRun(RunDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "descriptor");
            var checkpoint =
                PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS.stream()
                    .filter(value -> value.checkpointId().equals(
                        descriptor.checkpointId()
                    ))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                        "no-factorization checkpoint is not frozen"
                    ));
            var profile =
                PolynomialTheoryUtilityExecutionInputs.profile(PROFILE_ID);
            String expectedRunId =
                PolynomialTheoryUtilityExecutionIdentity.runId(
                    profile,
                    checkpoint
                );
            if (!PROFILE_ID.equals(descriptor.profileId())
                    || !ADAPTER_ID.equals(descriptor.adapterId())
                    || !expectedRunId.equals(descriptor.runId())
                    || descriptor.expectedCaseCount()
                        != PolynomialTheoryUtilityCaseCorpus
                            .ORDERED_CASE_IDS.size()) {
                throw new IllegalArgumentException(
                    "no-factorization run differs from the frozen profile"
                );
            }
            List<PolynomialTheoryUtilityExecutionInput> expectedInputs =
                PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
                    .filter(value -> expectedRunId.equals(value.runId()))
                    .toList();
            if (expectedInputs.size() != descriptor.expectedCaseCount()) {
                throw new IllegalStateException(
                    "no-factorization run input count differs from the freeze"
                );
            }
            return new BaselineRun(descriptor, expectedInputs);
        }

        private static final class BaselineRun implements Run {
            private final RunDescriptor descriptor;
            private final List<PolynomialTheoryUtilityExecutionInput>
                expectedInputs;
            private int nextCase;
            private boolean closed;

            private BaselineRun(
                RunDescriptor descriptor,
                List<PolynomialTheoryUtilityExecutionInput> expectedInputs
            ) {
                this.descriptor = descriptor;
                this.expectedInputs = List.copyOf(expectedInputs);
            }

            @Override
            public CandidateResult execute(
                PolynomialTheoryUtilityExecutionInput input,
                PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
            ) {
                Objects.requireNonNull(input, "input");
                Objects.requireNonNull(formationCase, "formationCase");
                if (closed || nextCase >= descriptor.expectedCaseCount()) {
                    throw new IllegalStateException(
                        "no-factorization run cannot accept another case"
                    );
                }
                var expectedInput = expectedInputs.get(nextCase);
                if (!expectedInput.equals(input)
                        || !expectedInput.caseId().equals(
                            formationCase.caseId()
                        )) {
                    throw new IllegalArgumentException(
                        "no-factorization input differs from its frozen position"
                    );
                }
                nextCase++;
                return CandidateResult.noTransition(input, DETAIL_CODE);
            }

            @Override
            public void close() {
                if (closed) {
                    throw new IllegalStateException(
                        "no-factorization run is already closed"
                    );
                }
                closed = true;
                if (nextCase != descriptor.expectedCaseCount()) {
                    throw new IllegalStateException(
                        "no-factorization run closed before all frozen cases"
                    );
                }
            }
        }
    }

    /** Exact one-adapter-per-frozen-profile registry. */
    final class AdapterRegistry {
        private final Map<String, PolynomialTheoryUtilityProfileAdapter>
            byProfile;

        public AdapterRegistry(
            List<PolynomialTheoryUtilityProfileAdapter> adapters
        ) {
            Objects.requireNonNull(adapters, "adapters");
            Map<String, String> expected = new LinkedHashMap<>();
            PolynomialTheoryUtilityExecutionPlan.PROFILES.forEach(profile ->
                expected.put(profile.profileId(), profile.adapterId()));

            Map<String, PolynomialTheoryUtilityProfileAdapter> supplied =
                new LinkedHashMap<>();
            for (var adapter : adapters) {
                Objects.requireNonNull(adapter, "adapter");
                String profileId = requireText(
                    adapter.profileId(),
                    "profileId"
                );
                String adapterId = requireText(
                    adapter.adapterId(),
                    "adapterId"
                );
                if (!adapterId.equals(expected.get(profileId))) {
                    throw new IllegalArgumentException(
                        "adapter differs from its frozen profile: " + profileId
                    );
                }
                if (supplied.putIfAbsent(profileId, adapter) != null) {
                    throw new IllegalArgumentException(
                        "duplicate polynomial utility profile: " + profileId
                    );
                }
            }
            if (!supplied.keySet().equals(expected.keySet())) {
                throw new IllegalArgumentException(
                    "adapter inventory differs from the frozen profile contract"
                );
            }

            Map<String, PolynomialTheoryUtilityProfileAdapter> ordered =
                new LinkedHashMap<>();
            expected.keySet().forEach(profileId ->
                ordered.put(profileId, supplied.get(profileId)));
            byProfile = Collections.unmodifiableMap(ordered);
        }

        public PolynomialTheoryUtilityProfileAdapter require(
            String profileId,
            String adapterId
        ) {
            String profile = requireText(profileId, "profileId");
            String expectedAdapter = requireText(adapterId, "adapterId");
            var adapter = byProfile.get(profile);
            if (adapter == null
                    || !profile.equals(adapter.profileId())
                    || !expectedAdapter.equals(adapter.adapterId())) {
                throw new IllegalArgumentException(
                    "execution input has no exact frozen adapter"
                );
            }
            return adapter;
        }

        public List<String> profileIds() {
            return List.copyOf(byProfile.keySet());
        }
    }

    /** Complete target-blind result batch before canonical artifact freezing. */
    final class CandidateBatch {
        public static final String SCHEMA =
            "regelsuche.polynomial-theory-utility-candidate-batch/v1";
        public static final String EVIDENCE_STATUS =
            "TARGET_BLIND_RESULTS_COLLECTED_NOT_FROZEN";

        private final String inputContentHash;
        private final long inputByteLength;
        private final List<CandidateResult> results;

        private CandidateBatch(
            PolynomialTheoryUtilityExecutionInputArtifact inputs,
            List<CandidateResult> results
        ) {
            Objects.requireNonNull(inputs, "inputs");
            this.results = List.copyOf(
                Objects.requireNonNull(results, "results")
            );
            if (this.results.size() != inputs.inputs().size()
                    || this.results.size()
                        != PolynomialTheoryUtilityExecutionInputs
                            .EXPECTED_INPUT_COUNT) {
                throw new IllegalArgumentException(
                    "candidate batch must contain one result per frozen input"
                );
            }
            var identities = new HashSet<String>();
            for (int index = 0; index < this.results.size(); index++) {
                var result = Objects.requireNonNull(
                    this.results.get(index),
                    "result"
                );
                result.validateAgainst(inputs.inputs().get(index));
                if (!identities.add(result.resultId())) {
                    throw new IllegalArgumentException(
                        "candidate result identities are not unique"
                    );
                }
            }
            inputContentHash = inputs.contentHash();
            inputByteLength = inputs.byteLength();
        }

        static CandidateBatch create(
            PolynomialTheoryUtilityExecutionInputArtifact inputs,
            List<CandidateResult> results
        ) {
            return new CandidateBatch(inputs, results);
        }

        public String schema() {
            return SCHEMA;
        }

        public String studyId() {
            return PolynomialTheoryUtilityPreregistration.STUDY_ID;
        }

        public String evidenceStatus() {
            return EVIDENCE_STATUS;
        }

        public String inputContentHash() {
            return inputContentHash;
        }

        public long inputByteLength() {
            return inputByteLength;
        }

        public List<CandidateResult> results() {
            return results;
        }
    }

    /** Executes the frozen run-major matrix without qualification access. */
    final class TargetBlindRunner {
        public CandidateBatch execute(
            PolynomialTheoryUtilityExecutionInputArtifact inputs,
            AdapterRegistry registry
        ) {
            Objects.requireNonNull(inputs, "inputs");
            Objects.requireNonNull(registry, "registry");
            List<PolynomialTheoryUtilityCaseCorpus.FormationCase> cases =
                PolynomialTheoryUtilityCaseCorpus.load().cases();
            int runSize = cases.size();
            List<CandidateResult> results =
                new ArrayList<>(inputs.inputs().size());
            Set<String> runIds = new HashSet<>();

            for (int offset = 0;
                    offset < inputs.inputs().size();
                    offset += runSize) {
                if (offset + runSize > inputs.inputs().size()) {
                    throw new IllegalStateException(
                        "execution input artifact ends with a partial run"
                    );
                }
                List<PolynomialTheoryUtilityExecutionInput> runInputs =
                    inputs.inputs().subList(offset, offset + runSize);
                var first = runInputs.getFirst();
                requireRunChunk(first, runInputs, cases);
                if (!runIds.add(first.runId())) {
                    throw new IllegalStateException(
                        "execution input artifact repeats a run identity"
                    );
                }

                var adapter = registry.require(
                    first.profileId(),
                    first.adapterId()
                );
                var descriptor = new RunDescriptor(
                    first.runId(),
                    first.profileId(),
                    first.checkpointId(),
                    first.adapterId(),
                    runSize
                );
                try (var run = Objects.requireNonNull(
                        adapter.openRun(descriptor),
                        "adapter run")) {
                    for (int index = 0; index < runSize; index++) {
                        var input = runInputs.get(index);
                        var result = Objects.requireNonNull(
                            run.execute(input, cases.get(index)),
                            "adapter result"
                        );
                        result.validateAgainst(input);
                        results.add(result);
                    }
                }
            }

            int expectedRuns =
                PolynomialTheoryUtilityExecutionPlan.PROFILES.size()
                    * PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS.size();
            if (runIds.size() != expectedRuns) {
                throw new IllegalStateException(
                    "runner did not execute exactly the frozen runs"
                );
            }
            return CandidateBatch.create(inputs, results);
        }

        private static void requireRunChunk(
            PolynomialTheoryUtilityExecutionInput first,
            List<PolynomialTheoryUtilityExecutionInput> runInputs,
            List<PolynomialTheoryUtilityCaseCorpus.FormationCase> cases
        ) {
            for (int index = 0; index < runInputs.size(); index++) {
                var input = runInputs.get(index);
                if (!first.runId().equals(input.runId())
                        || !first.profileId().equals(input.profileId())
                        || !first.checkpointId().equals(input.checkpointId())
                        || !first.adapterId().equals(input.adapterId())
                        || !input.caseId().equals(cases.get(index).caseId())) {
                    throw new IllegalStateException(
                        "execution input differs from its frozen run position"
                    );
                }
            }
        }
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }
}
