package de.regelsuche.egraph.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.egraph.ENode;
import de.regelsuche.egraph.EqualitySaturation;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.transform.AstRewriteTransformationEngine.CancelDivisionFactorRule;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/** Derives ground scalar cancellation axioms from receiver-owned mathematical premises. */
public final class CancellationProof {
    private static final String PREFIX = "eqsat.scalar.factor-cancellation/v1/";
    private static final Pattern SYMBOL = Pattern.compile("[\\p{L}][\\p{L}\\p{N}_]*");
    private static final PatternExpr REST = PatternExpr.var("rest");

    private CancellationProof() {}

    /** Reconstruct every authorized rule independently before invoking the bounded DAG interpreter. */
    public static ExtractionProofChecker.CheckResult check(Expr input, Expr output, ExtractionCertificate certificate,
            Collection<? extends RewriteRule> trustedRules, List<Assumption> assumptions, ProofLimits limits) {
        var budget = new ProofSupport(limits);
        try {
            requireRule(trustedRules);
            budget.size(certificate.rules().size(), limits.maxTerms(), "guard rule limit");
            var rules = new ArrayList<RewriteRule>();
            for (var identity : certificate.rules()) {
                budget.work(1);
                String id = identity.id();
                budget.chars(id);
                if (!id.startsWith(PREFIX)) throw new IllegalArgumentException("foreign guarded rule");
                String[] parts = id.substring(PREFIX.length()).split("/", 3);
                if (parts.length != 3 || !(parts[0].equals("left") || parts[0].equals("right"))) {
                    throw new IllegalArgumentException("invalid guarded rule identity");
                }
                boolean left = parts[0].equals("left");
                Factor factor = switch (parts[1]) {
                    case "number" -> exact(budget.number(parts[2]));
                    case "symbol" -> symbol(parts[2], assumptions, budget);
                    default -> throw new IllegalArgumentException("unsupported scalar factor");
                };
                rules.add(rule(factor, left));
            }
            return ExtractionProofChecker.check(input, output, certificate, rules, remaining(limits, budget), true)
                .includingPrelude(budget.work);
        } catch (ExtractionProofBuilder.LimitExceeded exhausted) {
            return ExtractionProofChecker.CheckResult.denied(ExtractionProofChecker.Status.LIMIT_EXCEEDED,
                exhausted.getMessage(), budget.work);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return ExtractionProofChecker.CheckResult.denied(ExtractionProofChecker.Status.REJECTED,
                "missing or malformed cancellation premise", budget.work);
        }
    }

