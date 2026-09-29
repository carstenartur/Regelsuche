package de.regelsuche.search.program;

import static de.regelsuche.search.program.RewritePrograms.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RewriteProgramsTypedApiTest {
    private static final TransformationEngine EMPTY = expression -> List.of();

    @Test
    void materializesTheExistingIrWithStableDistinctStructuralIds() {
        var shared = source(EMPTY);
        var draft = firstApplicable(shared, sequence(shared, shared), shared);
        var root = assertInstanceOf(RewriteProgram.FirstApplicable.class, draft.named("strategy"));
        assertEquals("strategy", root.id());
        assertEquals(List.of("strategy/0", "strategy/1", "strategy/2"),
            root.alternatives().stream().map(RewriteProgram::id).toList());
        var nested = assertInstanceOf(RewriteProgram.Sequence.class, root.alternatives().get(1));
        assertEquals(List.of("strategy/1/0", "strategy/1/1"),
            nested.steps().stream().map(RewriteProgram::id).toList());
        assertSame(EMPTY, assertInstanceOf(RewriteProgram.Source.class, nested.steps().getFirst()).engine());
        assertEquals(root, draft.named("strategy"));
        assertNotEquals(root, draft.named("other-name"));
    }

    @Test
    void usesTheSameInterpreterAndProducesTheSameTransformations() {
        TransformationEngine normalize = expression -> expression.equals("x + 0")
            ? List.of(new Transformation("normalize", "x")) : List.of();
        var typed = firstApplicable(source(EMPTY), source(normalize)).named("demo");
        var explicit = firstApplicable("demo", source("demo/0", EMPTY), source("demo/1", normalize));
        var actual = new ProgrammedTransformationEngine(typed).transform("x + 0");
        assertFalse(actual.isEmpty());
        assertEquals(new ProgrammedTransformationEngine(explicit).transform("x + 0"), actual);
    }

    @Test
    void snapshotsChildrenAndSupportsEveryOrdinaryCombinator() {
        var children = new Draft[] { source(EMPTY), source(EMPTY) };
        var draft = choice(children);
        children[0] = null;
        assertInstanceOf(RewriteProgram.Choice.class, draft.named("choice"));
        var repeated = assertInstanceOf(RewriteProgram.Repeat.class, repeat(3, source(EMPTY)).named("repeat"));
        assertEquals(1, repeated.minIterations());
        assertEquals(3, repeated.maxIterations());
        assertEquals("repeat/0", repeated.body().id());
        assertEquals(2, assertInstanceOf(RewriteProgram.Repeat.class,
            repeat(2, 3, source(EMPTY)).named("range")).minIterations());
        var guarded = require(source(EMPTY), "preserves equivalence", equivalencePreserving());
        assertInstanceOf(RewriteProgram.Require.class, guarded.named("guard"));
        var ordered = prioritize(guarded, "lower cost first", byEstimatedCostThenRule());
        assertInstanceOf(RewriteProgram.Prioritize.class, ordered.named("order"));
        assertEquals(2, assertInstanceOf(RewriteProgram.Prune.class,
            prune(ordered, 2, "bounded sample").named("bounded")).maxCandidates());
    }

    @Test
    void rejectsInvalidConstructionWithoutInterpretingNamesAsProcedures() {
        assertThrows(IllegalArgumentException.class, () -> source(EMPTY).named(" "));
        assertThrows(IllegalArgumentException.class, () -> choice(new Draft[0]));
        assertThrows(NullPointerException.class, () -> choice((Draft[]) null));
        assertThrows(NullPointerException.class, () -> sequence(new Draft[] { null }));
        assertThrows(NullPointerException.class, () -> source((TransformationEngine) null));
        assertThrows(NullPointerException.class, () -> budgetedSource((BudgetedTransformationSource) null));
        assertThrows(IllegalArgumentException.class, () -> repeat(0, source(EMPTY)).named("bad"));
        assertThrows(IllegalArgumentException.class, () -> prune(source(EMPTY), 0, "bad").named("bad"));
        assertThrows(NullPointerException.class, () -> require(source(EMPTY), "condition", null));
        assertThrows(NullPointerException.class, () -> prioritize(source(EMPTY), "order", null));
        assertThrows(NullPointerException.class, () -> repeat(2, null));
    }

    @Test
    void prefersActualRuleObjectsAndPreservesUnlistedCandidates() {
        var preferred = new Rule("z-preferred");
        var second = new Rule("b-second");
        var values = List.of(candidate("a-unlisted"), candidate(second.id()), candidate(preferred.id()));
        var sorted = values.stream().sorted(preferRules(preferred, second)).toList();
        assertEquals(List.of("z-preferred", "b-second", "a-unlisted"),
            sorted.stream().map(value -> value.lastStep().rule()).toList());
        assertEquals(sorted, values.stream().sorted(preferRules(List.of(preferred, second))).toList());
        assertEquals(values.size(), sorted.size());
    }

    @Test
    void rejectsAmbiguousOrMissingRuleIdentities() {
        var rule = new Rule("same");
        assertThrows(IllegalArgumentException.class, () -> preferRules(rule, new Rule("same")));
        assertThrows(IllegalArgumentException.class, () -> preferRules(new Rule(" ")));
        assertThrows(IllegalArgumentException.class, () -> preferRules(new Rule(null)));
        assertThrows(NullPointerException.class, () -> preferRules((RewriteRule[]) null));
        assertThrows(NullPointerException.class, () -> preferRules((List<RewriteRule>) null));
        assertThrows(NullPointerException.class, () -> preferRules(Arrays.asList(rule, null)));
    }

    private static RewriteCandidate candidate(String rule) {
        return new RewriteCandidate("source", "x", "x", List.of(new Transformation(rule, "x")));
    }

    private record Rule(String id) implements RewriteRule {
        @Override public RewriteKind kind() { return RewriteKind.NORMALIZE; }
        @Override public boolean mayIncreaseComplexity() { return false; }
        @Override public int estimatedCostDelta() { return 0; }
        @Override public boolean isEquivalencePreservingByConstruction() { return true; }
        @Override public boolean matches(Expr subtree) { return true; }
        @Override public Expr apply(Expr subtree) { return subtree; }
    }
}
