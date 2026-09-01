package de.regelsuche.benchmark.polynomial;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** One immutable terminal result for an exact frozen utility-study input. */
public record PolynomialTheoryUtilityCandidateResult(
    String resultId,
    PolynomialTheoryUtilityExecutionInput input,
    String sourceRootExpression,
    TerminalStatus terminalStatus,
    String detailCode,
    PolynomialTheoryUtilityWorkBreakdown work,
    List<PolynomialTheoryUtilityTransitionOutcome> transitions,
    String verifierOutcome
) {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-candidate-result/v2";
    public static final String NO_TRANSITION_EVIDENCE = "NONE";
    private static final Pattern SHA_256 =
        Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Map<
        String,
        PolynomialTheoryUtilityExecutionInput
    > FROZEN_INPUTS = loadFrozenInputs();
    private static final Map<
        String,
        PolynomialTheoryUtilityCaseCorpus.FormationCase
    > FORMATION_CASES = loadFormationCases();

    public PolynomialTheoryUtilityCandidateResult {
        resultId = requireHash(resultId, "resultId");
        input = Objects.requireNonNull(input, "input");
        if (!input.equals(frozenInput(input.inputId()))) {
            throw new IllegalArgumentException(
                "candidate result input differs from the frozen matrix"
            );
        }
        sourceRootExpression = requireText(
            sourceRootExpression,
            "sourceRootExpression"
        );
        terminalStatus = Objects.requireNonNull(
            terminalStatus,
            "terminalStatus"
        );
        detailCode = requireText(detailCode, "detailCode");
        work = Objects.requireNonNull(work, "work");
        transitions = List.copyOf(
            Objects.requireNonNull(transitions, "transitions")
        );
        verifierOutcome = requireText(
            verifierOutcome,
            "verifierOutcome"
        );

        var formationCase = formationCase(input.caseId());
        var profile = PolynomialTheoryUtilityExecutionInputs.profile(
            input.profileId()
        );
        if (!sourceRootExpression.equals(formationCase.sourceExpression())) {
            throw new IllegalArgumentException(
                "candidate result source differs from frozen formation"
            );
        }
        requireWorkWithinAuthority(input, work);
        requireEvidence(terminalStatus, transitions, verifierOutcome);
        requireProfileWork(profile, work, transitions);
        requireTransitions(
            input,
            formationCase,
            profile,
            work,
            transitions
        );

        if (!resultId.equals(identity(
                input,
                sourceRootExpression,
                terminalStatus,
                detailCode,
                work,
                transitions,
                verifierOutcome))) {
            throw new IllegalArgumentException(
                "candidate result identity differs from its fields"
            );
        }
    }

    public static PolynomialTheoryUtilityCandidateResult create(
        PolynomialTheoryUtilityExecutionInput input,
        PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase,
        TerminalStatus terminalStatus,
        String detailCode,
        PolynomialTheoryUtilityWorkBreakdown work,
        List<PolynomialTheoryUtilityTransitionOutcome> transitions,
        String verifierOutcome
    ) {
        Objects.requireNonNull(input, "input");
        var studyCase = Objects.requireNonNull(
            formationCase,
            "formationCase"
        );
        if (!input.caseId().equals(studyCase.caseId())
                || !formationCase(input.caseId()).equals(studyCase)) {
            throw new IllegalArgumentException(
                "candidate result received a substituted formation case"
            );
        }
        var retainedWork = Objects.requireNonNull(work, "work");
        List<PolynomialTheoryUtilityTransitionOutcome> retainedTransitions =
            List.copyOf(Objects.requireNonNull(transitions, "transitions"));
        return new PolynomialTheoryUtilityCandidateResult(
            identity(
                input,
                studyCase.sourceExpression(),
                terminalStatus,
                detailCode,
                retainedWork,
                retainedTransitions,
                verifierOutcome
            ),
            input,
            studyCase.sourceExpression(),
            terminalStatus,
            detailCode,
            retainedWork,
            retainedTransitions,
            verifierOutcome
        );
    }

    public static PolynomialTheoryUtilityCandidateResult noTransition(
        PolynomialTheoryUtilityExecutionInput input,
        PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase,
        String detailCode
    ) {
        return create(
            input,
            formationCase,
            TerminalStatus.NO_TRANSITION,
            detailCode,
            PolynomialTheoryUtilityWorkBreakdown.zero(),
            List.of(),
            "NOT_REQUESTED"
        );
    }

    public String schema() {
        return SCHEMA;
    }

    public int generatedTransitions() {
        return transitions.size();
    }

    public String transitionEvidenceHash() {
        if (transitions.isEmpty()) {
            return NO_TRANSITION_EVIDENCE;
        }
        StringBuilder material = new StringBuilder();
        append(
            material,
            "regelsuche.polynomial-theory-utility-transition-root/v1"
        );
        append(material, Integer.toString(transitions.size()));
        transitions.forEach(value -> append(
            material,
            value.transitionId()
        ));
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            material.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    public void validateAgainst(
        PolynomialTheoryUtilityExecutionInput expected,
        PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
    ) {
        if (!input.equals(Objects.requireNonNull(expected, "expected"))) {
            throw new IllegalArgumentException(
                "candidate result refers to another frozen execution input"
            );
        }
        var studyCase = Objects.requireNonNull(
            formationCase,
            "formationCase"
        );
        if (!input.caseId().equals(studyCase.caseId())
                || !formationCase(input.caseId()).equals(studyCase)
                || !sourceRootExpression.equals(
                    studyCase.sourceExpression())) {
            throw new IllegalArgumentException(
                "candidate result refers to another formation case"
            );
        }
    }

    private static void requireWorkWithinAuthority(
        PolynomialTheoryUtilityExecutionInput input,
        PolynomialTheoryUtilityWorkBreakdown work
    ) {
        if (work.primitiveWork() > input.admittedPrimitiveWork()
                || work.mechanicalWork() > input.totalMechanicalWork()
                || work.factorizationWork() > input.factorizationWork()) {
            throw new IllegalArgumentException(
                "candidate result work differs from frozen authority"
            );
        }
    }

    private static void requireEvidence(
        TerminalStatus status,
        List<PolynomialTheoryUtilityTransitionOutcome> transitions,
        String verifier
    ) {
        boolean validated = status == TerminalStatus.VALIDATED_TRANSITION;
        if (validated) {
            if (transitions.isEmpty() || !"VERIFIED".equals(verifier)) {
                throw new IllegalArgumentException(
                    "validated result lacks verifier-backed transitions"
                );
            }
        } else if (!transitions.isEmpty() || "VERIFIED".equals(verifier)) {
            throw new IllegalArgumentException(
                "non-transition result retains transition authority"
            );
        }
    }

    private static void requireProfileWork(
        PolynomialTheoryUtilityExecutionProfile profile,
        PolynomialTheoryUtilityWorkBreakdown work,
        List<PolynomialTheoryUtilityTransitionOutcome> transitions
    ) {
        if ("DISABLED".equals(profile.factorizationMode())
                && work.factorizationWork() != 0L) {
            throw new IllegalArgumentException(
                "factorization-disabled profile retained factorization work"
            );
        }
        if ("DISABLED".equals(profile.cacheMode())
                && (work.cacheLookupWork() != 0L
                    || work.cacheInsertionWork() != 0L
                    || work.cacheEvictionWork() != 0L
                    || work.cacheReplayWork() != 0L)) {
            throw new IllegalArgumentException(
                "cache-disabled profile retained aggregate cache work"
            );
        }
        if (transitions.isEmpty()
                && (work.cacheInsertionWork() != 0L
                    || work.cacheEvictionWork() != 0L
                    || work.cacheReplayWork() != 0L)) {
            throw new IllegalArgumentException(
                "transition-free result retained derived-cache mutation work"
            );
        }
    }

    private static void requireTransitions(
        PolynomialTheoryUtilityExecutionInput input,
        PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase,
        PolynomialTheoryUtilityExecutionProfile profile,
        PolynomialTheoryUtilityWorkBreakdown aggregateWork,
        List<PolynomialTheoryUtilityTransitionOutcome> transitions
    ) {
        var transitionWork = PolynomialTheoryUtilityWorkBreakdown.zero();
        Set<String> transitionIds = new HashSet<>();
        for (int index = 0; index < transitions.size(); index++) {
            var transition = Objects.requireNonNull(
                transitions.get(index),
                "transition"
            );
            transition.validateAgainst(
                index,
                input,
                formationCase,
                profile
            );
            if (!transitionIds.add(transition.transitionId())) {
                throw new IllegalArgumentException(
                    "candidate result repeats a transition identity"
                );
            }
            transitionWork = transitionWork.plus(transition.work());
        }
        if (!aggregateWork.covers(transitionWork)) {
            throw new IllegalArgumentException(
                "candidate result work does not cover transition work"
            );
        }
    }

    private static String identity(
        PolynomialTheoryUtilityExecutionInput input,
        String sourceRootExpression,
        TerminalStatus status,
        String detail,
        PolynomialTheoryUtilityWorkBreakdown work,
        List<PolynomialTheoryUtilityTransitionOutcome> transitions,
        String verifier
    ) {
        StringBuilder material = new StringBuilder();
        append(material, SCHEMA);
        append(material, PolynomialTheoryUtilityPreregistration.STUDY_ID);
        append(material, Objects.requireNonNull(input, "input").inputId());
        append(
            material,
            requireText(sourceRootExpression, "sourceRootExpression")
        );
        append(material, Objects.requireNonNull(status, "status").name());
        append(material, requireText(detail, "detailCode"));
        Objects.requireNonNull(work, "work").appendIdentityMaterial(material);
        append(material, requireText(verifier, "verifierOutcome"));
        append(material, Integer.toString(transitions.size()));
        transitions.forEach(value -> append(
            material,
            Objects.requireNonNull(value, "transition").transitionId()
        ));
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            material.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static Map<
        String,
        PolynomialTheoryUtilityExecutionInput
    > loadFrozenInputs() {
        Map<String, PolynomialTheoryUtilityExecutionInput> values =
            new LinkedHashMap<>();
        for (var value :
                PolynomialTheoryUtilityExecutionInputs.freeze().inputs()) {
            if (values.putIfAbsent(value.inputId(), value) != null) {
                throw new IllegalStateException(
                    "execution matrix repeats an input identity"
                );
            }
        }
        return Collections.unmodifiableMap(values);
    }

    private static PolynomialTheoryUtilityExecutionInput frozenInput(
        String inputId
    ) {
        var value = FROZEN_INPUTS.get(requireHash(inputId, "inputId"));
        if (value == null) {
            throw new IllegalArgumentException(
                "unknown frozen polynomial utility input: " + inputId
            );
        }
        return value;
    }

    private static Map<
        String,
        PolynomialTheoryUtilityCaseCorpus.FormationCase
    > loadFormationCases() {
        Map<String, PolynomialTheoryUtilityCaseCorpus.FormationCase> values =
            new LinkedHashMap<>();
        for (var value : PolynomialTheoryUtilityCaseCorpus.load().cases()) {
            if (values.putIfAbsent(value.caseId(), value) != null) {
                throw new IllegalStateException(
                    "formation corpus repeats a case identity"
                );
            }
        }
        return Collections.unmodifiableMap(values);
    }

    private static PolynomialTheoryUtilityCaseCorpus.FormationCase
            formationCase(String caseId) {
        var value = FORMATION_CASES.get(requireText(caseId, "caseId"));
        if (value == null) {
            throw new IllegalArgumentException(
                "unknown frozen polynomial utility case: " + caseId
            );
        }
        return value;
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
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    public enum TerminalStatus {
        VALIDATED_TRANSITION,
        NO_TRANSITION,
        UNSUPPORTED,
        BUDGET_INCONCLUSIVE,
        TECHNICAL_FAILURE
    }
}
