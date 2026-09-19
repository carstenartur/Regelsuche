package de.regelsuche.research;

import de.regelsuche.ast.*;
import de.regelsuche.transform.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Optional development benchmark; intentionally outside the frozen regression-gate inventory. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms256m", "-Xmx256m"})
@State(Scope.Thread)
public class RuleShapeIndexBenchmarks {
    @Param({"16", "256", "4096"}) public int unrelatedRules;
    @Param({"SPARSE_FUNCTIONS", "SAME_OPERATOR"}) public String shape;
    private PreparedAstRewriteTransformationEngine scanEngine;
    private AstRewriteTransport scan;
    private AstRewriteTransport indexed;
    private Expr source;

    @Setup public void setup() {
        var a = PatternExpr.var("A");
        var rules = new ArrayList<RewriteRule>();
        for (int i = 0; i < unrelatedRules; i++) {
            PatternExpr pattern = shape.equals("SPARSE_FUNCTIONS") ? PatternExpr.fn("unused" + i, a)
                : PatternExpr.op(BinaryOperator.ADD, a, PatternExpr.num(i + 1));
            // Exact identities add matching cost without introducing unsupported mathematics.
            rules.add(new PatternRewriteRule("identity" + i, pattern, pattern));
        }
        rules.add(new PatternRewriteRule("zero", PatternExpr.op(BinaryOperator.ADD, a, PatternExpr.num(0)), a));
        scanEngine = new PreparedAstRewriteTransformationEngine(rules, 64, 128);
        scan = scanEngine.astTransport();
        indexed = scanEngine.withRuleIndex().astTransport();
        var arguments = new ArrayList<Expr>();
        for (int i = 0; i < 4; i++) arguments.add(new BinaryExpr(new VariableExpr("x" + i), BinaryOperator.ADD, new NumberExpr(0)));
        source = new FunctionExpr("context", arguments);
        if (scan.generate(source).size() != 4 || !scan.generate(source).equals(indexed.generate(source))) {
            throw new IllegalStateException("benchmark successor relation differs");
        }
    }

    @Benchmark public List<AstRewriteTransport.Step> scan() { return scan.generate(source); }
    @Benchmark public List<AstRewriteTransport.Step> indexed() { return indexed.generate(source); }
    @Benchmark public PreparedAstRewriteTransformationEngine compileIndex() { return scanEngine.withRuleIndex(); }
}
