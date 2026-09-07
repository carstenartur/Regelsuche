package de.regelsuche.search.program;

import static de.regelsuche.search.program.RewritePrograms.*;
import static de.regelsuche.search.program.BudgetedRewriteProgramExecution.WorkKind.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.search.program.BudgetedRewriteProgramExecution.ExactTheoryPath;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.ExplorationLimits;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.LimitKind;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.PathBudget;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.Status;
import de.regelsuche.search.program.BudgetedTransformationSource.ExactTheoryTransition;
import de.regelsuche.search.program.BudgetedTransformationSource.Result;
import de.regelsuche.search.program.BudgetedTransformationSource.SourceIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Protocol tests use controlled sources, not a claimed mathematical prover. */
class BudgetedRewriteProgramCompositionTest {
    private static final ExplorationLimits LIMITS = new ExplorationLimits(10000, 10000, 100);
    private final RewriteProgramInterpreter interpreter = new RewriteProgramInterpreter();

    @Test
    void choiceSharesPathAuthorityButCountsBothExecutions() {
        TestSource a = fixed("a", "x", "x + 0", 7);
        TestSource b = fixed("b", "x", "0 + x", 7);
        var result = run(choice("choose", budgetedSource("a", a), budgetedSource("b", b)), 7);
        assertEquals(Status.COMPLETE_WITH_CANDIDATES, result.status());
        assertEquals(List.of(7L), a.budgets);
        assertEquals(List.of(7L), b.budgets);
        assertEquals(2, result.candidates().size());
        assertEquals(2L, result.work().get(LEAF_EXECUTIONS).longValue());
        assertEquals(2L, result.work().get(ALTERNATIVES_EVALUATED).longValue());
        for (ExactTheoryPath path : result.candidates()) {
            assertEquals(7L, path.mathematicalWorkUnits());
            assertEquals(0L, path.primitiveRewriteSteps());
            assertTrue(path.primitiveRuleIds().isEmpty());
            assertEquals(1L, path.exactTheorySteps());
        }
        assertEquals(13L, result.availableBudget().primitiveRewriteUnits());
        assertTrue(result.sourceExecutions().stream()
            .allMatch(call -> call.availablePrimitiveRewriteUnits() == 13));
        assertEquals(result.work().values().stream().mapToLong(Long::longValue).sum(),
            result.totalMechanicalWorkUnits());
        assertEquals(result.sourceExecutions().stream()
            .mapToLong(call -> call.execution().mechanicalWork().totalMechanicalWorkUnits()).sum(),
            result.work().get(DELEGATED_MECHANICAL_WORK).longValue());
    }

    @Test
    void sequenceSucceedsAtExactSumAndRetainsOrderedEvidence() {
        TestSource a = fixed("a", "x", "x + 0", 7);
        TestSource b = fixed("b", "x + 0", "(x + 0) + 0", 5);
        var result = run(sequence("seq", budgetedSource("a", a), budgetedSource("b", b)), 12);
        assertTrue(result.complete());
        assertEquals(List.of(5L), b.budgets);
        var path = result.candidates().getFirst();
        assertEquals(12L, path.mathematicalWorkUnits());
        assertEquals(List.of("a", "b"), path.steps().stream().map(step -> step.node().id()).toList());
        assertEquals(List.of(hash("a"), hash("b")), path.steps().stream()
            .map(step -> step.transition().evidenceHash()).toList());
        assertEquals(2L, path.exactTheorySteps());
        assertEquals(0L, path.primitiveRewriteSteps());
    }

    @Test
    void sequenceOneUnitShortIsInconclusiveWithBoundBudgetBlock() {
        TestSource b = fixed("b", "x + 0", "(x + 0) + 0", 5);
        var result = run(sequence("seq", budgetedSource("a", fixed("a", "x", "x + 0", 7)),
            budgetedSource("b", b)), 11);
        assertEquals(Status.INCOMPLETE_WITHOUT_CANDIDATES, result.status());
        assertEquals(List.of(4L), b.budgets);
        var block = result.budgetBlocks().getFirst();
        assertEquals("b", block.nodeId());
        assertEquals("x + 0", block.inputExpression());
        assertEquals(4L, block.availableWorkUnits());
        assertEquals(5L, block.requiredWorkUnits());
        assertEquals(result.sourceExecutions().getLast().execution().contentHash(), block.sourceExecutionHash());
        assertEquals(result.sourceExecutions().getLast().prefixHash(), block.prefixHash());
        assertEquals(1L, result.work().get(BUDGET_BLOCKS).longValue());
    }

