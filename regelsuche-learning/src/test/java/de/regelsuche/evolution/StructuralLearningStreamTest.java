package de.regelsuche.evolution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Development stream: paid acquisition, frozen control, reload and identical declared tasks. */
public final class StructuralLearningStreamTest {
    private static CheckedSchemaTaskSolver.Task factor(String source) {
        return StructuralConstructionTest.task(source, StructuralTaskObjective.FACTORIZATION);
    }
    private static CheckedSchemaTaskSolver.Task squares(String source) {
        return StructuralConstructionTest.task(source, StructuralTaskObjective.SUM_OF_TWO_SQUARES);
    }
    private static void require(boolean condition, String detail) { StructuralConstructionTest.require(condition, detail); }

    @Test
    @Timeout(180)
    void cumulativeMixedStreamPaysItsAcquisition() throws Exception {
        cumulativeMixedStreamPaysItsAcquisition(
            Path.of("build", "reports", "structural-construction-learning"));
    }

    public void cumulativeMixedStreamPaysItsAcquisition(Path directory) throws Exception {
        Files.createDirectories(directory);
        var genome = TraceStrategyTransferExample.inventory();
        var baseline = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.STRUCTURAL_V1);
        var first = baseline.learnBatch(List.of(factor("x^4+4*y^4"), factor("a^4+4*b^4")),
            List.of(factor("(p*q)^4+4*r^4")), 5_000_000);
        require(first.promoted(), "first structural generation must really be learned");
        var second = first.next().learnBatch(List.of(squares("(a^2+b^2)*(c^2+d^2)"), squares("(p^2+q^2)*(r^2+s^2)")),
            List.of(squares("((u+1)^2+v^2)*(w^2+z^2)"), factor("(p*q)^4+4*r^4")), 5_000_000);
        require(second.promoted() && second.next().schemaCount() > first.next().schemaCount(), "second generation keeps and augments prior knowledge");
        var frozen = CumulativeTaskKnowledge.load(genome, first.next().save(directory.resolve("frozen")));
        var cumulative = CumulativeTaskKnowledge.load(genome, second.next().save(directory.resolve("cumulative")));
        long frozenWork = frozen.acquisitionWork() + frozen.sessionSetupWork();
        long cumulativeWork = cumulative.acquisitionWork() + cumulative.sessionSetupWork();
        long baselineWork = 0;
        int solved = 0;
        var rows = new ArrayList<String>();
        rows.add("round,case,baseline_solved,baseline_work,frozen_solved,frozen_work,cumulative_solved,cumulative_work");
        for (int round = 0; round < 8; round++) {
            String u = "u" + round, v = "v" + round, w = "w" + round, z = "z" + round;
            var requests = List.of(factor(u + "^4+4*" + v + "^4"),
                squares("(" + u + "^2+" + v + "^2)*(" + w + "^2+" + z + "^2)"),
                factor("(" + u + "+1)*(" + v + "+2)"), factor(u + "^2+2"));
            for (int index = 0; index < requests.size(); index++) {
                var request = requests.get(index);
                var b = baseline.solve(request, CumulativeTaskKnowledge.Strategy.BASELINE);
                var f = frozen.solve(request, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
                var c = cumulative.solve(request, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
                require(b.solved() == f.solved() && f.solved() == c.solved(), "matched task success in every arm, including negative cases");
                baselineWork = Math.addExact(baselineWork, b.totalWork());
                frozenWork = Math.addExact(frozenWork, f.totalWork());
                cumulativeWork = Math.addExact(cumulativeWork, c.totalWork());
                if (b.solved()) solved++;
                rows.add(round + "," + index + "," + b.solved() + "," + b.totalWork() + "," + f.solved() + "," + f.totalWork() + "," + c.solved() + "," + c.totalWork());
            }
        }
        Files.write(directory.resolve("mixed-stream.csv"), rows);
        require(cumulativeWork < baselineWork && cumulativeWork < frozenWork, "development stream pays measured acquisition against the same construction baseline and frozen control");
        String summary = "STRUCTURAL_STREAM queries=32 solvedEach=" + solved + " baseline=" + baselineWork
            + " frozen=" + frozenWork + " cumulative=" + cumulativeWork + " acquisition=" + cumulative.acquisitionWork()
            + " setup=" + cumulative.sessionSetupWork() + " firstAcquisition=" + frozen.acquisitionWork()
            + " firstSetup=" + frozen.sessionSetupWork() + " generation=" + cumulative.generation() + " schemata=" + cumulative.schemaCount();
        System.out.println(summary);
        Files.writeString(directory.resolve("summary.txt"), summary + "\n");
        Files.writeString(directory.resolve("snapshot-path.txt"), cumulative.save(directory.resolve("cumulative")).toAbsolutePath().toString());
    }

    private static void restoredProcess(Path snapshot) throws Exception {
        var knowledge = CumulativeTaskKnowledge.load(TraceStrategyTransferExample.inventory(), snapshot);
        for (var request : List.of(factor("(a*b)^4+4*c^4"), squares("((r+1)^2+s^2)*(t^2+u^2)"))) {
            var execution = knowledge.solve(request, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            require(execution.solved() && execution.solution().orElseThrow().learnedApplications() > 0, "new JVM uses checked saved mathematics on compound substitutions without training");
        }
        System.out.println("PASS fresh JVM restored both structural capabilities; setup=" + knowledge.sessionSetupWork());
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--restore")) restoredProcess(Path.of(args[1]));
        else if (args.length == 1) new StructuralLearningStreamTest().cumulativeMixedStreamPaysItsAcquisition(Path.of(args[0]));
        else throw new IllegalArgumentException("expected report-directory or --restore snapshot");
    }
}
