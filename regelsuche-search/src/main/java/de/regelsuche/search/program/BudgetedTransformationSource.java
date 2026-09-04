package de.regelsuche.search.program;

import de.regelsuche.assumption.AssumptionSignature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * A transformation source whose mathematical work authority is supplied
 * explicitly for every invocation.
 *
 * <p>The source does not emit ordinary primitive rewrites. Its candidates are
 * typed exact-theory transitions with their own evidence and mathematical work.
 * The protocol itself does not prove that a source is trustworthy; concrete
 * adapters must bind their {@link SourceIdentity#authorityHash()} to
 * independently verified evidence.</p>
 */
public interface BudgetedTransformationSource {
    String PROTOCOL_REVISION =
        "regelsuche.budgeted-transformation-source/v1";

    SourceIdentity identity();

    Result transform(String expression, long availableMathematicalWorkUnits);

    enum Status {
        CANDIDATES,
        NO_MATCH,
        BUDGET_INCONCLUSIVE
    }

    /** Immutable identity captured before a source invocation. */
    record SourceIdentity(
        String sourceId,
        String revisionHash,
        String authorityHash
    ) {
        public SourceIdentity {
            sourceId = requireText(sourceId, "sourceId");
            revisionHash = requireSha256(revisionHash, "revisionHash");
            authorityHash = requireSha256(authorityHash, "authorityHash");
        }
    }

    /** One non-primitive exact-theory transition proposed by the source. */
    record ExactTheoryTransition(
        String sourceExpression,
        String transformedExpression,
        String theoryStepId,
        String evidenceHash,
        List<String> assumptions,
        long mathematicalWorkUnits,
        String applicationKey,
        String contentHash
    ) {
        public ExactTheoryTransition {
            sourceExpression = normalizeExpression(
                sourceExpression,
                "sourceExpression");
            transformedExpression = normalizeExpression(
                transformedExpression,
                "transformedExpression");
            if (sourceExpression.equals(transformedExpression)) {
                throw new IllegalArgumentException(
                    "exact-theory transition must change the representation");
            }
            theoryStepId = requireText(theoryStepId, "theoryStepId");
            evidenceHash = requireSha256(evidenceHash, "evidenceHash");
            assumptions = normalizeAssumptions(assumptions);
            if (mathematicalWorkUnits < 1) {
                throw new IllegalArgumentException(
                    "mathematicalWorkUnits must be positive");
            }
            applicationKey = requireText(applicationKey, "applicationKey");
            contentHash = requireSha256(contentHash, "contentHash");
            String expected = transitionHash(
                sourceExpression,
                transformedExpression,
                theoryStepId,
                evidenceHash,
                assumptions,
                mathematicalWorkUnits,
                applicationKey);
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "exact-theory transition contentHash differs from contents");
            }
        }

        public static ExactTheoryTransition create(
            String sourceExpression,
            String transformedExpression,
            String theoryStepId,
            String evidenceHash,
            List<String> assumptions,
            long mathematicalWorkUnits,
            String applicationKey
        ) {
            String source = normalizeExpression(
                sourceExpression,
                "sourceExpression");
            String transformed = normalizeExpression(
                transformedExpression,
                "transformedExpression");
            String step = requireText(theoryStepId, "theoryStepId");
            String evidence = requireSha256(evidenceHash, "evidenceHash");
            List<String> normalizedAssumptions =
                normalizeAssumptions(assumptions);
            String key = requireText(applicationKey, "applicationKey");
            return new ExactTheoryTransition(
                source,
                transformed,
                step,
                evidence,
                normalizedAssumptions,
                mathematicalWorkUnits,
                key,
                transitionHash(
                    source,
                    transformed,
                    step,
                    evidence,
                    normalizedAssumptions,
                    mathematicalWorkUnits,
                    key));
        }
    }

    /** Complete terminal result for one explicit source budget. */
    record Result(
        SourceIdentity sourceIdentity,
        String sourceExpression,
        long availableMathematicalWorkUnits,
        Status status,
        List<ExactTheoryTransition> candidates,
        long minimumRequiredMathematicalWorkUnits,
        long mechanicalWorkUnits,
        String detailCode,
        String contentHash
    ) {
        public Result {
            sourceIdentity = Objects.requireNonNull(
                sourceIdentity,
                "sourceIdentity");
            sourceExpression = normalizeExpression(
                sourceExpression,
                "sourceExpression");
            if (availableMathematicalWorkUnits < 0) {
                throw new IllegalArgumentException(
                    "availableMathematicalWorkUnits must not be negative");
            }
            status = Objects.requireNonNull(status, "status");
            candidates = normalizeCandidates(candidates);
            if (minimumRequiredMathematicalWorkUnits < 0
                    || mechanicalWorkUnits < 0) {
                throw new IllegalArgumentException(
                    "source work values must not be negative");
            }
            detailCode = requireText(detailCode, "detailCode");
            validateState(
                sourceExpression,
                availableMathematicalWorkUnits,
                status,
                candidates,
                minimumRequiredMathematicalWorkUnits);
            contentHash = requireSha256(contentHash, "contentHash");
            String expected = resultHash(
                sourceIdentity,
                sourceExpression,
                availableMathematicalWorkUnits,
                status,
                candidates,
                minimumRequiredMathematicalWorkUnits,
                mechanicalWorkUnits,
                detailCode);
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "budgeted source result contentHash differs from contents");
            }
        }

        public static Result candidates(
            SourceIdentity sourceIdentity,
            String sourceExpression,
            long availableMathematicalWorkUnits,
            List<ExactTheoryTransition> candidates,
            long mechanicalWorkUnits,
            String detailCode
        ) {
            List<ExactTheoryTransition> normalized =
                normalizeCandidates(candidates);
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(
                    "candidate result requires at least one transition");
            }
            long minimum = normalized.stream()
                .mapToLong(ExactTheoryTransition::mathematicalWorkUnits)
                .min()
                .orElseThrow();
            return create(
                sourceIdentity,
                sourceExpression,
                availableMathematicalWorkUnits,
                Status.CANDIDATES,
                normalized,
                minimum,
                mechanicalWorkUnits,
                detailCode);
        }

        public static Result noMatch(
            SourceIdentity sourceIdentity,
            String sourceExpression,
            long availableMathematicalWorkUnits,
            long mechanicalWorkUnits,
            String detailCode
        ) {
            return create(
                sourceIdentity,
                sourceExpression,
                availableMathematicalWorkUnits,
                Status.NO_MATCH,
                List.of(),
                0,
                mechanicalWorkUnits,
                detailCode);
        }

        public static Result budgetInconclusive(
            SourceIdentity sourceIdentity,
            String sourceExpression,
            long availableMathematicalWorkUnits,
            long minimumRequiredMathematicalWorkUnits,
            long mechanicalWorkUnits,
            String detailCode
        ) {
            return create(
                sourceIdentity,
                sourceExpression,
                availableMathematicalWorkUnits,
                Status.BUDGET_INCONCLUSIVE,
                List.of(),
                minimumRequiredMathematicalWorkUnits,
                mechanicalWorkUnits,
                detailCode);
        }

        public boolean complete() {
            return status != Status.BUDGET_INCONCLUSIVE;
        }

        private static Result create(
            SourceIdentity identity,
            String expression,
            long available,
            Status status,
            List<ExactTheoryTransition> candidates,
            long minimumRequired,
            long mechanicalWork,
            String detailCode
        ) {
            SourceIdentity checkedIdentity = Objects.requireNonNull(
                identity,
                "sourceIdentity");
            String normalizedExpression = normalizeExpression(
                expression,
                "sourceExpression");
            Status checkedStatus = Objects.requireNonNull(status, "status");
            List<ExactTheoryTransition> normalizedCandidates =
                normalizeCandidates(candidates);
            String detail = requireText(detailCode, "detailCode");
            return new Result(
                checkedIdentity,
                normalizedExpression,
                available,
                checkedStatus,
                normalizedCandidates,
                minimumRequired,
                mechanicalWork,
                detail,
                resultHash(
                    checkedIdentity,
                    normalizedExpression,
                    available,
                    checkedStatus,
                    normalizedCandidates,
                    minimumRequired,
                    mechanicalWork,
                    detail));
        }
    }

    private static void validateState(
        String sourceExpression,
        long availableWork,
        Status status,
        List<ExactTheoryTransition> candidates,
        long minimumRequiredWork
    ) {
        switch (status) {
            case CANDIDATES -> {
                long actualMinimum = candidates.stream()
                    .mapToLong(ExactTheoryTransition::mathematicalWorkUnits)
                    .min()
                    .orElse(0);
                if (candidates.isEmpty()
                        || minimumRequiredWork != actualMinimum
                        || minimumRequiredWork < 1
                        || minimumRequiredWork > availableWork
                        || candidates.stream().anyMatch(candidate ->
                            !sourceExpression.equals(
                                candidate.sourceExpression())
                                || candidate.mathematicalWorkUnits()
                                    > availableWork)) {
                    throw new IllegalArgumentException(
                        "candidate result is inconsistent with its source budget");
                }
            }
            case NO_MATCH -> {
                if (!candidates.isEmpty() || minimumRequiredWork != 0) {
                    throw new IllegalArgumentException(
                        "no-match result must be complete and candidate-free");
                }
            }
            case BUDGET_INCONCLUSIVE -> {
                if (!candidates.isEmpty()
                        || minimumRequiredWork <= availableWork) {
                    throw new IllegalArgumentException(
                        "budget-inconclusive result requires more work than offered");
                }
            }
        }
    }

    private static List<ExactTheoryTransition> normalizeCandidates(
        List<ExactTheoryTransition> candidates
    ) {
        Objects.requireNonNull(candidates, "candidates");
        List<ExactTheoryTransition> normalized = candidates.stream()
            .map(candidate -> Objects.requireNonNull(candidate, "candidate"))
            .sorted(Comparator.comparing(ExactTheoryTransition::contentHash))
            .toList();
        if (new HashSet<>(normalized.stream()
                .map(ExactTheoryTransition::contentHash)
                .toList()).size() != normalized.size()) {
            throw new IllegalArgumentException(
                "exact-theory transition identities must be unique");
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeAssumptions(List<String> assumptions) {
        Objects.requireNonNull(assumptions, "assumptions");
        return AssumptionSignature.ofExpressions(assumptions)
            .normalizedAssumptions();
    }

    private static String transitionHash(
        String source,
        String transformed,
        String theoryStepId,
        String evidenceHash,
        List<String> assumptions,
        long mathematicalWorkUnits,
        String applicationKey
    ) {
        StringBuilder material = new StringBuilder();
        append(material, PROTOCOL_REVISION);
        append(material, "exact-theory-transition");
        append(material, source);
        append(material, transformed);
        append(material, theoryStepId);
        append(material, evidenceHash);
        append(material, Long.toString(mathematicalWorkUnits));
        append(material, applicationKey);
        assumptions.forEach(value -> append(material, value));
        return sha256(material.toString());
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
        append(material, PROTOCOL_REVISION);
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
        return sha256(material.toString());
    }

    private static String normalizeExpression(String value, String name) {
        String normalized = requireText(value, name)
            .trim()
            .replaceAll("\\s+", " ");
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                name + " contains a control character");
        }
        return normalized;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
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
