package de.regelsuche.search.program;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.PreparedAstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class CompiledAstRewriteProgramTest {
    private static final PatternExpr A = PatternExpr.var("A");
    private static final RewriteRule ZERO = new PatternRewriteRule("zero", PatternExpr.op(ADD, A, PatternExpr.num(0)), A);
    private static final RewriteRule SQUARE = new PatternRewriteRule("square", PatternExpr.op(MUL, A, A),
        PatternExpr.op(POW, A, PatternExpr.num(2)));
    private final ExpressionParser parser = new ExpressionParser();

    private static RewriteProgram source(String id, RewriteRule... rules) {
        return RewritePrograms.source(id, new PreparedAstRewriteTransformationEngine(List.of(rules), 64, 128));
    }

    private static CompiledLinearRewriteEngine compiled() {
        return new CompiledLinearRewriteEngine(RewritePrograms.sequence("zero-square",
            source("remove-zero", ZERO), source("recognize-square", SQUARE)), 128);
    }

    private void assertTransfer(Expr compound) {
        Expr input = new BinaryExpr(new BinaryExpr(compound, ADD, new NumberExpr(0)), MUL, compound);
        var result = compiled().compileAst().transformMeasured(input);
        assertEquals(1, result.candidates().size());
        var candidate = result.candidates().getFirst();
        assertEquals(input, candidate.source());
        assertEquals(new BinaryExpr(compound, POW, new NumberExpr(2)), candidate.target());
        assertEquals(List.of(input, new BinaryExpr(compound, MUL, compound), candidate.target()), candidate.states());
        assertEquals(List.of("remove-zero", "recognize-square"), candidate.sourceIds());
        assertEquals(List.of("zero", "square"), candidate.steps().stream().map(step -> step.rule()).toList());
    }

    @Test void groupedAdditionSurvivesEveryCompiledSource() {
        assertTransfer(parser.parseTerm("a+(b+c)"));
    }

    @Test void exactRationalIsNotReplacedWithADivisionTreeBetweenSources() {
        assertTransfer(NumberExpr.exact("1/3"));
    }

    @Test void orderedFunctionArgumentsRetainGroupingAndNumericLeaves() {
        assertTransfer(new FunctionExpr("f", List.of(parser.parseTerm("a*(b*c)"), NumberExpr.exact("2/7"))));
    }
}
