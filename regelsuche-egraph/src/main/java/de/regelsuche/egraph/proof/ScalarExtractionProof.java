package de.regelsuche.egraph.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.*;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.transform.*;
import de.regelsuche.value.ExprValueArena;
import java.util.*;
import java.util.function.LongConsumer;

/** Receiver-owned scalar laws and ground lemmas checked before reuse by the detached congruence proof checker. */
public final class ScalarExtractionProof {
    public static final String DOMAIN = "exact-rational-field/ac-add-mul/v1";
    public static final String ANALYSIS = "exact-scalar-class-facts/v1";
    public static final String BACKEND = "receiver.checked-backend-equality/v1";
    public static final String AC = "intrinsic.ac-normalization/v1";
    private static final Set<String> NATIVE = Set.of("ast_add_zero_left","ast_add_zero_right","ast_multiply_one_left",
        "ast_multiply_one_right","ast_subtract_zero","ast_divide_one","ast_double_term","ast_factor_common_left","ast_factor_common_right","ast_cancel_division_factor");
    private static final Map<String,String> TRUSTED = trusted();
    private ScalarExtractionProof() {}

    public record Entry(String id, String contentHash, String disposition) { }
    public record Context(String domain, String analysis, String sourceRevision, List<Entry> inventory, List<Assumption> assumptions) {
        public Context {
            inventory = List.copyOf(inventory); assumptions = List.copyOf(assumptions);
        }

        public static Context create(Collection<? extends RewriteRule> rules, List<Assumption> assumptions, String revision) {
            var inventory = new TreeMap<String,Entry>();
            for (var rule : rules) {
                String hash = RuleInventoryFingerprint.ruleContentHash(rule);
                String disposition = hash.equals(TRUSTED.get(rule.id())) ? "NATIVE_SUPPORTED" : "EXCLUDED_NO_NATIVE_SCALAR_CHECKER";
                if (inventory.put(rule.id(),new Entry(rule.id(),hash,disposition)) != null) throw new IllegalArgumentException("duplicate rule identity");
            }
            if (assumptions.size() > 64 || !revision.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("invalid scalar context");
            return new Context(DOMAIN, ANALYSIS, revision, List.copyOf(inventory.values()), assumptions);
        }

        public boolean enables(String rule) {
            return inventory.stream().anyMatch(e -> e.id().equals(rule) && e.disposition().equals("NATIVE_SUPPORTED")
                && e.contentHash().equals(TRUSTED.get(rule)));
        }
    }

    public record BackendWitness(String backend, String obligationJson, String executionJson, String resultJson) { }
    @FunctionalInterface
    public interface BackendVerifier { boolean verify(Context context, Expr before, Expr after, BackendWitness witness); }
    public record Lemma(String ruleId, Expr before, Expr after, BackendWitness backend) {
        public Lemma(String ruleId, Expr before, Expr after) { this(ruleId,before,after,null); }
    }
    public record Bundle(Context context, List<Lemma> lemmas, ExtractionCertificate dag) {
        public Bundle { lemmas = List.copyOf(lemmas); }
    }

    public static Optional<Bundle> build(Expr source, Expr target, Context context, List<Lemma> observations, ProofLimits limits) {
        return build(source,target,context,observations,limits,null);
    }
    public static Optional<Bundle> build(Expr source, Expr target, Context context, List<Lemma> observations,
            ProofLimits limits, BackendVerifier verifier) {
        return build(source, target, context, observations, limits, verifier, null);
    }

    /** Observes native proof production, including failed preflight, construction and detached checks. */
    public static Optional<Bundle> buildObserved(Expr source, Expr target, Context context,
            List<Lemma> observations, ProofLimits limits, LongConsumer work) {
        return build(source, target, context, observations, limits, null, work);
    }

    private static Optional<Bundle> build(Expr source, Expr target, Context context, List<Lemma> observations,
            ProofLimits limits, BackendVerifier verifier, LongConsumer work) {
        charge(work, 1L + observations.size());
        List<Lemma> lemmas = List.copyOf(new LinkedHashSet<>(observations));
        var rules = verified(context, lemmas, limits, verifier, work);
        if (rules == null) return Optional.empty();
        var builder = proofBuilder(rules, limits, work);
        try {
            int input = builder.addExpression(source), output = builder.addExpression(target);
            for (var rule : rules) builder.recordInstance(builder.prepareInstance(rule.id(),Map.of()));
            return builder.build(input,output).certificate().map(dag -> new Bundle(context, lemmas, dag))
                .filter(bundle -> replay(source, target, context, bundle, limits, verifier, work).isPresent());
        } finally {
            charge(work, builder.workUsed());
        }
    }