    @Test
    void branchingSequenceKeepsPerPrefixRemaindersAndPartialSuccess() {
        TestSource suffix = new TestSource("suffix", (self, expression, budget) ->
            reply(self, expression, expression + " + 0", budget, 3, List.of()));
        var result = run(sequence("seq", choice("branches",
            budgetedSource("cheap", fixed("cheap", "x", "x + 0", 1)),
            budgetedSource("costly", fixed("costly", "x", "0 + x", 4))),
            budgetedSource("suffix", suffix)), 5);
        assertEquals(List.of(4L, 1L), suffix.budgets);
        assertEquals(Status.INCOMPLETE_WITH_CANDIDATES, result.status());
        assertEquals(1, result.candidates().size());
        assertEquals(4L, result.candidates().getFirst().mathematicalWorkUnits());
        assertEquals(1, result.budgetBlocks().size());
    }

    @Test
    void firstApplicableSkipsOnlyCompleteEmptyAlternatives() {
        TestSource miss = fixed("miss", "other", "other + 0", 1);
        TestSource selected = fixed("selected", "x", "x + 0", 1);
        TestSource skipped = fixed("skipped", "x", "0 + x", 1);
        var result = run(firstApplicable("first", budgetedSource("miss", miss),
            budgetedSource("selected", selected), budgetedSource("skipped", skipped)), 1);
        assertTrue(result.complete());
        assertEquals(1, miss.calls);
        assertEquals(1, selected.calls);
        assertEquals(0, skipped.calls);
        assertEquals(1L, result.work().get(ALTERNATIVES_SKIPPED).longValue());
    }

    @Test
    void firstApplicableStopsAtIncompleteEmptyAlternative() {
        TestSource skipped = fixed("skipped", "x", "0 + x", 1);
        var result = run(firstApplicable("first",
            budgetedSource("blocked", fixed("blocked", "x", "x + 0", 2)),
            budgetedSource("skipped", skipped)), 1);
        assertEquals(Status.INCOMPLETE_WITHOUT_CANDIDATES, result.status());
        assertEquals(0, skipped.calls);
    }

    @Test
    void firstApplicableKeepsIncompleteCandidateSetWithoutTryingFallback() {
        TestSource skipped = fixed("skipped", "x", "x - 0", 1);
        var result = run(firstApplicable("first", choice("mixed",
            budgetedSource("blocked", fixed("blocked", "x", "x + 0", 2)),
            budgetedSource("ok", fixed("ok", "x", "0 + x", 1))),
            budgetedSource("skipped", skipped)), 1);
        assertEquals(Status.INCOMPLETE_WITH_CANDIDATES, result.status());
        assertEquals(1, result.candidates().size());
        assertEquals(0, skipped.calls);
    }

    @Test
    void laterCompleteMissDoesNotEraseEarlierIncompleteness() {
        var result = run(sequence("seq", choice("mixed",
            budgetedSource("blocked", fixed("blocked", "x", "x + 0", 2)),
            budgetedSource("ok", fixed("ok", "x", "0 + x", 1))),
            budgetedSource("miss", fixed("miss", "other", "other + 0", 1))), 1);
        assertEquals(Status.INCOMPLETE_WITHOUT_CANDIDATES, result.status());
    }

    @Test
    void completeNoMatchRemainsDifferentFromBudgetFailure() {
        var result = run(budgetedSource("miss", fixed("miss", "other", "other + 0", 1)), 0);
        assertEquals(Status.COMPLETE_WITHOUT_CANDIDATES, result.status());
        assertTrue(result.budgetBlocks().isEmpty());
    }

    @Test
    void repeatUsesTheRemainingBudgetAndRetainsEveryPermittedEndpoint() {
        TestSource source = appendZero("grow", 3);
        var result = run(repeat("repeat", 1, 2, budgetedSource("grow", source)), 6);
        assertTrue(result.complete());
        assertEquals(List.of(6L, 3L), source.budgets);
        assertEquals(List.of(3L, 6L), result.candidates().stream()
            .map(ExactTheoryPath::mathematicalWorkUnits).toList());
        assertEquals(List.of(1L, 2L), result.candidates().stream()
            .map(ExactTheoryPath::exactTheorySteps).toList());
    }

