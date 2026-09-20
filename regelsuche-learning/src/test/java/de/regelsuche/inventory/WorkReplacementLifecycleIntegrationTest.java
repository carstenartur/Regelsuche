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
    private static final List<String> QUERIES = List.of("(x+y)*(x-y)+y*y", "(u+v)*(u-v)+v*v");
    @Test void realLearningSelectionRestoreAndTypedQueriesRunInAllThreeProfiles() throws Exception {
        var fixture = prepareFixture();
        for (var profile : Profile.values()) {
            var manifest = manifest(profile);
            var factory = new ArmFactory(manifest, fixture);
            var queries = QUERIES.stream().map(source -> new WorkReplacementExperiment.Query(source,
                WorkReplacementLearning.identity(source), problem(source, MoveContext.Phase.FROZEN_EVALUATION, fixture.session().verifier()),
                WorkReplacementProcessFixture::score)).toList();
            var report = new WorkReplacementExperiment().run(manifest, queries, factory.plans());
            assertReport(profile, fixture, factory, report);
        }
    }

    private record Fixture(WorkReplacementLearning.Acquired acquired, LifecycleWorkAccount acquisitionOnly,
            OracleHint supplied, TypedLearnedMoveInventory.SearchSession session,
            List<TypedSourcePolicySelection.Profile> profiles, List<TypedPolicySelection.TrainingTask> tasks,
            TypedSourcePolicySelection.Frozen selected, LifecycleWorkAccount training) {}

    private static Fixture prepareFixture() {
        var initial = manifest(Profile.LOADED_STREAM);
        var training = new WorkReplacementExperiment.Journal();
        var acquired = WorkReplacementLearning.acquire(initial, TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits(), training, "acquire");
        var acquisitionOnly = training.account();
        var supplied = supplyOracle(acquired.model());
        var session = acquired.inventory().newSchemaSearchSession(acquired.model(), 4, Map.of());
        var profiles = List.of(new TypedSourcePolicySelection.Profile("selected", session.providers(), MovePriorityPolicy.INVENTORY_ORDER));
        var tasks = List.of("a+0", "b+0").stream().map(source -> new TypedPolicySelection.TrainingTask(source,
            problem(source, MoveContext.Phase.TRAIN, session.verifier()))).toList();
        var selected = WorkReplacementLearning.select(initial, tasks, profiles, WorkReplacementProcessFixture::score, training, "select");
        assertTrainingAccount(training.account(), selected);
        return new Fixture(acquired, acquisitionOnly, supplied, session, profiles, tasks, selected, training.account());
    }

    private static void assertTrainingAccount(LifecycleWorkAccount training, TypedSourcePolicySelection.Frozen selected) {
        assertTrue(training.work(TRAINING_SEARCH) > 0);
        assertTrue(training.work(RULE_FORMATION_PROOF) > 0);
        assertTrue(training.work(COMPILATION) > 0);
        assertEquals(selected.trainingWork(), training.work(SELECTION_TRAINING));
        long artifactBytes = training.receipts().stream()
            .filter(receipt -> Set.of("acquire/train", "acquire/compile", "acquire/schemas", "select/selection").contains(receipt.id()))
            .mapToLong(receipt -> receipt.rawReceipt().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).reduce(0, Math::addExact);
        assertEquals(artifactBytes, training.work(OUTPUT), "each intended acquisition artifact is paid exactly once");
    }

    private static final class ArmFactory {
        private final WorkReplacementManifest manifest;
        private final Fixture fixture;
        private final java.util.concurrent.atomic.AtomicInteger acquisitionCalls = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();

        ArmFactory(WorkReplacementManifest manifest, Fixture fixture) {
            this.manifest = manifest;
            this.fixture = fixture;
        }

        Map<Arm, WorkReplacementExperiment.Plan> plans() {
            var plans = new EnumMap<Arm, WorkReplacementExperiment.Plan>(Arm.class);
            for (var arm : Arm.values()) plans.put(arm, new WorkReplacementExperiment.Plan(manifest.binding(arm),
                journal -> acquire(arm, journal), provisionedAcquisition(arm), (journal, prefix) -> open(arm, journal, prefix)));
            return plans;
        }

        private LifecycleWorkAccount provisionedAcquisition(Arm arm) {
            return switch (arm) {
                case B0, B1 -> LifecycleWorkAccount.empty();
                case L1 -> fixture.training();
                case L_ORACLE -> fixture.acquisitionOnly().plus(fixture.supplied().work());
            };
        }

        private void acquire(Arm arm, WorkReplacementExperiment.Journal journal) {
            acquisitionCalls.incrementAndGet();
            if (!learned(arm)) return;
            var actual = WorkReplacementLearning.acquire(manifest, TraceStrategyTransferExample.inventory(),
                TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits(), journal, "acquire");
            assertEquals(fixture.acquired().model().toCanonicalJson(), actual.model().toCanonicalJson());
            if (arm == Arm.L_ORACLE) journal.append(supplyOracle(actual.model()).work());
            else WorkReplacementLearning.select(manifest, fixture.tasks(), fixture.profiles(), WorkReplacementProcessFixture::score, journal, "select");
        }

        private WorkReplacementExperiment.Session open(Arm arm, WorkReplacementExperiment.Journal journal, String prefix) {
            opens.incrementAndGet();
            String oracleSchemaId = arm == Arm.L_ORACLE ? fixture.supplied().schemaId() : "";
            if (manifest.profile() == Profile.FRESH_PROCESS_PER_QUERY) return new Child(learned(arm) ? fixture.acquired().model().toCanonicalJson() : "",
                learned(arm), arm == Arm.B0, oracleSchemaId, journal, prefix);
            var providers = loadedProviders(arm, oracleSchemaId, journal, prefix);
            var selected = fixture.selected();
            return new WorkReplacementTypedExecution(new TypedSourcePolicySelection.Profile(selected.selected().id(), providers,
                selected.selected().policy()), arm == Arm.B0, selected.trainingSources());
        }

        private List<MoveProvider> loadedProviders(Arm arm, String oracleSchemaId, WorkReplacementExperiment.Journal journal, String prefix) {
            var acquired = fixture.acquired();
            var inventory = WorkReplacementLearning.primitives(acquired.formation().inventory(), journal, prefix);
            if (!learned(arm)) {
                journal.append(LifecycleWorkAccount.of(LifecycleWorkAccount.Receipt.skipped(prefix + "/restore", RESTORE_REPROOF,
                    "primitive baseline has no learned model")));
                return inventory.primitiveProviders();
            }
            var model = WorkReplacementLearning.restore(acquired.model().toCanonicalJson(), acquired.formation().inventory().contentHash(), journal, prefix);
            return arm == Arm.L_ORACLE ? model.providers(1, Map.of(), Set.of(oracleSchemaId))
                : inventory.newSchemaSearchSession(model, 4, Map.of()).providers();
        }
    }

    private static boolean learned(Arm arm) { return arm == Arm.L1 || arm == Arm.L_ORACLE; }

    private static void assertReport(Profile profile, Fixture fixture, ArmFactory factory, WorkReplacementExperiment.Report report) throws Exception {
        assertEquals(profile == Profile.FRESH_PROCESS_PER_QUERY ? 8 : 4, factory.opens.get());
        assertEquals(profile == Profile.PROVISIONED_MODEL ? 0 : 4, factory.acquisitionCalls.get());
        for (var result : report.arms().values()) assertArm(profile, result);
        assertOracle(report, fixture.supplied());
        assertEquals(fixture.training().work(TRAINING_SEARCH), report.arms().get(Arm.L1).account().work(TRAINING_SEARCH));
        var artifact = JSON.readTree(WorkReplacementArtifacts.json(report));
        assertEquals(4, artifact.path("arms").size());
    }

    private static void assertArm(Profile profile, WorkReplacementExperiment.ArmResult result) {
        assertTrue(result.accountingComplete(), result.rows().toString());
        assertEquals(2, result.rows().size());
        if (learned(result.arm())) assertTrue(result.account().work(RESTORE_REPROOF) > 0);
        else assertEquals(0, result.account().work(RESTORE_REPROOF), "primitive control must not pay for unused learned-model reproof");
        assertTrue(result.rows().stream().allMatch(row -> row.status() == WorkReplacementExperiment.Status.QUALITY_REACHED), result.rows().toString());
        if (profile == Profile.FRESH_PROCESS_PER_QUERY) {
            assertNotEquals(result.rows().getFirst().processId(), result.rows().getLast().processId());
            assertNotEquals(ProcessHandle.current().pid(), result.rows().getFirst().processId());
        }
    }

    private static void assertOracle(WorkReplacementExperiment.Report report, OracleHint supplied) {
        var oracle = report.arms().get(Arm.L_ORACLE);
        assertTrue(oracle.rows().stream().allMatch(row -> row.evaluation().learnedWitnessIds().contains(supplied.providerId())),
            "oracle must use the supplied learned application in its selected witness");
        var selectedChecks = oracle.account().receipts().stream().filter(receipt -> receipt.phase() == FINAL_CHECK).toList();
        assertEquals(2, selectedChecks.size());
        assertTrue(selectedChecks.stream().allMatch(receipt -> receipt.rawReceipt().contains(supplied.schemaId())),
            "supplied schema ID must occur in the selected independently checked witness");
        assertTrue(oracle.account().receipts().stream().anyMatch(receipt -> receipt.rawRevision().equals("diagnostic-oracle-binding/v1")));
        assertTrue(oracle.account().receipts().stream().noneMatch(receipt -> receipt.rawRevision().equals(TypedSourcePolicySelection.QUALITY_REVISION)));
        assertThrows(IllegalArgumentException.class, () -> report.successes(Arm.L_ORACLE));
        assertThrows(IllegalArgumentException.class, () -> report.runtimeRatio(Arm.L_ORACLE, Arm.B1));
    }
    /** Diagnostic supplied knowledge: bounded matching only, paid separately from empirical selection. */
    private record OracleHint(String schemaId, String providerId, LifecycleWorkAccount work) {}
    private static OracleHint supplyOracle(CheckedLearnedSchemaModel model) {
        long work = 0;
        for (var schema : model.schemas()) {
            boolean applicable = true;
            for (String source : QUERIES) {
                var match = de.regelsuche.transform.ExprMatcher.pattern(schema.source()).match(new ExpressionParser().parseTerm(source),
                    new de.regelsuche.transform.ExprMatcher.MatchOptions(null, 1, 2048, 2048));
                work = Math.addExact(work, 1L + match.evaluatedSteps() + match.patternBranches());
                if (!match.complete() || !match.matched()) { applicable = false; break; }
                var target = schema.target().instantiate(match.matches().getFirst().bindings());
                work = Math.addExact(work, 1);
                if (WorkReplacementProcessFixture.nodes(target) > 3) { applicable = false; break; }
            }
            if (!applicable) continue;
            String providerId = model.providers(1, Map.of(), Set.of(schema.id())).getFirst().descriptor().id();
            String raw = LearnedSchedulingArtifacts.json(Map.of("schemaId", schema.id(), "providerId", providerId,
                "sources", QUERIES, "role", "DIAGNOSTIC_ORACLE_ONLY"));
            var journal = new WorkReplacementExperiment.Journal();
            WorkReplacementTypedExecution.charge(journal, "oracle/binding", SELECTION_TRAINING, work, "diagnostic-oracle-binding/v1", raw);
            WorkReplacementTypedExecution.charge(journal, "oracle/output", OUTPUT, raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                "utf8-materialized-bytes/v1", "");
            return new OracleHint(schema.id(), providerId, journal.account());
        }
        throw new AssertionError("fixture needs one applicable supplied schema: " + model.schemas().stream().map(CheckedLearnedSchemaModel.Schema::source).toList());
    }

    private static WorkReplacementManifest manifest(Profile profile) {
        var partitions = new ArrayList<Partition>();
        for (var input : TraceStrategyTransferExample.trainingInputs()) partitions.add(new Partition(input.id(),
            WorkReplacementLearning.identity(input.expression()), input.id(), Split.TRAIN, List.of()));
        for (String source : List.of("a+0", "b+0")) partitions.add(new Partition(source, WorkReplacementLearning.identity(source), "zero", Split.TRAIN, List.of()));
        for (String source : QUERIES) partitions.add(new Partition(source, WorkReplacementLearning.identity(source), "zero", Split.FINAL_TEST, List.of()));
        var base = WorkReplacementManifestTest.manifest(profile, 20_000_000, partitions);
        return new WorkReplacementManifest(base.baselineCommit(), base.revisions(), base.informationRegime(),
            new Quality(base.quality().objectiveDefinition(), base.quality().mode(), 3, base.quality().continuation()),
            base.seeds(), base.resources(), base.profile(), base.observationMode(), base.unsolvedPolicy(), base.partitions());
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
        Child(String model, boolean learned, boolean historical, String oracleSchemaId, WorkReplacementExperiment.Journal journal, String prefix) {
            try {
                process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", childClasspath(), WorkReplacementProcessFixture.class.getName()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
                input = process.inputReader(); output = process.outputWriter();
                send(Map.of("model", model, "learned", learned, "historical", historical, "oracleSchemaId", oracleSchemaId));
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
