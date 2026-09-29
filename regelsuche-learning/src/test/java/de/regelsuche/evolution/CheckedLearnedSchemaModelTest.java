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
        var model = checkedExpansionModel();
        Expr unmatched = new BinaryExpr(parse("x+1"), ADD, balancedTree(507));
        Expr rejected = new BinaryExpr(parse("x+0"), ADD, balancedTree(507));
        var provider = model.providers().getFirst();
        var baseline = provider.candidates(MoveState.root(CODEC.encodeExpression(unmatched)), MoveContext.frozen("unused"));
        var attempted = provider.candidates(MoveState.root(CODEC.encodeExpression(rejected)), MoveContext.frozen("unused"));
        assertTrue(attempted.moves().isEmpty());
        assertTrue(attempted.work().totalWorkUnitsV2() >= baseline.work().totalWorkUnitsV2() + 512,
            "the target exceeds 512 nodes, so its already visited nodes must remain in rejected-work accounting");
    }

    private static CheckedLearnedSchemaModel checkedExpansionModel() throws Exception {
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
        return CheckedLearnedSchemaModel.load(tree.toString(), original.inventoryHash());
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

    @Test void actualSelectedWitnessImportsInAnotherJvmWithoutTrainingOrSearchingAgain(
            @org.junit.jupiter.api.io.TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) java.nio.file.Path directory) throws Exception {
        var learned=CheckedLearnedSchemaModel.learn(formation);
        var restored=CheckedLearnedSchemaModel.load(learned.toCanonicalJson(),formation.inventory().contentHash());
        Expr source=nestedImportSource();
        var context=TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION);
        var problem=new NativeMoveSearch.Problem(source,context,restored.nativeProviders(),MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED,new MoveSearch.Budget(0,1,100000,10,10_000_000));
        var selected=new NativeMoveSearch().searchUntil(problem,NativeTestObservation.Objective.DEPTH,0,
            SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(MoveSearch.Outcome.QUALITY_REACHED,selected.search().observedOutcome());
        assertEquals(1,selected.witness().size());
        var exact=assertInstanceOf(NativeMoveProof.Exact.class,selected.witness().getFirst().move().proof());
        assertEquals(nestedImportTarget(),exact.target());
        var data=assertInstanceOf(CheckedLearnedSchemaModel.ApplicationData.class,exact.evidence().binding().observation());
        assertFalse(data.path().isEmpty());assertEquals(List.of(1),data.path());
        assertEquals(source,data.source());
        var exported=selected.search().exportLegacy(10_000_000,SearchExpressionStore.Limits.DEFAULT);
        assertTrue(exported.artifactAvailable(),exported.accounting().detail());
        assertFalse(exported.complete(),"this witness test does not promote partial P04 accounting");
        var binding=((de.regelsuche.transform.TransformationProvenance.ExactTheoryStep)
            exported.projection().witness().getFirst().move().provenance()).evidence().binding();
        assertEquals(exact.evidence().exportLegacy().binding(),binding);
        var mapper=new ObjectMapper();
        var artifact=mapper.createObjectNode().put("model",restored.toCanonicalJson()).put("source",CODEC.encodeExpression(source));
        artifact.set("binding",mapper.valueToTree(binding));
        byte[] bytes=mapper.writeValueAsBytes(artifact);
        var input=directory.resolve("selected-witness.json");java.nio.file.Files.write(input,bytes);
        var output=directory.resolve("import-report.json");var errors=directory.resolve("import-errors.txt");
        var process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "-cp",importClasspath(),ImportWorker.class.getName(),input.toString())
            .redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
        try {
            assertTrue(process.waitFor(45,java.util.concurrent.TimeUnit.SECONDS),"fresh proof import timed out");
            assertEquals(0,process.exitValue(),()->{
                try{return java.nio.file.Files.readString(errors);}catch(java.io.IOException failure){return failure.toString();}
            });
            var report=mapper.readTree(java.nio.file.Files.readAllBytes(output));
            assertEquals(process.pid(),report.get("pid").longValue());
            assertNotEquals(ProcessHandle.current().pid(),report.get("pid").longValue());
            assertEquals(byteHash(bytes),report.get("artifactHash").textValue());
            assertEquals(importWorkerHash(),report.get("workerHash").textValue());
            assertEquals(CODEC.encodeExpression(source),report.get("source").textValue());
            assertEquals(CODEC.encodeExpression(nestedImportTarget()),report.get("target").textValue());
            assertTrue(report.get("accepted").booleanValue());assertTrue(report.get("registeredAccepted").booleanValue());
            for(String phase:List.of("loadWork","replayWork","registeredWork","directIncludingDelegatedWork","retentionWork"))
                assertTrue(report.get(phase).longValue()>0,phase);
            ((ObjectNode)report).put("producerPid",ProcessHandle.current().pid()).put("artifactBytes",bytes.length)
                .put("formationWork",restored.formationWork()).put("searchObservedWork",selected.totalWork())
                .put("exportObservedWork",exported.accounting().work()).put("accountingComplete",false);
            System.out.println("P04_IMPORTED_WITNESS "+mapper.writeValueAsString(report));
        } catch (Exception | Error failure) {
            System.err.println("P04_FAILED_IMPORT_FILES "+directory);
            throw failure;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            assertTrue(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS),"fresh import process did not terminate");
        }
    }

    /** Data consumer only: no learner, search call, candidate enumeration or original capability. */
    public static final class ImportWorker {
        public static void main(String[] args) throws Exception {
            var path=java.nio.file.Path.of(args[0]);
            if(java.nio.file.Files.size(path)>CheckedSchemaSupport.MAXIMUM_JSON_CHARACTERS)
                throw new IllegalArgumentException("import envelope limit");
            byte[] bytes=java.nio.file.Files.readAllBytes(path);
            var envelope=CheckedSchemaSupport.read(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            CheckedSchemaSupport.fields(envelope,"model","source","binding");
            // Receiver-owned development fixture, never an expectation copied from the artifact.
            String expectedInventory=TraceStrategyTransferExample.inventory().contentHash();
            var model=CheckedLearnedSchemaModel.load(CheckedSchemaSupport.text(envelope,"model"),expectedInventory);
            var binding=CheckedSchemaSupport.JSON.treeToValue(envelope.get("binding"),ExactTheoryEvidence.Binding.class);
            Expr source=CODEC.decodeExpression(CheckedSchemaSupport.text(envelope,"source"));
            assertEquals(nestedImportSource(),source);
            var state=state(source);
            var context=TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION);
            var providers=model.nativeProviders();var verifier=NativeVerifier.registered(providers);
            var meter=new ImportMeter(ImportMeter.Abort.NONE);NativeVerification replay,registered;
            var results=new Object[3];
            try(var scope=RetainedOperation.open(meter)) {
                meter.scope=scope;
                try(var held=RetainedOperation.retain(model,state,context,binding,providers,verifier,results)) {
                    replay=model.replayApplication(state,binding,context);results[0]=replay;
                    meter.executionWork(replay.work()); // exactly one delegation of the returned receipt
                    assertTrue(replay.accepted());
                    var move=new NativeSearchMove(replay.checkedProof(),providers.getFirst().descriptor(),0,Set.of());results[1]=move;
                    registered=verifier.verify(state,move,context);results[2]=registered;
                    meter.executionWork(registered.work());
                    assertTrue(registered.accepted());RetainedOperation.checkpoint();
                }
            }
            assertFalse(de.regelsuche.retention.RetainedJson.active());
            assertEquals(nestedImportTarget(),replay.checkedProof().target());
            var report=new ObjectMapper().createObjectNode().put("pid",ProcessHandle.current().pid())
                .put("artifactHash",byteHash(bytes)).put("workerHash",importWorkerHash())
                .put("source",CODEC.encodeExpression(source)).put("target",CODEC.encodeExpression(replay.checkedProof().target()))
                .put("accepted",replay.accepted()).put("registeredAccepted",registered.accepted())
                .put("loadWork",model.loadWork()).put("replayWork",replay.work()).put("registeredWork",registered.work())
                .put("directIncludingDelegatedWork",meter.directWork).put("retentionWork",meter.retentionWork);
            System.out.println(report);
        }
    }
    private static Expr importBase() {
        return new BinaryExpr(VariableExpr.scoped(new SymbolId(new UUID(0,91),2)),ADD,NumberExpr.exact("1/3"));
    }
    private static Expr nestedImportSource() {
        Expr a=importBase(),b=VariableExpr.scoped(new SymbolId(new UUID(0,91),3));
        Expr cancellation=new BinaryExpr(new BinaryExpr(new BinaryExpr(a,ADD,b),MUL,new BinaryExpr(a,SUB,b)),ADD,new BinaryExpr(b,MUL,b));
        return new BinaryExpr(new NumberExpr(7),MUL,cancellation);
    }
    private static Expr nestedImportTarget() {
        return new BinaryExpr(new NumberExpr(7),MUL,new BinaryExpr(importBase(),POW,new NumberExpr(2)));
    }
    private static String importClasspath() throws Exception {
        var entries=new java.util.LinkedHashSet<String>();entries.add(System.getProperty("java.class.path"));
        for(ClassLoader loader=ImportWorker.class.getClassLoader();loader!=null;loader=loader.getParent())
            if(loader instanceof java.net.URLClassLoader urls)for(var url:urls.getURLs())entries.add(java.nio.file.Path.of(url.toURI()).toString());
        return String.join(java.io.File.pathSeparator,entries);
    }
    private static String importWorkerHash() throws Exception {
        try(var input=ImportWorker.class.getResourceAsStream("CheckedLearnedSchemaModelTest$ImportWorker.class")) {
            return byteHash(java.util.Objects.requireNonNull(input).readAllBytes());
        }
    }
    private static String byteHash(byte[] bytes) throws Exception {
        return "sha256:"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
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

    @Test void importPreservesRepeatedFailureAcrossBindingAndJsonFrameCloses() { assertImportCleanup(ImportMeter.Abort.BINDINGS,false); }
    @Test void importPreservesRepeatedFailureAtTheResultFrameClose() { assertImportCleanup(ImportMeter.Abort.RESULT,false); }
    @Test void importAcquisitionFailureStillClosesTheAlreadyOpenedJsonScope() { assertImportCleanup(ImportMeter.Abort.JSON_OWNER_ACQUIRE,false); }
    @Test void importClosesAllResourcesDespiteDistinctJsonAndFrameFailures() { assertImportCleanup(ImportMeter.Abort.BINDINGS,true); }

    private static void assertImportCleanup(ImportMeter.Abort abort,boolean distinct) {
        var learned=CheckedLearnedSchemaModel.learn(formation);
        var model=CheckedLearnedSchemaModel.load(learned.toCanonicalJson(),learned.inventoryHash());
        Expr source=parse("(x+y)*(x-y)+y*y");var binding=applicationBinding(model,source);
        var meter=new ImportMeter(abort);meter.repeatClose=true;
        if(distinct) {
            meter.closeFailure=new IllegalStateException("additional imported frame close failure");
            meter.jsonFailure=new IllegalStateException("additional JSON release failure");
        }
        try(var scope=RetainedOperation.open(meter)) {
            meter.scope=scope;
            var thrown=assertThrows(IllegalArgumentException.class,()->model.replayApplication(state(source),binding,
                TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION)));
            assertSame(meter.failure,thrown,"later close attempts must preserve the original technical error");
            assertNotNull(meter.work);assertEquals(meter.work.units,meter.afterFailure.getLast());
            assertEquals(1,meter.afterFailure.stream().filter(units->units==meter.work.units).count());
            if(abort!=ImportMeter.Abort.RESULT)
                assertTrue(meter.afterFailure.stream().filter(units->units==4).count()>=3,"every acquired frame is released despite earlier close errors");
            if(distinct) {
                assertTrue(List.of(thrown.getSuppressed()).contains(meter.closeFailure));
                assertTrue(List.of(thrown.getSuppressed()).contains(meter.jsonFailure));
                assertTrue(meter.jsonFailureThrown,"the JSON scope release itself was reached");
            } else assertEquals(0,thrown.getSuppressed().length);
        }
        assertFalse(de.regelsuche.retention.RetainedJson.active());
        assertEquals(0,RetainedGraph.measure(meter.scope).retained().characters());
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
        enum Abort { NONE, BINDINGS, RESULT, CLOSE, REJECT_OBSERVATION, REJECT_CLOSE, JSON_OWNER_ACQUIRE }
        final Abort abort;RetainedOperation scope;CheckedSchemaSupport.Work work;IllegalArgumentException failure;
        boolean repeatClose,jsonFailureThrown;RuntimeException closeFailure,jsonFailure;
        int pathAllocations,pathInsertions,duplicateInsertions;Expr duplicateValue;long failedDebit,directWork,retentionWork;boolean sawBindings,sawResult,sawRejection,sawBindingsWithOutcome;String rejectedSource;final List<Long> afterFailure=new ArrayList<>(),afterResult=new ArrayList<>();
        ImportMeter(Abort abort){this.abort=abort;}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(scope);}
        @Override public void validationWork(long amount){executionWork(amount);}
        @Override public void executionWork(long amount) {
            directWork=Math.addExact(directWork,amount);
            if (failure!=null) {
                afterFailure.add(amount);
                if(repeatClose && amount==4)throw closeFailure==null?failure:closeFailure;
                if(jsonFailure!=null && !jsonFailureThrown && !de.regelsuche.retention.RetainedJson.active()
                        && !bindingReplayReferences(owners()).isEmpty()) {
                    jsonFailureThrown=true;throw jsonFailure;
                }
                return;
            }
            var owners=owners();
            for (Object value:bindingReplayReferences(owners)) {
                if (value instanceof ArrayList<?> path) {
                    if (amount==2 && path.isEmpty()) pathAllocations++;
                    if (amount==1 && path.equals(List.of(0))) pathInsertions++;
                }
                if (amount==2 && duplicateValue!=null && value instanceof java.util.TreeMap<?,?> map
                        && map.containsValue(duplicateValue) && !owners.contains("CHECKED_SCHEMA_DUPLICATE_BINDING")) duplicateInsertions++;
            }
            if (sawResult) afterResult.add(amount);
            boolean result=owners.stream().anyMatch(value->value instanceof NativeVerification);
            boolean bindings=bindingReplayReferences(owners).stream().anyMatch(value->value instanceof java.util.TreeMap<?,?> map
                && !map.isEmpty() && map.values().stream().allMatch(item->item instanceof Expr));
            if ((abort==Abort.BINDINGS && bindings && work!=null && work.units>1)
                    || (abort==Abort.RESULT && result)
                    || (abort==Abort.CLOSE && sawResult && amount==4)
                    || (abort==Abort.REJECT_CLOSE && sawRejection && amount==4)
                    || (abort==Abort.JSON_OWNER_ACQUIRE && amount==2 && owners.stream().anyMatch(value->
                        value instanceof Object[] values && values.length==1 && values[0] instanceof de.regelsuche.retention.RetainedJson.Scope))) {
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

    @Test void nativeVerifierPaysUnreturnedWorkOnSourceDomainAbort() { assertNativeVerifierAbort(VerifierMeter.Abort.SOURCE_RUNTIME,false,false); }
    @Test void nativeVerifierDoesNotMisclassifyAnObserverArgumentError() { assertNativeVerifierAbort(VerifierMeter.Abort.SOURCE_ARGUMENT,false,false); }
    @Test void nativeVerifierPaysUnreturnedWorkOnSourceDomainError() { assertNativeVerifierAbort(VerifierMeter.Abort.SOURCE_ERROR,false,false); }
    @Test void nativeVerifierPaysAReceiptLostAtPublication() { assertNativeVerifierAbort(VerifierMeter.Abort.RESULT,false,false); }
    @Test void nativeVerifierPaysAReceiptLostAtFinalClose() { assertNativeVerifierAbort(VerifierMeter.Abort.CLOSE,false,false); }
    @Test void nativeVerifierPreservesRepeatedFailureAtEveryClose() { assertNativeVerifierAbort(VerifierMeter.Abort.SOURCE_ARGUMENT,true,false); }
    @Test void nativeVerifierSuppressesDistinctCloseFailure() { assertNativeVerifierAbort(VerifierMeter.Abort.SOURCE_ARGUMENT,true,true); }
    @Test void nativeVerifierKeepsTechnicalFailureAfterSemanticDomainRejection() { assertNativeVerifierAbort(VerifierMeter.Abort.REJECT_OBSERVATION,false,false); }
    @Test void nativeVerifierKeepsCloseFailureAfterSemanticDomainRejection() { assertNativeVerifierAbort(VerifierMeter.Abort.REJECT_CLOSE,false,false); }
    @Test void nativeVerifierKeepsRepeatedFailureAcrossOccurrenceReplay() { assertNativeVerifierAbort(VerifierMeter.Abort.SUBSTITUTION,true,false); }

    private static void assertNativeVerifierAbort(VerifierMeter.Abort abort,boolean repeatClose,boolean distinctClose) {
        var learned=CheckedLearnedSchemaModel.learn(formation);
        var model=CheckedLearnedSchemaModel.load(learned.toCanonicalJson(),learned.inventoryHash());
        Expr source=parse("(x+y)*(x-y)+y*y");
        var context=TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION);
        var providers=model.nativeProviders();var verifier=NativeVerifier.registered(providers);
        var proposal=providers.getFirst().candidates(state(source),context).moves().getFirst();
        boolean rejected=abort==VerifierMeter.Abort.REJECT_OBSERVATION || abort==VerifierMeter.Abort.REJECT_CLOSE;
        Expr received=rejected?parse("x/0"):source;
        var meter=new VerifierMeter(abort,received,proposal);meter.repeatClose=repeatClose;
        if(distinctClose)meter.closeFailure=new IllegalStateException("separate native verifier close failure");
        try(var scope=RetainedOperation.open(meter)) {
            meter.scope=scope;
            var thrown=assertThrows(Throwable.class,()->verifier.verify(state(received),proposal,context));
            assertSame(meter.failure,thrown,"a technical failure is neither a rejection receipt nor a new self-suppression error");
            assertNotNull(meter.work);assertTrue(meter.work.units>1);
            assertEquals(meter.work.units,meter.afterFailure.getLast(),"unreturned root Work is settled after all frames close");
            assertEquals(1,meter.afterFailure.stream().filter(units->units==meter.work.units).count(),"one settlement, separate from direct observation work");
            assertTrue(meter.sawRoot,"the actual receiving state/proposal and verifier Work have an owner");
            assertEquals(abort==VerifierMeter.Abort.RESULT || abort==VerifierMeter.Abort.CLOSE,meter.sawResult);
            if(distinctClose)assertTrue(List.of(thrown.getSuppressed()).contains(meter.closeFailure));
            else assertEquals(0,thrown.getSuppressed().length,"repeating the same failure does not create a suppressed copy");
        }
        assertEquals(0,RetainedGraph.measure(meter.scope).retained().characters());
    }

    @Test void nativeVerifierDelegatesAcceptedAndRejectedWorkOnlyInItsReceipt() {
        var learned=CheckedLearnedSchemaModel.learn(formation);
        var model=CheckedLearnedSchemaModel.load(learned.toCanonicalJson(),learned.inventoryHash());
        Expr source=parse("(x+y)*(x-y)+y*y");
        var context=TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION);
        var providers=model.nativeProviders();var verifier=NativeVerifier.registered(providers);
        var proposal=providers.getFirst().candidates(state(source),context).moves().getFirst();
        for(Expr received:List.of(source,parse("x/0"))) {
            var meter=new VerifierMeter(VerifierMeter.Abort.NONE,received,proposal);
            try(var scope=RetainedOperation.open(meter)) {
                meter.scope=scope;var receipt=verifier.verify(state(received),proposal,context);
                assertEquals(received==source,receipt.accepted());assertTrue(meter.sawRoot);assertTrue(meter.sawResult);
                assertEquals(receipt.work(),meter.work.units);
                assertEquals(List.of(4L),meter.afterResult,"normal close only; delegated Work is not also locally settled");
                if(received!=source) {
                    assertEquals("CHECKED_SCHEMA_UNSUPPORTED_OR_MALFORMED",receipt.reason());
                    assertEquals(2,receipt.work());assertNull(receipt.checkedProof());assertNull(receipt.ruleId());
                }
            }
        }
    }

    @Test void importedSubstitutionDomainRejectionCannotHideOccurrenceCloseFailure() throws Exception {
        assertImportedSubstitutionClose(VerifierMeter.Abort.OCCURRENCE_REJECT_CLOSE);
    }
    @Test void importedDomainRejectionCannotHideTheLaterBindingOwnerCloseFailure() throws Exception {
        assertImportedSubstitutionClose(VerifierMeter.Abort.BINDING_REJECT_CLOSE);
    }
    private static void assertImportedSubstitutionClose(VerifierMeter.Abort abort) throws Exception {
        var model=CheckedLearnedSchemaModel.learn(formation);Expr source=parse("(x+y)*(x-y)+y*y");
        var binding=applicationBinding(model,source);var data=(ObjectNode)new ObjectMapper().readTree(binding.canonicalEvidenceJson());
        ((ObjectNode)data.get("bindings").get(0)).put("expression",CODEC.encodeExpression(parse("x/0")));
        var invalid=withEvidence(binding,data.toString());
        var context=TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION);
        var semantic=assertThrows(IllegalArgumentException.class,()->model.replayApplication(state(source),invalid,context));
        assertEquals("checked scalar division needs nonzero literal denominator",semantic.getMessage());
        var meter=new VerifierMeter(abort,source,null);
        try(var scope=RetainedOperation.open(meter)) {
            meter.scope=scope;
            var thrown=assertThrows(Throwable.class,()->model.replayApplication(state(source),invalid,context));
            assertSame(meter.failure,thrown);assertTrue(meter.sawRejectedDomain);assertTrue(meter.sawOutcome);
            assertEquals(meter.work.units,meter.afterFailure.getLast());
            assertEquals(1,meter.afterFailure.stream().filter(units->units==meter.work.units).count());
        }
        assertFalse(de.regelsuche.retention.RetainedJson.active());
        assertEquals(0,RetainedGraph.measure(meter.scope).retained().characters());
    }

    @Test void importedTargetDomainRejectionCannotHideOccurrenceCloseFailure() throws Exception {
        var model=checkedExpansionModel();Expr replacement=balancedTree(509);
        Expr source=new BinaryExpr(replacement,ADD,new NumberExpr(0));
        var binding=applicationBinding(model,parse("x+0"));
        var data=(ObjectNode)new ObjectMapper().readTree(binding.canonicalEvidenceJson());
        String encodedSource=CODEC.encodeExpression(source);data.put("source",encodedSource);
        ((ObjectNode)data.get("bindings").get(0)).put("expression",CODEC.encodeExpression(replacement));
        String json=data.toString();
        var invalid=new ExactTheoryEvidence.Binding(encodedSource,binding.transformedExpression(),binding.theoryStepId(),
            SchematicProofPlan.hash(json),binding.receiptArtifactId(),binding.runArtifactId(),binding.canonicalWorkUnits(),json);
        var context=TypedMoveSearch.Context.sourceOnly(List.of(),MoveContext.Phase.FROZEN_EVALUATION);
        var semantic=assertThrows(IllegalArgumentException.class,()->model.replayApplication(state(source),invalid,context));
        assertEquals("checked scalar polynomial structure limit",semantic.getMessage(),"source has 511 nodes; only the 513-node generated target is out of domain");
        var meter=new VerifierMeter(VerifierMeter.Abort.TARGET_REJECT_CLOSE,source,null);
        try(var scope=RetainedOperation.open(meter)) {
            meter.scope=scope;
            var thrown=assertThrows(Throwable.class,()->model.replayApplication(state(source),invalid,context));
            assertSame(meter.failure,thrown);assertTrue(meter.sawTargetDomain);assertTrue(meter.sawOutcome);
            assertEquals(meter.work.units,meter.afterFailure.getLast());
            assertEquals(1,meter.afterFailure.stream().filter(units->units==meter.work.units).count());
        }
        assertFalse(de.regelsuche.retention.RetainedJson.active());
        assertEquals(0,RetainedGraph.measure(meter.scope).retained().characters());
    }

    /** Observes actual frame values; a nested application ledger cannot stand in for the verifier ledger. */
    private static final class VerifierMeter implements RetainedOperation.Sink {
        enum Abort { NONE,SOURCE_RUNTIME,SOURCE_ARGUMENT,SOURCE_ERROR,RESULT,CLOSE,REJECT_OBSERVATION,REJECT_CLOSE,SUBSTITUTION,OCCURRENCE_REJECT_CLOSE,TARGET_REJECT_CLOSE,BINDING_REJECT_CLOSE }
        final Abort abort;final Expr received;final NativeSearchMove proposal;final Throwable failure;
        RetainedOperation scope;CheckedSchemaSupport.Work work;RuntimeException closeFailure;
        boolean tripped,repeatClose,sawRoot,sawResult,resultCheckpoint,sawRejectedDomain,sawTargetDomain,sawOutcome;
        final List<Long> afterFailure=new ArrayList<>(),afterResult=new ArrayList<>();
        VerifierMeter(Abort abort,Expr received,NativeSearchMove proposal) {
            this.abort=abort;this.received=received;this.proposal=proposal;
            failure=abort==Abort.SOURCE_RUNTIME?new IllegalStateException("native verifier resource abort"):
                abort==Abort.SOURCE_ERROR?new AssertionError("native verifier resource error"):
                new IllegalArgumentException("native verifier observation "+abort);
        }
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(scope);}
        @Override public void validationWork(long amount){executionWork(amount);}
        @Override public void executionWork(long amount) {
            if(tripped) {
                afterFailure.add(amount);
                if(repeatClose && amount==4) {
                    if(closeFailure!=null)throw closeFailure;
                    fail();
                }
                return;
            }
            var snapshot=snapshot();
            if(resultCheckpoint)afterResult.add(amount);
            boolean sourceAbort=(abort==Abort.SOURCE_RUNTIME || abort==Abort.SOURCE_ARGUMENT || abort==Abort.SOURCE_ERROR)
                && snapshot.sourceGrowth();
            if(sourceAbort || (abort==Abort.RESULT && snapshot.receipt())
                    || (abort==Abort.CLOSE && resultCheckpoint && amount==4)
                    || (abort==Abort.REJECT_CLOSE && sawRejectedDomain && amount==4)
                    || (abort==Abort.SUBSTITUTION && snapshot.substitution())
                    || (abort==Abort.OCCURRENCE_REJECT_CLOSE && sawRejectedDomain && sawOutcome && !snapshot.outcome() && amount==4)
                    || (abort==Abort.TARGET_REJECT_CLOSE && sawTargetDomain && sawOutcome && !snapshot.outcome() && amount==4)
                    || (abort==Abort.BINDING_REJECT_CLOSE && sawRejectedDomain && sawOutcome && !snapshot.bindingReplay() && amount==4)) {
                tripped=true;fail();
            }
        }
        @Override public void checkpoint() {
            RetainedGraph.measure(scope);var snapshot=snapshot();
            if(!tripped && abort==Abort.REJECT_OBSERVATION && sawRejectedDomain) {tripped=true;fail();}
            resultCheckpoint|=snapshot.receipt();
        }
        private void fail() {
            if(failure instanceof RuntimeException exception)throw exception;
            throw (Error)failure;
        }
        private Snapshot snapshot() {
            var queue=new ArrayDeque<Object>();var seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var visitor=new RetainedGraph.Visitor() {
                @Override public void reference(Object value){if(value!=null)queue.add(value);}
                @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
            };
            visitor.reference(scope);
            while(!queue.isEmpty()) {
                Object value=queue.remove();if(!seen.add(value))continue;
                if(value instanceof RetainedGraph.View view)view.retainedReferences(visitor);
                else if(value instanceof Object[] array)for(Object item:array)visitor.reference(item);
                else if(value instanceof Collection<?> items)items.forEach(visitor::reference);
                else if(value instanceof Map<?,?> map)map.forEach((key,item)->{visitor.reference(key);visitor.reference(item);});
            }
            boolean sourceGrowth=false,substitution=false;
            boolean outcome=seen.stream().anyMatch(value->value instanceof de.regelsuche.transform.ExprMatcher.MatchOutcome);
            boolean receipt=seen.stream().anyMatch(value->value instanceof NativeVerification);
            sawResult|=receipt;sawOutcome|=outcome;
            for(Object owner:seen)if(owner instanceof RetainedOperation.Frame frame) {
                var direct=new ArrayList<Object>();
                frame.retainedReferences(new RetainedGraph.Visitor() {
                    @Override public void reference(Object value){direct.add(value);}
                    @Override public void requireExact(Object value,Class<?> type){assertEquals(type,value.getClass());}
                });
                for(Object ref:direct)if(ref instanceof Object[] values) {
                    CheckedSchemaSupport.Work ledger=null;boolean boundary=false,source=false,target=false;Expr current=null;int waiting=-1;
                    for(Object value:values) {
                        if(value instanceof CheckedSchemaSupport.Work candidate)ledger=candidate;
                        boundary|=proposal==null?value instanceof ExactTheoryEvidence.Binding:value==proposal;
                        source|=value==received;
                        target|=value instanceof BinaryExpr binary && binary.operator()==ADD
                            && binary.left().equals(received) && binary.right().equals(new NumberExpr(0));
                        if(value instanceof ArrayDeque<?> deque)waiting=deque.size();
                        if(value instanceof Object[] slot && slot.length==1 && slot[0] instanceof RetainedGraph.View node
                                && slot[0].getClass().getEnclosingClass()==CheckedSchemaSupport.class
                                && slot[0].getClass().getSimpleName().equals("Node")) {
                            var refs=new ArrayList<Object>();
                            node.retainedReferences(new RetainedGraph.Visitor() {
                                @Override public void reference(Object item){refs.add(item);}
                                @Override public void requireExact(Object item,Class<?> type){assertEquals(type,item.getClass());}
                            });
                            current=(Expr)refs.getFirst();
                        }
                    }
                    if(boundary && ledger!=null){work=ledger;sawRoot=true;}
                    // Baseline domain frames allow a RED test to observe unpaid Work before the root owner exists.
                    if(work==null && source && waiting>=0)work=ledger;
                    sourceGrowth|=source && current==received && waiting>0;
                    substitution|=!source && current!=null && outcome;
                    sawTargetDomain|=target && current!=null && waiting>=0;
                    sawRejectedDomain|=current instanceof BinaryExpr binary && binary.operator()==DIV
                        && binary.right().equals(new NumberExpr(0));
                }
            }
            return new Snapshot(sourceGrowth,substitution,receipt,outcome,seen.stream().anyMatch(value->value.getClass().getSimpleName().equals("BindingReplay")));
        }
        private record Snapshot(boolean sourceGrowth,boolean substitution,boolean receipt,boolean outcome,boolean bindingReplay) {}
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
