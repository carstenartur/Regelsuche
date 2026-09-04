package de.regelsuche.search.program;

import static de.regelsuche.search.program.RewritePrograms.budgetedSource;
import static de.regelsuche.search.program.RewritePrograms.choice;
import static de.regelsuche.search.program.RewritePrograms.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.search.program.BudgetedTransformationSource.ExactTheoryTransition;
import de.regelsuche.search.program.BudgetedTransformationSource.Result;
import de.regelsuche.search.program.BudgetedTransformationSource.SourceIdentity;
import de.regelsuche.search.program.BudgetedTransformationSource.Status;
import de.regelsuche.search.program.BudgetedTransformationSourceProgramExecution.ExactTheoryCandidate;
import de.regelsuche.transform.Transformation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BudgetedTransformationSourceRewriteProgramTest {
    private static final long REQUIRED_WORK = 7;

    private final RewriteProgramInterpreter interpreter =
        new RewriteProgramInterpreter();

    @Test
    void executesOneTopLevelBudgetedSourceWithoutPrimitiveConversion() {
        FixedSource source = new FixedSource();
        RewriteProgram.BudgetedSource program = budgetedSource(
            "verified-theory",
            "Verified exact theory",
            RewriteProgram.SourceLocation.at("checked-plan.java", 7, 3),
            source);

        BudgetedTransformationSourceProgramExecution execution =
            interpreter.executeBudgetedSource(program, "  x  ", REQUIRED_WORK);

        assertEquals(Status.CANDIDATES, execution.status());
        assertTrue(execution.complete());
        assertEquals("x", execution.inputExpression());
        assertEquals(REQUIRED_WORK, execution.availableMathematicalWorkUnits());
        assertEquals(REQUIRED_WORK,
            execution.minimumRequiredMathematicalWorkUnits());
        assertEquals("EXACT_THEORY_CANDIDATE", execution.detailCode());
        assertEquals(1, execution.candidates().size());

        ExactTheoryCandidate candidate = execution.candidates().getFirst();
        assertEquals(program.id(), candidate.originNodeId());
        assertEquals("x", candidate.sourceExpression());
        assertEquals("x + 0", candidate.transformedExpression());
        assertEquals("test.exact-theory/v1", candidate.theoryStepId());
        assertEquals(source.evidenceHash, candidate.evidenceHash());
        assertEquals(REQUIRED_WORK, candidate.mathematicalWorkUnits());
        assertEquals(0, candidate.primitiveRewriteSteps());
        assertTrue(candidate.primitiveRuleIds().isEmpty());
        assertEquals(1, candidate.exactTheorySteps());
        assertEquals(
            List.of("test.exact-theory/v1"),
            candidate.exactTheoryStepIds());

        assertEquals(8,
            execution.sourceExecution().mechanicalWork()
                .totalMechanicalWorkUnits());
        assertEquals(12, execution.programWork().totalMechanicalWorkUnits());
        assertEquals(
            execution.contentHash(),
            interpreter.executeBudgetedSource(
                program,
                "x",
                REQUIRED_WORK).contentHash());
        assertEquals(2, source.invocations.get());
    }

    @Test
    void preservesCompleteNoMatchAndIncompleteBudgetExhaustion() {
        FixedSource source = new FixedSource();
        RewriteProgram.BudgetedSource program =
            budgetedSource("verified-theory", source);

        BudgetedTransformationSourceProgramExecution noMatch =
            interpreter.executeBudgetedSource(program, "y", REQUIRED_WORK);
        assertEquals(Status.NO_MATCH, noMatch.status());
        assertTrue(noMatch.complete());
        assertTrue(noMatch.candidates().isEmpty());
        assertEquals(0, noMatch.minimumRequiredMathematicalWorkUnits());

        BudgetedTransformationSourceProgramExecution insufficient =
            interpreter.executeBudgetedSource(
                program,
                "x",
                REQUIRED_WORK - 1);
        assertEquals(Status.BUDGET_INCONCLUSIVE, insufficient.status());
        assertFalse(insufficient.complete());
        assertTrue(insufficient.candidates().isEmpty());
        assertEquals(
            REQUIRED_WORK,
            insufficient.minimumRequiredMathematicalWorkUnits());
        assertNotEquals(noMatch.contentHash(), insufficient.contentHash());
    }

    @Test
    void ordinaryInterpreterRejectsBudgetedNodesBeforeAnySourceSideEffect() {
        AtomicInteger ordinaryInvocations = new AtomicInteger();
        FixedSource budgeted = new FixedSource();
        RewriteProgram mixed = choice(
            "mixed",
            source("ordinary", expression -> {
                ordinaryInvocations.incrementAndGet();
                return List.of(new Transformation("ordinary", "ordinary-x"));
            }),
            budgetedSource("verified-theory", budgeted));

        IllegalArgumentException nested = assertThrows(
            IllegalArgumentException.class,
            () -> interpreter.execute(mixed, "x"));
        assertTrue(nested.getMessage().contains("executeBudgetedSource"));
        assertEquals(0, ordinaryInvocations.get());
        assertEquals(0, budgeted.invocations.get());

        assertThrows(
            IllegalArgumentException.class,
            () -> interpreter.execute(
                budgetedSource("top-level-budgeted", budgeted),
                "x"));
        assertEquals(0, budgeted.invocations.get());
    }

    private static final class FixedSource
            implements BudgetedTransformationSource {
        private final String evidenceHash = hash("test-evidence");
        private final SourceIdentity identity = new SourceIdentity(
            "test.fixed-budgeted-source/v1",
            hash("test-source-revision"),
            evidenceHash);
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public SourceIdentity identity() {
            return identity;
        }

        @Override
        public Result transform(
            String expression,
            long availableMathematicalWorkUnits
        ) {
            invocations.incrementAndGet();
            if (!"x".equals(expression)) {
                return Result.noMatch(
                    identity,
                    expression,
                    availableMathematicalWorkUnits,
                    1,
                    "SOURCE_MISMATCH");
            }
            if (availableMathematicalWorkUnits < REQUIRED_WORK) {
                return Result.budgetInconclusive(
                    identity,
                    expression,
                    availableMathematicalWorkUnits,
                    REQUIRED_WORK,
                    2,
                    "INSUFFICIENT_WORK");
            }
            ExactTheoryTransition transition = ExactTheoryTransition.create(
                "x",
                "x + 0",
                "test.exact-theory/v1",
                evidenceHash,
                List.of(),
                REQUIRED_WORK,
                hash("test-application"));
            return Result.candidates(
                identity,
                expression,
                availableMathematicalWorkUnits,
                List.of(transition),
                3,
                "EXACT_THEORY_CANDIDATE");
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
