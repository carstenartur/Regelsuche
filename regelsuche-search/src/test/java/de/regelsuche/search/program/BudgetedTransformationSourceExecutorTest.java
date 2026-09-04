package de.regelsuche.search.program;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.search.program.BudgetedTransformationSource.ExactTheoryTransition;
import de.regelsuche.search.program.BudgetedTransformationSource.Result;
import de.regelsuche.search.program.BudgetedTransformationSource.SourceIdentity;
import de.regelsuche.search.program.BudgetedTransformationSource.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BudgetedTransformationSourceExecutorTest {
    private final BudgetedTransformationSourceExecutor executor =
        new BudgetedTransformationSourceExecutor();

    @Test
    void executesOneExactTheoryCandidateWithoutPrimitiveRewriteProjection() {
        SourceIdentity identity = identity("fixed");
        ExactTheoryTransition transition = transition(7);
        BudgetedTransformationSource source = fixed(
            identity,
            Result.candidates(
                identity,
                "x*x",
                7,
                List.of(transition),
                3,
                "EXACT_CANDIDATE"));

        var execution = executor.execute(source, "  x*x  ", 7);

        assertEquals(Status.CANDIDATES, execution.status());
        assertTrue(execution.complete());
        assertEquals(List.of(transition), execution.candidates());
        assertEquals(7, execution.candidates().getFirst()
            .mathematicalWorkUnits());
        assertEquals(0, execution.candidates().getFirst()
            .assumptions().size());
        assertEquals(1, execution.mechanicalWork().executorInvocations());
        assertEquals(2, execution.mechanicalWork().sourceIdentityReads());
        assertEquals(1, execution.mechanicalWork().sourceInvocations());
        assertEquals(1, execution.mechanicalWork().candidateObservations());
        assertEquals(3, execution.mechanicalWork()
            .sourceMechanicalWorkUnits());
        assertEquals(8, execution.mechanicalWork().totalMechanicalWorkUnits());
        assertTrue(execution.contentHash().startsWith("sha256:"));
        assertEquals(
            execution,
            executor.execute(source, "x*x", 7));
    }

    @Test
    void keepsNoMatchAndBudgetInconclusiveDistinct() {
        SourceIdentity identity = identity("terminal");
        Result noMatch = Result.noMatch(
            identity,
            "y",
            6,
            1,
            "SOURCE_MISMATCH");
        Result inconclusive = Result.budgetInconclusive(
            identity,
            "x*x",
            6,
            7,
            2,
            "INSUFFICIENT_WORK");

        var noMatchExecution = executor.execute(
            fixed(identity, noMatch),
            "y",
            6);
        var inconclusiveExecution = executor.execute(
            fixed(identity, inconclusive),
            "x*x",
            6);

        assertEquals(Status.NO_MATCH, noMatchExecution.status());
        assertTrue(noMatchExecution.complete());
        assertTrue(noMatchExecution.candidates().isEmpty());
        assertEquals(
            Status.BUDGET_INCONCLUSIVE,
            inconclusiveExecution.status());
        assertFalse(inconclusiveExecution.complete());
        assertTrue(inconclusiveExecution.candidates().isEmpty());
        assertEquals(
            7,
            inconclusiveExecution.sourceResult()
                .minimumRequiredMathematicalWorkUnits());
    }

    @Test
    void rejectsIdentityAndInvocationSubstitution() {
        SourceIdentity before = identity("before");
        SourceIdentity after = identity("after");
        AtomicBoolean transformed = new AtomicBoolean();
        BudgetedTransformationSource changing = new BudgetedTransformationSource() {
            @Override
            public SourceIdentity identity() {
                return transformed.get() ? after : before;
            }

            @Override
            public Result transform(String expression, long available) {
                transformed.set(true);
                return Result.noMatch(
                    before,
                    expression,
                    available,
                    0,
                    "NO_MATCH");
            }
        };
        assertThrows(
            IllegalArgumentException.class,
            () -> executor.execute(changing, "x", 1));

        BudgetedTransformationSource wrongInput = new BudgetedTransformationSource() {
            @Override
            public SourceIdentity identity() {
                return before;
            }

            @Override
            public Result transform(String expression, long available) {
                return Result.noMatch(
                    before,
                    "another-expression",
                    available,
                    0,
                    "WRONG_INPUT");
            }
        };
        assertThrows(
            IllegalArgumentException.class,
            () -> executor.execute(wrongInput, "x", 1));
    }

    @Test
    void resultAndTransitionInvariantsFailClosed() {
        SourceIdentity identity = identity("invariants");
        ExactTheoryTransition transition = transition(7);

        assertThrows(IllegalArgumentException.class, () ->
            Result.candidates(
                identity,
                "x*x",
                6,
                List.of(transition),
                0,
                "OVER_BUDGET_CANDIDATE"));
        assertThrows(IllegalArgumentException.class, () ->
            Result.budgetInconclusive(
                identity,
                "x*x",
                7,
                7,
                0,
                "NOT_ACTUALLY_INCONCLUSIVE"));
        assertThrows(IllegalArgumentException.class, () ->
            new ExactTheoryTransition(
                "x*x",
                "x^2",
                "exact-theory",
                hash("evidence"),
                List.of(),
                7,
                "application",
                hash("forged-transition")));

        String forgedMinimumHash = resultHash(
            identity,
            "x*x",
            7,
            Status.CANDIDATES,
            List.of(transition),
            6,
            0,
            "FORGED_MINIMUM");
        assertThrows(IllegalArgumentException.class, () ->
            new Result(
                identity,
                "x*x",
                7,
                Status.CANDIDATES,
                List.of(transition),
                6,
                0,
                "FORGED_MINIMUM",
                forgedMinimumHash));
    }

    private static BudgetedTransformationSource fixed(
        SourceIdentity identity,
        Result result
    ) {
        return new BudgetedTransformationSource() {
            @Override
            public SourceIdentity identity() {
                return identity;
            }

            @Override
            public Result transform(String expression, long available) {
                return result;
            }
        };
    }

    private static SourceIdentity identity(String suffix) {
        return new SourceIdentity(
            "source-" + suffix,
            hash("revision-" + suffix),
            hash("authority-" + suffix));
    }

    private static ExactTheoryTransition transition(long work) {
        return ExactTheoryTransition.create(
            "x*x",
            "x^2",
            "exact-polynomial-equivalence",
            hash("evidence"),
            List.of(),
            work,
            "application");
    }

    private static String resultHash(
        SourceIdentity identity,
        String expression,
        long available,
        Status status,
        List<ExactTheoryTransition> candidates,
        long minimumRequired,
        long mechanicalWork,
        String detailCode
    ) {
        StringBuilder material = new StringBuilder();
        append(material, BudgetedTransformationSource.PROTOCOL_REVISION);
        append(material, "source-result");
        append(material, identity.sourceId());
        append(material, identity.revisionHash());
        append(material, identity.authorityHash());
        append(material, expression);
        append(material, Long.toString(available));
        append(material, status.name());
        append(material, Long.toString(minimumRequired));
        append(material, Long.toString(mechanicalWork));
        append(material, detailCode);
        candidates.forEach(candidate ->
            append(material, candidate.contentHash()));
        return hash(material.toString());
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.getBytes(StandardCharsets.UTF_8).length)
            .append(':')
            .append(value);
    }

    private static String hash(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