    public static boolean check(Expr source, Expr target, Context receiver, Bundle bundle, ProofLimits limits) {
        return check(source,target,receiver,bundle,limits,null);
    }
    public static boolean check(Expr source, Expr target, Context receiver, Bundle bundle, ProofLimits limits, BackendVerifier verifier) {
        return replay(source, target, receiver, bundle, limits, verifier, null).isPresent();
    }

    private static OptionalLong replay(Expr source, Expr target, Context receiver, Bundle bundle, ProofLimits limits,
            BackendVerifier verifier, LongConsumer work) {
        charge(work, 1);
        try {
            if (!receiver.equals(bundle.context())) return OptionalLong.empty();
            var rules = verified(receiver, bundle.lemmas(), limits, verifier, work);
            if (rules == null) return OptionalLong.empty();
            var checked = ExtractionProofChecker.check(source, target, bundle.dag(), rules, limits, true);
            charge(work, checked.workUsed());
            return checked.status() == ExtractionProofChecker.Status.ACCEPTED
                    ? OptionalLong.of(checked.workUsed()) : OptionalLong.empty();
        } catch (IllegalArgumentException | ExtractionProofBuilder.LimitExceeded rejected) {
            return OptionalLong.empty();
        }
    }

    private static List<PatternRewriteRule> verified(Context context, List<Lemma> lemmas, ProofLimits limits,
            BackendVerifier verifier, LongConsumer work) {
        charge(work, 1);
        if (!DOMAIN.equals(context.domain()) || !ANALYSIS.equals(context.analysis()) || !context.sourceRevision().matches("[0-9a-f]{40}")
                || lemmas.size() > Math.min(limits.maxProofSteps(),4096)) return null;
        var bounds = proofBuilder(List.of(), limits, work);
        var rules = new ArrayList<PatternRewriteRule>();
        try (var arena = new ExprValueArena(Math.max(1, limits.maxTerms()))) {
            for (var step : lemmas) {
                charge(work, 1);
                bounds.addExpression(step.before()); bounds.addExpression(step.after());
                if (step.ruleId().equals(BACKEND)) {
                    if (verifier == null || step.backend() == null || !verifier.verify(context,step.before(),step.after(),step.backend())) return null;
                } else if (step.backend() != null || !valid(context, step, arena)) return null;
                rules.add(new PatternRewriteRule("scalar-ground/" + rules.size() + "/" + step.ruleId(),
                    ground(step.before()), ground(step.after())));
            }
        } finally {
            charge(work, bounds.workUsed());
        }
        return rules;
    }

    /** Measures the detached inference checker after the ordinary scalar premise and lemma checks. */
    public static OptionalLong detachedReplayWork(Expr source, Expr target, Context receiver, Bundle bundle,
            ProofLimits limits) {
        return replay(source, target, receiver, bundle, limits, null, null);
    }

    public static boolean admits(Context receiver, Lemma lemma) {
        return admitsObserved(receiver, lemma, null);
    }

    /** Observes an actual checked scalar union witness, whether admitted or rejected. */
    public static boolean admitsObserved(Context receiver, Lemma lemma, LongConsumer work) {
        return verified(receiver, List.of(lemma), ProofLimits.defaults(), null, work) != null;
    }

    private static ExtractionProofBuilder proofBuilder(List<PatternRewriteRule> rules, ProofLimits limits,
            LongConsumer work) {
        try {
            return new ExtractionProofBuilder(rules, limits, true);
        } catch (ExtractionProofBuilder.LimitExceeded exhausted) {
            charge(work, exhausted.workUsed());
            throw exhausted;
        } catch (ExtractionProofBuilder.UnsupportedRule unsupported) {
            charge(work, unsupported.workUsed());
            throw unsupported;
        } catch (ExtractionProofBuilder.InvalidInput invalid) {
            charge(work, invalid.workUsed());
            throw invalid;
        }
    }

    private static void charge(LongConsumer work, long units) {
        if (work != null) work.accept(units);
    }

