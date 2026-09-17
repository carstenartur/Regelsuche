package de.regelsuche.evolution;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.ast.*;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.search.program.CompiledAstRewriteProgram;
import de.regelsuche.search.program.CompiledLinearRewriteEngine;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Actual evolution compilation, persisted training trajectories and independent receiving instances. */
@Timeout(30)
class PersistedAstBindingTransportTest {
    private static final List<String> GENES = List.of("difference-product", "square-product", "cancel-addend");
    private final ExpressionParser parser = new ExpressionParser();
    private final CompiledAstReplayCodec codec = new CompiledAstReplayCodec();

    private CompiledAstRewriteProgram compiled() {
        var genome = TraceStrategyTransferExample.inventory();
        var nodes = new ArrayList<EvolutionRewriteProgramPlan.Node>();
        for (int i = 0; i < GENES.size(); i++) nodes.add(new EvolutionRewriteProgramPlan.Source("step-" + i, List.of(GENES.get(i))));
        var plan = EvolutionRewriteProgramPlan.create(genome, new EvolutionRewriteProgramPlan.Sequence("trajectory", nodes), 16, 16);
        return new CompiledLinearRewriteEngine(new EvolutionRewriteProgramCompiler().compile(genome, plan).program(), 128).compileAst();
    }

    private TraceBindingModel modelFromReloadedTraining() {
        var traces = new ArrayList<TraceBindingModel.Trace>();
        int id = 0;
        for (String input : List.of("(x+y)*(x-y)+y*y+101", "(u+v)*(u-v)+v*v+103")) {
            var source = parser.parseTerm(input);
            var candidate = compiled().transformMeasured(source).candidates().getFirst();
            byte[] bytes = codec.encode(candidate);
            var reloaded = new CompiledAstReplayCodec().decode(bytes);
            assertEquals(candidate, reloaded);
            assertEquals(candidate.target(), compiled().replayEncoded(source, bytes).target());
            traces.add(new TraceBindingModel.Trace("persisted-train-" + id++, GENES, reloaded.states()));
        }
        return TraceBindingModel.learn(traces, Set.of(GENES), 4, 100_000, 100_000);
    }

    @Test void persistedTrainingAndApplicationKeepGroupedBindingsThroughAllStages(@TempDir Path directory) throws Exception {
        var model = modelFromReloadedTraining();
        assertEquals(1, model.templates().size());
        String input = "((a+(b+c))+y)*((a+(b+c))-y)+y*y+107";
        Expr source = parser.parseTerm(input);
        var generated = compiled().transformMeasured(source).candidates().getFirst();
        Path file = directory.resolve("compiled-proof-data.json");
        Files.write(file, codec.encode(generated));
        byte[] saved = Files.readAllBytes(file);
        var restored = new CompiledAstReplayCodec().decode(saved);
        assertEquals(4, restored.states().size());
        assertEquals(generated, restored);
        assertEquals(parser.parseTerm("(a+(b+c))^2+107"), compiled().replayEncoded(source, saved).target());
        assertEquals(1, model.session().matching(GENES, restored.states()).size());
        new ExactPolynomialAnalysis().requireEquivalent(input, ExpressionFormatter.format(restored.target()));
    }

    @Test void atomicRationalLeavesRemainUsableForLearningAfterReload() {
        Expr compound = new BinaryExpr(parser.parseTerm("a"), ADD, NumberExpr.exact("1/3"));
        Expr y = parser.parseTerm("y");
        Expr source = new BinaryExpr(new BinaryExpr(new BinaryExpr(new BinaryExpr(compound, ADD, y), MUL,
            new BinaryExpr(compound, SUB, y)), ADD, new BinaryExpr(y, MUL, y)), ADD, new NumberExpr(109));
        var generated = compiled().transformMeasured(source).candidates().getFirst();
        byte[] bytes = codec.encode(generated);
        var restored = codec.decode(bytes);
        assertEquals(new BinaryExpr(new BinaryExpr(compound, POW, new NumberExpr(2)), ADD, new NumberExpr(109)), restored.target());
        assertEquals(restored.target(), compiled().replayEncoded(source, bytes).target());
        assertEquals(1, modelFromReloadedTraining().session().matching(GENES, restored.states()).size());
    }

    @Test void scopedAliasesSurvivePersistenceButADifferentSourceIdentityIsRejected() {
        var scope = new SymbolScope(new UUID(0, 112)); scope.alias("alias", scope.resolve("y"));
        Expr source = SymbolicExpression.parse("(x+y)*(x-y)+alias*alias+113", scope).expression();
        var generated = compiled().transformMeasured(source).candidates().getFirst();
        byte[] bytes = codec.encode(generated);
        var restored = codec.decode(bytes);
        assertEquals(SymbolicExpression.parse("x^2+113", scope).expression(), restored.target());
        assertEquals(restored.target(), compiled().replayEncoded(source, bytes).target());
        assertEquals(1, modelFromReloadedTraining().session().matching(GENES, restored.states()).size());
        Expr other = SymbolicExpression.parse("(x+y)*(x-y)+y*y+113", new SymbolScope(new UUID(0, 113))).expression();
        assertThrows(IllegalArgumentException.class, () -> compiled().replayEncoded(other, bytes));
    }

    @Test void alteredMiddleStateIsRejectedEvenWithUnchangedSourceAndFinalResult() throws Exception {
        Expr source = parser.parseTerm("(x+y)*(x-y)+y*y+127");
        var program = compiled();
        var actual = program.transformMeasured(source).candidates().getFirst();
        byte[] original = codec.encode(actual);
        assertEquals(actual.target(), program.replayEncoded(source, original).target());
        var json = new ObjectMapper();
        var root = (ObjectNode) json.readTree(original);
        ((ArrayNode) root.get("states")).set(1, root.get("states").get(0).deepCopy());
        byte[] altered = json.writeValueAsBytes(root);
        var imported = codec.decode(altered);
        assertEquals(actual.source(), imported.source());
        assertEquals(actual.target(), imported.target());
        assertNotEquals(codec.contentHash(actual), codec.contentHash(imported));
        assertThrows(IllegalArgumentException.class, () -> program.replayEncoded(source, altered));
    }

    @Test void replayValidityDoesNotTurnAWrongOccurrenceIntoTheLearnedStrategy() {
        var model = modelFromReloadedTraining();
        Expr source = parser.parseTerm("(x+y)*(x-y)+y*y+(w-z^2+z*z)^2");
        int accepted = 0, rejected = 0;
        for (var candidate : compiled().transformMeasured(source).candidates()) {
            byte[] bytes = codec.encode(candidate);
            var restored = codec.decode(bytes);
            assertEquals(candidate, restored);
            assertEquals(restored.target(), compiled().replayEncoded(source, bytes).target());
            if (model.session().matching(GENES, restored.states()).isEmpty()) rejected++;
            else accepted++;
        }
        assertTrue(accepted > 0);
        assertTrue(rejected > 0, "valid primitive replay is not permission to change an unrelated bound residual");
    }
}
