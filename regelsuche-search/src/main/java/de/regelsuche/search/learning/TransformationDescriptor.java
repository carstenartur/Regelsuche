package de.regelsuche.search.learning;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.search.strategy.SearchProblem.TargetRelation;
import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchEventType;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.value.ExprValueFactory;
import de.regelsuche.value.ExprValueFactory.AssociativeCommutativeValue;
import de.regelsuche.value.ExprValueFactory.ExprValue;
import de.regelsuche.value.ExprValueFactory.OrderedValue;
import de.regelsuche.value.ExprValueFactory.ValueKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Versioned, rule-ID-independent description of one already-applicable transformation.
 * Every predictive field is available before candidate selection; outcome labels and
 * hidden-reference metadata are deliberately absent.
 */
public record TransformationDescriptor(
    RewriteKind rewriteKind,
    boolean equivalencePreserving,
    boolean mayIncreaseComplexity,
    int estimatedCostDelta,
    AstDelta astDelta,
    RootSignature parentRoot,
    RootSignature childRoot,
    LocalChange localChange,
    int assumptionCount,
    Map<AssumptionClass, Integer> assumptionClassCounts,
    boolean targeted,
    int targetDistanceBefore,
    int targetDistanceAfter,
    int targetDistanceDelta,
    boolean available
) {
    public static final String SCHEMA = "regelsuche.transformation-descriptor/v2";

    public TransformationDescriptor {
        Objects.requireNonNull(rewriteKind, "rewriteKind");
        Objects.requireNonNull(astDelta, "astDelta");
        Objects.requireNonNull(parentRoot, "parentRoot");
        Objects.requireNonNull(childRoot, "childRoot");
        Objects.requireNonNull(localChange, "localChange");
        if (assumptionCount < 0) {
            throw new IllegalArgumentException("assumptionCount must not be negative");
        }
        EnumMap<AssumptionClass, Integer> sorted = new EnumMap<>(AssumptionClass.class);
        if (assumptionClassCounts != null) {
            assumptionClassCounts.forEach((kind, count) -> {
                if (kind == null || count == null || count < 1) {
                    throw new IllegalArgumentException("assumption class counts must be positive");
                }
                sorted.put(kind, count);
            });
        }
        if (sorted.values().stream().mapToInt(Integer::intValue).sum() != assumptionCount) {
            throw new IllegalArgumentException("assumption class counts must match assumptionCount");
        }
        assumptionClassCounts = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
        if (targeted) {
            if (targetDistanceBefore < 0 || targetDistanceAfter < 0
                    || targetDistanceDelta != targetDistanceAfter - targetDistanceBefore) {
                throw new IllegalArgumentException("invalid target-distance descriptor");
            }
        } else if (targetDistanceBefore != -1 || targetDistanceAfter != -1
                || targetDistanceDelta != 0) {
            throw new IllegalArgumentException("untargeted descriptors use -1/-1/0 distances");
        }
    }

    /** Sorted, inspectable linear feature vector; concrete rule IDs never enter this map. */
    public Map<String, Integer> featureVector() {
        Map<String, Integer> features = new TreeMap<>();
        features.put("bias", 1);
        features.put("rewriteKind." + rewriteKind.name(), 1);
        features.put("equivalencePreserving", equivalencePreserving ? 1 : 0);
        features.put("mayIncreaseComplexity", mayIncreaseComplexity ? 1 : 0);
        features.put("estimatedCostDelta", estimatedCostDelta);
        astDelta.addTo(features, "ast.");
        features.put("root.parent." + parentRoot.kind().name(), 1);
        features.put("root.child." + childRoot.kind().name(), 1);
        features.put("root.transition." + parentRoot.kind().name()
            + "_TO_" + childRoot.kind().name(), 1);
        features.put("root.parentArity", parentRoot.arity());
        features.put("root.childArity", childRoot.arity());
        features.put("root.arityDelta", childRoot.arity() - parentRoot.arity());
        localChange.addTo(features);
        features.put("assumption.count", assumptionCount);
        assumptionClassCounts.forEach((kind, count) ->
            features.put("assumption." + kind.name(), count));
        features.put("targeted", targeted ? 1 : 0);
        if (targeted) {
            features.put("target.distanceBefore", targetDistanceBefore);
            features.put("target.distanceAfter", targetDistanceAfter);
            features.put("target.distanceDelta", targetDistanceDelta);
        }
        features.put("available", available ? 1 : 0);
        return Collections.unmodifiableMap(new LinkedHashMap<>(features));
    }

    /** Canonical predictive material used for hashes and model training. */
    public String predictiveMaterial() {
        StringBuilder material = new StringBuilder(SCHEMA);
        featureVector().forEach((name, value) -> material
            .append('\n').append(name).append('=').append(value));
        return material.toString();
    }

    public String predictiveFingerprint() {
        return "sha256:" + sha256(predictiveMaterial());
    }

    public record AstDelta(
        int nodeCount,
        int maxDepth,
        int variableOccurrences,
        int distinctVariables,
        int numericLiterals,
        int additions,
        int subtractions,
        int multiplications,
        int divisions,
        int powers,
        int functions,
        boolean parseable
    ) {
        static AstDelta between(ExpressionFeatures parent, ExpressionFeatures child) {
            return new AstDelta(
                child.nodeCount() - parent.nodeCount(),
                child.maxDepth() - parent.maxDepth(),
                child.variableOccurrences() - parent.variableOccurrences(),
                child.distinctVariables() - parent.distinctVariables(),
                child.numericLiterals() - parent.numericLiterals(),
                child.additions() - parent.additions(),
                child.subtractions() - parent.subtractions(),
                child.multiplications() - parent.multiplications(),
                child.divisions() - parent.divisions(),
                child.powers() - parent.powers(),
                child.functions() - parent.functions(),
                parent.parseable() && child.parseable());
        }

        private void addTo(Map<String, Integer> features, String prefix) {
            features.put(prefix + "nodeCountDelta", nodeCount);
            features.put(prefix + "maxDepthDelta", maxDepth);
            features.put(prefix + "variableOccurrencesDelta", variableOccurrences);
            features.put(prefix + "distinctVariablesDelta", distinctVariables);
            features.put(prefix + "numericLiteralsDelta", numericLiterals);
            features.put(prefix + "additionsDelta", additions);
            features.put(prefix + "subtractionsDelta", subtractions);
            features.put(prefix + "multiplicationsDelta", multiplications);
            features.put(prefix + "divisionsDelta", divisions);
            features.put(prefix + "powersDelta", powers);
            features.put(prefix + "functionsDelta", functions);
            features.put(prefix + "parseable", parseable ? 1 : 0);
        }

        private static AstDelta unavailable() {
            return between(ExpressionFeatures.unavailable(), ExpressionFeatures.unavailable());
        }
    }

    /** Conservative local tree-difference inferred without rule or application identifiers. */
    public record LocalChange(
        LocalStatus status,
        int depth,
        OccurrenceRole role,
        int argumentIndex,
        RootSignature contextRoot,
        RootSignature beforeRoot,
        RootSignature afterRoot,
        AstDelta astDelta
    ) {
        private static final RootSignature NO_ROOT =
            new RootSignature(RootKind.UNPARSEABLE, 0);

        public LocalChange {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(contextRoot, "contextRoot");
            Objects.requireNonNull(beforeRoot, "beforeRoot");
            Objects.requireNonNull(afterRoot, "afterRoot");
            Objects.requireNonNull(astDelta, "astDelta");
            if (status == LocalStatus.AVAILABLE) {
                if (depth < 0 || role == OccurrenceRole.UNAVAILABLE || !astDelta.parseable()) {
                    throw new IllegalArgumentException("available local change requires a position");
                }
                if (role == OccurrenceRole.ARGUMENT && argumentIndex < 0) {
                    throw new IllegalArgumentException("function argument role requires an index");
                }
                if (role != OccurrenceRole.ARGUMENT && argumentIndex != -1) {
                    throw new IllegalArgumentException("only function arguments carry an index");
                }
            } else if (depth != -1 || role != OccurrenceRole.UNAVAILABLE
                    || argumentIndex != -1) {
                throw new IllegalArgumentException("unavailable local changes carry no position");
            }
        }

        public boolean available() {
            return status == LocalStatus.AVAILABLE;
        }

        private static LocalChange available(
            int depth,
            OccurrenceRole role,
            int argumentIndex,
            RootSignature contextRoot,
            Expr before,
            Expr after
        ) {
            return new LocalChange(
                LocalStatus.AVAILABLE,
                depth,
                role,
                argumentIndex,
                contextRoot,
                rootSignature(before),
                rootSignature(after),
                AstDelta.between(ExpressionFeatures.of(before), ExpressionFeatures.of(after)));
        }

        private static LocalChange unavailable(LocalStatus status) {
            if (status == LocalStatus.AVAILABLE) {
                throw new IllegalArgumentException("unavailable status must not be AVAILABLE");
            }
            return new LocalChange(
                status,
                -1,
                OccurrenceRole.UNAVAILABLE,
                -1,
                NO_ROOT,
                NO_ROOT,
                NO_ROOT,
                AstDelta.unavailable());
        }

        private void addTo(Map<String, Integer> features) {
            features.put("local.status." + status.name(), 1);
            features.put("local.available", available() ? 1 : 0);
            if (!available()) {
                return;
            }
            features.put("local.depth", depth);
            features.put("local.role." + role.name(), 1);
            if (role == OccurrenceRole.ARGUMENT) {
                features.put("local.argumentIndex", argumentIndex);
            }
            features.put("local.before." + beforeRoot.kind().name(), 1);
            features.put("local.after." + afterRoot.kind().name(), 1);
            features.put("local.transition." + beforeRoot.kind().name()
                + "_TO_" + afterRoot.kind().name(), 1);
            features.put("local.beforeArity", beforeRoot.arity());
            features.put("local.afterArity", afterRoot.arity());
            features.put("local.arityDelta", afterRoot.arity() - beforeRoot.arity());
            if (role != OccurrenceRole.ROOT) {
                features.put("local.context." + contextRoot.kind().name(), 1);
                features.put("local.contextArity", contextRoot.arity());
            }
            astDelta.addTo(features, "local.ast.");
        }
    }

    public record RootSignature(RootKind kind, int arity) {
        public RootSignature {
            Objects.requireNonNull(kind, "kind");
            if (arity < 0) {
                throw new IllegalArgumentException("root arity must not be negative");
            }
        }
    }

    public enum RootKind {
        ADD,
        SUB,
        MUL,
        DIV,
        POW,
        FUNCTION,
        VARIABLE,
        NUMBER,
        UNPARSEABLE
    }

    public enum LocalStatus {
        AVAILABLE,
        AMBIGUOUS,
        UNPARSEABLE,
        IDENTICAL
    }

    public enum OccurrenceRole {
        ROOT,
        LEFT,
        RIGHT,
        AC_CHILD,
        ARGUMENT,
        UNAVAILABLE
    }

    public enum AssumptionClass {
        NON_ZERO,
        POSITIVE,
        NON_NEGATIVE,
        INTEGER,
        NATURAL,
        RATIONAL,
        REAL,
        INVERTIBLE,
        DOMAIN_MEMBERSHIP,
        CUSTOM
    }

    /** Bounded owner for deterministic descriptor creation for one search problem. */
    public static final class Factory implements AutoCloseable {
        private static final int UNPARSEABLE_DISTANCE = 100_000;

        private final SearchTarget target;
        private final ExpressionCanonicalizer canonicalizer;
        private final ExpressionParser parser = new ExpressionParser();
        private final ExprValueFactory valueFactory = new ExprValueFactory();
        private final String normalizedTarget;
        private final ExprValue targetValue;
        private final Map<ValueKey, Integer> targetOccurrences;
        private final Map<String, Integer> distanceCache = new LinkedHashMap<>();

        public Factory(SearchTarget target, ExpressionCanonicalizer canonicalizer) {
            this.target = target;
            this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
            normalizedTarget = target == null ? "" : normalize(target.targetExpression());
            targetValue = target == null ? null : value(normalizedTarget);
            targetOccurrences = targetValue == null ? Map.of() : occurrenceMultiset(targetValue);
        }

        public TransformationDescriptor from(SearchEvent event) {
            Objects.requireNonNull(event, "event");
            if (event.type() != SearchEventType.TRANSFORMATION_GENERATED
                    || event.parentExpression().isBlank()
                    || event.rewriteKind() == null) {
                throw new IllegalArgumentException("descriptor requires a transformation decision event");
            }
            Expr parent = parse(event.parentExpression());
            Expr child = parse(event.expression());
            ExpressionFeatures parentFeatures = parent == null
                ? ExpressionFeatures.unavailable()
                : ExpressionFeatures.of(parent);
            ExpressionFeatures childFeatures = child == null
                ? ExpressionFeatures.unavailable()
                : ExpressionFeatures.of(child);
            AstDelta delta = AstDelta.between(parentFeatures, childFeatures);
            RootSignature parentRoot = rootSignature(parent);
            RootSignature childRoot = rootSignature(child);
            LocalChange localChange = inferLocalChange(parent, child);
            Map<AssumptionClass, Integer> assumptionClasses = assumptionClasses(event.assumptions());
            boolean targeted = target != null;
            int before = targeted ? distance(event.parentExpression()) : -1;
            int after = targeted ? distance(event.expression()) : -1;
            return new TransformationDescriptor(
                event.rewriteKind(),
                event.equivalencePreservingByConstruction(),
                event.mayIncreaseComplexity(),
                event.estimatedCostDelta(),
                delta,
                parentRoot,
                childRoot,
                localChange,
                event.assumptions().size(),
                assumptionClasses,
                targeted,
                before,
                after,
                targeted ? after - before : 0,
                delta.parseable()
                    && parentRoot.kind() != RootKind.UNPARSEABLE
                    && childRoot.kind() != RootKind.UNPARSEABLE
                    && (!targeted || before < UNPARSEABLE_DISTANCE
                        && after < UNPARSEABLE_DISTANCE));
        }

        private Expr parse(String expression) {
            try {
                return parser.parseTerm(expression);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        private int distance(String expression) {
            String normalized = normalize(expression);
            return distanceCache.computeIfAbsent(normalized, this::computeDistance);
        }

        private int computeDistance(String expression) {
            if (target.relation() == TargetRelation.SYNTAX_EXACT
                    && expression.equals(normalizedTarget)) {
                return 0;
            }
            if (targetValue == null) {
                return UNPARSEABLE_DISTANCE;
            }
            ExprValue candidate = value(expression);
            if (candidate == null) {
                return UNPARSEABLE_DISTANCE;
            }
            int semantic = semanticDistance(candidate, targetValue, targetOccurrences);
            if (target.relation() != TargetRelation.SYNTAX_EXACT) {
                return semantic;
            }
            return Math.min(UNPARSEABLE_DISTANCE - 1, semantic + 1);
        }

        private ExprValue value(String expression) {
            try {
                Expr parsed = parser.parseTerm(expression);
                return valueFactory.fromExpr(canonicalizer.canonicalize(parsed));
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        @Override
        public void close() {
            distanceCache.clear();
            valueFactory.close();
        }
    }

    private static LocalChange inferLocalChange(Expr parent, Expr child) {
        if (parent == null || child == null) {
            return LocalChange.unavailable(LocalStatus.UNPARSEABLE);
        }
        if (parent.equals(child)) {
            return LocalChange.unavailable(LocalStatus.IDENTICAL);
        }
        return descendLocalChange(
            parent,
            child,
            0,
            OccurrenceRole.ROOT,
            -1,
            new RootSignature(RootKind.UNPARSEABLE, 0));
    }

    private static LocalChange descendLocalChange(
        Expr parent,
        Expr child,
        int depth,
        OccurrenceRole role,
        int argumentIndex,
        RootSignature contextRoot
    ) {
        if (parent instanceof BinaryExpr parentBinary
                && child instanceof BinaryExpr childBinary
                && parentBinary.operator() == childBinary.operator()) {
            boolean leftSame = parentBinary.left().equals(childBinary.left());
            boolean rightSame = parentBinary.right().equals(childBinary.right());
            if (leftSame != rightSame) {
                boolean leftChanged = !leftSame;
                OccurrenceRole childRole = associativeCommutative(parentBinary.operator())
                    ? OccurrenceRole.AC_CHILD
                    : leftChanged ? OccurrenceRole.LEFT : OccurrenceRole.RIGHT;
                return descendLocalChange(
                    leftChanged ? parentBinary.left() : parentBinary.right(),
                    leftChanged ? childBinary.left() : childBinary.right(),
                    depth + 1,
                    childRole,
                    -1,
                    rootSignature(parent));
            }
            return LocalChange.unavailable(LocalStatus.AMBIGUOUS);
        }
        if (parent instanceof FunctionExpr parentFunction
                && child instanceof FunctionExpr childFunction
                && parentFunction.name().equals(childFunction.name())
                && parentFunction.arguments().size() == childFunction.arguments().size()) {
            int changedIndex = -1;
            for (int index = 0; index < parentFunction.arguments().size(); index++) {
                if (!parentFunction.arguments().get(index)
                        .equals(childFunction.arguments().get(index))) {
                    if (changedIndex >= 0) {
                        return LocalChange.unavailable(LocalStatus.AMBIGUOUS);
                    }
                    changedIndex = index;
                }
            }
            if (changedIndex < 0) {
                return LocalChange.unavailable(LocalStatus.IDENTICAL);
            }
            return descendLocalChange(
                parentFunction.arguments().get(changedIndex),
                childFunction.arguments().get(changedIndex),
                depth + 1,
                OccurrenceRole.ARGUMENT,
                changedIndex,
                rootSignature(parent));
        }
        return LocalChange.available(
            depth,
            role,
            argumentIndex,
            contextRoot,
            parent,
            child);
    }

    private static boolean associativeCommutative(BinaryOperator operator) {
        return operator == BinaryOperator.ADD || operator == BinaryOperator.MUL;
    }

    private static RootSignature rootSignature(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            RootKind kind = switch (binary.operator()) {
                case ADD -> RootKind.ADD;
                case SUB -> RootKind.SUB;
                case MUL -> RootKind.MUL;
                case DIV -> RootKind.DIV;
                case POW -> RootKind.POW;
            };
            int arity = kind == RootKind.ADD || kind == RootKind.MUL
                ? flattenedArity(expression, binary.operator())
                : 2;
            return new RootSignature(kind, arity);
        }
        if (expression instanceof FunctionExpr function) {
            return new RootSignature(RootKind.FUNCTION, function.arguments().size());
        }
        if (expression instanceof VariableExpr) {
            return new RootSignature(RootKind.VARIABLE, 0);
        }
        if (expression instanceof NumberExpr) {
            return new RootSignature(RootKind.NUMBER, 0);
        }
        return new RootSignature(RootKind.UNPARSEABLE, 0);
    }

    private static int flattenedArity(
        Expr expression,
        BinaryOperator operator
    ) {
        if (expression instanceof BinaryExpr binary && binary.operator() == operator) {
            return flattenedArity(binary.left(), operator)
                + flattenedArity(binary.right(), operator);
        }
        return 1;
    }

    private static Map<AssumptionClass, Integer> assumptionClasses(List<String> assumptions) {
        EnumMap<AssumptionClass, Integer> counts = new EnumMap<>(AssumptionClass.class);
        assumptions.forEach(assumption -> counts.merge(classify(assumption), 1, Integer::sum));
        return counts;
    }

    private static AssumptionClass classify(String assumption) {
        String normalized = AssumptionSignature.normalizeExpression(assumption);
        if (normalized.contains(" != 0")) {
            return AssumptionClass.NON_ZERO;
        }
        if (normalized.contains(" >= 0")) {
            return AssumptionClass.NON_NEGATIVE;
        }
        if (normalized.contains(" > 0")) {
            return AssumptionClass.POSITIVE;
        }
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("∈ N") || lower.contains(" in n")) {
            return AssumptionClass.NATURAL;
        }
        if (normalized.contains("∈ Z") || lower.contains(" in z")) {
            return AssumptionClass.INTEGER;
        }
        if (normalized.contains("∈ Q") || lower.contains(" in q")) {
            return AssumptionClass.RATIONAL;
        }
        if (normalized.contains("∈ R") || lower.contains(" in r")) {
            return AssumptionClass.REAL;
        }
        if (lower.contains("invertible")) {
            return AssumptionClass.INVERTIBLE;
        }
        if (normalized.contains("∈") || lower.contains(" in ")) {
            return AssumptionClass.DOMAIN_MEMBERSHIP;
        }
        return AssumptionClass.CUSTOM;
    }

    private static int semanticDistance(
        ExprValue candidate,
        ExprValue target,
        Map<ValueKey, Integer> targetOccurrences
    ) {
        if (candidate.sameValue(target)) {
            return 0;
        }
        Map<ValueKey, Integer> candidateOccurrences = occurrenceMultiset(candidate);
        Set<ValueKey> keys = new HashSet<>(candidateOccurrences.keySet());
        keys.addAll(targetOccurrences.keySet());
        long difference = 0;
        for (ValueKey key : keys) {
            difference += Math.abs(
                candidateOccurrences.getOrDefault(key, 0)
                    - targetOccurrences.getOrDefault(key, 0));
        }
        if (!valueRootSignature(candidate).equals(valueRootSignature(target))) {
            difference += 2;
        }
        return difference >= Factory.UNPARSEABLE_DISTANCE
            ? Factory.UNPARSEABLE_DISTANCE - 1
            : (int) difference;
    }

    private static Map<ValueKey, Integer> occurrenceMultiset(ExprValue root) {
        Map<ValueKey, Integer> counts = new LinkedHashMap<>();
        collect(root, 1, counts);
        return Map.copyOf(counts);
    }

    private static void collect(
        ExprValue value,
        int multiplicity,
        Map<ValueKey, Integer> counts
    ) {
        counts.merge(value.key(), multiplicity, Math::addExact);
        if (value instanceof OrderedValue ordered) {
            ordered.operands().forEach(operand -> collect(operand, multiplicity, counts));
        } else if (value instanceof AssociativeCommutativeValue ac) {
            ac.multiplicities().forEach((operand, count) ->
                collect(operand, Math.multiplyExact(multiplicity, count), counts));
        }
    }

    private static String valueRootSignature(ExprValue value) {
        if (value instanceof OrderedValue ordered) {
            return "ordered:" + ordered.operator().id();
        }
        if (value instanceof AssociativeCommutativeValue ac) {
            return "ac:" + ac.operator().id();
        }
        return value.getClass().getSimpleName();
    }

    private static String normalize(String expression) {
        return expression == null ? "" : expression.trim().replaceAll("\\s+", " ");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
