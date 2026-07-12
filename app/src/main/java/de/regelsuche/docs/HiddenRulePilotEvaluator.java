package de.regelsuche.docs;

import de.regelsuche.docs.HiddenRuleHoldoutPartition.SplitCollision;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeResult;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.mining.RulePatternCanonicalizer;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Post-hoc evaluator; hidden references never enter the runtime runner. */
public final class HiddenRulePilotEvaluator {
    private final SymPyEquivalenceService equivalence = new SymPyEquivalenceService();
    private final HiddenRuleHoldoutPartition partition = new HiddenRuleHoldoutPartition();

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
        boolean rediscovered = relation != CandidateRelation.NONE
            && relation != CandidateRelation.DIFFERENT;
        boolean accepted = leakage.isEmpty()
            && runtimeResult.frozen()
            && holdoutsPassed
            && material
            && rediscovered;
        return new Evaluation(
            runtimeTask.opaqueCaseId(),
            hiddenReference.family(),
            leakage,
            relation,
            runtimeResult.frozen(),
            holdoutsPassed,
            material,
            accepted,
            blockers(runtimeResult, leakage, relation, material, rediscovered));
    }

    private List<LeakageViolation> leakageViolations(
        RuntimeTask task,
        RuntimeResult result,
        HiddenReference reference
    ) {
        List<LeakageViolation> violations = new ArrayList<>();
        String observable = normalized(task.observableInput());
        for (String token : reference.forbiddenRuntimeTokens()) {
            if (containsForbidden(observable, token)) {
                violations.add(new LeakageViolation("RUNTIME_INPUT", fingerprint(token)));
            }
        }
        inspectPrimitiveRules(task, reference, violations);
        String hiddenId = normalized(reference.hiddenRuleId());
        for (String primitiveRuleId : result.primitiveRuleIds()) {
            if (!hiddenId.isEmpty() && normalized(primitiveRuleId).equals(hiddenId)) {
                violations.add(new LeakageViolation(
                    "PRIMITIVE_RULE_PATH", fingerprint(hiddenId)));
            }
        }
        for (SplitCollision collision : partition.audit(task).collisions()) {
            violations.add(new LeakageViolation(
                collision.kind(), collision.fingerprint()));
        }
        return violations.stream().distinct().toList();
    }

    private static void inspectPrimitiveRules(
        RuntimeTask task,
        HiddenReference reference,
        List<LeakageViolation> violations
    ) {
        if (!(task.primitiveEngine() instanceof AstRewriteTransformationEngine engine)) {
            return;
        }
        String hiddenId = normalized(reference.hiddenRuleId());
        String hiddenPattern = RulePatternCanonicalizer.hash(
            reference.leftPattern(), reference.rightPattern());
        for (RewriteRule rule : engine.rules()) {
            if (normalized(rule.id()).equals(hiddenId)) {
                violations.add(new LeakageViolation(
                    "PRIMITIVE_RULE_ID", fingerprint(hiddenId)));
            }
            if (rule instanceof PatternRewriteRule patternRule) {
                String visiblePattern = RulePatternCanonicalizer.hash(
                    patternText(patternRule.source()), patternText(patternRule.target()));
                if (visiblePattern.equals(hiddenPattern)) {
                    violations.add(new LeakageViolation(
                        "PRIMITIVE_RULE_TEMPLATE", fingerprint(hiddenPattern)));
                }
            }
        }
    }

    private CandidateRelation relation(RuntimeResult result, HiddenReference reference) {
        if (result.candidate() == null) {
            return CandidateRelation.NONE;
        }
        CandidateRelation structural = structuralRelation(
            result.candidate().leftPattern(),
            result.candidate().rightPattern(),
            reference.leftPattern(),
            reference.rightPattern());
        if (structural == CandidateRelation.DIFFERENT) {
            return structural;
        }

        Set<String> candidateAssumptions = normalizedAssumptions(
            result.candidate().assumptions());
        Set<String> hiddenAssumptions = normalizedAssumptions(reference.assumptions());
        if (candidateAssumptions.equals(hiddenAssumptions)) {
            return structural;
        }
        if (candidateAssumptions.containsAll(hiddenAssumptions)) {
            return CandidateRelation.WEAKER;
        }
        if (hiddenAssumptions.containsAll(candidateAssumptions)) {
            return CandidateRelation.STRONGER;
        }
        return CandidateRelation.DIFFERENT;
    }

    private CandidateRelation structuralRelation(
        String candidateLeft,
        String candidateRight,
        String referenceLeft,
        String referenceRight
    ) {
        if (compact(candidateLeft).equals(compact(referenceLeft))
                && compact(candidateRight).equals(compact(referenceRight))) {
            return CandidateRelation.EXACT;
        }
        if (RulePatternCanonicalizer.hash(candidateLeft, candidateRight)
                .equals(RulePatternCanonicalizer.hash(referenceLeft, referenceRight))) {
            return CandidateRelation.ALPHA_EQUIVALENT;
        }
        if (equivalence.areEquivalent(candidateLeft, referenceLeft)
                && equivalence.areEquivalent(candidateRight, referenceRight)) {
            return CandidateRelation.SEMANTICALLY_EQUIVALENT;
        }
        return CandidateRelation.DIFFERENT;
    }

    private static List<String> blockers(
        RuntimeResult result,
        List<LeakageViolation> leakage,
        CandidateRelation relation,
        boolean material,
        boolean rediscovered
    ) {
        List<String> blockers = new ArrayList<>();
        if (leakage.stream().anyMatch(violation ->
                violation.location().contains("TRAIN_")
                    || violation.location().contains("DUPLICATE_"))) {
            blockers.add("train/holdout split leakage detected");
        }
        if (leakage.stream().anyMatch(violation ->
                !violation.location().contains("TRAIN_")
                    && !violation.location().contains("DUPLICATE_"))) {
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
        } else if (!rediscovered) {
            blockers.add("candidate differs from hidden reference");
        }
        return List.copyOf(blockers);
    }

    private static boolean containsForbidden(String observable, String token) {
        String normalizedToken = normalized(token);
        if (normalizedToken.isEmpty()) {
            return false;
        }
        boolean identifierLike = normalizedToken.length() >= 4
            && normalizedToken.matches("[a-z0-9_.:-]+");
        if (identifierLike) {
            return observable.contains(normalizedToken);
        }
        String compactToken = compact(normalizedToken);
        return observable.lines().map(HiddenRulePilotEvaluator::compact)
            .anyMatch(line -> line.equals(compactToken));
    }

    private static Set<String> normalizedAssumptions(List<String> assumptions) {
        Set<String> result = new LinkedHashSet<>();
        if (assumptions != null) {
            assumptions.stream()
                .map(HiddenRulePilotEvaluator::normalized)
                .filter(value -> !value.isEmpty())
                .forEach(result::add);
        }
        return Set.copyOf(result);
    }

    private static String patternText(PatternExpr pattern) {
        if (pattern instanceof PatternExpr.Placeholder placeholder) {
            return placeholder.name();
        }
        if (pattern instanceof PatternExpr.LiteralNumber number) {
            return number.value() == Math.rint(number.value())
                ? Long.toString((long) number.value())
                : Double.toString(number.value());
        }
        if (pattern instanceof PatternExpr.LiteralVariable variable) {
            return variable.name();
        }
        if (pattern instanceof PatternExpr.Operation operation) {
            return "(" + patternText(operation.left()) + " " + operation.operator().symbol()
                + " " + patternText(operation.right()) + ")";
        }
        PatternExpr.Function function = (PatternExpr.Function) pattern;
        return function.name() + "(" + function.arguments().stream()
            .map(HiddenRulePilotEvaluator::patternText)
            .reduce((left, right) -> left + ", " + right)
            .orElse("") + ")";
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
        STRONGER,
        WEAKER,
        DIFFERENT
    }

    public record HiddenReference(
        String hiddenRuleId,
        String family,
        String leftPattern,
        String rightPattern,
        List<String> assumptions,
        List<String> forbiddenRuntimeTokens
    ) {
        public HiddenReference {
            requireText(hiddenRuleId, "hiddenRuleId");
            requireText(family, "family");
            requireText(leftPattern, "leftPattern");
            requireText(rightPattern, "rightPattern");
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            List<String> tokens = new ArrayList<>();
            tokens.add(hiddenRuleId);
            tokens.add(family);
            tokens.add(leftPattern);
            tokens.add(rightPattern);
            tokens.addAll(assumptions);
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
        boolean pilotAccepted,
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
