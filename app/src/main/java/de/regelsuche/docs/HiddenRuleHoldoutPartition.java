package de.regelsuche.docs;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.docs.HiddenRulePilotRunner.NegativeHoldout;
import de.regelsuche.docs.HiddenRulePilotRunner.PositiveHoldout;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.value.ExprValueFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Audits train/holdout separation with both mathematical {@code ValueKey}
 * identity and syntax-shape alpha equivalence.
 */
public final class HiddenRuleHoldoutPartition {
    private final ExpressionParser parser = new ExpressionParser();

    public SplitAudit audit(RuntimeTask task) {
        Objects.requireNonNull(task, "task");
        PairFingerprint training = pair(
            task.inputExpression(), task.target().targetExpression());
        InputFingerprint trainingInput = input(task.inputExpression());

        List<HoldoutFingerprint> positives = task.positiveHoldouts().stream()
            .map(holdout -> new HoldoutFingerprint(
                holdout.id(),
                pair(holdout.inputExpression(), holdout.targetExpression())))
            .toList();
        List<NegativeFingerprint> negatives = task.negativeHoldouts().stream()
            .map(holdout -> new NegativeFingerprint(holdout.id(), input(holdout.inputExpression())))
            .toList();

        List<SplitCollision> collisions = new ArrayList<>();
        for (HoldoutFingerprint holdout : positives) {
            if (holdout.fingerprint().exactValue().equals(training.exactValue())) {
                collisions.add(new SplitCollision(
                    "TRAIN_POSITIVE_VALUE", holdout.id(), training.exactValue()));
            }
            if (holdout.fingerprint().alphaShape().equals(training.alphaShape())) {
                collisions.add(new SplitCollision(
                    "TRAIN_POSITIVE_ALPHA", holdout.id(), training.alphaShape()));
            }
        }
        for (NegativeFingerprint holdout : negatives) {
            if (holdout.fingerprint().exactValue().equals(trainingInput.exactValue())) {
                collisions.add(new SplitCollision(
                    "TRAIN_NEGATIVE_VALUE", holdout.id(), trainingInput.exactValue()));
            }
            if (holdout.fingerprint().alphaShape().equals(trainingInput.alphaShape())) {
                collisions.add(new SplitCollision(
                    "TRAIN_NEGATIVE_ALPHA", holdout.id(), trainingInput.alphaShape()));
            }
        }
        duplicatePositiveCollisions(positives, collisions);
        duplicateNegativeCollisions(negatives, collisions);

        return new SplitAudit(training, positives, negatives, List.copyOf(collisions));
    }

    private void duplicatePositiveCollisions(
        List<HoldoutFingerprint> holdouts,
        List<SplitCollision> collisions
    ) {
        Map<String, String> exactOwners = new LinkedHashMap<>();
        Map<String, String> alphaOwners = new LinkedHashMap<>();
        for (HoldoutFingerprint holdout : holdouts) {
            recordDuplicate(
                exactOwners, holdout.fingerprint().exactValue(), holdout.id(),
                "POSITIVE_VALUE_DUPLICATE", collisions);
            recordDuplicate(
                alphaOwners, holdout.fingerprint().alphaShape(), holdout.id(),
                "POSITIVE_ALPHA_DUPLICATE", collisions);
        }
    }

    private void duplicateNegativeCollisions(
        List<NegativeFingerprint> holdouts,
        List<SplitCollision> collisions
    ) {
        Map<String, String> exactOwners = new LinkedHashMap<>();
        Map<String, String> alphaOwners = new LinkedHashMap<>();
        for (NegativeFingerprint holdout : holdouts) {
            recordDuplicate(
                exactOwners, holdout.fingerprint().exactValue(), holdout.id(),
                "NEGATIVE_VALUE_DUPLICATE", collisions);
            recordDuplicate(
                alphaOwners, holdout.fingerprint().alphaShape(), holdout.id(),
                "NEGATIVE_ALPHA_DUPLICATE", collisions);
        }
    }

    private static void recordDuplicate(
        Map<String, String> owners,
        String fingerprint,
        String id,
        String kind,
        List<SplitCollision> collisions
    ) {
        String previous = owners.putIfAbsent(fingerprint, id);
        if (previous != null) {
            collisions.add(new SplitCollision(kind, previous + "," + id, fingerprint));
        }
    }

    private PairFingerprint pair(String input, String target) {
        Expr inputExpr = parser.parseTerm(input);
        Expr targetExpr = parser.parseTerm(target);
        String exact;
        String alpha;
        try (ExprValueFactory factory = new ExprValueFactory()) {
            exact = factory.fromExpr(inputExpr).key() + "->" + factory.fromExpr(targetExpr).key();
        }
        Map<String, String> variableNames = new LinkedHashMap<>();
        Expr alphaInput = alphaNormalize(inputExpr, variableNames);
        Expr alphaTarget = alphaNormalize(targetExpr, variableNames);
        try (ExprValueFactory factory = new ExprValueFactory()) {
            alpha = factory.fromExpr(alphaInput).key() + "->" + factory.fromExpr(alphaTarget).key();
        }
        return new PairFingerprint(digest(exact), digest(alpha));
    }

    private InputFingerprint input(String expression) {
        Expr parsed = parser.parseTerm(expression);
        String exact;
        String alpha;
        try (ExprValueFactory factory = new ExprValueFactory()) {
            exact = factory.fromExpr(parsed).key().toString();
        }
        Expr alphaExpr = alphaNormalize(parsed, new LinkedHashMap<>());
        try (ExprValueFactory factory = new ExprValueFactory()) {
            alpha = factory.fromExpr(alphaExpr).key().toString();
        }
        return new InputFingerprint(digest(exact), digest(alpha));
    }

    private Expr alphaNormalize(Expr expression, Map<String, String> variables) {
        if (expression instanceof VariableExpr variable) {
            String renamed = variables.computeIfAbsent(
                variable.name(), ignored -> "v" + variables.size());
            return new VariableExpr(renamed);
        }
        if (expression instanceof NumberExpr) {
            return expression;
        }
        if (expression instanceof FunctionExpr function) {
            return new FunctionExpr(
                function.name(),
                function.arguments().stream()
                    .map(argument -> alphaNormalize(argument, variables))
                    .toList());
        }
        BinaryExpr binary = (BinaryExpr) expression;
        return new BinaryExpr(
            alphaNormalize(binary.left(), variables),
            binary.operator(),
            alphaNormalize(binary.right(), variables));
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record PairFingerprint(String exactValue, String alphaShape) {
    }

    public record InputFingerprint(String exactValue, String alphaShape) {
    }

    public record HoldoutFingerprint(String id, PairFingerprint fingerprint) {
    }

    public record NegativeFingerprint(String id, InputFingerprint fingerprint) {
    }

    public record SplitCollision(String kind, String holdoutId, String fingerprint) {
    }

    public record SplitAudit(
        PairFingerprint training,
        List<HoldoutFingerprint> positives,
        List<NegativeFingerprint> negatives,
        List<SplitCollision> collisions
    ) {
        public SplitAudit {
            positives = List.copyOf(positives);
            negatives = List.copyOf(negatives);
            collisions = List.copyOf(collisions);
        }

        public boolean passed() {
            return collisions.isEmpty();
        }
    }
}
