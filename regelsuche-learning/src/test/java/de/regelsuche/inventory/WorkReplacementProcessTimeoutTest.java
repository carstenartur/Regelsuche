package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.inventory.WorkReplacementManifest.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class WorkReplacementProcessTimeoutTest {
    @Test void declaredQueryDeadlineKillsChildRetainsPaidWorkAndEveryRow() {
        var manifest = manifest();
        var processes = new ArrayList<Process>();
        var watchdogFired = new AtomicBoolean();
        try (var watchdog = Executors.newSingleThreadScheduledExecutor()) {
            var plans = new EnumMap<Arm, WorkReplacementExperiment.Plan>(Arm.class);
            for (var arm : Arm.values()) plans.put(arm, new WorkReplacementExperiment.Plan(manifest.binding(arm),
                journal -> journal.append(WorkReplacementExperimentTest.paidTraining(11)), null, (journal, prefix) -> {
                    var child = new WorkReplacementLifecycleIntegrationTest.Child(WorkReplacementHangingProcessFixture.class,
                        Map.of(), manifest.resources().queryTimeoutNanos(), journal, prefix);
                    var process = child.process().orElseThrow();
                    processes.add(process);
                    watchdog.schedule(() -> {
                        if (process.isAlive()) { watchdogFired.set(true); process.destroyForcibly(); }
                    }, 2, TimeUnit.SECONDS);
                    return child;
                }));
            var queries = List.of(WorkReplacementExperimentTest.query("first"), WorkReplacementExperimentTest.query("remaining"));
            var report = new WorkReplacementExperiment().run(manifest, queries, plans);
            assertReport(report);
            assertFalse(watchdogFired.get(), "declared 50ms query deadline must kill the child before the 2s test watchdog");
            assertEquals(4, processes.size());
            assertTrue(processes.stream().noneMatch(Process::isAlive));
        } finally { processes.forEach(Process::destroyForcibly); }
    }

    private static void assertReport(WorkReplacementExperiment.Report report) {
        assertEquals(4, report.arms().size());
        for (var result : report.arms().values()) {
            assertEquals(2, result.rows().size());
            assertEquals(WorkReplacementExperiment.Status.TIMEOUT, result.rows().getFirst().status());
            assertEquals("first", result.rows().getFirst().queryId());
            assertEquals(WorkReplacementExperiment.Status.NOT_RUN, result.rows().getLast().status());
            assertEquals("remaining", result.rows().getLast().queryId());
            assertEquals(18, result.account().totalWork());
            assertFalse(result.accountingComplete());
            assertFalse(result.withinBudget(report.manifest().resources().totalWork()));
            assertTrue(result.account().receipts().stream().anyMatch(receipt -> receipt.rawReceipt().equals("known startup work")));
        }
        assertEquals(0, report.successes(Arm.L1));
        assertThrows(IllegalStateException.class, () -> report.runtimeRatio(Arm.B1, Arm.L1));
    }

    private static WorkReplacementManifest manifest() {
        String source = WorkReplacementLearning.identity("x+0");
        var base = WorkReplacementManifestTest.manifest(Profile.LOADED_STREAM, 20000, List.of(
            new Partition("first", source, "family", Split.FINAL_TEST, List.of()),
            new Partition("remaining", source, "family", Split.FINAL_TEST, List.of())));
        return new WorkReplacementManifest(base.baselineCommit(), base.revisions(), base.informationRegime(), base.quality(), base.seeds(),
            new Resources(20000, TimeUnit.MILLISECONDS.toNanos(50), 64, 8, "REMAINING_EQUAL_SHARE_WITH_CARRY"),
            base.profile(), base.observationMode(), UnsolvedPolicy.PENALIZE_AT_TIMEOUT, base.partitions());
    }
}