    @Test
    void repeatHonorsMinimumAndPreservesExhaustionAfterAValidEndpoint() {
        var program = repeat("repeat", 2, 3, budgetedSource("grow", appendZero("grow", 3)));
        var result = run(program, 6);
        assertEquals(Status.INCOMPLETE_WITH_CANDIDATES, result.status());
        assertEquals(List.of(6L), result.candidates().stream()
            .map(ExactTheoryPath::mathematicalWorkUnits).toList());
        assertEquals(0L, result.budgetBlocks().getFirst().availableWorkUnits());
    }

    @Test
    void cyclesReturnWithTheirRealCostRatherThanResettingAuthority() {
        TestSource cycle = new TestSource("cycle", (self, expression, budget) ->
            reply(self, expression, expression.equals("x") ? "x + 0" : "x", budget, 2, List.of()));
        var result = run(repeat("repeat", 3, budgetedSource("cycle", cycle)), 4);
        assertEquals(List.of(4L, 2L, 0L), cycle.budgets);
        assertEquals(Status.INCOMPLETE_WITH_CANDIDATES, result.status());
        assertEquals("x", result.candidates().getLast().transformedExpression());
        assertEquals(4L, result.candidates().getLast().mathematicalWorkUnits());
        assertEquals(2L, result.candidates().getLast().exactTheorySteps());
    }

    @Test
    void pruningIsDeterministicIncompleteAndDoesNotChargeDiscardedPathWork() {
        var program = prune("prune", choice("choice",
            budgetedSource("first", fixed("first", "x", "x + 0", 7)),
            budgetedSource("second", fixed("second", "x", "0 + x", 5))), 1, "first-in-program-order");
        var result = run(program, 7);
        assertEquals(Status.INCOMPLETE_WITH_CANDIDATES, result.status());
        assertEquals(7L, result.candidates().getFirst().mathematicalWorkUnits());
        assertEquals(5L, result.pruning().getFirst().removedPaths().getFirst().mathematicalWorkUnits());
        assertEquals(2, result.sourceExecutions().size());
        assertEquals(1L, result.work().get(PRUNED_PATHS).longValue());
        assertEquals(result.contentHash(), run(program, 7).contentHash());
    }

    @Test
    void unsupportedNodesAreRejectedBeforeAnySourceOrLambdaCallback() {
        TestSource valid = appendZero("valid", 1);
        AtomicInteger ordinaryCalls = new AtomicInteger();
        var ordinary = source("ordinary", expression -> { ordinaryCalls.incrementAndGet(); return List.of(); });
        for (RewriteProgram unsupported : List.of(ordinary,
                require("require", ordinary, "never evaluated", candidate -> { throw new AssertionError(); }),
                prioritize("prioritize", ordinary, "never evaluated", (a, b) -> { throw new AssertionError(); }))) {
            assertThrows(IllegalArgumentException.class, () -> run(
                choice("choice", budgetedSource("valid", valid), unsupported), 1));
        }
        assertEquals(0, valid.calls);
        assertEquals(0, valid.identityReads);
        assertEquals(0, ordinaryCalls.get());
    }

    @Test
    void duplicateStructuralIdsFailBeforeSourceCallbacks() {
        TestSource source = appendZero("source", 1);
        assertThrows(IllegalArgumentException.class, () -> run(choice("choice",
            budgetedSource("same", source), budgetedSource("same", source)), 1));
        assertEquals(0, source.identityReads);
        assertEquals(0, source.calls);
    }

    @Test
    void mutationOfAnotherSourceBetweenPreflightAndInvocationFailsClosed() {
        TestSource second = appendZero("second", 1);
        TestSource first = new TestSource("first", (self, expression, budget) -> {
            second.identity = new SourceIdentity("substitution", hash("revision"), hash("authority"));
            return reply(self, expression, expression + " + 0", budget, 1, List.of());
        });
        assertThrows(IllegalArgumentException.class, () -> run(sequence("sequence",
            budgetedSource("first", first), budgetedSource("second", second)), 2));
        assertEquals(0, second.calls);
    }

