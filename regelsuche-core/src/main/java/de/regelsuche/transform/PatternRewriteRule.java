package de.regelsuche.transform;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Rewrite rule backed by the shared AST pattern DSL. */
public class PatternRewriteRule implements RewriteRule {
    private static final Set<String> BUILT_IN_PACK_IDS = Set.of(
        "core.ast-rewrite",
        "knowledge.algebra",
        "knowledge.binomial",
        "knowledge.factorization",
        "knowledge.trigonometry",
        "knowledge.rational-function",
        "knowledge.discovery-operators"
    );

    private final String id;
    private final PatternExpr source;
    private final PatternExpr target;
    private final RuleKind kind;
    private final boolean mayIncreaseComplexity;
    private final int estimatedCostDelta;
    private final boolean equivalencePreserving;
    private final Set<String> requiredNonZeroVariables;
    private final RuleDescriptor descriptor;
    private final MatchBudget matchBudget;
    private final RecognitionProfile recognitionProfile;
    private final ThreadLocal<PreparedMatch> preparedMatch = new ThreadLocal<>();

    public PatternRewriteRule(
        String id,
        String sourcePattern,
        String targetPattern,
        RuleKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreserving
    ) {
        this(id, sourcePattern, targetPattern, kind, mayIncreaseComplexity,
            estimatedCostDelta, equivalencePreserving, Set.of());
    }

    public PatternRewriteRule(
        String id,
        String sourcePattern,
        String targetPattern,
        RuleKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreserving,
        Set<String> requiredNonZeroVariables
    ) {
        this(
            id,
            sourcePattern,
            targetPattern,
            kind,
            mayIncreaseComplexity,
            estimatedCostDelta,
            equivalencePreserving,
            requiredNonZeroVariables,
            RuleDescriptor.builtIn("core.ast-rewrite"),
            MatchBudget.standard(),
            RecognitionProfile.SAFE_DEFAULT
        );
    }

    public PatternRewriteRule(
        String id,
        String sourcePattern,
        String targetPattern,
        RuleKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreserving,
        Set<String> requiredNonZeroVariables,
        RuleDescriptor descriptor
    ) {
        this(
            id,
            sourcePattern,
            targetPattern,
            kind,
            mayIncreaseComplexity,
            estimatedCostDelta,
            equivalencePreserving,
            requiredNonZeroVariables,
            descriptor,
            MatchBudget.standard(),
            RecognitionProfile.SAFE_DEFAULT
        );
    }

    public PatternRewriteRule(
        String id,
        String sourcePattern,
        String targetPattern,
        RuleKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreserving,
        Set<String> requiredNonZeroVariables,
        RuleDescriptor descriptor,
        MatchBudget matchBudget,
        RecognitionProfile recognitionProfile
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.source = PatternParser.parse(sourcePattern);
        this.target = PatternParser.parse(targetPattern);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.mayIncreaseComplexity = mayIncreaseComplexity;
        this.estimatedCostDelta = estimatedCostDelta;
        this.equivalencePreserving = equivalencePreserving;
        this.requiredNonZeroVariables = Set.copyOf(requiredNonZeroVariables);
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.matchBudget = Objects.requireNonNull(matchBudget, "matchBudget");
        this.recognitionProfile = Objects.requireNonNull(recognitionProfile, "recognitionProfile");
        validateDescriptor(this.descriptor);
        if (!source.variables().containsAll(this.requiredNonZeroVariables)) {
            throw new IllegalArgumentException("Non-zero variables must be bound by the source pattern");
        }
        if (!source.variables().containsAll(target.variables())) {
            throw new IllegalArgumentException("Target pattern references unbound variables");
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean matches(Expr subtree) {
        preparedMatch.remove();
        Map<String, Expr> bindings = new HashMap<>();
        if (!EquivalenceAwarePatternMatcher.match(
                source,
                subtree,
                bindings,
                recognitionProfile,
                matchBudget)) {
            return false;
        }
        preparedMatch.set(new PreparedMatch(subtree, Map.copyOf(bindings)));
        return true;
    }

    @Override
    public Expr apply(Expr subtree) {
        PreparedMatch prepared = preparedMatch.get();
        preparedMatch.remove();
        if (prepared != null && prepared.subtree() == subtree) {
            return target.instantiate(prepared.bindings());
        }
        Map<String, Expr> bindings = new HashMap<>();
        if (!EquivalenceAwarePatternMatcher.match(
                source,
                subtree,
                bindings,
                recognitionProfile,
                matchBudget)) {
            throw new IllegalArgumentException("Pattern does not match subtree for rule " + id);
        }
        return target.instantiate(bindings);
    }

    @Override
    public List<Assumption> assumptions(Expr subtree) {
        Map<String, Expr> bindings = new HashMap<>();
        if (!EquivalenceAwarePatternMatcher.match(
                source,
                subtree,
                bindings,
                recognitionProfile,
                matchBudget)) {
            return List.of();
        }
        return requiredNonZeroVariables.stream()
            .sorted()
            .map(name -> Assumption.nonZero(bindings.get(name).toString()))
            .toList();
    }

    @Override
    public RuleKind kind() {
        return kind;
    }

    @Override
    public boolean mayIncreaseComplexity() {
        return mayIncreaseComplexity;
    }

    @Override
    public int estimatedCostDelta() {
        return estimatedCostDelta;
    }

    @Override
    public boolean isEquivalencePreservingByConstruction() {
        return equivalencePreserving;
    }

    @Override
    public RuleDescriptor descriptor() {
        return descriptor;
    }

    PatternExpr sourcePattern() {
        return source;
    }

    PatternExpr targetPattern() {
        return target;
    }

    MatchBudget matchBudget() {
        return matchBudget;
    }

    RecognitionProfile recognitionProfile() {
        return recognitionProfile;
    }

    private static void validateDescriptor(RuleDescriptor descriptor) {
        if (descriptor.packId().startsWith("core.") && !BUILT_IN_PACK_IDS.contains(descriptor.packId())) {
            throw new IllegalArgumentException("Unregistered core pack id: " + descriptor.packId());
        }
    }

    private record PreparedMatch(Expr subtree, Map<String, Expr> bindings) {
        private PreparedMatch {
            Objects.requireNonNull(subtree, "subtree");
            bindings = Map.copyOf(bindings);
        }
    }
}
