package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.Test;

class MoveSearchDominanceTest {
    // These are search-contract graph fixtures, not mathematical learning benchmarks.
    private static final MoveVerifier GRAPH = (source, move, context) ->
        new MoveVerifier.Verification(true, 3, List.of("declared-graph-edge"), "FIXTURE");

    @Test void aShortcutReplacesRatherThanDuplicatesTheSameContinuation() {
        var counts = new HashMap<String, Integer>();
        var problem = problem(counts, true, state -> 0, GRAPH, MoveSearch.Mode.FAST);
        var baseline = new MoveSearch().search(problem);
        assertEquals(2, counts.get("b"));
        counts.clear();
        var replaced = new MoveSearch().search(problem, SearchContinuationContract.DECLARED_STATE_LOCAL);
        assertEquals(1, counts.get("b"), "the expensive route must not expand b a second time");
        assertEquals(expressions(baseline), expressions(replaced));
        assertTrue(replaced.metrics().totalWork() < baseline.metrics().totalWork(),
            "the entire charged index overhead must be paid, not only count fewer expansions");
        assertTrue(replaced.events().stream().anyMatch(event -> event.decision().name().equals("DOMINATED")));
        assertFalse(replaced.completeBoundedRelation());
        System.out.println("DOMINANCE_DIAGNOSTIC baseline=" + baseline.metrics().totalWork()
            + " replacement=" + replaced.metrics().totalWork() + " expanded="
            + baseline.metrics().expandedStates() + "/" + replaced.metrics().expandedStates());
    }

    @Test void laterCheaperArrivalInvalidatesAnAlreadyQueuedContinuation() {
        var counts = new HashMap<String, Integer>();
        var problem = problem(counts, false,
            state -> state.expression().equals("b") && state.searchDepth() > 1 ? 1000 : 0,
            GRAPH, MoveSearch.Mode.FAST);
        var result = new MoveSearch().search(problem, SearchContinuationContract.DECLARED_STATE_LOCAL);
        assertEquals(1, counts.get("b"));
        assertEquals(1, result.reachedStates().stream().filter(s -> s.expression().equals("b")).count());
        assertTrue(result.events().stream().anyMatch(e -> e.target().expression().equals("b")
            && e.target().searchDepth() == 2 && e.decision() == MoveSearch.Decision.ENQUEUED));
    }

    @Test void rejectedCheapArrivalCannotSuppressAnAdmittedLongerRoute() {
        var counts = new HashMap<String, Integer>();
        MoveVerifier verifier = (source, move, context) -> source.expression().equals("root")
                && move.transformation().transformedExpression().equals("b")
            ? new MoveVerifier.Verification(false, 3, List.of(), "FORGED_SHORTCUT")
            : GRAPH.verify(source, move, context);
        var result = new MoveSearch().search(problem(counts, true, state -> 0, verifier, MoveSearch.Mode.FAST),
            SearchContinuationContract.DECLARED_STATE_LOCAL);
        assertEquals(1, counts.get("b"));
        assertTrue(result.reachedStates().stream().anyMatch(s -> s.expression().equals("z39")));
        assertTrue(result.events().stream().anyMatch(e -> e.decision() == MoveSearch.Decision.PROOF_REJECTED));
    }

    @Test void defaultPathSensitiveSearchIsExactlyPreserved() {
        var first = new MoveSearch().search(problem(new HashMap<>(), true, s -> 0, GRAPH, MoveSearch.Mode.FAST));
        var explicit = new MoveSearch().search(problem(new HashMap<>(), true, s -> 0, GRAPH, MoveSearch.Mode.FAST),
            SearchContinuationContract.PATH_SENSITIVE);
        assertEquals(first, explicit);
    }

    @Test void completeReferenceCannotSilentlyEnableDominance() {
        assertThrows(IllegalArgumentException.class, () -> new MoveSearch().search(
            problem(new HashMap<>(), true, s -> 0, GRAPH, MoveSearch.Mode.COMPLETE_BOUNDED_REFERENCE),
            SearchContinuationContract.DECLARED_STATE_LOCAL));
    }

    private static java.util.Set<String> expressions(MoveSearch.Result result) {
        return result.reachedStates().stream().map(MoveState::expression).collect(java.util.stream.Collectors.toSet());
    }

    private static MoveSearch.Problem problem(Map<String, Integer> counts, boolean shortcutFirst,
            ToDoubleFunction<MoveState> score, MoveVerifier verifier, MoveSearch.Mode mode) {
        var descriptor = new MoveProvider.Descriptor("graph", "graph", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "fixture");
        var provider = new EngineMoveProvider(descriptor, source -> {
            counts.merge(source, 1, Integer::sum);
            var targets = new ArrayList<String>();
            if (source.equals("root")) targets.addAll(shortcutFirst ? List.of("b", "a") : List.of("a", "b"));
            else if (source.equals("a")) targets.add("b");
            else if (source.equals("b")) targets.add("z0");
            else if (source.startsWith("z")) {
                int index = Integer.parseInt(source.substring(1));
                if (index < 39) targets.add("z" + (index + 1));
            }
            return targets.stream().map(target -> new Transformation(
                (source.equals("root") ? (target.equals(shortcutFirst ? "b" : "a") ? "0-" : "1-") : "")
                    + source + "-" + target, target)).toList();
        }, true);
        return new MoveSearch.Problem("root", MoveContext.frozen("absent"), List.of(provider),
            MovePriorityPolicy.INVENTORY_ORDER, verifier, score, mode, MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(100, 100, 0, 500, 100000));
    }
}
