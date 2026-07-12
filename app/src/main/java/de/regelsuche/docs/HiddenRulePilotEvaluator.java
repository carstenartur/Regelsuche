package de.regelsuche.docs;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeResult;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.mining.RulePatternCanonicalizer;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.value.ExprValueFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Post-hoc evaluator; hidden references never enter the runtime runner. */
public final class HiddenRulePilotEvaluator {
    private final SymPyEquivalenceService equivalence = new SymPyEquivalenceService();
    private final ExpressionParser parser = new ExpressionParser();

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
        boolean rediscovered = relation == CandidateRelation.EXACT
            || relation == CandidateRelation.ALPHA_EQUIVALENT
            || relation == CandidateRelation.SEMANTICALLY_EQUIVALENT;
        boolean accepted = leakage.isEmpty()
            && runtimeResult.frozen()
            && holdoutsPassed
            && material
            && rediscovered;
        List<String> blockers = blockers(
            runtimeResult, leakage, relation, material, rediscovered);
        return new Evaluation(
            runtimeTask.opaqueCaseId(),
            hiddenReference.family(),
            leakage,
            relation,
            runtimeResult.frozen(),
            holdoutsPassed,
            material,
            accepted,
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
        violations.addAll(splitLeakage(task));
        return List.copyOf(violations);
    }

    private List<LeakageViolation> splitLeakage(RuntimeTask task) {
        List<LeakageViolation> violations = new ArrayList<>();
        String training = pairFingerprint(
            task.inputExpression(), task.target().targetExpression());
        Set<String> positiveClasses = new LinkedHashSet<>();
        for (HiddenRulePilotRunner.PositiveHoldout holdout : task.positiveHoldouts()) {
            String holdoutClass = pairFingerprint(
                holdout.inputExpression(), holdout.targetExpression());
            if (training.equals(holdoutClass)) {
                violations.add(new LeakageViolation(
                    "TRAIN_HOLDOUT_ALPHA_CLASS", shortFingerprint(holdout.id())));
            }
            if (!positiveClasses.add(holdoutClass)) {
                violations.add(new LeakageViolation(
                    "DUPLICATE_POSITIVE_ALPHA_CLASS", shortFingerprint(holdout.id())));
            }
        }

        String trainingInput = expressionFingerprint(task.inputExpression());
        Set<String> negativeClasses = new LinkedHashSet<>();
        for (HiddenRulePilotRunner.NegativeHoldout holdout : task.negativeHoldouts()) {
            String holdoutClass = expressionFingerprint(holdout.inputExpression());
            if (trainingInput.equals(holdoutClass)) {
                violations.add(new LeakageViolation(
                    "TRAIN_NEGATIVE_ALPHA_CLASS", shortFingerprint(holdout.id())));
            }
            if (!negativeClasses.add(holdoutClass)) {
                violations.add(new LeakageViolation(
                    "DUPLICATE_NEGATIVE_ALPHA_CLASS", shortFingerprint(holdout.id())));
            }
        }
        return violations;
    }

    private String pairFingerprint(String input, String target) {
        Map<String, String> variables = new LinkedHashMap<>();
        Expr normalizedInput = alphaNormalize(parser.parseTerm(input), variables);
        Expr normalizedTarget = alphaNormalize(parser.parseTerm(target), variables);
        try (ExprValueFactory factory = new ExprValueFactory()) {
            return factory.fromExpr(normalizedInput).key()
                + "->" + factory.fromExpr(normalizedTarget).key();
        }
    }

    private String expressionFingerprint(String expression) {
        Map<String, String> variables = new LinkedHashMap<>();
        Expr normalizedExpression = alphaNormalize(parser.parseTerm(expression), variables);
        try (ExprValueFactory factory = new ExprValueFactory()) {
            return factory.fromExpr(normalizedExpression).key().toString();
        }
    }

    private Expr alphaNormalize(Expr expression, Map<String, String> variables) {
        if (expression instanceof VariableExpr variable) {
            String replacement = variables.computeIfAbsent(
                variable.name(), ignored -> "v" + (variables.size() + 1));
            return new VariableExpr(replacement);
        }
        if (expression instanceof NumberExpr) {
            return expression;
        }
        if (expression instanceof FunctionExpr function) {
            return new FunctionExpr(function.name(), function.arguments().stream()
                .map(argument -> alphaNormalize(argument, variables))
                .toList());
        }
        BinaryExpr binary = (BinaryExpr) expression;
        return new BinaryExpr(
            alphaNormalize(binary.left(), variables),
            binary.operator(),
            alphaNormalize(binary.right(), variables));
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
        boolean material,
        boolean rediscovered
    ) {
        List<String> blockers = new ArrayList<>();
        if (leakage.stream().anyMatch(violation ->
                violation.location().contains("ALPHA_CLASS"))) {
            blockers.add("train/holdout split leakage detected");
        }
        if (leakage.stream().anyMatch(violation ->
                !violation.location().contains("ALPHA_CLASS"))) {
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

    private static boolean containsToken(String observable, String token) {
        String normalizedToken = normalized(token);
        if (!leakSensitive(normalizedToken)) {
            return false;
        }
        if (looksLikeExpression(normalizedToken)) {
            String compactToken = compact(normalizedToken);
            return observable.lines().map(HiddenRulePilotEvaluator::compact)
                .anyMatch(line -> line.equals(compactToken));
        }
        return observable.contains(normalizedToken);
    }

    private static boolean leakSensitive(String token) {
        return !token.isEmpty() && compact(token).length() >= 4;
    }

    private static boolean looksLikeExpression(String token) {
        return token.chars().anyMatch(character -> "+-*/^()".indexOf(character) >= 0);
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

    private static String shortFingerprint(String value) {
        return Integer.toHexString(Objects.requireNonNull(value, "value").hashCode());
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
