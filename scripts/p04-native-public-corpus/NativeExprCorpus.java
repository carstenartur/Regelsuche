package de.regelsuche.preparation;

import com.fasterxml.jackson.databind.*;
import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.*;
import de.regelsuche.evolution.*;
import de.regelsuche.retention.*;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.*;
import de.regelsuche.transform.*;
import java.nio.file.*;
import java.util.*;
import static de.regelsuche.ast.BinaryOperator.ADD;

/** Public development characterization. Calls the production kernel; grants no proof authority. */
public final class NativeExprCorpus {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final int MAX_PULLS = 10_000;
    private final Path baseline, output;
    private final long diagnosticWork, exportWork;
    private final TraceRewriteStrategyLearner.FrozenStrategy formation;
    private final CheckedLearnedSchemaModel model;
    private final String schema;

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("baseline-results output diagnostic-work export-work");
        var driver = new NativeExprCorpus(Path.of(args[0]), Path.of(args[1]), Long.parseLong(args[2]), Long.parseLong(args[3]));
        driver.run();
    }
    NativeExprCorpus(Path baseline, Path output, long diagnosticWork, long exportWork) throws Exception {
        this.baseline=baseline;this.output=output;this.diagnosticWork=diagnosticWork;this.exportWork=exportWork;
        if (diagnosticWork<1 || exportWork<1) throw new IllegalArgumentException("finite positive diagnostic budgets required");
        Files.createDirectories(output);
        formation=new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(),TraceStrategyTransferExample.limits());
        var learned=formation.typedMoves().checkedSchemas();
        model=CheckedLearnedSchemaModel.load(learned.toCanonicalJson(),formation.inventory().contentHash());
        schema=read("matcher-plan.json").path("selectedSchema").asText();
        write("training.json",Map.of("formation",JSON.readTree(formation.toCanonicalJson()),
            "model",JSON.readTree(model.toCanonicalJson()),"inventory",JSON.readTree(formation.inventory().toCanonicalJson()),
            "trainingSearchWork",formation.trainingSearchWorkUnits(),"trainingPrimitiveWork",formation.trainingPrimitiveWorkUnits(),
            "formationWork",learned.formationWork(),"restoreWork",model.loadWork(),"modelEqualsFrozen",JSON.readTree(model.toCanonicalJson()).equals(read("training/model.json")),
            "accountingComplete",false,"scope","Existing declared receipts; scopes may overlap; preparation/import/output not free and not a complete lifecycle sum."));
    }
    private void run() throws Exception {
        write("protocol.json",Map.of("revision","regelsuche.public-native-p03-differential/v1",
            "nativeWorkRevision",NativeMoveSearch.REVISION,"coverage",NativeMoveSearch.coverage(),
            "diagnosticTotalWork",diagnosticWork,"exportWorkBudget",exportWork,"cursorPullLimit",MAX_PULLS,
            "retentionLimits",SearchExpressionStore.Limits.DEFAULT,"accountingComplete",false,
            "budgetRule","Always execute frozen numeric budgets; diagnostic changes only totalWork for BOTH current legacy and native. Never an economic comparison."));
        write("measurement-boundaries.json",Map.of(
            "internalCodec","Native call through production cleanup and final replay; import precedes this scope. Independent explicit export follows it.",
            "search","Production native receipt, whose partial coverage and public INCONCLUSIVE result are preserved.",
            "directCalls","Cursor/replay/eager observations have a separate test-only sink revision; provider receipts may overlap and are not added again.",
            "reporter","Baseline import, fixture construction, report JSON/AST serialization, filesystem I/O and this harness itself are not fully metered.",
            "training","Existing learner/restore receipts only, with no complete lifecycle sum.",
            "accountingComplete",false,"withinBudget",false,"economicComparison",false));
        var cases=new ArrayList<Object>();
        for(JsonNode row:read("cases.json")) for(boolean diagnostic:new boolean[]{false,true}) {
            cases.add(searchCase(row,diagnostic));write("cases.json",cases);
        }
        var quality=new ArrayList<Object>();
        for(JsonNode row:read("quality-cases.json")) for(boolean diagnostic:new boolean[]{false,true}) {
            quality.add(qualityCase(row,diagnostic));write("quality-cases.json",quality);
        }
        identities();
        var cursors=new ArrayList<Object>();
        for(JsonNode row:read("cursor-cases.json")) {cursors.add(cursorCase(row));write("cursor-cases.json",cursors);}
        negatives();
        write("coverage.json",Map.of("searchRows",cases.size(),"qualityRows",quality.size(),"cursorRows",cursors.size(),
            "expectedSearchRows",48,"expectedQualityRows",4,"expectedCursorRows",10,
            "matrixExecuted",true,"accountingComplete",false,"withinBudget",false,
            "interpretation","All rows retained including errors. Semantic comparisons are evaluated by run.py; no P04 or learning-benefit claim."));
    }

    private record Providers(List<MoveProvider> legacy,TypedMoveSearch.Verifier legacyVerifier,
            List<NativeMoveProvider> nativeProviders,NativeVerifier nativeVerifier,long compilation) { }
    private Providers providers(String id,JsonNode row) {
        var legacy=new ArrayList<MoveProvider>();var nativeProviders=new ArrayList<NativeMoveProvider>();
        var checkers=new HashMap<String,TypedMoveSearch.Verifier>();
        long compilation=0;
        if(id.equals("actual-learned-program")) {
            var inventory=formation.typedMoves();
            return new Providers(inventory.providers(),inventory.verifier(),inventory.nativeProviders(),
                NativeVerifier.registered(inventory.nativeProviders()),inventory.formationWork());
        }
        boolean schematic=id.startsWith("schema-") || id.startsWith("actual-learned-schema")
            || id.startsWith("p03-") || id.startsWith("quality-");
        if(schematic) {
            var selected=id.startsWith("schema-assumption")?model.requiring(List.of("x > 0")):model;
            boolean lazy=id.startsWith("p03-") || id.equals("quality-prepaid-incremental");
            int maximum=id.startsWith("quality-")||lazy?1:4;
            Set<String> included=maximum==1?Set.of(schema):selected.schemas().stream().map(CheckedLearnedSchemaModel.Schema::id)
                .collect(java.util.stream.Collectors.toSet());
            if(lazy) {
                var plan=CheckedSchemaMatcherPlan.prepare(selected,maximum,Map.of(),included);
                legacy.add(plan.provider());nativeProviders.add(plan.nativeProvider());compilation=plan.compilationWork();
            } else {
                legacy.addAll(selected.providers(maximum,Map.of(),included));nativeProviders.addAll(selected.nativeProviders(maximum,Map.of(),included));
            }
            for(var provider:legacy)checkers.put(provider.descriptor().id(),selected.verifier());
        }
        for(JsonNode declaration:row.path("providers")) {
            String name=declaration.path("id").asText();
            if(!name.startsWith("p04-"))continue;
            var descriptor=JSON.convertValue(declaration,MoveProvider.Descriptor.class);
            var base=new PatternRewriteRule(name,PatternExpr.op(ADD,PatternExpr.var("A"),PatternExpr.num(0)),PatternExpr.var("A"));
            RewriteRule rule=name.equals("p04-conditional-zero")?new ConditionalZero(base):base;
            var transport=new AstRewriteTransport(List.of(rule),32,32);
            legacy.add(TypedMoveSearch.primitiveProvider(descriptor,transport));
            nativeProviders.add(new NativeMoveSearch.Primitive(descriptor,transport));
            checkers.put(name,TypedMoveSearch.primitiveReplay(transport));
        }
        var nativeVerifier=NativeVerifier.registered(nativeProviders);
        if(id.equals("continuation-history-gated-path-sensitive")) {
            for(int i=0;i<legacy.size();i++) {
                var old=legacy.get(i);boolean first=old.descriptor().id().startsWith("p04-route-");
                legacy.set(i,new LegacyFilter(old,first));nativeProviders.set(i,new NativeFilter(nativeProviders.get(i),first));
            }
        }
        TypedMoveSearch.Verifier verifier=(state,move,context)->Objects.requireNonNull(checkers.get(move.ruleId()),"registered legacy checker").verify(state,move,context);
        return new Providers(List.copyOf(legacy),verifier,List.copyOf(nativeProviders),nativeVerifier,compilation);
    }
    /** Same public conditional rule, with explicit ownership instead of an opaque anonymous subclass. */
    private record ConditionalZero(PatternRewriteRule delegate) implements RewriteRule,RetainedGraph.View {
        @Override public String id(){return delegate.id();}
        @Override public RewriteKind kind(){return delegate.kind();}
        @Override public boolean mayIncreaseComplexity(){return delegate.mayIncreaseComplexity();}
        @Override public int estimatedCostDelta(){return delegate.estimatedCostDelta();}
        @Override public boolean isEquivalencePreservingByConstruction(){return delegate.isEquivalencePreservingByConstruction();}
        @Override public boolean matches(Expr expression){return delegate.matches(expression);}
        @Override public Expr apply(Expr expression){return delegate.apply(expression);}
        @Override public List<Assumption> assumptions(Expr expression){return List.of(Assumption.nonZero("x"));}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(delegate);}
    }
    private static boolean eligible(boolean first,int depth,String previous){return first?depth==0:previous.equals("p04-route-b");}
    private record LegacyFilter(MoveProvider original,boolean first) implements TypedMoveSearch.TypedProvider {
        @Override public Descriptor descriptor(){return original.descriptor();}
        @Override public Batch candidates(MoveState state,MoveContext context){return eligible(first,state.searchDepth(),state.previousRule())
            ?original.candidates(state,context):new Batch(List.of(),TransformationWorkMetrics.ZERO.withDelegatedMechanicalWork(1),false);}
    }
    private record NativeFilter(NativeMoveProvider original,boolean first) implements NativeMoveProvider,RetainedGraph.View {
        @Override public MoveProvider.Descriptor descriptor(){return original.descriptor();}
        @Override public IncrementalProviderContract.Mathematics mathematicalKind(){return original.mathematicalKind();}
        @Override public Batch candidates(TypedMoveSearch.State state,TypedMoveSearch.Context context){return eligible(first,state.searchDepth(),state.previousRule())
            ?original.candidates(state,context):new Batch(List.of(),TransformationWorkMetrics.ZERO.withDelegatedMechanicalWork(1),false);}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(original);}
    }
    private Map<String,Object> searchCase(JsonNode frozen,boolean diagnostic) {
        var row=header(frozen,diagnostic);String id=frozen.path("id").asText();
        try {
            Expr source=CODEC.decodeExpression(frozen.path("source").asText()),goal=CODEC.decodeExpression(frozen.path("goal").asText());
            var context=new TypedMoveSearch.Context(goal,strings(frozen.path("context").path("assumptions")),MoveContext.Phase.FROZEN_EVALUATION);
            var budget=budget(frozen,diagnostic);var providers=providers(id,frozen);
            var schedule=MoveSearch.Scheduling.valueOf(frozen.path("scheduling").asText());
            var continuation=SearchContinuationContract.valueOf(frozen.path("continuation").asText());
            row.put("budget",budget);row.put("preparationDeclaredWork",providers.compilation());
            try {
                var result=new TypedMoveSearch().search(new TypedMoveSearch.Problem(source,context,providers.legacy(),
                    MovePriorityPolicy.INVENTORY_ORDER,providers.legacyVerifier(),s->0,MoveSearch.Mode.FAST,schedule,budget),continuation);
                row.put("currentLegacy",JSON.readTree(LearnedSchedulingArtifacts.resultJson(result.encodedResult())));
            } catch(Exception|AssertionError failure){row.put("legacyError",error(failure));}
            NativeMoveSearch.Result result;
            try(var observation=AstTransportObservation.open()) {
                try {
                    result=new NativeMoveSearch().search(new NativeMoveSearch.Problem(source,context,providers.nativeProviders(),
                        MoveSearch.Mode.FAST,schedule,budget,NativeMovePriorityPolicy.INVENTORY_ORDER,NativeMoveSearch.ZeroScore.INSTANCE,
                        NativeStateValue.NONE,providers.nativeVerifier()),continuation);
                } catch(NativeMoveSearch.TargetCheckFailure failure) {
                    result=failure.attempted();row.put("nativeFinalCheckError",error(failure));
                } finally {row.put("nativeInternalCodec",transport(observation));}
            }
            nativeResult(row,result);
        } catch(Exception|AssertionError failure){row.put("error",error(failure));}
        return row;
    }
    private Map<String,Object> qualityCase(JsonNode frozen,boolean diagnostic) {
        var row=header(frozen,diagnostic);String id=frozen.path("id").asText();
        try {
            Expr source=CODEC.decodeExpression(frozen.path("source").asText());var providers=providers(id,frozen);
            var context=TypedMoveSearch.Context.sourceOnly(strings(frozen.path("assumptions")),MoveContext.Phase.FROZEN_EVALUATION);
            var budget=budget(frozen,diagnostic);var schedule=MoveSearch.Scheduling.valueOf(frozen.path("scheduling").asText());
            long threshold=frozen.path("maximumOutputScore").asLong();row.put("budget",budget);row.put("preparationDeclaredWork",providers.compilation());
            try {
                var result=new TypedSourceOnlySearch().searchUntil(new TypedMoveSearch.Problem(source,context,providers.legacy(),
                    MovePriorityPolicy.INVENTORY_ORDER,providers.legacyVerifier(),s->0,MoveSearch.Mode.FAST,schedule,budget),
                    NodeObjective.INSTANCE,threshold,SearchContinuationContract.PATH_SENSITIVE);
                row.put("currentLegacy",JSON.readTree(LearnedSchedulingArtifacts.resultJson(result.search().encodedResult())));
                row.put("legacyIncumbent",CODEC.encodeExpression(result.incumbent().expression()));
                row.put("legacyOutputScore",result.outputScore());row.put("legacyFinalReplayWork",result.replayWork());
            } catch(Exception|AssertionError failure){row.put("legacyError",error(failure));}
            NativeMoveSearch.QualityResult selected;
            try(var observation=AstTransportObservation.open()) {
                try {
                    selected=new NativeMoveSearch().searchUntil(new NativeMoveSearch.Problem(source,context,providers.nativeProviders(),
                        MoveSearch.Mode.FAST,schedule,budget,NativeMovePriorityPolicy.INVENTORY_ORDER,NativeMoveSearch.ZeroScore.INSTANCE,
                        NativeStateValue.NONE,providers.nativeVerifier()),NodeObjective.INSTANCE,threshold,SearchContinuationContract.PATH_SENSITIVE);
                } catch(NativeMoveSearch.FinalCheckFailure failure) {
                    selected=failure.attempted();row.put("nativeFinalCheckError",error(failure));
                } finally {row.put("nativeInternalCodec",transport(observation));}
            }
            row.put("hasIncumbent",selected.hasIncumbent());
            if(selected.hasIncumbent()) {
                row.put("nativeIncumbent",CODEC.encodeExpression(selected.incumbent().expression()));
                row.put("nativeInputScore",selected.inputScore());row.put("nativeOutputScore",selected.outputScore());
            }
            nativeResult(row,selected.search());
        } catch(Exception|AssertionError failure){row.put("error",error(failure));}
        return row;
    }
    private enum NodeObjective implements TypedSourceOnlySearch.Objective,RetainedGraph.View {
        INSTANCE;
        @Override public TypedSourceOnlySearch.Score evaluate(TypedMoveSearch.State state){long nodes=nodes(state.expression());return new TypedSourceOnlySearch.Score(nodes,nodes);}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){}
    }
    private static long nodes(Expr expression) {
        if(expression instanceof BinaryExpr binary)return 1+nodes(binary.left())+nodes(binary.right());
        if(expression instanceof FunctionExpr function)return 1+function.arguments().stream().mapToLong(NativeExprCorpus::nodes).sum();return 1;
    }
    private void nativeResult(Map<String,Object> row,NativeMoveSearch.Result result) throws Exception {
        var accounting=result.accounting();long paid=result.totalWork();
        row.put("nativeOutcome",result.outcome());row.put("nativeObservedOutcome",result.observedOutcome());
        row.put("nativeOutput",CODEC.encodeExpression(result.output()));row.put("nativeWorkRevision",result.workRevision());
        row.put("nativeCoverage",result.coverage());row.put("accountingComplete",result.accountingComplete());row.put("withinBudget",result.withinBudget());
        row.put("observationsComplete",result.observationsComplete());row.put("observedTotalWork",paid);row.put("finalReplayWork",result.replayWork());
        row.put("nativeMetrics",result.metrics());row.put("nativeAccounting",Map.of("validation",accounting.validationWork(),"execution",accounting.executionWork(),
            "storage",accounting.storageWork(),"retention",accounting.retentionWork(),"peak",accounting.peak(),"result",accounting.resultRetained(),
            "external",accounting.externalRetained(),"liveAfterClose",accounting.live(),"detail",accounting.detail()));
        row.put("cursorReceipts",result.cursorReceipts().stream().map(receipt->Map.of("source",stateProjection(receipt.source()),
            "closed",receipt.closed(),"lanes",receipt.lanes())).toList());row.put("batchCursorReceipts",result.batchCursorReceipts());
        try(var observation=AstTransportObservation.open()) {
            var exported=result.exportLegacy(exportWork,SearchExpressionStore.Limits.DEFAULT);
            var bill=exported.accounting();row.put("export",Map.of("artifactAvailable",exported.artifactAvailable(),"complete",exported.complete(),
                "work",bill.work(),"workBudget",bill.budget(),"workRevision",bill.workRevision(),"peak",bill.peak(),"retained",bill.resultRetained(),
                "detail",bill.detail(),"observationsComplete",bill.observationsComplete(),"codec",transport(observation)));
            if(exported.artifactAvailable())row.put("nativeProjection",JSON.readTree(LearnedSchedulingArtifacts.resultJson(exported.projection())));
        }
        row.put("searchUnchangedAfterExport",result.totalWork()==paid && result.accounting()==accounting);
    }

    private void identities() throws Exception {
        var outputRows=new ArrayList<Object>();
        for(JsonNode frozen:read("identities.json")) {
            var row=new TreeMap<String,Object>();row.put("fixture",frozen);
            try {
                Expr left=CODEC.decodeExpression(frozen.path("left").asText()),right=CODEC.decodeExpression(frozen.path("right").asText());
                var observer=AstTransportObservation.open();
                try(observer;var store=new SearchExpressionStore(SearchExpressionStore.Limits.DEFAULT)) {
                    row.put("nativeSameReference",store.intern(left)==store.intern(right));row.put("identityWork",store.work());
                    row.put("observedRetention",store.statistics());
                } finally {row.put("nativeInternalCodec",transport(observer));}
            } catch(Exception|AssertionError failure){row.put("error",error(failure));}
            outputRows.add(row);
        }
        write("identities.json",outputRows);
        var states=new ArrayList<Object>();
        for(JsonNode pair:read("state-identities.json"))states.add(Map.of("fixture",pair,"nativeStateEqual",state(pair.path("left")).equals(state(pair.path("right")))));
        write("state-identities.json",states);
        write("symbol-bindings.json",read("symbol-bindings.json"));write("continuation-contracts.json",read("continuation-contracts.json"));
    }
    /** Separate diagnostic observation account; does not retrofit production search receipts. */
    private static final class Observation implements RetainedOperation.Sink {
        Object[] roots;RetainedOperation scope;long execution,validation,retention;RetainedGraph.Usage peak=new RetainedGraph.Usage(0,0,0);
        @Override public void executionWork(long units){execution=Math.addExact(execution,units);}
        @Override public void validationWork(long units){validation=Math.addExact(validation,units);}
        @Override public long observedWork(){return Math.addExact(execution,Math.addExact(validation,retention));}
        @Override public void checkpoint(){var seen=RetainedGraph.measure(this);retention=Math.addExact(retention,seen.work());peak=peak.maximum(seen.peak());}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(roots);visitor.reference(scope);}
        Map<String,Object> report(){return Map.of("execution",execution,"validation",validation,"retention",retention,"total",observedWork(),"peak",peak,
            "revision","public-native-observer/v1","accountingComplete",false,"scope","Diagnostic direct-call owner plus production atom observations; declared receipts retained separately, no unproved disjoint lifecycle sum. Not the production search ledger.");}
    }
    private Map<String,Object> cursorCase(JsonNode frozen) {
        String id=frozen.path("id").asText();var row=new TreeMap<String,Object>();row.put("id",id);row.put("frozen",frozen);
        var trace=new ArrayList<Object>();var actual=new ArrayList<NativeMoveProof>();var meter=new Observation();
        IncrementalProviderContract.ObjectCursor<NativeMoveProof> cursor=null;
        try {
            var selected=id.equals("missing-assumptions")?model.requiring(List.of("x > 0")):model;
            var plan=CheckedSchemaMatcherPlan.prepare(selected,1,Map.of(),Set.of(schema));var provider=plan.nativeProvider();
            var source=state(frozen.path("source"));var context=TypedMoveSearch.Context.sourceOnly(strings(frozen.path("context").path("initialAssumptions")),MoveContext.Phase.FROZEN_EVALUATION);
            long allowance=frozen.path("trace").get(1).path("allowance").asLong();
            var stop=id.startsWith("close-after-")?IncrementalProviderContract.ApplicationPhase.valueOf(id.substring(12)):null;
            meter.roots=new Object[]{provider,source,context,actual,null};
            var codec=AstTransportObservation.open();
            try(codec;var scope=RetainedOperation.open(meter)) {
                meter.scope=scope;cursor=provider.openSession(source,context);meter.roots[4]=cursor;
                long previous=cursor.snapshot().work().metrics().totalWorkUnitsV2();boolean monotonic=true,complete=true;
                try {
                    trace.add(Map.of("operation","created","snapshot",cursor.snapshot()));boolean stopped=false;
                    for(int index=0;index<MAX_PULLS;index++) {
                        var emitted=cursor.next(allowance);emitted.ifPresent(actual::add);var snapshot=cursor.snapshot();
                        long paid=snapshot.work().metrics().totalWorkUnitsV2();monotonic&=paid>=previous;previous=paid;complete&=snapshot.accountingComplete();
                        trace.add(Map.of("operation","next","allowance",allowance,"snapshot",snapshot,"emitted",emitted.isPresent()));
                        if(stop!=null && snapshot.work().prepaidApplications().phaseCalls().getOrDefault(stop.name(),0L)>0) {
                            var before=cursor.snapshot();row.put("zeroPullEmpty",cursor.next(0).isEmpty());row.put("zeroPullReceiptUnchanged",before.equals(cursor.snapshot()));stopped=true;break;
                        }
                        if(id.equals("zero-allowance-close") || terminal(snapshot.status())) {stopped=true;break;}
                    }
                    row.put("stoppedWithinPullBound",stopped);
                    row.put("cursorWorkMonotonic",monotonic);row.put("declaredSnapshotsComplete",complete);
                    row.put("statusBeforeClose",cursor.snapshot().status());
                    if(stop!=null) {
                        row.put("phaseStopEmitsNothing",actual.isEmpty());
                        row.put("phaseStopMathematicsExact",cursor.snapshot().work().mathematics().exactTheorySteps()==
                            (stop==IncrementalProviderContract.ApplicationPhase.EVIDENCE?1:0));
                    }
                } finally {cursor.close();trace.add(Map.of("operation","close","snapshot",cursor.snapshot()));}
                var closed=cursor.snapshot();cursor.close();row.put("idempotentClose",closed.equals(cursor.snapshot()));
                row.put("postClosePullEmpty",cursor.next(100000).isEmpty());row.put("postCloseReceiptUnchanged",closed.equals(cursor.snapshot()));
            } finally {row.put("nativeInternalCodec",transport(codec));}
            row.put("definition",provider.contractDefinition());row.put("compilation",plan.compilationReceipt());
            row.put("trace",trace);row.put("observations",meter.report());row.put("finalSnapshot",cursor.snapshot());
            row.put("declaredCursorWork",cursor.snapshot().work().metrics().totalWorkUnitsV2());
            var paidWork=cursor.snapshot().work();var prepaid=paidWork.prepaidApplications();
            long phaseCalls=prepaid.phaseCalls().values().stream().reduce(0L,Math::addExact);
            long operations=paidWork.operations().values().stream().reduce(0L,Math::addExact);
            row.put("prepaidCandidateNotDoubleCharged",paidWork.metrics().candidateWork().equals(ExecutionWork.ZERO));
            row.put("declaredPrepaidLedgerExact",paidWork.metrics().totalWorkUnitsV2()==Math.addExact(operations,Math.addExact(phaseCalls,prepaid.chargedUnits())));
            var replay=new ArrayList<Object>();var replayMeter=new Observation();var verifier=selected.nativeVerifier();
            replayMeter.roots=new Object[]{verifier,source,context,actual,null};
            var replayCodec=AstTransportObservation.open();
            try(replayCodec;var scope=RetainedOperation.open(replayMeter)) {
                replayMeter.scope=scope;
                for(var proof:actual) {
                    var move=new NativeSearchMove(proof,provider.descriptor(),0,Set.of());replayMeter.roots[4]=move;
                    var checked=verifier.verify(source,move,context);
                    replay.add(Map.of("accepted",checked.accepted(),"reason",checked.reason(),"declaredWork",checked.work()));
                }
            } finally {row.put("replayInternalCodec",transport(replayCodec));}
            row.put("replayObservations",replayMeter.report());row.put("transformations",exportProofs(actual,row,"export"));
            row.put("replay",replay);row.put("accountingComplete",false);row.put("withinBudget",false);
            var eager=selected.nativeProviders(1,Map.of(),Set.of(schema)).getFirst();
            NativeMoveProvider.Batch batch;var eagerMeter=new Observation();eagerMeter.roots=new Object[]{eager,source,context,null};
            var eagerCodec=AstTransportObservation.open();
            try(eagerCodec;var scope=RetainedOperation.open(eagerMeter)) {
                eagerMeter.scope=scope;batch=eager.candidates(source,context);eagerMeter.roots[3]=batch;eagerMeter.checkpoint();
            } finally {row.put("eagerInternalCodec",transport(eagerCodec));}
            row.put("eagerObservations",eagerMeter.report());row.put("eagerDeclaredWork",batch.work());
            row.put("eagerTransformations",exportProofs(batch.moves().stream().map(NativeSearchMove::proof).toList(),row,"eagerExport"));
        } catch(Exception|AssertionError failure){row.put("error",error(failure));row.put("trace",trace);row.put("observations",meter.report());}
        return row;
    }
    private static List<Transformation> exportProofs(List<NativeMoveProof> proofs,Map<String,Object> row,String label) {
        var exported=new ArrayList<Transformation>();var meter=new Observation();meter.roots=new Object[]{proofs,exported,null};
        var codec=AstTransportObservation.open();
        try(codec;var scope=RetainedOperation.open(meter);var json=RetainedJson.open()) {
            meter.scope=scope;meter.roots[2]=json;
            for(var proof:proofs)exported.add(proof.exportLegacy());
            meter.checkpoint();
        } finally {row.put(label+"Codec",transport(codec));row.put(label+"Observations",meter.report());}
        row.put(label+"Observations",meter.report());return exported;
    }
    private static boolean terminal(IncrementalProviderContract.Status status) {
        return status==IncrementalProviderContract.Status.EXHAUSTED || status==IncrementalProviderContract.Status.INCONCLUSIVE
            || status==IncrementalProviderContract.Status.CLOSED || status==IncrementalProviderContract.Status.FAILED;
    }
    private void negatives() throws Exception {
        var results=new ArrayList<Object>();
        for(String name:List.of("assumption-replay-negative.json","schema-source-binding-negative.json")) {
            var row=new TreeMap<String,Object>();row.put("id",name);var fixture=read(name);row.put("frozen",fixture);
            try {
                String caseId=name.startsWith("assumption")?"primitive-assumption-present":"actual-learned-schema-scoped-rational";
                JsonNode sourceCase=null;for(JsonNode item:read("cases.json"))if(item.path("id").asText().equals(caseId))sourceCase=item;
                var providers=providers(caseId,Objects.requireNonNull(sourceCase));var source=state(CODEC.decodeExpression(sourceCase.path("source").asText()));
                var context=new TypedMoveSearch.Context(CODEC.decodeExpression(sourceCase.path("goal").asText()),strings(sourceCase.path("context").path("assumptions")),MoveContext.Phase.FROZEN_EVALUATION);
                var wrong=state(CODEC.decodeExpression(fixture.path("source").asText()));var wrongContext=TypedMoveSearch.Context.frozen(context.goal());
                var meter=new Observation();meter.roots=new Object[]{providers.nativeProviders().getFirst(),providers.nativeVerifier(),source,context,wrong,wrongContext,null};
                var codec=AstTransportObservation.open();
                try(codec;var scope=RetainedOperation.open(meter)) {
                    meter.scope=scope;var batch=providers.nativeProviders().getFirst().candidates(source,context);meter.roots[6]=batch;
                    var proof=batch.moves().stream().filter(move->move.targetExpression().equals(context.goal())).findFirst().orElseThrow();
                    var checked=providers.nativeVerifier().verify(wrong,proof,wrongContext);
                    row.put("accepted",checked.accepted());row.put("reason",checked.reason());row.put("declaredVerificationWork",checked.work());
                    row.put("declaredGenerationWork",batch.work());
                } finally {row.put("nativeInternalCodec",transport(codec));row.put("observations",meter.report());}
                row.put("observations",meter.report());
            } catch(Exception|AssertionError failure){row.put("error",error(failure));}
            results.add(row);
        }
        write("negative-replay.json",results);
    }
    private Map<String,Object> header(JsonNode frozen,boolean diagnostic) {
        var row=new TreeMap<String,Object>();row.put("id",frozen.path("id").asText());row.put("budgetMode",diagnostic?"diagnostic-ample":"frozen-numeric");
        row.put("frozen",frozen);row.put("performanceComparable",false);return row;
    }
    private MoveSearch.Budget budget(JsonNode row,boolean diagnostic) {
        var value=JSON.convertValue(row.path("budget"),MoveSearch.Budget.class);
        return diagnostic?new MoveSearch.Budget(value.maxPrimitiveSteps(),value.maxSearchDepth(),value.maxTheoryWork(),value.maxStates(),diagnosticWork,value.maxComplexityDebt()):value;
    }
    private static TypedMoveSearch.State state(Expr expression){return new TypedMoveSearch.State(expression,0,0,"",List.of(),Set.of(),0);}
    private static TypedMoveSearch.State state(JsonNode value){return new TypedMoveSearch.State(CODEC.decodeExpression(value.path("expression").asText()),
        value.path("searchDepth").asInt(),value.path("primitiveDepth").asInt(),value.path("previousRule").asText(),strings(value.path("assumptions")),new TreeSet<>(strings(value.path("capabilities"))),value.path("complexityDebt").asInt());}
    private static List<String> strings(JsonNode values){var result=new ArrayList<String>();values.forEach(value->result.add(value.asText()));return List.copyOf(result);}
    private static Map<String,Long> transport(AstTransportObservation observation){var result=new TreeMap<String,Long>();for(var operation:AstTransportObservation.Operation.values())result.put(operation.name(),observation.count(operation));return result;}
    private static Map<String,String> error(Throwable failure){return Map.of("class",failure.getClass().getName(),"message",String.valueOf(failure.getMessage()));}
    private JsonNode read(String name)throws Exception{return JSON.readTree(Files.readString(baseline.resolve(name)));}
    private static Map<String,Object> stateProjection(TypedMoveSearch.State state){return Map.of("expression",CODEC.encodeExpression(state.expression()),
        "searchDepth",state.searchDepth(),"primitiveDepth",state.primitiveDepth(),"previousRule",state.previousRule(),
        "assumptions",state.assumptions(),"capabilities",state.capabilities(),"complexityDebt",state.complexityDebt());}
    private static Object jsonInputs(Object value) {
        if(value instanceof JsonNode node)return JSON.convertValue(node,Object.class);
        if(value instanceof Optional<?> optional)return optional.isPresent()?Map.of("present",true,"value",jsonInputs(optional.orElseThrow())):Map.of("present",false);
        if(value instanceof Map<?,?> map){var result=new TreeMap<String,Object>();map.forEach((key,item)->result.put((String)key,jsonInputs(item)));return result;}
        if(value instanceof List<?> list)return list.stream().map(NativeExprCorpus::jsonInputs).toList();
        return value;
    }
    private void write(String name,Object value)throws Exception{Files.writeString(output.resolve(name),LearnedSchedulingArtifacts.json(jsonInputs(value))+"\n");}
}
