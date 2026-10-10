package de.regelsuche.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.AstTransportObservation;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CheckedSchemaBoundedNativeTest {
    private static final String PAIR = "((a+b)*(a-b)+b*b)+((c+d)*(c-d)+d*d)";
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static CheckedLearnedSchemaModel model;
    private static String schema;

    @BeforeAll static void learnAndRestore() {
        var formation = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
        var formed = CheckedLearnedSchemaModel.learn(formation);
        model = CheckedLearnedSchemaModel.load(formed.toCanonicalJson(), formed.inventoryHash());
        schema = model.providers().getFirst().candidates(MoveState.root(CODEC.encodeExpression(parse("(x+y)*(x-y)+y*y"))),
            MoveContext.frozen("unused")).moves().stream()
            .filter(move -> CODEC.decodeExpression(move.transformation().transformedExpression()).equals(parse("x^2")))
            .findFirst().orElseThrow().transformation().rule();
    }
    @Test void actualLearnedRestoredSchemaStopsAfterOneLazyApplication() {
        var eager = run(model, schema, false);
        var lazy = run(model, schema, true);
        for (var attempt : List.of(eager, lazy)) {
            var result = attempt.result();
            assertTrue(result.withinBudget(), result.search().accounting().detail());
            assertEquals(MoveSearch.Outcome.QUALITY_REACHED, result.search().outcome());
            assertEquals(1, result.witness().size());
            var step = result.witness().getFirst();
            assertTrue(step.verification().accepted());
            assertInstanceOf(NativeMoveProof.Exact.class, step.move().proof());
            assertEquals(SearchMove.SourceKind.LEARNED, step.move().descriptor().sourceKind());
            assertTrue(result.replayWork() > 0);
        }
        assertEquals(eager.result().incumbent(), lazy.result().incumbent());
        assertEquals(2, eager.result().search().metrics().generatedSuccessors());
        assertEquals(1, lazy.result().search().metrics().generatedSuccessors());
        assertTrue(model.loadWork() > 0);
        assertTrue(lazy.compilationWork() > 0);
    }
    @Test void identicalModelBytesDoNotAuthorizeAnotherModelsProviderIdentity() {
        var other = CheckedLearnedSchemaModel.load(model.toCanonicalJson(), model.inventoryHash());
        var providers = other.nativeProviders(1, Map.of(), Set.of(schema));
        var checker = NativeVerifier.registered(model.nativeProviders(1, Map.of(), Set.of(schema)));
        assertPartialUnexecuted(providers, checker);
    }
    @Test void installedCheckerInspectsTheActualNestedApplicationFactory() {
        var descriptor = model.nativeProviders().getFirst().descriptor();
        var plan = new CheckedSchemaMatcherPlan(model, descriptor, 1, Map.of(), Set.of(schema), UnknownApplication.INSTANCE);
        var providers = List.<NativeMoveProvider>of(plan.nativeProvider());
        assertPartialUnexecuted(providers, NativeVerifier.registered(providers));
    }
    private static void assertPartialUnexecuted(List<NativeMoveProvider> providers, NativeVerifier checker) {
        var x = parse("x");
        var p = new NativeMoveSearch.Problem(x, TypedMoveSearch.Context.frozen(x), providers,
            MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(1, 1, 0, 8, 100_000_000),
            NativeMovePriorityPolicy.INVENTORY_ORDER, NativeMoveSearch.ZeroScore.INSTANCE, NativeStateValue.NONE, checker);
        var result = NativeMoveSearch.boundedAccounting().search(p, SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(MoveSearch.Outcome.TARGET_REACHED, result.observedOutcome());
        assertTrue(result.observationsComplete());
        assertFalse(result.accountingComplete());
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE, result.outcome());
    }
    @Test void freshJvmRestoresTheModelAndUsesASelectedLearnedProof(@TempDir Path directory) throws Exception {
        var modelFile = directory.resolve("model.json");
        Files.writeString(modelFile, model.toCanonicalJson());
        var stdout = directory.resolve("worker.json");
        var stderr = directory.resolve("worker.stderr");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-XX:ActiveProcessorCount=2", "-cp", classpath(), Worker.class.getName(), modelFile.toString(), model.inventoryHash(), schema)
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "fresh JVM exceeded its explicit timeout");
            assertEquals(0, process.exitValue(), Files.readString(stderr));
            var report = new ObjectMapper().readTree(Files.readString(stdout));
            assertEquals(process.pid(), report.path("pid").asLong());
            assertNotEquals(ProcessHandle.current().pid(), report.path("pid").asLong());
            for (var field : List.of("loadWork", "compileWork", "queryWork", "exportWork"))
                assertTrue(report.path(field).asLong() > 0, field);
            assertTrue(report.path("accountingComplete").asBoolean());
            assertEquals(1, report.path("selectedLearnedProofs").asInt());
            System.out.println("P04_BOUNDED_RESTORED " + report);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }
    private record Attempt(NativeMoveSearch.QualityResult result, long compilationWork) {}
    private static Attempt run(CheckedLearnedSchemaModel loaded, String schemaId, boolean lazy) {
        var plan = lazy ? CheckedSchemaMatcherPlan.prepare(loaded, 1, Map.of(), Set.of(schemaId)) : null;
        var providers = lazy ? List.<NativeMoveProvider>of(plan.nativeProvider()) : loaded.nativeProviders(1, Map.of(), Set.of(schemaId));
        var source = parse(PAIR);
        var p = new NativeMoveSearch.Problem(source, TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION),
            providers, MoveSearch.Mode.FAST, lazy ? MoveSearch.Scheduling.STAGED_INCREMENTAL : MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(0, 1, 100_000, 32, 1_000_000_000));
        long threshold = NativeNodeCountObjective.INSTANCE.evaluate(new TypedMoveSearch.State(source, 0, 0, "", List.of(), Set.of(), 0)).value() - 1;
        try (var codec = AstTransportObservation.open()) {
            var result = NativeMoveSearch.boundedAccounting().searchUntil(p, NativeNodeCountObjective.INSTANCE, threshold,
                SearchContinuationContract.PATH_SENSITIVE);
            assertEquals(0, codec.total());
            return new Attempt(result, plan == null ? 0 : plan.compilationWork());
        }
    }
    private static Expr parse(String text) { return new ExpressionParser().parseExactTerm(text).expression(); }
    private static String classpath() throws Exception {
        var entries = new java.util.LinkedHashSet<String>();
        entries.add(System.getProperty("java.class.path"));
        for (ClassLoader loader = Worker.class.getClassLoader(); loader != null; loader = loader.getParent())
            if (loader instanceof java.net.URLClassLoader urls)
                for (var url : urls.getURLs()) entries.add(Path.of(url.toURI()).toString());
        return String.join(java.io.File.pathSeparator, entries);
    }
    public static final class Worker {
        public static void main(String[] args) throws Exception {
            var loaded = CheckedLearnedSchemaModel.load(Files.readString(Path.of(args[0])), args[1]);
            var attempt = run(loaded, args[2], true);
            var result = attempt.result();
            assertTrue(result.withinBudget());
            assertEquals(1, result.witness().size());
            assertInstanceOf(NativeMoveProof.Exact.class, result.witness().getFirst().move().proof());
            assertTrue(result.witness().getFirst().verification().accepted());
            var exported = result.search().exportLegacy(100_000_000, SearchExpressionStore.Limits.DEFAULT);
            assertTrue(exported.complete());
            System.out.println(new ObjectMapper().createObjectNode().put("pid", ProcessHandle.current().pid())
                .put("loadWork", loaded.loadWork()).put("compileWork", attempt.compilationWork())
                .put("queryWork", result.totalWork()).put("exportWork", exported.accounting().work())
                .put("accountingComplete", result.search().accountingComplete()).put("selectedLearnedProofs", 1));
        }
    }
    private enum UnknownApplication implements CheckedSchemaMatcherPlan.Application, RetainedGraph.View {
        INSTANCE;
        @Override public CheckedSchemaMatcherPlan.ApplicationSteps start(CheckedLearnedSchemaModel.Schema schema, Expr source,
                String encoded, List<Integer> path, Map<String, Expr> bindings, CheckedSchemaSupport.Work work) {
            throw new AssertionError("unknown application was invoked");
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {}
    }
}