    /** Runs the existing native guard engine, then reconstructs and checks only its justified ground witnesses. */
    public static EqualitySaturation.ProvedResult run(EqualitySaturation engine, Expr input, List<Assumption> context,
            ToIntFunction<ENode> objective, EqualitySaturation.ProofOptions options) {
        var budget = new ProofSupport(options.captureLimits());
        try {
            requireRule(engine.rules());
            var assumptions = List.copyOf(context);
            // Bound the complete input before it reaches the recursive native inserter.
            var preflight = new ExtractionProofBuilder(List.of(), options.captureLimits());
            preflight.addExpression(input);
            budget.work(preflight.workUsed());
            var observed = engine.saturateUnderAssumptions(input, assumptions, objective);
            var axioms = new LinkedHashMap<String, PatternRewriteRule>();
            var instances = new ArrayList<Instance>();
            for (var decision : observed.decisions()) {
                if (!decision.applied()) continue;
                var source = (BinaryExpr) decision.before();
                var product = (BinaryExpr) source.left();
                Factor factor = switch (source.right()) {
                    case NumberExpr n -> exact(budget.number(budget.rational(n.value())));
                    case VariableExpr v -> symbol(v.name(), assumptions, budget);
                    default -> throw new IllegalArgumentException("unsupported guard subject");
                };
                // The producer's orientation is a hint; replay checks the actual ground endpoints.
                boolean left = source.right().equals(product.left());
                var rule = rule(factor, left);
                axioms.putIfAbsent(rule.id(), rule);
                instances.add(new Instance(rule.id(), decision.after()));
            }
            var builder = new ExtractionProofBuilder(axioms.values(), remaining(options.captureLimits(), budget), true);
            int before = builder.addExpression(input);
            Expr output = observed.result().expression();
            int after = builder.addExpression(output);
            for (var instance : instances) {
                int rest = builder.addExpression(instance.rest());
                builder.recordInstance(builder.prepareInstance(instance.id(), Map.of("rest", rest)));
            }
            long capture = budget.work + builder.workUsed();
            var built = builder.build(before, after);
            long build = builder.workUsed() + budget.work - capture;
            if (built.certificate().isEmpty()) {
                var status = built.status() == ExtractionProofBuilder.BuildStatus.LIMIT_EXCEEDED
                    ? ProofOutcome.Status.LIMIT_EXCEEDED : ProofOutcome.Status.EXTRACTION_UNAVAILABLE;
                return unavailable(input, status, built.detail(), capture, build, 0);
            }
            var replay = check(input, output, built.certificate().orElseThrow(), engine.rules(), assumptions, options.replayLimits());
            if (replay.status() != ExtractionProofChecker.Status.ACCEPTED) {
                var status = replay.status() == ExtractionProofChecker.Status.LIMIT_EXCEEDED
                    ? ProofOutcome.Status.LIMIT_EXCEEDED : ProofOutcome.Status.REPLAY_REJECTED;
                return unavailable(input, status, replay.detail(), capture, build, replay.workUsed());
            }
            return new EqualitySaturation.ProvedResult(output, Optional.empty(),
                ProofOutcome.complete(replay.checked().orElseThrow(), capture, build));
        } catch (ExtractionProofBuilder.LimitExceeded exhausted) {
            return unavailable(input, ProofOutcome.Status.LIMIT_EXCEEDED, exhausted.getMessage(), budget.work, 0, 0);
        } catch (IllegalArgumentException invalid) {
            return unavailable(input, ProofOutcome.Status.UNSUPPORTED_RULE, invalid.getMessage(), budget.work, 0, 0);
        }
    }

    private static Factor symbol(String name, List<Assumption> assumptions, ProofSupport budget) {
        budget.chars(name);
        if (!SYMBOL.matcher(name).matches()) throw new IllegalArgumentException("non-atomic guard subject");
        var nonzero = Assumption.nonZero(name);
        var positive = Assumption.positive(name);
        for (var fact : assumptions) {
            budget.work(1);
            if (fact.symbols().size() != 1) continue;
            budget.chars(fact.symbols().getFirst());
            budget.chars(fact.expression());
            if (fact.equals(nonzero) || fact.equals(positive)) {
                return new Factor("symbol/" + name, PatternExpr.variable(name));
            }
        }
        throw new IllegalArgumentException("receiver has no canonical nonzero premise");
    }

    private static Factor exact(ExactRational number) {
        if (number.isZero()) throw new IllegalArgumentException("zero cancellation");
        return new Factor("number/" + number.canonicalText(), PatternExpr.num(number));
    }

    private static PatternRewriteRule rule(Factor factor, boolean left) {
        var product = PatternExpr.op(BinaryOperator.MUL, left ? factor.subject() : REST, left ? REST : factor.subject());
        return new PatternRewriteRule(PREFIX + (left ? "left/" : "right/") + factor.key(),
            PatternExpr.op(BinaryOperator.DIV, product, factor.subject()), REST);
    }

    private static void requireRule(Collection<? extends RewriteRule> rules) {
        if (rules.size() != 1 || !(rules.iterator().next() instanceof CancelDivisionFactorRule)) {
            throw new IllegalArgumentException("requires only the built-in scalar cancellation rule");
        }
    }

    private static ProofLimits remaining(ProofLimits limits, ProofSupport spent) {
        return new ProofLimits(limits.maxTerms(), limits.maxProofSteps(), limits.maxReferences(),
            Math.toIntExact(limits.maxCharacters() - spent.characters), limits.maxRationalBits(),
            limits.maxDepth(), limits.maxExpandedNodes(), limits.maxWork() - spent.work);
    }

    private static EqualitySaturation.ProvedResult unavailable(Expr input, ProofOutcome.Status status, String detail,
            long capture, long build, long replay) {
        return new EqualitySaturation.ProvedResult(input, Optional.empty(),
            ProofOutcome.unavailable(status, detail, capture, build, replay));
    }

    private record Factor(String key, PatternExpr subject) {}
    private record Instance(String id, Expr rest) {}
}