    private static boolean valid(Context context, Lemma lemma, ExprValueArena arena) {
        Expr before = lemma.before(), after = lemma.after();
        if (lemma.ruleId().equals(AC)) return arena.intern(before) == arena.intern(after);
        if (!context.enables(lemma.ruleId()) || !(before instanceof BinaryExpr binary)) return false;
        BinaryOperator op = binary.operator();
        return switch (lemma.ruleId()) {
            case "ast_add_zero_left","ast_add_zero_right" -> op == BinaryOperator.ADD
                && remove(arena, before, new NumberExpr(0), after, op);
            case "ast_multiply_one_left","ast_multiply_one_right" -> op == BinaryOperator.MUL
                && remove(arena, before, new NumberExpr(1), after, op);
            case "ast_subtract_zero" -> op == BinaryOperator.SUB && binary.right().equals(new NumberExpr(0))
                && arena.intern(binary.left()) == arena.intern(after);
            case "ast_divide_one" -> op == BinaryOperator.DIV && binary.right().equals(new NumberExpr(1))
                && arena.intern(binary.left()) == arena.intern(after);
            case "ast_double_term" -> op == BinaryOperator.ADD && arena.intern(binary.left()) == arena.intern(binary.right())
                && arena.intern(after) == arena.intern(new BinaryExpr(new NumberExpr(2),BinaryOperator.MUL,binary.left()));
            case "ast_factor_common_left","ast_factor_common_right" -> op == BinaryOperator.ADD && factorization(arena,before,after);
            case "ast_cancel_division_factor" -> op == BinaryOperator.DIV
                && binary.left() instanceof BinaryExpr product && product.operator() == BinaryOperator.MUL
                && ScalarGuardFacts.of(binary.right(),context.assumptions()).nonzero() == ScalarGuardFacts.Truth.TRUE
                && remove(arena, binary.left(), binary.right(), after, BinaryOperator.MUL);
            default -> false;
        };
    }

    private static boolean factorization(ExprValueArena arena, Expr before, Expr after) {
        if (!(after instanceof BinaryExpr product) || product.operator() != BinaryOperator.MUL) return false;
        for (boolean swap : List.of(false,true)) {
            Expr factor = swap ? product.right() : product.left();
            Expr other = swap ? product.left() : product.right();
            if (!(other instanceof BinaryExpr sum) || sum.operator() != BinaryOperator.ADD) continue;
            Expr expanded = new BinaryExpr(new BinaryExpr(factor,BinaryOperator.MUL,sum.left()),BinaryOperator.ADD,
                new BinaryExpr(factor,BinaryOperator.MUL,sum.right()));
            if (arena.intern(expanded) == arena.intern(before)) return true;
        }
        return false;
    }

    private static boolean remove(ExprValueArena arena, Expr before, Expr factor, Expr after, BinaryOperator op) {
        var factors = new ArrayList<Expr>();
        flatten(before, op, factors);
        var id = arena.intern(factor);
        for (int i=0;i<factors.size();i++) {
            if (arena.intern(factors.get(i)) != id) continue;
            factors.remove(i);
            return !factors.isEmpty() && arena.intern(join(factors,op)) == arena.intern(after);
        }
        return false;
    }

    public static void flatten(Expr value, BinaryOperator operator, List<Expr> out) {
        if (value instanceof BinaryExpr binary && binary.operator() == operator) {
            flatten(binary.left(),operator,out); flatten(binary.right(),operator,out);
        } else out.add(value);
    }
    public static Expr join(List<Expr> terms, BinaryOperator op) {
        Expr value = terms.getFirst();
        for (int i=1;i<terms.size();i++) value = new BinaryExpr(value, op, terms.get(i));
        return value;
    }
    private static PatternExpr ground(Expr expression) {
        return switch (expression) {
            case NumberExpr n -> PatternExpr.num(n.value());
            case VariableExpr v -> PatternExpr.variable(v.name());
            case BinaryExpr b -> PatternExpr.op(b.operator(),ground(b.left()),ground(b.right()));
            case FunctionExpr f -> PatternExpr.fn(f.name(),f.arguments().stream().map(ScalarExtractionProof::ground).toArray(PatternExpr[]::new));
        };
    }
    private static Map<String,String> trusted() {
        var map = new TreeMap<String,String>();
        for (var rule : AstRewriteTransformationEngine.allBuiltInRules()) {
            if (NATIVE.contains(rule.id())) map.put(rule.id(),RuleInventoryFingerprint.ruleContentHash(rule));
        }
        return Map.copyOf(map);
    }
}
