package de.regelsuche.evolution;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.symbol.SymbolId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CheckedLearnedSchemaModelTest {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static TraceRewriteStrategyLearner.FrozenStrategy formation;

    @BeforeAll static void train() {
        formation = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
    }

    @Test void formsDirectCheckedRulesFromChangedSubtreesOfActualSelectedPaths() {
        var model = CheckedLearnedSchemaModel.learn(formation);
        Expr source = parse("(x+y)*(x-y)+y*y");
        Expr target = parse("x^2");
        var move = moves(model, source).stream().filter(value ->
            CODEC.decodeExpression(value.transformation().transformedExpression()).equals(target)).findFirst().orElseThrow();
        assertEquals(0, move.transformation().primitiveStepCount(), "a proved schema is not a fictitious primitive trace");
        assertEquals(1, move.transformation().exactTheoryStepCount());
        assertEquals(SearchMove.ProofStrength.VERIFIED, move.proofStrength());
        assertTrue(model.verifier().verify(state(source), move, TypedMoveSearch.Context.frozen(target)).accepted());
        assertTrue(model.formationWork() > 0);
        assertFalse(model.schemas().isEmpty());
        assertEquals(formation.inventory().contentHash(), model.inventoryHash());
    }

    @Test void repeatedBindingsRejectNearMissAndAcceptCompoundScopedBindingsInAnOuterContext() {
        var model = CheckedLearnedSchemaModel.learn(formation);
        Expr nearMiss = parse("(x+y)*(x-y)+z*z");
        assertTrue(moves(model, nearMiss).stream().noneMatch(move ->
            CODEC.decodeExpression(move.transformation().transformedExpression()).equals(parse("x^2"))));
        Expr a = VariableExpr.scoped(new SymbolId(new UUID(0, 91), 2));
        Expr b = VariableExpr.scoped(new SymbolId(new UUID(0, 91), 3));
        Expr base = new BinaryExpr(a, ADD, NumberExpr.exact("1/3"));
        Expr cancellation = new BinaryExpr(new BinaryExpr(new BinaryExpr(base, ADD, b), MUL,
            new BinaryExpr(base, SUB, b)), ADD, new BinaryExpr(b, MUL, b));
        Expr source = new BinaryExpr(new NumberExpr(7), MUL, cancellation);
        Expr target = new BinaryExpr(new NumberExpr(7), MUL, new BinaryExpr(base, POW, new NumberExpr(2)));
        var move = moves(model, source).stream().filter(value ->
            CODEC.decodeExpression(value.transformation().transformedExpression()).equals(target)).findFirst().orElseThrow();
        assertTrue(model.verifier().verify(state(source), move, TypedMoveSearch.Context.frozen(target)).accepted());
    }

    @Test void excludesUnsupportedDomainsAndRequiresCallerPrerequisitesAtGenerationAndVerification() {
        var model = CheckedLearnedSchemaModel.learn(formation);
        assertTrue(moves(model, parse("(sin(x)+y)*(sin(x)-y)+y*y")).isEmpty());
        assertTrue(moves(model, parse("(1/x+y)*(1/x-y)+y*y")).isEmpty());
        var gated = model.requiring(List.of("x > 0"));
        Expr source = parse("(x+y)*(x-y)+y*y");
        assertTrue(moves(gated, source).isEmpty());
        var carried = new MoveContext("unused", List.of("x > 0"), MoveContext.Phase.FROZEN_EVALUATION);
        var move = gated.providers().getFirst().candidates(MoveState.root(CODEC.encodeExpression(source)), carried).moves().getFirst();
        assertFalse(gated.verifier().verify(state(source), move, TypedMoveSearch.Context.frozen(parse("x^2"))).accepted());
        assertTrue(gated.verifier().verify(state(source), move,
            TypedMoveSearch.Context.sourceOnly(List.of("x > 0"), MoveContext.Phase.FROZEN_EVALUATION)).accepted());
    }

    @Test void persistedModelIsReprovedAndReusableWithoutTheFormationObject() throws Exception {
        var learned = CheckedLearnedSchemaModel.learn(formation);
        String json = learned.toCanonicalJson();
        var loaded = CheckedLearnedSchemaModel.load(json, formation.inventory().contentHash());
        assertEquals(json, loaded.toCanonicalJson());
        assertTrue(loaded.loadWork() > 0);
        Expr source = parse("(u+v)*(u-v)+v*v");
        assertEquals(moves(learned, source), moves(loaded, source));
        var move = moves(loaded, source).getFirst();
        assertTrue(loaded.verifier().verify(state(source), move,
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION)).accepted());
        assertThrows(IllegalArgumentException.class, () -> CheckedLearnedSchemaModel.load(json, "sha256:" + "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> CheckedLearnedSchemaModel.load(
            json.replace(CheckedLearnedSchemaModel.CHECKER_REVISION, "outdated-checker"), learned.inventoryHash()));
        var tree = (ObjectNode) new ObjectMapper().readTree(json);
        var first = (ObjectNode) tree.get("schemas").get(0);
        first.set("target", new ObjectMapper().createArrayNode().add("N").add("991"));
        var failure = assertThrows(IllegalArgumentException.class, () -> CheckedLearnedSchemaModel.load(tree.toString(), learned.inventoryHash()));
        assertTrue(failure.getMessage().contains("polynomial normal forms differ"), "semantic recheck must happen before digest comparison");
    }

    @Test void reusedOrForgedMoveMetadataCannotBypassOccurrenceAndFullTargetVerification() {
        var model = CheckedLearnedSchemaModel.learn(formation);
        Expr source = parse("(x+y)*(x-y)+y*y");
        var move = moves(model, source).getFirst();
        var context = TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION);
        assertFalse(model.verifier().verify(state(parse("(x+y)*(x-y)+z*z")), move, context).accepted());
        var forgedProof = new SearchMove(move.transformation(), move.sourceKind(), move.ruleId(), move.ruleFamily(),
            move.generationCost(), move.applicationCost(), move.verificationCost(), move.primitiveExpansion(), move.assumptions(),
            SearchMove.ProofStrength.REPLAYABLE, move.provenance(), move.capabilityDelta(), move.valueEvidence());
        assertFalse(model.verifier().verify(state(source), forgedProof, context).accepted());
        var transformation = move.transformation();
        assertThrows(IllegalArgumentException.class, () -> new de.regelsuche.transform.Transformation(
            transformation.rule(), CODEC.encodeExpression(parse("991")), transformation.kind(),
            transformation.mayIncreaseComplexity(), transformation.estimatedCostDelta(), true,
            transformation.applicationKey(), transformation.assumptions(), transformation.packId(),
            transformation.license(), transformation.primitiveRuleIds(), transformation.provenance()));
        assertThrows(IllegalArgumentException.class, () -> de.regelsuche.transform.ExactTheoryEvidence.fromVerified(
            ((de.regelsuche.transform.TransformationProvenance.ExactTheoryStep) transformation.provenance()).evidence().binding()));
    }

    @Test void rejectsHugeInputsAndReportsTruncatedSelectionWithoutMakingACompletenessClaim() {
        var model = CheckedLearnedSchemaModel.learn(formation);
        Expr expression = new VariableExpr("x");
        for (int i = 0; i < 90; i++) expression = new BinaryExpr(expression, ADD, new NumberExpr(0));
        final Expr huge = expression;
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertTrue(moves(model, huge).isEmpty()));
        assertThrows(IllegalArgumentException.class, () -> CheckedLearnedSchemaModel.load("[".repeat(200), model.inventoryHash()));
        Expr source = parse("(x+y)*(x-y)+y*y");
        var limited = model.providers(1).getFirst().candidates(MoveState.root(CODEC.encodeExpression(source)), MoveContext.frozen("unused"));
        if (model.schemas().stream().filter(schema -> schema.source() instanceof de.regelsuche.transform.PatternExpr.Operation operation
                && operation.operator() == ADD).count() > 1) assertFalse(limited.complete());
        for (var move : limited.moves()) assertTrue(model.verifier().verify(state(source), move,
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION)).accepted());
    }

    @Test void schemaSubsetPaysOnlyForItsIndexAndDoesNotChangeProofAuthorization() {
        var model = CheckedLearnedSchemaModel.learn(formation);
        Expr source = parse("(x+y)*(x-y)+y*y");
        var full = model.providers().getFirst().candidates(MoveState.root(CODEC.encodeExpression(source)), MoveContext.frozen("unused"));
        var targetMove = full.moves().stream().filter(move -> CODEC.decodeExpression(move.transformation().transformedExpression())
            .equals(parse("x^2"))).findFirst().orElseThrow();
        String schemaId = targetMove.transformation().rule();
        var subset = model.providers(1, java.util.Map.of(), Set.of(schemaId)).getFirst()
            .candidates(MoveState.root(CODEC.encodeExpression(source)), MoveContext.frozen("unused"));
        assertEquals(1, subset.moves().size());
        assertEquals(targetMove.transformation(), subset.moves().getFirst().transformation());
        assertTrue(subset.work().totalWorkUnitsV2() <= full.work().totalWorkUnitsV2());
        assertTrue(model.verifier().verify(state(source), subset.moves().getFirst(),
            TypedMoveSearch.Context.frozen(parse("x^2"))).accepted());
        assertTrue(model.providers(1, java.util.Map.of(), Set.of()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> model.providers(1, java.util.Map.of(), Set.of("unregistered")));
    }

    @Test void failedInstantiationStillPaysForTheVisitedTargetValidationWork() throws Exception {
        // A coherent, independently true expansion exercises the rejection path; it is never a stored learned template.
        var original = CheckedLearnedSchemaModel.learn(formation);
        var tree = (ObjectNode) new ObjectMapper().readTree(original.toCanonicalJson());
        var sourcePattern = de.regelsuche.transform.PatternExpr.op(ADD,
            de.regelsuche.transform.PatternExpr.var("P0"), de.regelsuche.transform.PatternExpr.num(0));
        var targetPattern = de.regelsuche.transform.PatternExpr.op(ADD, sourcePattern, de.regelsuche.transform.PatternExpr.num(0));
        String proof = CheckedSchemaSupport.prove(sourcePattern, targetPattern, original.bounds(), new CheckedSchemaSupport.Work());
        var schema = ((ObjectNode) tree.get("schemas").get(0)).deepCopy();
        schema.put("id", "checked-schema:" + SchematicProofPlan.hash(original.inventorySemanticsHash() + "\n" + proof));
        schema.put("proofHash", SchematicProofPlan.hash(proof));
        schema.set("source", CheckedSchemaSupport.pattern(sourcePattern));
        schema.set("target", CheckedSchemaSupport.pattern(targetPattern));
        tree.putArray("schemas").add(schema);
        var model = CheckedLearnedSchemaModel.load(tree.toString(), original.inventoryHash());
        Expr unmatched = new BinaryExpr(parse("x+1"), ADD, balancedTree(507));
        Expr rejected = new BinaryExpr(parse("x+0"), ADD, balancedTree(507));
        var provider = model.providers().getFirst();
        var baseline = provider.candidates(MoveState.root(CODEC.encodeExpression(unmatched)), MoveContext.frozen("unused"));
        var attempted = provider.candidates(MoveState.root(CODEC.encodeExpression(rejected)), MoveContext.frozen("unused"));
        assertTrue(attempted.moves().isEmpty());
        assertTrue(attempted.work().totalWorkUnitsV2() >= baseline.work().totalWorkUnitsV2() + 512,
            "the target exceeds 512 nodes, so its already visited nodes must remain in rejected-work accounting");
    }

    @Test void enormousPersistedBoundsAreRejectedAsInvalidArtifacts() throws Exception {
        var model = CheckedLearnedSchemaModel.learn(formation);
        var tree = (ObjectNode) new ObjectMapper().readTree(model.toCanonicalJson());
        ((ObjectNode) tree.get("bounds")).put("maximumPatternNodes", Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> CheckedLearnedSchemaModel.load(tree.toString(), model.inventoryHash()));
    }

    @Test void independentlyReplaysASchemaAfterTheSearchAssessesAnActualNewCapability() {
        var model = CheckedLearnedSchemaModel.learn(formation);
        Expr source = parse("(x+y)*(x-y)+y*y"), target = parse("x^2");
        var a = de.regelsuche.transform.PatternExpr.var("A");
        var squareOpening = new de.regelsuche.transform.AstRewriteTransport(List.of(
            new de.regelsuche.transform.PatternRewriteRule("open-square",
                de.regelsuche.transform.PatternExpr.op(POW, a, de.regelsuche.transform.PatternExpr.num(2)),
                de.regelsuche.transform.PatternExpr.op(MUL, a, a))), 32, 32);
        TypedMoveSearch.StateEvaluator evaluator = (state, context) -> {
            var expansions = squareOpening.generate(state.expression());
            if (expansions.isEmpty()) return new StateValue.Assessment(1, 0, 1, 0, java.util.Map.of());
            String encodedSource = CODEC.encodeExpression(state.expression());
            var capability = new StateValue.Capability("open-square", encodedSource, "actual-transport-occurrence",
                encodedSource, CODEC.encodeExpression(expansions.getFirst().target()));
            return new StateValue.Assessment(1, 0, 1, expansions.size(), java.util.Map.of("open-square", capability));
        };
        var problem = new TypedMoveSearch.Problem(source,
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION), model.providers(),
            MovePriorityPolicy.INVENTORY_ORDER, model.verifier(), state -> 0, MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(6, 1, 1_000, 32, 30_000), evaluator);
        var result = new TypedSourceOnlySearch().search(problem,
            state -> new TypedSourceOnlySearch.Score(state.expression().equals(target) ? 0 : 1, 1));
        assertEquals(target, result.incumbent().expression());
        assertEquals(Set.of("open-square"), result.witness().getFirst().move().capabilityDelta());
        assertTrue(result.replayWork() > 0);
    }

    @Test void mathematicalVerificationDoesNotAuthorizeForgedSearchCapabilities() {
        var model = CheckedLearnedSchemaModel.learn(formation);
        Expr source = parse("(x+y)*(x-y)+y*y"), target = parse("x^2");
        var provider = model.providers().getFirst();
        var alteredAnnotations = new TypedMoveSearch.TypedProvider() {
            @Override public Descriptor descriptor() { return provider.descriptor(); }
            @Override public Batch candidates(MoveState state, MoveContext context) {
                var batch = provider.candidates(state, context);
                return new Batch(batch.moves().stream().map(move -> move.withCapabilityDelta(Set.of("forged"))).toList(),
                    batch.work(), batch.complete());
            }
        };
        var problem = new TypedMoveSearch.Problem(source,
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION), List.of(alteredAnnotations),
            MovePriorityPolicy.INVENTORY_ORDER, model.verifier(), state -> 0, MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(6, 1, 1_000, 32, 30_000));
        var result = new TypedSourceOnlySearch().search(problem,
            state -> new TypedSourceOnlySearch.Score(state.expression().equals(target) ? 0 : 1, 1));
        assertEquals(target, result.incumbent().expression());
        assertTrue(result.incumbent().capabilities().isEmpty());
        assertTrue(result.witness().getFirst().move().capabilityDelta().isEmpty());
    }

    private static Expr balancedTree(int nodes) {
        if (nodes == 1) return new VariableExpr("q");
        int left = (nodes - 1) / 2;
        if (left % 2 == 0) left--;
        return new BinaryExpr(balancedTree(left), ADD, balancedTree(nodes - 1 - left));
    }

    private static List<SearchMove> moves(CheckedLearnedSchemaModel model, Expr source) {
        return model.providers().stream().flatMap(provider -> provider.candidates(
            MoveState.root(CODEC.encodeExpression(source)), MoveContext.frozen("unused")).moves().stream()).toList();
    }
    private static TypedMoveSearch.State state(Expr expression) {
        return new TypedMoveSearch.State(expression, 0, 0, "", List.of(), Set.of(), 0);
    }
    private static Expr parse(String value) { return new ExpressionParser().parseExactTerm(value).expression(); }
}
