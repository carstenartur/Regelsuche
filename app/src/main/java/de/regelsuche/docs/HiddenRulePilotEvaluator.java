package de.regelsuche.docs;

import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeResult;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.mining.RulePatternCanonicalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Post-hoc evaluator; hidden references never enter the runtime runner. */
public final class HiddenRulePilotEvaluator {
    private final SymPyEquivalenceService equivalence = new SymPyEquivalenceService();

    public Evaluation evaluate(
        RuntimeTask runtimeTask,
        RuntimeResult runtimeResult,
        HiddenReference hiddenReference
    ) {
        Objects.requireNonNull(runtimeTask, "runtimeTask");
        Objects.requireNonNull(runtimeResult, "runtimeResult");
        Objects.requireNonNull(hiddenReference, "hiddenReference");
        if (!runtimeTask.opaqueCaseId().equals(runtimeResult.opaqueCaseId())) {
            throw new IllegalArgumentException("runtime task/result case ids differ");
        }

        List<LeakageViolation> leakage = leakageViolations(
            runtimeTask, runtimeResult, hiddenReference);
        CandidateRelation relation = relation(runtimeResult, hiddenReference);
        boolean holdoutsPassed = runtimeResult.holdouts().allPassed();
        boolean material = runtimeResult.holdouts().materialAblations() > 0;
        boolean eligible = leakage.isEmpty()
            && runtimeResult.frozen()
            && holdoutsPassed
            && material
            && relation != CandidateRelation.NONE;
        List<String> blockers = blockers(runtimeResult, leakage, relation, material);
        return new Evaluation(
            runtimeTask.opaqueCaseId(),
            hiddenReference.family(),
            leakage,
            relation,
            runtimeResult.frozen(),
            holdoutsPassed,
            material,
            eligible,
            blockers);
    }

    private List<LeakageViolation> leakageViolations(
        RuntimeTask task,
        RuntimeResult result,
        HiddenReference reference
    ) {
        List<LeakageViolation> violations = new ArrayList<>();
        String observable = normalized(task.observableInput());
        for (String token : reference.forbiddenRuntimeTokens()) {
            if (containsToken(observable, token)) {
                violations.add(new LeakageViolation("RUNTIME_INPUT", fingerprint(token)));
            }
        }
        String hiddenId = normalized(reference.hiddenRuleId());
        for (String primitiveRuleId : result.primitiveRuleIds()) {
            if (!hiddenId.isEmpty() && normalized(primitiveRuleId).contains(hiddenId)) {
                violations.add(new LeakageViolation("PRIMITIVE_RULE_PATH", fingerprint(hiddenId)));
            }
        }
        return List.copyOf(violations);
    }

    private CandidateRelation relation(RuntimeResult result, HiddenReference reference) {
        if (result.candidate() == null) {
            return CandidateRelation.NONE;
        }
        String candidateLeft = result.candidate().leftPattern();
        String candidateRight = result.candidate().rightPattern();
        if (compact(candidateLeft).equals(compact(reference.leftPattern()))
                && compact(candidateRight).equals(compact(reference.rightPattern()))) {
            return CandidateRelation.EXACT;
        }
        if (RulePatternCanonicalizer.hash(candidateLeft, candidateRight)
                .equals(RulePatternCanonicalizer.hash(
                    reference.leftPattern(), reference.rightPattern()))) {
            return CandidateRelation.ALPHA_EQUIVALENT;
        }
        if (equivalence.areEquivalent(candidateLeft, reference.leftPattern())
                && equivalence.areEquivalent(candidateRight, reference.rightPattern())) {
            return CandidateRelation.SEMANTICALLY_EQUIVALENT;
        }
        return CandidateRelation.DIFFERENT;
    }

    private List<String> blockers(
        RuntimeResult result,
        List<LeakageViolation> leakage,
        CandidateRelation relation,
        boolean material
    ) {
        List<String> blockers = new ArrayList<>();
        if (!leakage.isEmpty()) {
            blockers.add("runtime leakage detected");
        }
        if (!result.frozen()) {
            blockers.add("candidate was not frozen");
        }
        if (!result.holdouts().allPassed()) {
            blockers.add("holdout validation failed");
        }
        if (!material) {
            blockers.add("paired ablation showed no material benefit");
        }
        if (relation == CandidateRelation.NONE) {
            blockers.add("no candidate was produced");
        }
        return List.copyOf(blockers);
    }

    private static boolean containsToken(String observable, String token) {
        String normalizedToken = normalized(token);
        if (normalizedToken.isEmpty()) {
            return false;
        }
        return observable.contains(normalizedToken)
            || compact(observable).contains(compact(normalizedToken));
    }

    private static String normalized(String value) {
        return value == null
            ? ""
            : value.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private static String compact(String value) {
        return normalized(value).replaceAll("\\s+", "");
    }

    private static String fingerprint(String value) {
        return Integer.toHexString(compact(value).hashCode());
    }

    public enum CandidateRelation {
        NONE,
        EXACT,
        ALPHA_EQUIVALENT,
        SEMANTICALLY_EQUIVALENT,
        DIFFERENT
    }

    public record HiddenReference(
        String hiddenRuleId,
        String family,
        String leftPattern,
        String rightPattern,
        List<String> forbiddenRuntimeTokens
    ) {
        public HiddenReference {
            requireText(hiddenRuleId, "hiddenRuleId");
            requireText(family, "family");
            requireText(leftPattern, "leftPattern");
            requireText(rightPattern, "rightPattern");
            List<String> tokens = new ArrayList<>();
            tokens.add(hiddenRuleId);
            tokens.add(leftPattern);
            tokens.add(rightPattern);
            if (forbiddenRuntimeTokens != null) {
                tokens.addAll(forbiddenRuntimeTokens);
            }
            forbiddenRuntimeTokens = tokens.stream()
                .filter(token -> token != null && !token.isBlank())
                .distinct()
                .toList();
        }
    }

    public record LeakageViolation(String location, String tokenFingerprint) {
    }

    public record Evaluation(
        String opaqueCaseId,
        String family,
        List<LeakageViolation> leakageViolations,
        CandidateRelation candidateRelation,
        boolean candidateFrozen,
        boolean holdoutsPassed,
        boolean materialAblation,
        boolean galleryEvidenceEligible,
        List<String> blockers
    ) {
        public Evaluation {
            leakageViolations = List.copyOf(leakageViolations);
            blockers = List.copyOf(blockers);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