    @Test
    void mutationOfEvenAnUnselectedSourceInvalidatesTheFrozenProgram() {
        TestSource skipped = appendZero("skipped", 1);
        TestSource first = new TestSource("first", (self, expression, budget) -> {
            skipped.identity = new SourceIdentity("changed", hash("changed"), hash("authority"));
            return reply(self, expression, expression + " + 0", budget, 1, List.of());
        });
        assertThrows(IllegalArgumentException.class, () -> run(firstApplicable("first-applicable",
            budgetedSource("first", first), budgetedSource("skipped", skipped)), 1));
        assertEquals(0, skipped.calls);
    }

    @Test
    void forgedSourceResultIdentityAndOverflowAreRejected() {
        TestSource forged = new TestSource("forged", (self, expression, budget) -> Result.noMatch(
            new SourceIdentity("wrong", hash("wrong"), hash("wrong")), expression, budget, 0, "MISS"));
        assertThrows(IllegalArgumentException.class, () -> run(budgetedSource("forged", forged), 0));
        TestSource overflow = new TestSource("overflow", (self, expression, budget) ->
            Result.noMatch(self.identity, expression, budget, Long.MAX_VALUE, "MISS"));
        assertThrows(ArithmeticException.class, () -> run(budgetedSource("overflow", overflow), 0));
    }

    @Test
    void nodeLimitStopsBeforeSourceInvocationAndCannotClaimNoMatch() {
        TestSource source = appendZero("source", 1);
        var result = interpreter.executeBudgeted(choice("choice", budgetedSource("source", source)),
            "x", new PathBudget(0, 10), new ExplorationLimits(1, 10, 10));
        assertEquals(Status.INCOMPLETE_WITHOUT_CANDIDATES, result.status());
        assertEquals(LimitKind.NODE_VISITS, result.limitBlocks().getFirst().reason());
        assertEquals(0, source.calls);
    }

    @Test
    void extensionAndStepCeilingsKeepTheirPartialResultsAndCauses() {
        for (ExplorationLimits limits : List.of(new ExplorationLimits(100, 1, 10),
                new ExplorationLimits(100, 10, 1))) {
            var result = interpreter.executeBudgeted(repeat("repeat", 3,
                budgetedSource("grow", appendZero("grow", 1))), "x", new PathBudget(0, 100), limits);
            assertEquals(Status.INCOMPLETE_WITH_CANDIDATES, result.status());
            assertEquals(1, result.candidates().size());
            assertEquals(1L, result.candidates().getFirst().mathematicalWorkUnits());
            assertEquals(1, result.limitBlocks().size());
        }
    }

    @Test
    void hugeRepeatBoundCannotOverflowOrEvadeExplicitExplorationLimit() {
        var result = interpreter.executeBudgeted(repeat("repeat", Integer.MAX_VALUE,
            budgetedSource("grow", appendZero("grow", 1))), "x", new PathBudget(0, Long.MAX_VALUE),
            new ExplorationLimits(5, 100, 100));
        assertFalse(result.complete());
        assertEquals(4, result.candidates().size());
        assertEquals(LimitKind.NODE_VISITS, result.limitBlocks().getFirst().reason());
    }

    @Test
    void compositionPreservesAssumptionsInsteadOfMergingByExpressionAlone() {
        TestSource a = new TestSource("a", (self, expression, budget) ->
            reply(self, expression, expression + " + 0", budget, 1, List.of("x!=0")));
        TestSource b = new TestSource("b", (self, expression, budget) ->
            reply(self, expression, expression + " + 0", budget, 1, List.of("y > 0", "x != 0")));
        var result = run(sequence("seq", budgetedSource("a", a), budgetedSource("b", b)), 2);
        assertEquals(List.of("x != 0", "y > 0"), result.candidates().getFirst().assumptions());
        var alternatives = run(choice("choice", budgetedSource("a", a), budgetedSource("b", b)), 1);
        assertEquals(2, alternatives.candidates().size());
        assertNotEquals(alternatives.candidates().getFirst().contentHash(),
            alternatives.candidates().getLast().contentHash());
    }

