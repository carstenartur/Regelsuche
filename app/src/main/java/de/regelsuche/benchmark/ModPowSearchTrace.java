package de.regelsuche.benchmark;

import static de.regelsuche.benchmark.ModPowTransferFiles.require;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.benchmark.ModPowTransferFiles.Row;
import de.regelsuche.mining.TypedOutputPattern;
import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.PatternRewriteRule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Execution ledger and incumbent trace for the two preregistered one-step schedulers. */
final class ModPowSearchTrace {
    record Matching(long attempts, long steps, long branches) {
        Matching plus(Matching other) {
            return new Matching(attempts + other.attempts, steps + other.steps, branches + other.branches);
        }
        long total() { return attempts + steps + branches; }
    }
    record Improvement(long work, long dag) {}
    record Work(Matching primitiveMatch, Matching learnedMatch, int generated, int replayed, int approved, int scored) {
        long total() { return primitiveMatch.total() + learnedMatch.total() + generated + replayed + scored; }
    }
    private static final List<PatternRewriteRule> RULES = ModPowFrozenTransfer.compositionRules();
    private static final ExprMatcher EXACT = ExprMatcher.pattern(RULES.getFirst().source());
    static final Matching NONE = new Matching(0, 0, 0);
    private final Row row;
    private final Set<Expr> seen = new HashSet<>();
    private final Set<Expr> primitiveTargets = new HashSet<>();
    private final List<Improvement> improvements = new ArrayList<>();
    private Matching primitiveMatch = NONE, learnedMatch = NONE;
    private int generated, replayed, approved, scored;
    private long best;

    ModPowSearchTrace(Row row) {
        this.row = row;
        best = ModPowFrozenTransfer.cost(row, row.source()).dag();
        scored++;
    }

    void runLearned(TypedOutputPattern pattern) {
        var result = pattern.findWithMatchWork(row.source(), TypedOutputPattern.MAXIMUM_ASSIGNMENTS);
        require(result.result().complete(), "incomplete AC matching on " + row.id());
        learnedMatch = new Matching(result.result().assignmentAttempts(), result.result().matcherSteps(), result.patternBranches());
        for (var proposal : result.result().applications()) {
            accept(proposal.target(), proposal.positions().getFirst());
        }
    }

    void runPrimitive() {
        var source = row.source();
        for (int index = 0; index < source.arguments().size(); index++) {
            var match = EXACT.match(source.arguments().get(index));
            require(match.complete(), "incomplete primitive match");
            primitiveMatch = primitiveMatch.plus(new Matching(1, match.evaluatedSteps(), match.patternBranches()));
            for (var binding : match.matches()) {
                for (var rule : RULES) {
                    var outputs = new ArrayList<>(source.arguments());
                    outputs.set(index, rule.target().instantiate(binding.bindings()));
                    var target = new FunctionExpr(source.name(), outputs);
                    primitiveTargets.add(target);
                    accept(target, index);
                }
            }
        }
    }

    static Set<Expr> referenceTargets(Row row) {
        var transport = new AstRewriteTransport(List.copyOf(RULES), 1024, 4096);
        var targets = new HashSet<Expr>();
        for (var step : transport.generate(row.source())) targets.add(step.target());
        return targets;
    }

    static long referenceDag(Row row, Set<Expr> targets) {
        long best = ModPowFrozenTransfer.cost(row, row.source()).dag();
        for (var target : targets) {
            if (ModPowCompositionReplay.verify(row.source(), target, row.assumptions()).isPresent()) {
                best = Math.min(best, ModPowFrozenTransfer.cost(row, target).dag());
            }
        }
        return best;
    }

    private void accept(FunctionExpr target, int changed) {
        generated++;
        if (!seen.add(target)) return;
        replayed++;
        if (ModPowCompositionReplay.verify(row.source(), target, row.assumptions()).isEmpty()) return;
        approved++;
        for (int i = 0; i < target.arguments().size(); i++) {
            require(i == changed || target.arguments().get(i) == row.source().arguments().get(i), "untouched output replaced");
        }
        scored++;
        long dag = ModPowFrozenTransfer.cost(row, target).dag();
        if (dag < best) {
            best = dag;
            improvements.add(new Improvement(work().total(), best));
        }
    }

    Work work() { return new Work(primitiveMatch, learnedMatch, generated, replayed, approved, scored); }
    List<Improvement> improvements() { return List.copyOf(improvements); }
    Set<Expr> primitiveTargets() { return Set.copyOf(primitiveTargets); }
    long best() { return best; }
    long firstOptimum(long source, long optimum) {
        if (source == optimum) return 0;
        return improvements.stream().filter(p -> p.dag() == optimum).findFirst().orElseThrow().work();
    }
}
