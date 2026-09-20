package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.inventory.WorkReplacementManifest.*;
import static de.regelsuche.inventory.LifecycleWorkAccount.Phase.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.evolution.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(180)
class WorkReplacementLifecycleIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Test void realLearningSelectionRestoreAndTypedQueriesRunInAllThreeProfiles() throws Exception {
        var initial = manifest(Profile.LOADED_STREAM);
        var training = new WorkReplacementExperiment.Journal();
        var acquired = WorkReplacementLearning.acquire(initial, TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits(), training, "acquire");
        var session = acquired.inventory().newSchemaSearchSession(acquired.model(), 4, Map.of());
        var profiles = List.of(new TypedSourcePolicySelection.Profile("selected", session.providers(), MovePriorityPolicy.INVENTORY_ORDER));
        var tasks = List.of("a+0", "b+0").stream().map(source -> new TypedPolicySelection.TrainingTask(source,
            problem(source, MoveContext.Phase.TRAIN, session.verifier()))).toList();
        var selected = WorkReplacementLearning.select(initial, tasks, profiles, WorkReplacementProcessFixture::score, training, "select");
        assertTrue(training.account().work(TRAINING_SEARCH) > 0);
        assertTrue(training.account().work(RULE_FORMATION_PROOF) > 0);
        assertTrue(training.account().work(COMPILATION) > 0);
        assertEquals(selected.trainingWork(), training.account().work(SELECTION_TRAINING));
        for (var profile : Profile.values()) {
            var manifest = manifest(profile);
            var plans = new EnumMap<Arm, WorkReplacementExperiment.Plan>(Arm.class);
            var acquisitionCalls = new java.util.concurrent.atomic.AtomicInteger();
            var opens = new java.util.concurrent.atomic.AtomicInteger();
            for (var arm : Arm.values()) {
                boolean learned = arm == Arm.L1 || arm == Arm.L_ORACLE;
                var acquisition = learned ? training.account() : LifecycleWorkAccount.empty();
                plans.put(arm, new WorkReplacementExperiment.Plan(manifest.binding(arm), journal -> {
                    acquisitionCalls.incrementAndGet();
                    if (learned) {
                        var actual = WorkReplacementLearning.acquire(manifest, TraceStrategyTransferExample.inventory(),
                            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits(), journal, "acquire");
                        assertEquals(acquired.model().toCanonicalJson(), actual.model().toCanonicalJson());
                        WorkReplacementLearning.select(manifest, tasks, profiles, WorkReplacementProcessFixture::score, journal, "select");
                    }
                }, acquisition, (journal, prefix) -> {
                    opens.incrementAndGet();
                    if (profile == Profile.FRESH_PROCESS_PER_QUERY) return new Child(learned ? acquired.model().toCanonicalJson() : "", learned, arm == Arm.B0, journal, prefix);
                    var inventory = WorkReplacementLearning.primitives(acquired.formation().inventory(), journal, prefix);
                    var providers = inventory.primitiveProviders();
                    if (learned) {
                        var model = WorkReplacementLearning.restore(acquired.model().toCanonicalJson(), acquired.formation().inventory().contentHash(), journal, prefix);
                        providers = inventory.newSchemaSearchSession(model, 4, Map.of()).providers();
                    } else journal.append(LifecycleWorkAccount.of(LifecycleWorkAccount.Receipt.skipped(prefix + "/restore", RESTORE_REPROOF,
                        "primitive baseline has no learned model")));
                    return new WorkReplacementTypedExecution(new TypedSourcePolicySelection.Profile(selected.selected().id(), providers,
                        selected.selected().policy()), arm == Arm.B0, selected.trainingSources());
                }));
            }
            var queries = List.of("x+0", "y+0").stream().map(source -> new WorkReplacementExperiment.Query(source,
                WorkReplacementLearning.identity(source), problem(source, MoveContext.Phase.FROZEN_EVALUATION, session.verifier()),
                WorkReplacementProcessFixture::score)).toList();
            var report = new WorkReplacementExperiment().run(manifest, queries, plans);
            assertEquals(profile == Profile.FRESH_PROCESS_PER_QUERY ? 8 : 4, opens.get());
            assertEquals(profile == Profile.PROVISIONED_MODEL ? 0 : 4, acquisitionCalls.get());
            for (var result : report.arms().values()) {
                assertTrue(result.accountingComplete(), result.rows().toString());
                assertEquals(2, result.rows().size());
                if (result.arm() == Arm.B0 || result.arm() == Arm.B1) assertEquals(0, result.account().work(RESTORE_REPROOF),
                    "primitive control must not pay for unused learned-model reproof");
                else assertTrue(result.account().work(RESTORE_REPROOF) > 0);
                assertTrue(result.rows().stream().allMatch(row -> row.status() == WorkReplacementExperiment.Status.QUALITY_REACHED), result.rows().toString());
                if (profile == Profile.FRESH_PROCESS_PER_QUERY) {
                    assertNotEquals(result.rows().getFirst().processId(), result.rows().getLast().processId());
                    assertNotEquals(ProcessHandle.current().pid(), result.rows().getFirst().processId());
                }
            }
            assertEquals(training.account().work(TRAINING_SEARCH), report.arms().get(Arm.L1).account().work(TRAINING_SEARCH));
            var artifact = JSON.readTree(WorkReplacementArtifacts.json(report));
            assertEquals(4, artifact.path("arms").size());
        }
    }
    private static WorkReplacementManifest manifest(Profile profile) {
        var partitions = new ArrayList<Partition>();
        for (var input : TraceStrategyTransferExample.trainingInputs()) partitions.add(new Partition(input.id(),
            WorkReplacementLearning.identity(input.expression()), input.id(), Split.TRAIN, List.of()));
        for (String source : List.of("a+0", "b+0")) partitions.add(new Partition(source, WorkReplacementLearning.identity(source), "zero", Split.TRAIN, List.of()));
        for (String source : List.of("x+0", "y+0")) partitions.add(new Partition(source, WorkReplacementLearning.identity(source), "zero", Split.FINAL_TEST, List.of()));
        return WorkReplacementManifestTest.manifest(profile, 20_000_000, partitions);
    }
    private static TypedMoveSearch.Problem problem(String source, MoveContext.Phase phase, TypedMoveSearch.Verifier verifier) {
        return new TypedMoveSearch.Problem(new ExpressionParser().parseTerm(source), TypedMoveSearch.Context.sourceOnly(List.of(), phase),
            List.of(), MovePriorityPolicy.INVENTORY_ORDER, verifier, state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(8, 8, 100, 64, 20000));
    }
    private static final class Child implements WorkReplacementExperiment.Session {
        private final Process process;
        private final BufferedReader input;
        private final BufferedWriter output;
        Child(String model, boolean learned, boolean historical, WorkReplacementExperiment.Journal journal, String prefix) {
            try {
                process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", childClasspath(), WorkReplacementProcessFixture.class.getName()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
                input = process.inputReader(); output = process.outputWriter();
                send(Map.of("model", model, "learned", learned, "historical", historical));
                append(JSON.readTree(receive()), journal, prefix);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        @Override public Optional<Process> process() { return Optional.of(process); }
        @Override public WorkReplacementExperiment.Evaluation execute(WorkReplacementExperiment.Query query, long budget,
                Quality quality, WorkReplacementExperiment.Journal journal, String prefix) {
            try {
                send(Map.of("source", query.sourceIdentity(), "budget", budget, "quality", quality, "prefix", prefix));
                var response = JSON.readTree(receive());
                append(response.get("receipts"), journal, prefix);
                return JSON.treeToValue(response.get("evaluation"), WorkReplacementExperiment.Evaluation.class);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        private void send(Object value) throws IOException { output.write(JSON.writeValueAsString(value)); output.newLine(); output.flush(); }
        private String receive() throws Exception {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                try { return executor.submit(input::readLine).get(45, TimeUnit.SECONDS); }
                catch (TimeoutException failure) { process.destroyForcibly(); throw failure; }
            }
        }
        @Override public void close() {
            try { output.close(); if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly(); }
            catch (Exception failure) { process.destroyForcibly(); throw new IllegalStateException(failure); }
        }
        private static void append(com.fasterxml.jackson.databind.JsonNode values, WorkReplacementExperiment.Journal journal, String prefix) throws Exception {
            var receipts = new ArrayList<LifecycleWorkAccount.Receipt>();
            var childIds = new HashSet<String>();
            for (var value : values) {
                var receipt = JSON.treeToValue(value, LifecycleWorkAccount.Receipt.class);
                var children = receipt.children().stream().map(child -> prefix + "/" + child).toList();
                childIds.addAll(children);
                receipts.add(new LifecycleWorkAccount.Receipt(prefix + "/" + receipt.id(), receipt.phase(),
                    receipt.exclusiveWork(), receipt.inclusiveWork(), children, receipt.rawRevision(), receipt.rawReceipt(), receipt.zeroReason()));
            }
            journal.append(new LifecycleWorkAccount(receipts, receipts.stream().map(LifecycleWorkAccount.Receipt::id)
                .filter(id -> !childIds.contains(id)).toList()));
        }
    }
    private static String childClasspath() throws Exception {
        var entries = new LinkedHashSet<String>(); entries.add(System.getProperty("java.class.path"));
        for (ClassLoader loader = WorkReplacementProcessFixture.class.getClassLoader(); loader != null; loader = loader.getParent())
            if (loader instanceof java.net.URLClassLoader urls) for (var url : urls.getURLs()) entries.add(Path.of(url.toURI()).toString());
        return String.join(File.pathSeparator, entries);
    }
}
