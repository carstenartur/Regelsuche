package de.regelsuche.transform;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.value.CompactValueArena;
import de.regelsuche.value.CompactValueArena.Occurrence;
import de.regelsuche.value.CompactValueArena.Projection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Opt-in selected-occurrence execution; no global search identity, proof or enumeration policy. */
public final class NativeValueRewriteSession implements AutoCloseable {
    public static final String WORK_REVISION = "regelsuche.compact-native-rewrite-work/v1";
    public enum Status { APPLIED, NOT_MATCHED, UNCHANGED, MATCH_INCONCLUSIVE, BUDGET_EXHAUSTED, ARENA_EXHAUSTED, FAILED }
    public record Budget(int maxAttempts, int maxPrimitiveRewrites, int maxAncestorCopies, int matcherBranchLimit) {
        public Budget {
            if (maxAttempts < 1 || maxPrimitiveRewrites < 0 || maxAncestorCopies < 0 || matcherBranchLimit < 1)
                throw new IllegalArgumentException("invalid native local rewrite budget");
        }
    }
    /** Actual operations, including work whose candidate later fails arena admission. */
    public record Work(long attempts, long matcherBranches, long instantiations,
            long primitiveRewrites, long ancestorCopies, long legacyExports) { }
    public static final class Attempt {
        private final NativeValueRewriteSession issuer;
        private final Projection source;
        private final Projection target;
        private final Occurrence occurrence;
        private final int ruleIndex;
        private final Status status;
        private final String detail;
        private final Work work;
        private Attempt(NativeValueRewriteSession issuer, Projection source, Projection target,
                Occurrence occurrence, int ruleIndex, Status status, String detail) {
            this.issuer = issuer; this.source = source; this.target = target; this.occurrence = occurrence;
            this.ruleIndex = ruleIndex; this.status = status; this.detail = detail; this.work = issuer.work();
        }
        public Projection source() { return source; }
        public Optional<Projection> target() { return Optional.ofNullable(target); }
        public Occurrence occurrence() { return occurrence; }
        public int ruleIndex() { return ruleIndex; }
        public TransformationCursor.RuleDefinition ruleDefinition() { return issuer.definitions.get(ruleIndex); }
        public Status status() { return status; }
        public String detail() { return detail; }
        public Work work() { return work; }
    }

    private final PreparedAstRewriteTransformationEngine engine;
    private final List<PatternRewriteRule> rules;
    private final List<TransformationCursor.RuleDefinition> definitions;
    private final AssumptionSignature assumptions;
    private final Budget budget;
    private Projection current;
    private long attempts, matcherBranches, instantiations, primitiveRewrites, ancestorCopies, legacyExports;
    private boolean closed;

    NativeValueRewriteSession(PreparedAstRewriteTransformationEngine engine, Projection source,
            AssumptionSignature assumptions, Budget budget) {
        this.engine = Objects.requireNonNull(engine);
        this.budget = Objects.requireNonNull(budget);
        this.assumptions = AssumptionSignature.ofExpressions(Objects.requireNonNull(assumptions).normalizedAssumptions());
        rules = engine.rules().stream().map(rule -> {
            if (rule.getClass() != PatternRewriteRule.class)
                throw new IllegalArgumentException("compact native session requires exact PatternRewriteRule instances");
            return (PatternRewriteRule) rule;
        }).toList();
        definitions = rules.stream().map(TransformationCursor.RuleDefinition::of).toList();
        current = Objects.requireNonNull(source); current.value();
    }
    public Projection current() { ensureOpen(); return current; }
    public AssumptionSignature assumptions() { return assumptions; }
    public Budget budget() { return budget; }
    public Work work() { return new Work(attempts, matcherBranches, instantiations, primitiveRewrites, ancestorCopies, legacyExports); }

    public Attempt apply(Occurrence occurrence, int ruleIndex) {
        ensureOpen(); current.requireOccurrence(occurrence);
        PatternRewriteRule rule = rules.get(ruleIndex);
        if (attempts >= budget.maxAttempts() || primitiveRewrites >= budget.maxPrimitiveRewrites()
                || occurrence.path().size() > budget.maxAncestorCopies() - ancestorCopies)
            return result(occurrence, ruleIndex, Status.BUDGET_EXHAUSTED, "local rewrite budget");
        attempts++;
        var match = EquivalenceAwarePatternMatcher.matchDetailed(rule.source(), occurrence.syntax(), new HashMap<>(),
            rule.recognitionProfile(), budget.matcherBranchLimit());
        matcherBranches += match.visitedBranches();
        if (!match.matched()) return result(occurrence, ruleIndex,
            match.inconclusive() ? Status.MATCH_INCONCLUSIVE : Status.NOT_MATCHED, match.limitCode());
        return instantiate(occurrence, ruleIndex, rule, match);
    }
    private Attempt instantiate(Occurrence occurrence, int ruleIndex, PatternRewriteRule rule,
            EquivalenceAwarePatternMatcher.MatchAttempt match) {
        instantiations++;
        try {
            Expr replacement = rule.target().instantiate(match.bindings());
            if (replacement.equals(occurrence.syntax())) return result(occurrence, ruleIndex, Status.UNCHANGED, "");
            primitiveRewrites++;
            Projection source = current;
            ancestorCopies += occurrence.path().size();
            Projection target = source.replace(occurrence, replacement);
            current = target;
            return new Attempt(this, source, target, occurrence, ruleIndex, Status.APPLIED, "");
        } catch (CompactValueArena.CapacityExceeded failure) {
            return result(occurrence, ruleIndex, Status.ARENA_EXHAUSTED, failure.getMessage());
        } catch (IllegalArgumentException failure) {
            return result(occurrence, ruleIndex, Status.FAILED, failure.getMessage());
        }
    }
    private Attempt result(Occurrence occurrence, int ruleIndex, Status status, String detail) {
        return new Attempt(this, current, null, occurrence, ruleIndex, status, detail);
    }
    /** Full expression formatting and historical source hashing happen only at this explicit boundary. */
    public Transformation exportLegacy(Attempt attempt) {
        ensureOpen();
        if (attempt == null || attempt.issuer != this || attempt.status != Status.APPLIED)
            throw new IllegalArgumentException("legacy export requires this session's applied native attempt");
        legacyExports++;
        var rule = rules.get(attempt.ruleIndex);
        return new Transformation(rule.id(), ExpressionFormatter.format(attempt.target.syntax()), rule.kind(),
            rule.mayIncreaseComplexity(), rule.estimatedCostDelta(), rule.isEquivalencePreservingByConstruction(),
            rule.id() + ":" + engine.stableHash(attempt.occurrence.syntax()), List.of(),
            rule.descriptor().packId(), rule.descriptor().license());
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("native local rewrite session is closed"); }
    /** The caller owns the shared arena; closing a session does not close that owner. */
    @Override public void close() { closed = true; }
}
