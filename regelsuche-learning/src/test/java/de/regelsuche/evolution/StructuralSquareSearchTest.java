package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Source-only tasks: no expected expression or ordered transformation path enters the solver. */
@Timeout(180)
class StructuralSquareSearchTest {
    private static final MoveSearch.Budget BUDGET =
        new MoveSearch.Budget(32, 7, 10_000_000, 512, 10_000_000);

    @Test
    void findsExactSquareRepresentationsForHistoricalInputVariants() {
        var goal = StructuralTaskObjective.PERFECT_SQUARE;
        var profile = TaskConstructionProfile.STRUCTURAL_SQUARE_V1;
        var knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(), profile);
        var exact = new ExactPolynomialAnalysis();
        for (String source : List.of(
                "x*x+2*x*y+y*y", "x*x+y*y+2*y*x", "y*y+(2*x)*y+x*x",
                "x*x+3*x*a+2.25*a*a", "x*x+(4/3)*x*y+(4/9)*y*y")) {
            var task = new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(),
                List.of(), goal.goal(), BUDGET);
            var result = knowledge.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
            assertTrue(result.solved(), () -> "bounded source-only square search did not solve: " + source);
            var evidence = result.solution().orElseThrow().evidence();
            assertTrue(evidence.withinBudget());
            assertFalse(evidence.witness().isEmpty(), "the source is not already a square");
            assertTrue(StructuralTaskObjective.isSquare(evidence.incumbent().expression()));
            exact.requireEquivalent(source, ExpressionFormatter.format(evidence.incumbent().expression()));
            System.out.println("SQUARE_HISTORY source=" + source + " work=" + result.totalWork()
                + " steps=" + evidence.witness().size() + " output=" + ExpressionFormatter.format(evidence.incumbent().expression()));
        }
    }

    @Test
    void rationalMonomialExposureIsExactAndRejectsNonsquares() {
        var operation = StructuralConstructionPrograms.Operation.RATIONAL_EXPOSE_SQUARE;
        var parser = new ExpressionParser();
        var exact = new ExactPolynomialAnalysis();
        for (String source : List.of("(4/9)*y*y", "2.25*a*a")) {
            var report = StructuralConstructionPrograms.generate(operation, parser.parseExactTerm(source).expression());
            assertTrue(report.work() > 0);
            assertTrue(report.candidates().stream().anyMatch(candidate -> {
                exact.requireEquivalent(source, candidate);
                return StructuralTaskObjective.isSquare(parser.parseExactTerm(candidate).expression());
            }), source);
        }
        var negative = StructuralConstructionPrograms.generate(operation, parser.parseExactTerm("(2/3)*x*x").expression());
        assertTrue(negative.work() > 0);
        assertTrue(negative.candidates().isEmpty(), "an irrational coefficient must not be represented as an exact rational square");
    }

    @Test
    void goalRejectsNonsquaresAndSurvivesProfileRestoration() {
        var goal = StructuralTaskObjective.PERFECT_SQUARE;
        var genome = TraceStrategyTransferExample.inventory();
        var profile = TaskConstructionProfile.STRUCTURAL_SQUARE_V1;
        var initial = CumulativeTaskKnowledge.empty(genome, profile);
        var restored = CumulativeTaskKnowledge.restore(genome, initial.toCanonicalJson());
        assertEquals(profile, restored.constructionProfile());
        var task = new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm("x^2+3*x*y+y^2").expression(),
            List.of(), goal.goal(), new MoveSearch.Budget(16, 4, 1_000_000, 128, 200_000));
        var result = restored.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
        assertFalse(result.solved(), "a nonsquare cannot meet the exact perfect-square goal");
    }

    @Test
    void newProfileKeepsLegacySquareRecognitionForLargeExactProducts() {
        String factor = "1" + "0".repeat(60);
        String reciprocal = "0." + "0".repeat(59) + "1";
        String product = String.join("*", java.util.Collections.nCopies(6, factor)) + "*"
            + String.join("*", java.util.Collections.nCopies(6, reciprocal));
        String source = "(" + product + "*x)^2+2*" + product + "*x*y+y^2";
        var task = StructuralConstructionTest.task(source, StructuralTaskObjective.PERFECT_SQUARE);
        var solver = TaskConstructionProfile.STRUCTURAL_SQUARE_V1
            .attach(TypedLearnedMoveInventory.primitives(TraceStrategyTransferExample.inventory()).newTaskSolver());
        var oldRecognizer = solver.problem(task).providers().stream()
            .filter(provider -> provider.descriptor().id().endsWith(":RECOGNIZE_METERED_SQUARE"))
            .findFirst().orElseThrow(() -> new AssertionError("new profile discarded its legacy recognizer"));
        var state = de.regelsuche.search.moves.MoveState.root(
            new de.regelsuche.search.program.CompiledAstReplayCodec().encodeExpression(task.source()));
        var generated = StructuralConstructionPrograms.generate(StructuralConstructionPrograms.Operation.RECOGNIZE_SQUARE, task.source());
        assertFalse(generated.candidates().isEmpty());
        var metered = StructuralConstructionPrograms.generate(StructuralConstructionPrograms.Operation.RECOGNIZE_METERED_SQUARE, task.source());
        assertEquals(generated.candidates(), metered.candidates(), "metering must preserve the old matching behavior");
        assertTrue(metered.work() > generated.work(), "the additional arithmetic must be paid");
        new ExactPolynomialAnalysis().requireEquivalent(source, generated.candidates().getFirst());
        var batch = oldRecognizer.candidates(state, de.regelsuche.search.moves.MoveContext.frozen(""));
        assertFalse(batch.moves().isEmpty(), "the legacy matcher accepts this bounded exact product");
        assertTrue(solver.problem(task).providers().stream()
            .anyMatch(provider -> provider.descriptor().id().endsWith(":RECOGNIZE_RATIONAL_SQUARE")));
    }

    @Test
    void rationalCapabilityImprovesTheSameBoundedTaskWithoutFalseSuccess() {
        var inventory = TraceStrategyTransferExample.inventory();
        var before = CumulativeTaskKnowledge.empty(inventory, TaskConstructionProfile.STRUCTURAL_AUX_V1);
        var after = CumulativeTaskKnowledge.empty(inventory, TaskConstructionProfile.STRUCTURAL_SQUARE_V1);
        var budget = new MoveSearch.Budget(24, 6, 1_000_000, 128, 1_000_000);
        for (String source : List.of("x*x+3*x*a+2.25*a*a", "x*x+(4/3)*x*y+(4/9)*y*y")) {
            var task = new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(),
                List.of(), StructuralTaskObjective.PERFECT_SQUARE.goal(), budget);
            assertFalse(before.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE).solved());
            var result = after.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
            assertTrue(result.solved(), source);
            assertTrue(result.solution().orElseThrow().evidence().withinBudget());
            new ExactPolynomialAnalysis().requireEquivalent(source, ExpressionFormatter.format(
                result.solution().orElseThrow().evidence().incumbent().expression()));
        }
        var nearMiss = new CheckedSchemaTaskSolver.Task(
            new ExpressionParser().parseExactTerm("x*x+(5/3)*x*y+(4/9)*y*y").expression(), List.of(),
            StructuralTaskObjective.PERFECT_SQUARE.goal(), budget);
        assertFalse(after.solve(nearMiss, CumulativeTaskKnowledge.Strategy.BASELINE).solved());
        var tiny = new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm("x*x+2*x*y+y*y").expression(),
            List.of(), StructuralTaskObjective.PERFECT_SQUARE.goal(), new MoveSearch.Budget(24, 6, 1_000_000, 128, 1));
        assertFalse(after.solve(tiny, CumulativeTaskKnowledge.Strategy.BASELINE).solved(),
            "candidate generation and final replay must share the task budget");
    }

    @Test
    void completesRationalUnivariateSquaresInTheSameTaskSolver() {
        String source = "x^2+(4/3)*x+(4/9)";
        var task = StructuralConstructionTest.task(source, StructuralTaskObjective.PERFECT_SQUARE);
        var knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(),
            TaskConstructionProfile.STRUCTURAL_SQUARE_V1);
        var result = knowledge.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
        assertTrue(result.solved());
        assertFalse(result.solution().orElseThrow().evidence().witness().isEmpty());
        new ExactPolynomialAnalysis().requireEquivalent(source, ExpressionFormatter.format(
            result.solution().orElseThrow().evidence().incumbent().expression()));
        String withRemainder = "x^2+(4/3)*x+(1/3)";
        var generated = StructuralConstructionPrograms.generate(StructuralConstructionPrograms.Operation.RECOGNIZE_RATIONAL_SQUARE,
            new ExpressionParser().parseExactTerm(withRemainder).expression());
        assertFalse(generated.candidates().isEmpty());
        for (String candidate : generated.candidates()) {
            new ExactPolynomialAnalysis().requireEquivalent(withRemainder, candidate);
        }
    }

    @Test
    void directSquareGoalChargesPolynomialProductsEvenWithoutAConstructionProfile() {
        var source = new ExpressionParser().parseExactTerm("((x+y+z+w)^8)^2").expression();
        var task = new CheckedSchemaTaskSolver.Task(source, List.of(), StructuralTaskObjective.PERFECT_SQUARE.goal(),
            new MoveSearch.Budget(8, 2, 1_000_000, 32, 1_000));
        var result = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory())
            .solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
        assertFalse(result.solved(), "exact projection work must not disappear when using the goal without a profile");
        assertTrue(result.totalWork() > task.budget().totalWork());
        var wrapped = new CheckedSchemaTaskSolver.Task(source, List.of(),
            new CheckedSchemaTaskSolver.Goal(StructuralTaskObjective.PERFECT_SQUARE::evaluate, 0), task.budget());
        var wrappedResult = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(),
            TaskConstructionProfile.STRUCTURAL_SQUARE_V1).solve(wrapped, CumulativeTaskKnowledge.Strategy.BASELINE);
        assertFalse(wrappedResult.solved(), "the supplied square evaluator must report products through a callback too");
        assertTrue(wrappedResult.totalWork() > task.budget().totalWork());
    }

    @Test
    void learnedRationalSquareSchemaRepaysItsDevelopmentOnRelatedTasks() {
        var genome = TraceStrategyTransferExample.inventory();
        var initial = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.STRUCTURAL_SQUARE_V1);
        var goal = StructuralTaskObjective.PERFECT_SQUARE;
        var lessons = List.of(StructuralConstructionTest.task("x*x+3*x*a+2.25*a*a", goal),
            StructuralConstructionTest.task("(p+1)*(p+1)+3*(p+1)*q+2.25*q*q", goal));
        var validation = List.of(StructuralConstructionTest.task("(m+2)*(m+2)+3*(m+2)*n+2.25*n*n", goal));
        var update = initial.learnBatch(lessons, validation, 5_000_000);
        assertTrue(update.promoted(), () -> "schema did not pass the independent validation: " + update.reason());
        var restored = CumulativeTaskKnowledge.restore(genome, update.next().toCanonicalJson());
        assertEquals(TaskConstructionProfile.STRUCTURAL_SQUARE_V1, restored.constructionProfile());
        assertEquals(update.next().id(), restored.id());
        var invalid = StructuralConstructionTest.task("(x^2-(1/y))+(1/y)", goal);
        var rejected = org.junit.jupiter.api.Assertions.assertThrows(CheckedSchemaTaskSolver.UnsupportedTaskException.class,
            () -> restored.solve(invalid, CumulativeTaskKnowledge.Strategy.AUTOMATIC));
        assertTrue(rejected.inspectionWork() > 0 && rejected.routingWork() > 0,
            "an unsupported task retains both routing and source inspection work");
        var sink = new de.regelsuche.retention.RetainedOperation.Sink() {
            long work;
            @Override public void executionWork(long units) { work += units; }
            @Override public void validationWork(long units) { work += units; }
            @Override public void checkpoint() { }
            @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor visitor) { }
        };
        try (var scope = de.regelsuche.retention.RetainedOperation.open(sink)) {
            long before = sink.work;
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> restored.solve(invalid, CumulativeTaskKnowledge.Strategy.AUTOMATIC));
            assertEquals(before, sink.work, "nested observation is refused before routing work");
        }

        long baselineWork = initial.sessionSetupWork();
        long learnedWork = update.work().total() + restored.sessionSetupWork();
        var exact = new ExactPolynomialAnalysis();
        for (int offset = 3; offset <= 10; offset++) {
            String base = "(u+" + offset + ")";
            String source = base + "*" + base + "+3*" + base + "*v+2.25*v*v";
            var task = StructuralConstructionTest.task(source, goal);
            var before = initial.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
            var after = restored.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            assertTrue(before.solved() && after.solved());
            assertTrue(after.solution().orElseThrow().learnedApplications() > 0, "the checked schema must actually be used");
            for (var result : List.of(before, after)) {
                assertTrue(result.solution().orElseThrow().evidence().withinBudget());
                exact.requireEquivalent(source, ExpressionFormatter.format(
                    result.solution().orElseThrow().evidence().incumbent().expression()));
            }
            baselineWork += before.totalWork();
            learnedWork += after.totalWork();
        }
        for (String source : List.of("k^2", "u^2+3*u*v+v^2")) {
            var task = new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(),
                List.of(), goal.goal(), new MoveSearch.Budget(8, 2, 1_000_000, 32, 200_000));
            var before = initial.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
            var after = restored.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
            assertEquals(before.solved(), after.solved(), "already-solved and inconclusive controls keep their outcomes");
            assertEquals(source.equals("k^2"), before.solved());
            assertTrue(before.solution().orElseThrow().evidence().withinBudget());
            assertTrue(after.solution().orElseThrow().evidence().withinBudget());
            assertEquals(before.solution().orElseThrow().evidence().outputScore(),
                after.solution().orElseThrow().evidence().outputScore(), "the controls must retain equal quality");
            baselineWork += before.totalWork();
            learnedWork += after.totalWork();
        }
        assertTrue(learnedWork < baselineWork, "query savings must pay for acquisition, validation and restoration");
        System.out.println("SQUARE_LEARNING_STREAM baseline=" + baselineWork + " learned=" + learnedWork
            + " acquisition=" + update.work().total() + " restore=" + restored.sessionSetupWork());
    }
}
