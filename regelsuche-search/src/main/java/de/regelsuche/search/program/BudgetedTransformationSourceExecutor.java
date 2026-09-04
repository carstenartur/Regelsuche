package de.regelsuche.search.program;

import de.regelsuche.search.program.BudgetedTransformationSource.ExactTheoryTransition;
import de.regelsuche.search.program.BudgetedTransformationSource.Result;
import de.regelsuche.search.program.BudgetedTransformationSource.SourceIdentity;
import de.regelsuche.search.program.BudgetedTransformationSource.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Executes one {@link BudgetedTransformationSource} without converting its
 * exact-theory candidates into primitive {@code Transformation}s.
 */
public final class BudgetedTransformationSourceExecutor {
    public static final String EXECUTOR_REVISION =
        "regelsuche.budgeted-transformation-source-executor/v1";

    public Execution execute(
        BudgetedTransformationSource source,
        String expression,
        long availableMathematicalWorkUnits
    ) {
        Objects.requireNonNull(source, "source");
        if (availableMathematicalWorkUnits < 0) {
            throw new IllegalArgumentException(
                "availableMathematicalWorkUnits must not be negative");
        }
        String normalizedExpression = normalizeExpression(expression);
        SourceIdentity before = Objects.requireNonNull(
            source.identity(),
            "BudgetedTransformationSource.identity must not return null");
        Result result = Objects.requireNonNull(
            source.transform(
                normalizedExpression,
                availableMathematicalWorkUnits),
            "BudgetedTransformationSource.transform must not return null");
        SourceIdentity after = Objects.requireNonNull(
            source.identity(),
            "BudgetedTransformationSource.identity must not return null");
        if (!before.equals(after) || !before.equals(result.sourceIdentity())) {
            throw new IllegalArgumentException(
                "budgeted source identity changed during execution");
        }
        if (!normalizedExpression.equals(result.sourceExpression())
                || result.availableMathematicalWorkUnits()
                    != availableMathematicalWorkUnits) {
            throw new IllegalArgumentException(
                "budgeted source result differs from invocation inputs");
        }

        MechanicalWork work = MechanicalWork.forResult(result);
        String executionHash = executionHash(
            before,
            normalizedExpression,
            availableMathematicalWorkUnits,
            result,
            work);
        return new Execution(
            before,
            normalizedExpression,
            availableMathematicalWorkUnits,
            result,
            work,
            executionHash);
    }

    public record MechanicalWork(
        long executorInvocations,
        long sourceIdentityReads,
        long sourceInvocations,
        long candidateObservations,
        long sourceMechanicalWorkUnits,
        long totalMechanicalWorkUnits
    ) {
        public MechanicalWork {
            if (executorInvocations != 1
                    || sourceIdentityReads != 2
                    || sourceInvocations != 1
                    || candidateObservations < 0
                    || sourceMechanicalWorkUnits < 0) {
                throw new IllegalArgumentException(
                    "budgeted source mechanical work is inconsistent");
            }
            long expected = Math.addExact(
                Math.addExact(
                    executorInvocations,
                    sourceIdentityReads),
                Math.addExact(
                    sourceInvocations,
                    Math.addExact(
                        candidateObservations,
                        sourceMechanicalWorkUnits)));
            if (totalMechanicalWorkUnits != expected) {
                throw new IllegalArgumentException(
                    "totalMechanicalWorkUnits differs from its components");
            }
        }

        private static MechanicalWork forResult(Result result) {
            long candidates = result.candidates().size();
            long total = Math.addExact(
                4L,
                Math.addExact(candidates, result.mechanicalWorkUnits()));
            return new MechanicalWork(
                1,
                2,
                1,
                candidates,
                result.mechanicalWorkUnits(),
                total);
        }
    }

    public record Execution(
        SourceIdentity sourceIdentity,
        String inputExpression,
        long availableMathematicalWorkUnits,
        Result sourceResult,
        MechanicalWork mechanicalWork,
        String contentHash
    ) {
        public Execution {
            sourceIdentity = Objects.requireNonNull(
                sourceIdentity,
                "sourceIdentity");
            inputExpression = normalizeExpression(inputExpression);
            if (availableMathematicalWorkUnits < 0) {
                throw new IllegalArgumentException(
                    "availableMathematicalWorkUnits must not be negative");
            }
            sourceResult = Objects.requireNonNull(
                sourceResult,
                "sourceResult");
            mechanicalWork = Objects.requireNonNull(
                mechanicalWork,
                "mechanicalWork");
            if (!sourceIdentity.equals(sourceResult.sourceIdentity())
                    || !inputExpression.equals(
                        sourceResult.sourceExpression())
                    || availableMathematicalWorkUnits
                        != sourceResult.availableMathematicalWorkUnits()
                    || mechanicalWork.candidateObservations()
                        != sourceResult.candidates().size()
                    || mechanicalWork.sourceMechanicalWorkUnits()
                        != sourceResult.mechanicalWorkUnits()) {
                throw new IllegalArgumentException(
                    "budgeted source execution is not bound to its result");
            }
            contentHash = requireSha256(contentHash, "contentHash");
            String expected = executionHash(
                sourceIdentity,
                inputExpression,
                availableMathematicalWorkUnits,
                sourceResult,
                mechanicalWork);
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "budgeted source execution contentHash differs from contents");
            }
        }

        public Status status() {
            return sourceResult.status();
        }

        public List<ExactTheoryTransition> candidates() {
            return sourceResult.candidates();
        }

        public boolean complete() {
            return sourceResult.complete();
        }
    }

    private static String executionHash(
        SourceIdentity identity,
        String expression,
        long availableWork,
        Result result,
        MechanicalWork work
    ) {
        StringBuilder material = new StringBuilder();
        append(material, EXECUTOR_REVISION);
        append(material, identity.sourceId());
        append(material, identity.revisionHash());
        append(material, identity.authorityHash());
        append(material, expression);
        append(material, Long.toString(availableWork));
        append(material, result.contentHash());
        append(material, Long.toString(work.executorInvocations()));
        append(material, Long.toString(work.sourceIdentityReads()));
        append(material, Long.toString(work.sourceInvocations()));
        append(material, Long.toString(work.candidateObservations()));
        append(material, Long.toString(work.sourceMechanicalWorkUnits()));
        append(material, Long.toString(work.totalMechanicalWorkUnits()));
        return sha256(material.toString());
    }

    private static String normalizeExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression must not be blank");
        }
        String normalized = expression.trim().replaceAll("\\s+", " ");
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                "expression contains a control character");
        }
        return normalized;
    }

    private static String requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                name + " must be a lowercase SHA-256 identifier");
        }
        return value;
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.getBytes(StandardCharsets.UTF_8).length)
            .append(':')
            .append(value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
