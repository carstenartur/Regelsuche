package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CheckedSchemaProviderAccountingTest {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final String SINGLE = "(x+y)*(x-y)+y*y";
    private static final String MULTIPLE = "((a+b)*(a-b)+b*b)+((c+d)*(c-d)+d*d)";
    private static final TypedMoveSearch.Context CONTEXT = TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION);
    private static CheckedLearnedSchemaModel model;
    private static String selected;

    @BeforeAll static void learnAndRestore() {
        var formation = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
        var learned = CheckedLearnedSchemaModel.learn(formation);
        model = CheckedLearnedSchemaModel.load(learned.toCanonicalJson(), learned.inventoryHash());
        selected = model.nativeProviders().getFirst().candidates(state(parse(SINGLE)), CONTEXT).moves().stream()
            .filter(move -> move.targetExpression().equals(parse("x^2"))).findFirst().orElseThrow().exportLegacy().transformation().rule();
    }

    @Test void normalReceiptsAndDeclaredOrderPreserveTheHistoricalProviderContract() throws Exception {
        var observations = new ArrayList<Observation>();
        for (String source : List.of(SINGLE, MULTIPLE, "x+17", "x/0"))
            observations.add(observe(source, model, source, model.bounds().maximumSchemas(), schemaIds()));
        observations.add(observe("missing-prerequisite", model.requiring(List.of("x > 0")), SINGLE,
            model.bounds().maximumSchemas(), schemaIds()));
        observations.add(observe("selected-schema", model, MULTIPLE, 1, Set.of(selected)));
        observations.add(observe("one-schema-per-site", model, MULTIPLE, 1, schemaIds()));
        System.out.println("P04_PROVIDER_GOLD " + new ObjectMapper().writeValueAsString(observations));
    }

    private static Observation observe(String id, CheckedLearnedSchemaModel checked, String text, int maximum, Set<String> included) {
        Expr source = parse(text);
        var legacy = checked.providers(maximum, Map.of(), included).getFirst()
            .candidates(MoveState.root(CODEC.encodeExpression(source)), MoveContext.frozen("unused"));
        var nativeBatch = checked.nativeProviders(maximum, Map.of(), included).getFirst().candidates(state(source), CONTEXT);
        assertEquals(legacy.work(), nativeBatch.work(), id);
        assertEquals(legacy.complete(), nativeBatch.complete(), id);
        assertEquals(legacy.moves(), nativeBatch.moves().stream().map(NativeSearchMove::exportLegacy).toList(), id);
        return new Observation(id, nativeBatch.work(), nativeBatch.complete(), nativeBatch.moves().stream().map(move ->
            new Candidate(move.exportLegacy().transformation().rule(), CODEC.encodeExpression(move.targetExpression()),
                move.generationCost(), move.proof().work())).toList());
    }
    private static Set<String> schemaIds() { return model.schemas().stream().map(CheckedLearnedSchemaModel.Schema::id).collect(java.util.stream.Collectors.toSet()); }
    private static Expr parse(String text) { return new ExpressionParser().parseExactTerm(text).expression(); }
    private static TypedMoveSearch.State state(Expr source) { return new TypedMoveSearch.State(source, 0, 0, "", List.of(), Set.of(), 0); }
    record Observation(String id, TransformationWorkMetrics work, boolean complete, List<Candidate> candidates) {}
    record Candidate(String rule, String target, long generationCost, ExecutionWork work) {}
}
