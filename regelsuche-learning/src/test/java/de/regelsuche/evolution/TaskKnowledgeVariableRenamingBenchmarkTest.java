package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Paired development comparison on fixed algebraic families, including a negative control. */
@EnabledIfSystemProperty(named = "regelsuche.measureVariableRenaming", matches = "true")
class TaskKnowledgeVariableRenamingBenchmarkTest {
    private static final int BLOCKS = 16;
    private static final int REPETITIONS = 3;
    private static final CumulativeTaskKnowledge.Strategy STRATEGY = CumulativeTaskKnowledge.Strategy.BASELINE;

    private enum Profile { ORDINARY, EXACT_PATH, VARIABLE_RENAMING }
    private enum Scenario { REPEATED, MIXED }
    private record Input(CheckedSchemaTaskSolver.Task task, boolean solvable) { }
    private record Output(boolean solved, long score, Expr incumbent) { }
    private record Measurement(long work, long wallNanos, long cpuNanos, long reuses, long transfers, List<Output> outputs) { }

    @Test void comparePaidQueryWorkAndTimingAcrossFixedStreams() {
        var meter = TaskKnowledgeSelfImprover.systemMeter();
        var inventory = TraceStrategyTransferExample.inventory();
        for (var scenario : Scenario.values()) {
            var inputs = inputs(scenario);
            // Run all arms once before collecting timings from this shared process.
            for (var profile : Profile.values()) measure(inputs, profile, inventory, meter);
            for (int repetition = 0; repetition < REPETITIONS; repetition++) {
                var run = new Measurement[Profile.values().length];
                for (int index = 0; index < run.length; index++) {
                    var profile = Profile.values()[(index + repetition) % run.length];
                    run[profile.ordinal()] = measure(inputs, profile, inventory, meter);
                    audit(inputs, run[profile.ordinal()]);
                }
                for (var profile : Profile.values()) {
                    var result = run[profile.ordinal()];
                    for (int index = 0; index < inputs.size(); index++) {
                        assertEquals(run[Profile.ORDINARY.ordinal()].outputs().get(index).score(), result.outputs().get(index).score());
                    }
                    System.out.printf("VARIABLE_RENAMING %s %d %s queries=%d reuses=%d transfers=%d work=%d wall_ns=%d cpu_ns=%d%n",
                        scenario, repetition + 1, profile, inputs.size(), result.reuses(), result.transfers(), result.work(),
                        result.wallNanos(), result.cpuNanos());
                }
            }
        }
    }

    private static List<Input> inputs(Scenario scenario) {
        var inputs = new ArrayList<Input>();
        for (int block = 0; block < BLOCKS; block++) {
            int names = scenario == Scenario.REPEATED || block % 3 == 0 ? 0 : block;
            String left = "left" + names;
            String right = "right" + names;
            String prefix = "guard" + names + "_";
            inputs.add(input(CumulativeTaskKnowledgeContract.nested(left + "*" + right, 2, prefix) + "+11", 9, true));
            inputs.add(input(CumulativeTaskKnowledgeContract.nested(left + "*" + right, 3, prefix) + "+13", 11, true));
            inputs.add(input("((" + left + "+0)*1)+0", 1, true));
            inputs.add(input("(" + left + "+0)*(" + right + "+0)", 3, true));
            int constant = scenario == Scenario.REPEATED ? 17 : 17 + block;
            inputs.add(input(left + "+" + right + "+" + constant, 1, false));
        }
        return List.copyOf(inputs);
    }

    private static Input input(String source, int goal, boolean solvable) {
        return new Input(TaskKnowledgeSessionTest.task(source, goal), solvable);
    }

    private static Measurement measure(List<Input> inputs, Profile profile, EvolutionGenome inventory,
            TaskKnowledgeSelfImprover.Meter meter) {
        var start = meter.now();
        var generation = CumulativeTaskKnowledge.emptyPrepared(inventory, TaskConstructionProfile.NONE);
        long work = generation.sessionSetupWork();
        long reuses = 0;
        long transfers = 0;
        var outputs = new ArrayList<Output>();
        var seen = new HashSet<Expr>();
        try (var session = session(generation, profile)) {
            for (var input : inputs) {
                var query = session.solve(input.task(), STRATEGY);
                work = Math.addExact(work, query.totalWork());
                boolean distinct = seen.add(input.task().source());
                if (query.reused()) {
                    reuses++;
                    if (distinct) transfers++;
                }
                var evidence = query.execution().solution().orElseThrow().evidence();
                outputs.add(new Output(query.solved(), evidence.outputScore(), evidence.incumbent().expression()));
            }
            assertEquals(session.statistics().knownWork(), work - generation.sessionSetupWork());
            assertTrue(session.statistics().accountingComplete());
        }
        var end = meter.now();
        return new Measurement(work, end.wallNanos() - start.wallNanos(),
            start.cpuNanos() < 0 || end.cpuNanos() < start.cpuNanos() ? -1 : end.cpuNanos() - start.cpuNanos(), reuses, transfers, outputs);
    }

    private static TaskKnowledgeQuerySession session(CumulativeTaskKnowledge generation, Profile profile) {
        return switch (profile) {
            case ORDINARY -> new TaskKnowledgeQuerySession(generation, 0, 0);
            case EXACT_PATH -> new TaskKnowledgeQuerySession(generation, 32, 1_000_000);
            case VARIABLE_RENAMING -> TaskKnowledgeQuerySession.withVariableRenaming(generation, 32, 1_000_000);
        };
    }

    private static void audit(List<Input> inputs, Measurement arm) {
        var analysis = new ExactPolynomialAnalysis();
        for (int index = 0; index < inputs.size(); index++) {
            var input = inputs.get(index);
            var output = arm.outputs().get(index);
            assertEquals(input.solvable(), output.solved());
            analysis.requireEquivalent(ExpressionFormatter.format(input.task().source()),
                ExpressionFormatter.format(output.incumbent()));
        }
    }
}
