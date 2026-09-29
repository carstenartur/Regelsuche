package de.regelsuche.evolution;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.transform.ExactTheoryEvidence;
import de.regelsuche.transform.NativeExactTheoryEvidence;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
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

    @Test void nativeLearnedSchemasCarryPrivateExactStructureThroughIndependentReplay() {
        var learned=CheckedLearnedSchemaModel.learn(formation);
        var model=CheckedLearnedSchemaModel.load(learned.toCanonicalJson(),learned.inventoryHash());
        Expr source=parse("(x+y)*(x-y)+y*y"),target=parse("x^2");
        var providers=assertDoesNotThrow(()->model.nativeProviders());
        var context=TypedMoveSearch.Context.frozen(target);
        var generated=providers.getFirst().candidates(state(source),context);
        assertEquals(moves(model,source),generated.moves().stream().map(NativeSearchMove::exportLegacy).toList());
        var verifier=NativeVerifier.registered(providers);
        var proposal=generated.moves().stream().filter(move->move.targetExpression().equals(target)).findFirst().orElseThrow();
        var exact=(NativeMoveProof.Exact)proposal.proof();
        assertThrows(IllegalArgumentException.class,()->de.regelsuche.transform.NativeExactTheoryEvidence.fromVerified(exact.evidence().binding()));
        assertThrows(IllegalArgumentException.class,()->de.regelsuche.transform.NativeExactTheoryEvidence.fromVerified(exact.evidence().binding().observation()));
        var checked=verifier.verify(state(source),proposal,context);
        assertTrue(checked.accepted());assertTrue(checked.work()>0);
        assertEquals(model.verifier().verify(state(source),proposal.exportLegacy(),context),checked.exportLegacy());
        assertFalse(verifier.verify(state(parse("(x+y)*(x-y)+z*z")),proposal,context).accepted());
        var result=new NativeMoveSearch().search(new NativeMoveSearch.Problem(source,context,providers,
            MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(0,1,100000,10,1000000)),SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(MoveSearch.Outcome.TARGET_REACHED,result.observedOutcome());assertEquals(target,result.output());
        assertEquals(0,result.witness().getFirst().move().primitiveStepCount());
        var checks=new NativeTestObservation.Checks(verifier);
        var sourceOnly=new NativeMoveSearch.Problem(source,TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION),providers,
            MoveSearch.Mode.FAST,MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(0,1,100000,10,1000000),NativeMovePriorityPolicy.INVENTORY_ORDER,
            NativeMoveSearch.ZeroScore.INSTANCE,NativeStateValue.NONE,checks);
        var quality=new NativeMoveSearch().searchUntil(sourceOnly,NativeTestObservation.Objective.POWER,0,SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(MoveSearch.Outcome.QUALITY_REACHED,quality.search().observedOutcome());assertEquals(target,quality.incumbent().expression());
        assertEquals(2,checks.calls());assertTrue(quality.replayWork()>0);assertFalse(quality.withinBudget());assertTrue(quality.totalWork()<=quality.workBudget());
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
        var nativeProviders=model.nativeProviders();
        var nativeMoves=nativeProviders.getFirst().candidates(state(source),TypedMoveSearch.Context.frozen(target)).moves();
        assertEquals(moves(model,source),nativeMoves.stream().map(NativeSearchMove::exportLegacy).toList());
        var nativeMove=nativeMoves.stream().filter(candidate->candidate.targetExpression().equals(target)).findFirst().orElseThrow();
        assertTrue(NativeVerifier.registered(nativeProviders).verify(state(source),nativeMove,TypedMoveSearch.Context.frozen(target)).accepted());
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

    @Test void exportedApplicationIsDataUntilFreshModelReplaysTheEntireBinding() {
        var learned=CheckedLearnedSchemaModel.learn(formation);
        Expr source=parse("(x+y)*(x-y)+y*y");
        var binding=applicationBinding(learned,source);
        var imported=CheckedLearnedSchemaModel.load(learned.toCanonicalJson(),formation.inventory().contentHash());
        assertThrows(IllegalArgumentException.class,()->ExactTheoryEvidence.fromVerified(binding));
        assertThrows(IllegalArgumentException.class,()->NativeExactTheoryEvidence.fromVerified(binding));
        var context=TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION);
        var result=imported.replayApplication(state(source),binding,context);
        assertTrue(result.accepted());assertTrue(result.work()>0);assertTrue(imported.loadWork()>0);
        assertEquals(imported.nativeProviders().getFirst().descriptor().id(),result.ruleId());
        var proof=assertInstanceOf(NativeMoveProof.Exact.class,result.checkedProof());
        assertEquals(binding,proof.evidence().exportLegacy().binding());
        var move=new NativeSearchMove(proof,imported.nativeProviders().getFirst().descriptor(),0,Set.of());
        assertTrue(NativeVerifier.registered(imported.nativeProviders()).verify(state(source),move,context).accepted());
        assertEquals(imported.verifier().verify(state(source),move.exportLegacy(),context),result.exportLegacy());
    }

    @Test void importedBindingRejectsEveryAlteredPublicIdentityAndRetainsAttemptedWork() throws Exception {
        var model=CheckedLearnedSchemaModel.learn(formation);
        Expr source=parse("(x+y)*(x-y)+y*y");
        var binding=applicationBinding(model,source);
        var context=TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION);
        var mapper=new ObjectMapper();
        var values=mapper.valueToTree(binding);
        for (String field:List.of("sourceExpression","transformedExpression","theoryStepId","evidenceHash",
                "receiptArtifactId","runArtifactId","canonicalWorkUnits")) {
            var copy=((ObjectNode)values).deepCopy();
            if (field.equals("canonicalWorkUnits")) copy.put(field,binding.canonicalWorkUnits()+1);
            else if (field.endsWith("Hash") || field.endsWith("ArtifactId")) copy.put(field,"sha256:"+"0".repeat(64));
            else if (field.endsWith("Expression")) copy.put(field,CODEC.encodeExpression(parse("991")));
            else copy.put(field,"forged-theory");
            var rejected=model.replayApplication(state(source),mapper.treeToValue(copy,ExactTheoryEvidence.Binding.class),context);
            assertRejectedImport(rejected,field);
        }
        assertRejectedImport(model.replayApplication(state(parse("(x+y)*(x-y)+z*z")),binding,context),"changed receiving source");
        for (String field:List.of("schema","checkerRevision","inventorySemanticsHash","modelId","schemaId",
                "proofHash","domain","source","target","applicationWork")) {
            var data=(ObjectNode)mapper.readTree(binding.canonicalEvidenceJson());
            if (field.equals("applicationWork")) data.put(field,binding.canonicalWorkUnits()+1);
            else data.put(field,"forged");
            assertRejectedImport(model.replayApplication(state(source),withEvidence(binding,data.toString()),context),field);
        }
        var data=(ObjectNode)mapper.readTree(binding.canonicalEvidenceJson());
        data.putArray("path").add(0).add(0).add(0).add(0);
        assertRejectedImport(model.replayApplication(state(source),withEvidence(binding,data.toString()),context),"absent occurrence");
        data=(ObjectNode)mapper.readTree(binding.canonicalEvidenceJson());data.putArray("path").add(2);
        assertRejectedImport(model.replayApplication(state(source),withEvidence(binding,data.toString()),context),"invalid path");
        data=(ObjectNode)mapper.readTree(binding.canonicalEvidenceJson());
        ((com.fasterxml.jackson.databind.node.ArrayNode)data.get("bindings")).add(data.get("bindings").get(0).deepCopy());
        assertRejectedImport(model.replayApplication(state(source),withEvidence(binding,data.toString()),context),"duplicate binding");
        data=(ObjectNode)mapper.readTree(binding.canonicalEvidenceJson());
        ((ObjectNode)data.get("bindings").get(0)).put("expression",CODEC.encodeExpression(parse("991")));
        assertRejectedImport(model.replayApplication(state(source),withEvidence(binding,data.toString()),context),"forged binding");
    }

    @Test void importDoesNotInferPrerequisitesFromTheReceivedModel() {
        var model=CheckedLearnedSchemaModel.learn(formation).requiring(List.of("x > 0"));
        Expr source=parse("(x+y)*(x-y)+y*y");
        var context=TypedMoveSearch.Context.sourceOnly(List.of("x > 0"),MoveContext.Phase.FROZEN_EVALUATION);
        var move=model.nativeProviders().getFirst().candidates(state(source),context).moves().getFirst();
        var binding=((NativeMoveProof.Exact)move.proof()).evidence().exportLegacy().binding();
        var imported=CheckedLearnedSchemaModel.load(model.toCanonicalJson(),formation.inventory().contentHash());
        assertRejectedImport(imported.replayApplication(state(source),binding,
            TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION)),"missing prerequisite");
        assertTrue(imported.replayApplication(state(source),binding,context).accepted());
    }

    @Test void malformedImportCannotRelaxTheExistingStrictParser() {
        var model=CheckedLearnedSchemaModel.learn(formation);Expr source=parse("(x+y)*(x-y)+y*y");
        var binding=applicationBinding(model,source);
        var context=TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION);
        for (String json:List.of("{",binding.canonicalEvidenceJson()+" {}","{\"schema\":\"a\",\"schema\":\"b\"}",
                "[".repeat(150)," ".repeat(CheckedSchemaSupport.MAXIMUM_JSON_CHARACTERS)+"{}"))
            assertThrows(IllegalArgumentException.class,()->model.replayApplication(state(source),withEvidence(binding,json),context));
    }

    @Test void interruptedImportSettlesUnreturnedReplayWorkAndPreservesOriginalIllegalArgumentFailure() {
        assertImportFailure(ImportMeter.Abort.BINDINGS);
        assertImportFailure(ImportMeter.Abort.RESULT);
        assertImportFailure(ImportMeter.Abort.CLOSE);
    }

    @Test void semanticRejectionCannotHideObservationFailures() {
        assertImportFailure(ImportMeter.Abort.REJECT_OBSERVATION);
    }
    @Test void semanticRejectionCannotHideCleanupFailures() {
        assertImportFailure(ImportMeter.Abort.REJECT_CLOSE);
    }

    @Test void invalidPathRetainsPaidAllocationAndItsAlreadyAppendedPrefix() throws Exception {
        var model=CheckedLearnedSchemaModel.learn(formation);Expr source=parse("(x+y)*(x-y)+y*y");
        var binding=applicationBinding(model,source);var data=(ObjectNode)new ObjectMapper().readTree(binding.canonicalEvidenceJson());
        data.putArray("path").add(0).add(2);var meter=new ImportMeter(ImportMeter.Abort.NONE);
        observeRejectedBuild(model,source,withEvidence(binding,data.toString()),meter);
        assertAll(()->assertEquals(1,meter.pathAllocations,"owned path allocation is paid before invalid part"),
            ()->assertEquals(1,meter.pathInsertions,"the valid prefix insertion is paid before invalid part"));
    }

    @Test void duplicateBindingRetainsThePaidMapInsertionBeforeRejection() throws Exception {
        var model=CheckedLearnedSchemaModel.learn(formation);Expr source=parse("(x+y)*(x-y)+y*y");
        var binding=applicationBinding(model,source);var data=(ObjectNode)new ObjectMapper().readTree(binding.canonicalEvidenceJson());
        var duplicate=((ObjectNode)data.get("bindings").get(0)).deepCopy().put("expression",CODEC.encodeExpression(parse("991")));
        ((com.fasterxml.jackson.databind.node.ArrayNode)data.get("bindings")).add(duplicate);
        var meter=new ImportMeter(ImportMeter.Abort.NONE);meter.duplicateValue=parse("991");
        observeRejectedBuild(model,source,withEvidence(binding,data.toString()),meter);
        assertEquals(1,meter.duplicateInsertions,"the replacing map insertion remains paid despite duplicate rejection");
    }

    private static void observeRejectedBuild(CheckedLearnedSchemaModel model,Expr source,ExactTheoryEvidence.Binding binding,ImportMeter meter) {
        try (var scope=RetainedOperation.open(meter)) {
            meter.scope=scope;
            assertRejectedImport(model.replayApplication(state(source),binding,
                TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION)),"partial import build");
        }
    }

    @Test void successfulImportDelegatesItsReceiptWithoutAlsoSettlingItLocally() {
        var model=CheckedLearnedSchemaModel.learn(formation);Expr source=parse("(x+y)*(x-y)+y*y");
        var binding=applicationBinding(model,source);var meter=new ImportMeter(ImportMeter.Abort.NONE);
        try (var scope=RetainedOperation.open(meter)) {
            meter.scope=scope;
            var result=model.replayApplication(state(source),binding,
                TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION));
            assertTrue(result.accepted());assertTrue(meter.sawResult);assertTrue(meter.sawBindings);
            assertTrue(meter.sawBindingsWithOutcome,"the returned match outcome remains owned throughout binding decoding");
            assertEquals(result.work(),meter.work.units);
            assertEquals(List.of(4L),meter.afterResult,"the final frame close is direct work; the receipt is only delegated");
        }
        assertFalse(de.regelsuche.retention.RetainedJson.active());
        assertEquals(0,RetainedGraph.measure(meter.scope).retained().characters());
    }

    private static void assertImportFailure(ImportMeter.Abort kind) {
        var model=CheckedLearnedSchemaModel.learn(formation);Expr source=parse("(x+y)*(x-y)+y*y");
        var binding=applicationBinding(model,source);var meter=new ImportMeter(kind);
        boolean rejected=kind==ImportMeter.Abort.REJECT_OBSERVATION || kind==ImportMeter.Abort.REJECT_CLOSE;
        Expr receivedSource=rejected?parse("991"):source;
        meter.rejectedSource=rejected?CODEC.encodeExpression(receivedSource):null;
        try (var scope=RetainedOperation.open(meter)) {
            meter.scope=scope;
            var thrown=assertThrows(IllegalArgumentException.class,()->model.replayApplication(state(receivedSource),binding,
                TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION)));
            assertSame(meter.failure,thrown);
            assertTrue(meter.work.units>1,"domain and concrete replay work was already accumulated");
            assertEquals(meter.work.units,meter.afterFailure.getLast());
            assertEquals(1,meter.afterFailure.stream().filter(units->units==meter.work.units).count(),"one settlement only");
            assertTrue(meter.failedDebit==4 || meter.afterFailure.stream().anyMatch(units->units==4),"closing owners remains paid, including the failing close itself");
            assertEquals(kind==ImportMeter.Abort.RESULT || kind==ImportMeter.Abort.CLOSE,meter.sawResult);
        }
        assertFalse(de.regelsuche.retention.RetainedJson.active());
        assertEquals(0,RetainedGraph.measure(meter.scope).retained().characters());
    }

    private static final class ImportMeter implements RetainedOperation.Sink {
        enum Abort { NONE, BINDINGS, RESULT, CLOSE, REJECT_OBSERVATION, REJECT_CLOSE }
        final Abort abort;RetainedOperation scope;CheckedSchemaSupport.Work work;IllegalArgumentException failure;
        int pathAllocations,pathInsertions,duplicateInsertions;Expr duplicateValue;long failedDebit,directWork,retentionWork;boolean sawBindings,sawResult,sawRejection,sawBindingsWithOutcome;String rejectedSource;final List<Long> afterFailure=new ArrayList<>(),afterResult=new ArrayList<>();
        ImportMeter(Abort abort){this.abort=abort;}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(scope);}
        @Override public void validationWork(long amount){executionWork(amount);}
        @Override public void executionWork(long amount) {
            directWork=Math.addExact(directWork,amount);
            if (failure!=null) {afterFailure.add(amount);return;}
            var owners=owners();
            for (Object value:bindingReplayReferences(owners)) {
                if (value instanceof ArrayList<?> path) {
                    if (amount==2 && path.isEmpty()) pathAllocations++;
                    if (amount==1 && path.equals(List.of(0))) pathInsertions++;
                }
                if (amount==2 && duplicateValue!=null && value instanceof java.util.TreeMap<?,?> map
                        && map.containsValue(duplicateValue)) duplicateInsertions++;
            }
            if (sawResult) afterResult.add(amount);
            boolean result=owners.stream().anyMatch(value->value instanceof NativeVerification);
            boolean bindings=bindingReplayReferences(owners).stream().anyMatch(value->value instanceof java.util.TreeMap<?,?> map
                && !map.isEmpty() && map.values().stream().allMatch(item->item instanceof Expr));
            if ((abort==Abort.BINDINGS && bindings && work!=null && work.units>1)
                    || (abort==Abort.RESULT && result)
                    || (abort==Abort.CLOSE && sawResult && amount==4)
                    || (abort==Abort.REJECT_CLOSE && sawRejection && amount==4)) {
                failedDebit=amount;failure=new IllegalArgumentException("injected import "+abort);throw failure;
            }
        }
        @Override public void checkpoint() {
            retentionWork=Math.addExact(retentionWork,RetainedGraph.measure(scope).work());
            var owners=owners();
            sawRejection|=rejectedSource!=null && bindingReplayReferences(owners).contains(rejectedSource);
            if (failure==null && abort==Abort.REJECT_OBSERVATION && sawRejection) {
                failure=new IllegalArgumentException("injected rejected import observation");throw failure;
            }
            boolean bindings=bindingReplayReferences(owners).stream().anyMatch(value->value instanceof java.util.TreeMap<?,?> map
                && !map.isEmpty() && map.values().stream().allMatch(item->item instanceof Expr));
            sawBindings|=bindings;
            sawBindingsWithOutcome|=bindings && owners.stream().anyMatch(value->value instanceof de.regelsuche.transform.ExprMatcher.MatchOutcome);
            sawResult|=owners.stream().anyMatch(value->value instanceof NativeVerification);
        }
        private static List<Object> bindingReplayReferences(Set<Object> owners) {
            var direct=new ArrayList<Object>();
            for (Object owner:owners) if (owner instanceof RetainedGraph.View view
                    && owner.getClass().getSimpleName().equals("BindingReplay"))
                view.retainedReferences(new RetainedGraph.Visitor() {
                    @Override public void reference(Object value){direct.add(value);}
                    @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
                });
            return direct;
        }
        private Set<Object> owners() {
            var queue=new ArrayDeque<Object>();var seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var visitor=new RetainedGraph.Visitor() {
                @Override public void reference(Object value){if(value!=null)queue.add(value);}
                @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
            };
            visitor.reference(scope);
            while (!queue.isEmpty()) {
                Object value=queue.remove();if(!seen.add(value))continue;
                if (work==null && value instanceof CheckedSchemaSupport.Work candidate) work=candidate;
                if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Object[] array) for(Object item:array)visitor.reference(item);
                else if (value instanceof Collection<?> items) items.forEach(visitor::reference);
                else if (value instanceof Map<?,?> map) map.forEach((key,item)->{visitor.reference(key);visitor.reference(item);});
            }
            return seen;
        }
    }

    private static ExactTheoryEvidence.Binding applicationBinding(CheckedLearnedSchemaModel model,Expr source) {
        return ((de.regelsuche.transform.TransformationProvenance.ExactTheoryStep)moves(model,source).getFirst().provenance()).evidence().binding();
    }
    private static ExactTheoryEvidence.Binding withEvidence(ExactTheoryEvidence.Binding binding,String json) {
        return new ExactTheoryEvidence.Binding(binding.sourceExpression(),binding.transformedExpression(),binding.theoryStepId(),
            SchematicProofPlan.hash(json),binding.receiptArtifactId(),binding.runArtifactId(),binding.canonicalWorkUnits(),json);
    }
    private static void assertRejectedImport(NativeVerification rejected,String detail) {
        assertFalse(rejected.accepted(),detail);assertTrue(rejected.work()>0,detail);
        assertNull(rejected.checkedProof(),detail);assertNull(rejected.ruleId(),detail);
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
