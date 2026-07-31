package de.regelsuche.search.program;

import static de.regelsuche.search.program.RewritePrograms.byEstimatedCostThenRule;
import static de.regelsuche.search.program.RewritePrograms.choice;
import static de.regelsuche.search.program.RewritePrograms.firstApplicable;
import static de.regelsuche.search.program.RewritePrograms.prioritize;
import static de.regelsuche.search.program.RewritePrograms.prune;
import static de.regelsuche.search.program.RewritePrograms.repeat;
import static de.regelsuche.search.program.RewritePrograms.require;
import static de.regelsuche.search.program.RewritePrograms.sequence;
import static de.regelsuche.search.program.RewritePrograms.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.program.RewriteProgram.SourceLocation;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RewriteProgramInterpreterTest {
    private final RewriteProgramInterpreter interpreter =
        new RewriteProgramInterpreter();

    @Test
    void sequenceComposesOrdinaryTransformationsIntoOneSearchCandidate() {
        RewriteProgram program = sequence(
            "normalize-and-finish",
            source("normalize", engineFor(
                "x", transformation(
                    "normalize-x", "a", 2, true, "x != 0", "base", "MIT")
            )),
            source("finish", engineFor(
                "a", transformation(
                    "finish-a", "b", -1, true, "y >= 0", "extension", "Apache-2.0")
            ))
        );

        RewriteExecution execution = interpreter.execute(program, "x");

        assertTrue(execution.complete());
        assertEquals(1, execution.candidates().size());
        RewriteCandidate candidate = execution.candidates().get(0);
        assertEquals("b", candidate.outputExpression());
        assertEquals(List.of("normalize-x", "finish-a"), candidate.ruleIds());

        Transformation composed = candidate.toTransformation();
        assertEquals(
            "program:normalize-and-finish[normalize-x -> finish-a]",
            composed.rule()
        );
        assertEquals(1, composed.estimatedCostDelta());
        assertEquals(List.of("x != 0", "y >= 0"), composed.assumptions());
        assertEquals("base+extension", composed.packId());
        assertEquals("MIT+Apache-2.0", composed.license());
    }

    @Test
    void firstApplicableDoesNotEvaluateLaterAlternatives() {
        AtomicInteger skippedInvocations = new AtomicInteger();
        RewriteProgram program = firstApplicable(
            "fallback",
            source("empty", expression -> List.of()),
            source("selected", engineFor(
                "x", new Transformation("selected-rule", "a")
            )),
            source("must-not-run", expression -> {
                skippedInvocations.incrementAndGet();
                return List.of(new Transformation("late-rule", "z"));
            })
        );
        RewriteTraceCollector trace = new RewriteTraceCollector();

        RewriteExecution execution = interpreter.execute(
            program, "x", RewriteTraceLevel.FULL, trace);

        assertEquals(List.of("a"), outputs(execution));
        assertEquals(0, skippedInvocations.get());
        assertTrue(trace.events().stream().anyMatch(event ->
            event.type() == RewriteTraceEventType.ALTERNATIVE_SELECTED
                && event.detail().equals("selected")));
        assertTrue(trace.events().stream().anyMatch(event ->
            event.type() == RewriteTraceEventType.ALTERNATIVE_SKIPPED
                && event.nodeId().equals("must-not-run")));
    }

    @Test
    void requirePrioritizeAndPruneHaveDistinctVisibleSemantics() {
        RewriteProgram alternatives = choice(
            "alternatives",
            source("unordered", expression -> List.of(
                new Transformation(
                    "rule_z", "z", RewriteKind.NORMALIZE,
                    false, 0, true, "rule_z:z"),
                new Transformation(
                    "rule_a", "a", RewriteKind.NORMALIZE,
                    false, 5, true, "rule_a:a"),
                new Transformation(
                    "unsafe", "u", RewriteKind.NORMALIZE,
                    false, -5, false, "unsafe:u")
            ))
        );
        RewriteProgram program = prune(
            "one-candidate",
            prioritize(
                "cheapest-first",
                require(
                    "sound-only",
                    alternatives,
                    "equivalence preserving by construction",
                    RewritePrograms.equivalencePreserving()
                ),
                "estimated cost, then rule id",
                byEstimatedCostThenRule()
            ),
            1,
            "search profile candidate budget"
        );
        RewriteTraceCollector trace = new RewriteTraceCollector();

        RewriteExecution execution = interpreter.execute(
            program, "x", RewriteTraceLevel.FULL, trace);

        assertEquals(List.of("z"), outputs(execution));
        assertFalse(execution.complete());
        assertTrue(trace.events().stream().anyMatch(event ->
            event.type() == RewriteTraceEventType.CANDIDATE_REJECTED
                && event.outputExpression().equals("u")));
        assertTrue(trace.events().stream().anyMatch(event ->
            event.type() == RewriteTraceEventType.CANDIDATES_PRUNED
                && event.candidateCount() == 1
                && !event.complete()));
    }

    @Test
    void repeatRetainsEveryBoundedEndpoint() {
        TransformationEngine increment = expression -> switch (expression) {
            case "x" -> List.of(new Transformation("step-1", "x + 1"));
            case "x + 1" -> List.of(new Transformation("step-2", "x + 2"));
            default -> List.of();
        };
        RewriteProgram program = repeat(
            "bounded-increment",
            1,
            3,
            source("increment", increment)
        );

        RewriteExecution execution = interpreter.execute(program, "x");

        assertTrue(execution.complete());
        assertEquals(List.of("x + 1", "x + 2"), outputs(execution));
        assertEquals(
            List.of("step-1", "step-2"),
            execution.candidates().get(1).ruleIds()
        );
    }

    @Test
    void tracingDoesNotChangeCandidatesAndProgrammedEngineWorksWithSearch() {
        RewriteProgram program = source(
            "remove-zero",
            "Remove additive zero",
            SourceLocation.at("builtin-program.java", 12, 5),
            engineFor("x + 0", new Transformation("remove-zero", "x"))
        );
        RewriteTraceCollector trace = new RewriteTraceCollector();

        RewriteExecution withoutTrace = interpreter.execute(program, "x + 0");
        RewriteExecution withTrace = interpreter.execute(
            program, "x + 0", RewriteTraceLevel.FULL, trace);

        assertEquals(withoutTrace.transformations(), withTrace.transformations());
        assertTrue(trace.events().stream().allMatch(event ->
            event.sourceLocation().equals(
                SourceLocation.at("builtin-program.java", 12, 5))));

        SearchProblem problem = new SearchProblem(
            "x + 0",
            new ProgrammedTransformationEngine(program),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(2, 16, 1, 1, 8, 4)
        );
        List<SearchState> states = new BestFirstSearchStrategy().search(problem);

        assertTrue(states.stream().anyMatch(state -> state.expression().equals("x")));
    }

    private static TransformationEngine engineFor(
        String input,
        Transformation transformation
    ) {
        return expression -> expression.equals(input)
            ? List.of(transformation)
            : List.of();
    }

    private static Transformation transformation(
        String rule,
        String output,
        int cost,
        boolean equivalencePreserving,
        String assumption,
        String packId,
        String license
    ) {
        return new Transformation(
            rule,
            output,
            RewriteKind.NORMALIZE,
            false,
            cost,
            equivalencePreserving,
            rule + ":" + output,
            List.of(assumption),
            packId,
            license
        );
    }

    private static List<String> outputs(RewriteExecution execution) {
        return execution.candidates().stream()
            .map(RewriteCandidate::outputExpression)
            .toList();
    }
}
