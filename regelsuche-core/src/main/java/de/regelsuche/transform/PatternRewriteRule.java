package de.regelsuche.transform;

import de.regelsuche.ast.Expr;
import de.regelsuche.knowledge.RuleDescriptor;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

public class PatternRewriteRule implements RewriteRule {
    private final String id;
    private final PatternExpr source;
    private final PatternExpr target;
    private final RewriteKind kind;
    private final boolean mayIncreaseComplexity;
    private final int estimatedCostDelta;
    private final boolean equivalencePreservingByConstruction;
    private final RuleDescriptor descriptor;
    private final RecognitionProfile recognitionProfile;
    private final ThreadLocal<WeakReference<PreparedMatch>> preparedMatch =
        new ThreadLocal<>();

    public PatternRewriteRule(String id, PatternExpr source, PatternExpr target) {
        this(id, source, target, RewriteKind.NORMALIZE, false, 0, true);
    }

    public PatternRewriteRule(
        String id,
        PatternExpr source,
        PatternExpr target,
        RecognitionProfile recognitionProfile
    ) {
        this(id, source, target, RewriteKind.NORMALIZE, false, 0, true,
            RuleDescriptor.core(id, java.util.List.of()), recognitionProfile);
    }

    public PatternRewriteRule(String id, PatternExpr source, PatternExpr target, RuleDescriptor descriptor) {
        this(id, source, target, RewriteKind.NORMALIZE, false, 0, true, descriptor);
    }

    public PatternRewriteRule(
        String id,
        PatternExpr source,
        PatternExpr target,
        RuleDescriptor descriptor,
        RecognitionProfile recognitionProfile
    ) {
        this(id, source, target, RewriteKind.NORMALIZE, false, 0, true, descriptor, recognitionProfile);
    }

    public PatternRewriteRule(
        String id,
        PatternExpr source,
        PatternExpr target,
        RewriteKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreservingByConstruction
    ) {
        this(id, source, target, kind, mayIncreaseComplexity, estimatedCostDelta, equivalencePreservingByConstruction,
            RuleDescriptor.core(id, java.util.List.of()));
    }

    public PatternRewriteRule(
        String id,
        PatternExpr source,
        PatternExpr target,
        RewriteKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreservingByConstruction,
        RuleDescriptor descriptor
    ) {
        this(id, source, target, kind, mayIncreaseComplexity, estimatedCostDelta,
            equivalencePreservingByConstruction, descriptor, RecognitionProfile.exact());
    }

    public PatternRewriteRule(
        String id,
        PatternExpr source,
        PatternExpr target,
        RewriteKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreservingByConstruction,
        RuleDescriptor descriptor,
        RecognitionProfile recognitionProfile
    ) {
        if (id == null || id.isBlank() || source == null || target == null) {
            throw new IllegalArgumentException("id, source and target are required");
        }
        this.id = id;
        this.source = source;
        this.target = target;
        this.kind = kind;
        this.mayIncreaseComplexity = mayIncreaseComplexity;
        this.estimatedCostDelta = estimatedCostDelta;
        this.equivalencePreservingByConstruction = equivalencePreservingByConstruction;
        this.descriptor = descriptor == null ? RuleDescriptor.core(id, java.util.List.of()) : descriptor;
        this.recognitionProfile = recognitionProfile == null ? RecognitionProfile.exact() : recognitionProfile;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public RuleDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Source (left-hand side) pattern of this rule. Exposed so e-graph
     * adapters in {@code de.regelsuche.egraph} can match the pattern
     * directly against e-nodes/e-classes instead of materialising every
     * concrete representative first.
     */
    public PatternExpr source() {
        return source;
    }

    /**
     * Target (right-hand side) pattern of this rule. Exposed so e-graph
     * adapters can instantiate the rewrite directly inside the e-graph
     * (a-la egg's {@code Applier}) rather than via an AST round-trip.
     */
    public PatternExpr target() {
        return target;
    }

    /**
     * Equivalences that may be used while recognizing the source pattern.
     * Existing rules remain exact unless they explicitly opt in.
     */
    public RecognitionProfile recognitionProfile() {
        return recognitionProfile;
    }

    @Override
    public RewriteKind kind() {
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
        return equivalencePreservingByConstruction;
    }

    @Override
    public boolean matches(Expr subtree) {
        preparedMatch.remove();
        Map<String, Expr> bindings = new HashMap<>();
        if (!EquivalenceAwarePatternMatcher.match(source, subtree, bindings, recognitionProfile)) {
            return false;
        }
        preparedMatch.set(new WeakReference<>(
            new PreparedMatch(subtree, Map.copyOf(bindings))));
        return true;
    }

    @Override
    public Expr apply(Expr subtree) {
        WeakReference<PreparedMatch> reference = preparedMatch.get();
        preparedMatch.remove();
        PreparedMatch prepared = reference == null ? null : reference.get();
        if (prepared != null && prepared.subtree() == subtree) {
            return target.instantiate(prepared.bindings());
        }
        Map<String, Expr> bindings = new HashMap<>();
        if (!EquivalenceAwarePatternMatcher.match(source, subtree, bindings, recognitionProfile)) {
            throw new IllegalArgumentException("Rule does not match subtree");
        }
        return target.instantiate(bindings);
    }

    private record PreparedMatch(Expr subtree, Map<String, Expr> bindings) {
    }
}
