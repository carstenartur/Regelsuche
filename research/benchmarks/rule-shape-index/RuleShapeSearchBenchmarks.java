package de.regelsuche.research;

import de.regelsuche.ast.*;
import de.regelsuche.search.moves.*;
import de.regelsuche.transform.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Same inventory grid through a four-step typed search, including generation and replay. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms256m", "-Xmx256m"})
@State(Scope.Thread)
public class RuleShapeSearchBenchmarks {
    @Param({"16", "256", "4096"}) public int unrelatedRules;
    @Param({"SPARSE_FUNCTIONS", "SAME_OPERATOR"}) public String shape;
    private TypedMoveSearch.Problem scanProblem;
    private TypedMoveSearch.Problem indexedProblem;

    @Setup public void setup() {
        var a = PatternExpr.var("A");
        var rules = new ArrayList<RewriteRule>();
        for (int i = 0; i < unrelatedRules; i++) {
            PatternExpr pattern = shape.equals("SPARSE_FUNCTIONS") ? PatternExpr.fn("unused" + i, a)
                : PatternExpr.op(BinaryOperator.ADD, a, PatternExpr.num(i + 1));
            rules.add(new PatternRewriteRule("identity" + i, pattern, pattern));
        }
        rules.add(new PatternRewriteRule("zero", PatternExpr.op(BinaryOperator.ADD, a, PatternExpr.num(0)), a));
        var engine = new PreparedAstRewriteTransformationEngine(rules, 64, 128);
        var arguments = new ArrayList<Expr>();
        var goals = new ArrayList<Expr>();
        for (int i = 0; i < 4; i++) {
            Expr x = new VariableExpr("x" + i);
            arguments.add(new BinaryExpr(x, BinaryOperator.ADD, new NumberExpr(0)));
            goals.add(x);
        }
        Expr source = new FunctionExpr("context", arguments);
        Expr goal = new FunctionExpr("context", goals);
        scanProblem = problem(source, goal, engine.astTransport());
        indexedProblem = problem(source, goal, engine.withRuleIndex().astTransport());
        var scanned = scan();
        var filtered = indexed();
        if (!scanned.reached() || scanned.witness().size() != 4
                || !scanned.encodedResult().equals(filtered.encodedResult())) {
            throw new IllegalStateException("search outcome, ordered evidence or work relation differs");
        }
    }

    @Benchmark public TypedMoveSearch.Result scan() { return new TypedMoveSearch().search(scanProblem); }
    @Benchmark public TypedMoveSearch.Result indexed() { return new TypedMoveSearch().search(indexedProblem); }

    private static TypedMoveSearch.Problem problem(Expr source, Expr goal, AstRewriteTransport transport) {
        var descriptor = new MoveProvider.Descriptor("benchmark-primitives", "*", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "root-shape-study");
        return new TypedMoveSearch.Problem(source, TypedMoveSearch.Context.frozen(goal),
            List.of(TypedMoveSearch.primitiveProvider(descriptor, transport)), TypedMoveSearch.Policy.INVENTORY_ORDER,
            TypedMoveSearch.primitiveReplay(transport), state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(4, 4, 0, 100, 10_000));
    }
}
