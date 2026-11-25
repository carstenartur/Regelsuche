package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.search.program.BudgetedTransformationSource.Status;
import de.regelsuche.search.program.RewriteProgramInterpreter;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResidualPlanComparisonTest {
    @Test void trainingFreezesAnEndpointFreePolicyBeforeAnyHeldOutWork(@TempDir Path dir) throws Exception {
        ResidualPlanComparison.train(dir);
        assertTrue(Files.exists(dir.resolve("train.json")));
        assertFalse(Files.exists(dir.resolve("validation.json")));
        assertFalse(Files.exists(dir.resolve("final.json")));
        String plan = Files.readString(dir.resolve("plan.json"));
        assertFalse(plan.contains("a*c"));
        assertThrows(IllegalArgumentException.class, () -> ResidualPlanComparison.evaluate("validation", dir, SolverIr.sha256("wrong")));
    }
    @Test void detachedRegenerationPreservesEveryArmAndBothHistoricalMatches(@TempDir Path dir) throws Exception {
        Path receipts = Path.of("..", "docs", "research", "residual-plan-comparison-v1");
        ResidualPlanComparison.train(dir);
        String planHash = SolverIr.sha256(Files.readString(dir.resolve("plan.json")));
        ResidualPlanComparison.evaluate("validation", dir, planHash);
        ResidualPlanComparison.evaluate("final", dir, planHash);
        String finalHash = SolverIr.sha256(Files.readString(receipts.resolve("final.json")));
        assertThrows(IllegalArgumentException.class, () -> ResidualCorrespondence.observe(dir, SolverIr.sha256("replaced")));
        ResidualCorrespondence.observe(dir, finalHash);
        for (String name : java.util.List.of("train", "plan", "validation", "final", "correspondence")) {
            assertEquals(-1L, Files.mismatch(receipts.resolve(name + ".json"), dir.resolve(name + ".json")), name);
        }
        var matches = new ObjectMapper().readTree(dir.resolve("correspondence.json").toFile()).required("matches");
        assertEquals(12, matches.size());
        matches.forEach(row -> assertEquals(1, row.required("roots").size()));
    }

    private static final String SOURCE = "(a^2+b^2)*(c^2+d^2)";
    private static final List<String> IDS = List.of("plus-0-3", "reflected-1-2");

    @Test void onlyReplayedSourceIssuedOccurrencesCanCrossTheProgramBoundary() {
        var checked = ResidualPlanCompiler.bind(SOURCE, IDS, 100_000);
        assertEquals(SchematicProofPlanResolution.ResolutionState.COMPLETE_REFERENCES, checked.resolution().state());
        assertEquals(4, checked.resolution().outcomes().size());
        assertEquals("sha256:2364155f5de74e84534c2450a29dbc967877941b077392a17e992574b6ae1035", checked.proofRoot());
        assertEquals(List.of("0.0.0|1", "0.0.1|0.1"), checked.resolution().bindings().stream()
            .filter(binding -> binding.sort() == SchematicProofPlan.HoleSort.OCCURRENCE_PAIR)
            .map(SchematicProofPlanResolution.HoleBinding::canonicalValue).toList());
        assertEquals(2, checked.effects().size());
        assertEquals(3, checked.primitiveRules().size());
        assertEquals(3, checked.applicationKeys().size());
        var program = ResidualPlanCompiler.compile(checked, checked.checkWork());
        var interpreter = new RewriteProgramInterpreter();
        var run = interpreter.executeBudgetedSource(program, SOURCE, checked.checkWork());
        assertEquals(checked.expression(), run.sourceExecution().candidates().getFirst().transformedExpression());
        assertEquals(checked.checkWork(), run.sourceExecution().candidates().getFirst().mathematicalWorkUnits());
        assertEquals(Status.NO_MATCH, interpreter.executeBudgetedSource(program, "x", checked.checkWork()).sourceExecution().status());
        assertEquals(Status.BUDGET_INCONCLUSIVE, interpreter.executeBudgetedSource(program, SOURCE, checked.checkWork()-1).sourceExecution().status());
        assertThrows(IllegalArgumentException.class, () -> ResidualPlanCompiler.compile(checked, checked.checkWork()-1));
    }

    @Test void overlappingPartialAndFabricatedBindingsAndSubstitutedCertificatesAreRejected() {
        for (var ids : List.of(List.of("plus-0-3"), List.of("plus-0-3","reflected-0-2"),
                List.of("plus-0-3","forged"), List.of("plus-0-3","plus-1-2"))) {
            assertThrows(IllegalArgumentException.class, () -> ResidualPlanCompiler.bind(SOURCE, ids, 100_000), ids.toString());
        }
        var checked = ResidualPlanCompiler.bind(SOURCE, IDS, 100_000);
        var fake = new ResidualPlanCompiler.Bound(checked.source(), checked.plan(), checked.resolution(), checked.effects(),
            checked.expression(), List.of("forgedRule"), checked.applicationKeys(), checked.assumptions(),
            checked.proofRoot(), checked.checkWork());
        assertThrows(IllegalArgumentException.class, () -> ResidualPlanCompiler.compile(fake, 100_000));
        var partial = SchematicProofPlanResolution.create(checked.plan(), List.of(), List.of());
        var incomplete = new ResidualPlanCompiler.Bound(checked.source(), checked.plan(), partial, checked.effects(), checked.expression(),
            checked.primitiveRules(), checked.applicationKeys(), checked.assumptions(), checked.proofRoot(), checked.checkWork());
        assertThrows(IllegalArgumentException.class, () -> ResidualPlanCompiler.compile(incomplete, 100_000));
        assertThrows(IllegalArgumentException.class, () -> ResidualPlanCompiler.bind("a^2+b^2+c^2+d^2", IDS, 100_000));
    }



    @Test void suspendedPendingProofHasDifferentFuturesAndNoAuthorityUntilChecked() {
        var fast = ResidualPlanController.open(SOURCE, ResidualPlanController.Mode.DISCOVERY, 7, 100_000);
        var reference = ResidualPlanController.open(SOURCE, ResidualPlanController.Mode.COMPLETE_REFERENCE, 7, 100_000);
        assertNotEquals(fast.state().key(), reference.state().key());
        var initial = fast.state();
        assertEquals(0, initial.unresolvedObligations());
        assertTrue(initial.preparedGrowthCharacters() > 0);
        assertThrows(IllegalArgumentException.class, () -> fast.compileAccepted("forged", 100_000));
        var trace = new ArrayList<String>();
        while (!fast.state().done()) {
            var before = fast.state();
            var step = fast.advance();
            if (fast.state().unresolvedObligations() == 4) {
                var open = fast.openPlan().orElseThrow();
                assertEquals(4, open.holes().size());
                assertEquals(4, open.obligations().size());
                assertTrue(step.proofRoots().isEmpty());
                assertNotEquals(before.key(), fast.state().key());
            }
            for (String root : step.proofRoots()) {
                var bound = ResidualPlanCompiler.bind(SOURCE, step.effects(), 100_000);
                var program = fast.compileAccepted(root, 100_000);
                long total = Math.addExact(fast.state().work(), bound.checkWork());
                var interpreter = new de.regelsuche.search.program.RewriteProgramInterpreter();
                assertTrue(interpreter.executeBudgetedSource(program, SOURCE, total-1).candidates().isEmpty());
                var accepted = interpreter.executeBudgetedSource(program, SOURCE, total).sourceExecution().candidates().getFirst();
                assertEquals(total, accepted.mathematicalWorkUnits());
                assertEquals(root, accepted.applicationKey());
            }
            trace.addAll(step.proofRoots());
        }
        List<String> roots = trace.stream().sorted().toList();
        var ordinary = new ResidualPlanController(SOURCE).run(ResidualPlanController.Arm.LEARNED,7,0);
        assertEquals(ordinary.candidates().stream().map(ResidualPlanController.Candidate::root).toList(), roots);
        assertEquals(ordinary.work(), fast.state().work());
        var complete = new ArrayList<String>();
        while (!reference.state().done()) complete.addAll(reference.advance().proofRoots());
        assertEquals(roots, complete.stream().sorted().toList());
        assertTrue(fast.state().work() < reference.state().work());
        // Same exact roots can have different export work and authority after different enumeration prefixes.
        assertNotEquals(fast.state().key(), reference.state().key());
        var exhausted = ResidualPlanController.open(SOURCE, ResidualPlanController.Mode.DISCOVERY, 7, 1);
        assertTrue(exhausted.state().done());
        assertNotEquals(fast.state().key(), exhausted.state().key());
        assertThrows(IllegalArgumentException.class, () -> exhausted.compileAccepted(roots.getFirst(), 100_000));
        assertNotNull(fast.compileAccepted(roots.getFirst(), 100_000));
        assertThrows(IllegalStateException.class, fast::advance);
    }

    @Test void budgetExhaustionAndNonSquareSourcesCannotAuthorizeAnEdge() {
        var limited = ResidualPlanController.open(SOURCE, ResidualPlanController.Mode.DISCOVERY, 7, 1);
        assertTrue(limited.state().done());
        assertEquals("BUDGET_EXHAUSTED", limited.state().status());
        assertTrue(limited.state().work() <= 1);
        var unsupported = ResidualPlanController.open("a+b+c+d", ResidualPlanController.Mode.DISCOVERY, 7, 100_000);
        assertEquals("UNSUPPORTED", unsupported.state().status());
        assertEquals(0, unsupported.state().unresolvedObligations());
        assertThrows(IllegalArgumentException.class, () -> ResidualPlanController.open(SOURCE, ResidualPlanController.Mode.DISCOVERY, 8, 100_000));
    }
}