    @Test
    void topologyMetadataSourceIdentityAndLimitsAreCommitted() {
        var a = budgetedSource("a", fixed("a", "x", "x + 0", 1));
        var b = budgetedSource("b", fixed("b", "x", "0 + x", 1));
        var result = run(choice("root", a, b), 2);
        assertEquals(result.contentHash(), run(choice("root", a, b), 2).contentHash());
        assertNotEquals(result.programHash(), run(choice("root", b, a), 2).programHash());
        var renamed = budgetedSource("a", "changed label", RewriteProgram.SourceLocation.at("test", 2, 3), a.source());
        assertNotEquals(result.programHash(), run(choice("root", renamed, b), 2).programHash());
        assertNotEquals(run(repeat("root", 1, a), 2).programHash(), run(repeat("root", 2, a), 2).programHash());
        assertNotEquals(run(prune("root", a, 1, "a"), 2).programHash(),
            run(prune("root", a, 1, "b"), 2).programHash());
        var changedLimits = interpreter.executeBudgeted(choice("root", a, b), "x",
            new PathBudget(13, 2), new ExplorationLimits(9999, 10000, 100));
        assertEquals(result.programHash(), changedLimits.programHash());
        assertNotEquals(result.contentHash(), changedLimits.contentHash());
        assertThrows(UnsupportedOperationException.class, () -> result.candidates().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.work().clear());
    }

    @Test
    void invalidInputsAndStructuralDepthAreRejectedBeforeCallbacks() {
        TestSource source = appendZero("source", 1);
        var leaf = budgetedSource("leaf", source);
        assertThrows(IllegalArgumentException.class, () -> run(leaf, -1));
        assertThrows(IllegalArgumentException.class, () -> interpreter.executeBudgeted(
            leaf, "\uD800", new PathBudget(0, 1), LIMITS));
        RewriteProgram nested = leaf;
        for (int i = 0; i < 130; i++) { nested = choice("nested-" + i, nested); }
        RewriteProgram deep = nested;
        assertThrows(IllegalArgumentException.class, () -> run(deep, 1));
        assertEquals(0, source.calls);
        assertEquals(0, source.identityReads);
    }

    @Test
    void enumeratedTwoStepCasesMatchIndependentCostArithmetic() {
        for (int first = 1; first <= 4; first++) {
            for (int second = 1; second <= 4; second++) {
                for (int budget = 0; budget <= 9; budget++) {
                    var program = sequence("sequence",
                        budgetedSource("first", fixed("first", "x", "x + 0", first)),
                        budgetedSource("second", fixed("second", "x + 0", "(x + 0) + 0", second)));
                    var result = run(program, budget);
                    boolean affordable = budget >= first + second;
                    assertEquals(affordable, result.complete());
                    assertEquals(affordable ? 1 : 0, result.candidates().size());
                    if (affordable) {
                        assertEquals((long) first + second, result.candidates().getFirst().mathematicalWorkUnits());
                    }
                }
            }
        }
    }

    private BudgetedRewriteProgramExecution run(RewriteProgram program, long budget) {
        return interpreter.executeBudgeted(program, "x", new PathBudget(13, budget), LIMITS);
    }
    private static TestSource fixed(String id, String input, String output, long work) {
        return new TestSource(id, (self, expression, budget) -> input.equals(expression)
            ? reply(self, expression, output, budget, work, List.of())
            : Result.noMatch(self.identity, expression, budget, 1, "MISS"));
    }
    private static TestSource appendZero(String id, long work) {
        return new TestSource(id, (self, expression, budget) ->
            reply(self, expression, expression + " + 0", budget, work, List.of()));
    }
    private static Result reply(TestSource source, String input, String output,
                                long budget, long work, List<String> assumptions) {
        if (budget < work) {
            return Result.budgetInconclusive(source.identity, input, budget, work, 2, "BUDGET");
        }
        return Result.candidates(source.identity, input, budget, List.of(ExactTheoryTransition.create(
            input, output, "test.theory/" + source.identity.sourceId(), source.identity.authorityHash(),
            assumptions, work, hash(input + "|" + output))), 3, "CANDIDATE");
    }
    @FunctionalInterface
    private interface Responder { Result respond(TestSource source, String expression, long budget); }
    private static final class TestSource implements BudgetedTransformationSource {
        private SourceIdentity identity;
        private final Responder responder;
        private final List<Long> budgets = new ArrayList<>();
        private int calls;
        private int identityReads;
        private TestSource(String id, Responder responder) {
            this.identity = new SourceIdentity(id, hash("revision-" + id), hash(id));
            this.responder = responder;
        }
        public SourceIdentity identity() { identityReads++; return identity; }
        public Result transform(String expression, long budget) {
            calls++; budgets.add(budget); return responder.respond(this, expression, budget);
        }
    }
    private static String hash(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
