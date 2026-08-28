package de.regelsuche.parse;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Projects one source-bound AST occurrence from an {@link ExactParsedTerm}
 * into a new exact parsed-term companion without formatting or reparsing values.
 *
 * <p>The selected expression nodes and exact literal evidence remain the
 * parser-issued instances. Only source and literal ranges are shifted from the
 * original root coordinate system to the selected occurrence. A formatted
 * subtree snapshot may be checked as a staleness guard, but it never authorizes
 * a coefficient or exponent.</p>
 */
public final class ExactParsedSubtermProjector {
    public static final String PROJECTOR_ID =
        "regelsuche.exact-parsed-subterm-projector/v1";
    public static final int MAX_PATH_DEPTH = 4_096;
    public static final int MAX_ROOT_SOURCE_CODE_UNITS = 1_000_000;
    public static final int MAX_SUBTREE_NODES = 65_536;
    public static final int MAX_SUBTREE_SOURCE_CODE_UNITS = 1_000_000;
    public static final int MAX_FORMATTED_CODE_UNITS = 1_000_000;
    public static final long MAX_WORK_UNITS = 50_000_000L;

    private final Policy policy;

    public ExactParsedSubtermProjector() {
        this(Policy.boundedDefaults());
    }

    public ExactParsedSubtermProjector(Policy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public Policy policy() {
        return policy;
    }

    /**
     * Projects the occurrence at {@code path} after checking the formatter text
     * captured by the caller when that path was selected.
     */
    public Result project(
        ExactParsedTerm root,
        List<Integer> path,
        String expectedFormattedText
    ) {
        Objects.requireNonNull(root, "root");
        List<Integer> stablePath = validatePath(path);
        String expected = Objects.requireNonNull(
            expectedFormattedText,
            "expectedFormattedText");
        if (expected.isBlank()) {
            throw new IllegalArgumentException(
                "expected subtree text must not be blank");
        }

        Work work = new Work(policy.maxWorkUnits());
        if (stablePath.size() > policy.maxPathDepth()) {
            return failure(
                Status.BUDGET_INCONCLUSIVE,
                "MAX_PATH_DEPTH_EXCEEDED",
                stablePath,
                expected,
                "",
                Optional.empty(),
                Optional.empty(),
                "",
                "",
                work);
        }
        if (root.source().length() > policy.maxRootSourceCodeUnits()) {
            return failure(
                Status.BUDGET_INCONCLUSIVE,
                "MAX_ROOT_SOURCE_CODE_UNITS_EXCEEDED",
                stablePath,
                expected,
                "",
                Optional.empty(),
                Optional.empty(),
                "",
                "",
                work);
        }
        if (expected.length() > policy.maxFormattedCodeUnits()) {
            return failure(
                Status.BUDGET_INCONCLUSIVE,
                "MAX_EXPECTED_FORMATTED_CODE_UNITS_EXCEEDED",
                stablePath,
                expected,
                "",
                Optional.empty(),
                Optional.empty(),
                "",
                "",
                work);
        }

        String rootSourceHash = "";
        try {
            work.consume(
                "projection.root-source-hash-code-units",
                Math.multiplyExact(4L, root.source().length()));
            rootSourceHash = sha256(root.source());

            Expr selected = navigate(root.expression(), stablePath, work);
            if (selected == null) {
                return failure(
                    Status.POSITION_NOT_PRESENT,
                    "SELECTED_PATH_IS_NOT_PRESENT",
                    stablePath,
                    expected,
                    rootSourceHash,
                    Optional.empty(),
                    Optional.empty(),
                    "",
                    "",
                    work);
            }

            Optional<ExactParsedTerm.SourceRange> selectedRange =
                root.sourceRangeFor(selected);
            if (selectedRange.isEmpty()) {
                return failure(
                    Status.UNSUPPORTED,
                    "SELECTED_OCCURRENCE_HAS_NO_SOURCE_RANGE",
                    stablePath,
                    expected,
                    rootSourceHash,
                    Optional.empty(),
                    Optional.empty(),
                    "",
                    "",
                    work);
            }
            ExactParsedTerm.SourceRange occurrenceRange =
                selectedRange.orElseThrow();
            int selectedSourceLength = occurrenceRange.endExclusive()
                - occurrenceRange.startInclusive();
            if (selectedSourceLength
                    > policy.maxSubtreeSourceCodeUnits()) {
                throw limit("MAX_SUBTREE_SOURCE_CODE_UNITS_EXCEEDED");
            }

            Scan scan = scan(
                root,
                selected,
                occurrenceRange,
                work);
            work.consume(
                "projection.staleness-format-node-visits",
                scan.nodeCount());
            String actual = ExpressionFormatter.format(selected);
            if (actual.length() > policy.maxFormattedCodeUnits()) {
                throw limit("MAX_ACTUAL_FORMATTED_CODE_UNITS_EXCEEDED");
            }
            work.consume(
                "projection.staleness-format-code-units",
                actual.length());
            work.consume(
                "projection.staleness-text-comparison",
                Math.addExact(
                    (long) expected.length(),
                    actual.length()));
            if (!expected.equals(actual)) {
                return failure(
                    Status.POSITION_STALE,
                    "SELECTED_POSITION_TEXT_IS_STALE",
                    stablePath,
                    expected,
                    rootSourceHash,
                    Optional.of(actual),
                    Optional.of(occurrenceRange),
                    scan.rangeCommitmentHash(),
                    "",
                    work);
            }

            work.consume(
                "projection.selected-source-code-units",
                selectedSourceLength);
            String selectedSource = occurrenceRange.textFrom(root.source());
            long literalLexemeUnits = scan.literals().stream()
                .mapToLong(literal -> literal.sourceLexeme().length())
                .sum();
            work.consume(
                "projection.revalidation-node-visits",
                scan.nodeCount());
            work.consume(
                "projection.revalidation-range-bindings",
                scan.shiftedRanges().size());
            work.consume(
                "projection.revalidation-literal-bindings",
                scan.literals().size());
            work.consume(
                "projection.revalidation-literal-code-units",
                Math.addExact(
                    Math.multiplyExact(4L, literalLexemeUnits),
                    Math.multiplyExact(512L, scan.literals().size())));

            ExactParsedTerm projected;
            try {
                projected = new ExactParsedTerm(
                    selectedSource,
                    selected,
                    scan.literals(),
                    scan.shiftedRanges());
            } catch (IllegalArgumentException exception) {
                return failure(
                    Status.TECHNICAL_FAILURE,
                    "PROJECTED_TERM_REVALIDATION_FAILED",
                    stablePath,
                    expected,
                    rootSourceHash,
                    Optional.of(actual),
                    Optional.of(occurrenceRange),
                    scan.rangeCommitmentHash(),
                    "",
                    work);
            }
            return success(
                stablePath,
                expected,
                actual,
                rootSourceHash,
                occurrenceRange,
                scan.rangeCommitmentHash(),
                projected,
                work);
        } catch (ProjectionLimitReached exception) {
            return failure(
                Status.BUDGET_INCONCLUSIVE,
                exception.getMessage(),
                stablePath,
                expected,
                rootSourceHash,
                Optional.empty(),
                Optional.empty(),
                "",
                "",
                work);
        } catch (ProjectionInvariantFailure exception) {
            return failure(
                Status.TECHNICAL_FAILURE,
                exception.getMessage(),
                stablePath,
                expected,
                rootSourceHash,
                Optional.empty(),
                Optional.empty(),
                "",
                "",
                work);
        }
    }

    private Scan scan(
        ExactParsedTerm root,
        Expr selected,
        ExactParsedTerm.SourceRange selectedRange,
        Work work
    ) {
        Deque<Expr> pending = new ArrayDeque<>();
        pending.push(selected);
        IdentityHashMap<Expr, Boolean> visited = new IdentityHashMap<>();
        IdentityHashMap<Expr, ExactParsedTerm.SourceRange> shiftedRanges =
            new IdentityHashMap<>();
        List<ExactParsedTerm.LiteralOccurrence> literals =
            new ArrayList<>();
        EvidenceDigest commitment = new EvidenceDigest();
        int nodeCount = 0;

        while (!pending.isEmpty()) {
            Expr node = pending.pop();
            if (visited.put(node, Boolean.TRUE) != null) {
                throw invariant("PROJECTED_SUBTREE_REUSES_NODE_IDENTITY");
            }
            nodeCount++;
            if (nodeCount > policy.maxSubtreeNodes()) {
                throw limit("MAX_SUBTREE_NODES_EXCEEDED");
            }
            work.consume("projection.subtree-node-visits", 1);
            commitNodeKind(commitment, node, work);

            Optional<ExactParsedTerm.SourceRange> sourceRange =
                root.sourceRangeFor(node);
            if (sourceRange.isPresent()) {
                ExactParsedTerm.SourceRange absolute =
                    sourceRange.orElseThrow();
                if (!selectedRange.contains(absolute)) {
                    throw invariant(
                        "SUBTREE_NODE_RANGE_ESCAPES_SELECTED_OCCURRENCE");
                }
                ExactParsedTerm.SourceRange shifted =
                    new ExactParsedTerm.SourceRange(
                        absolute.startInclusive()
                            - selectedRange.startInclusive(),
                        absolute.endExclusive()
                            - selectedRange.startInclusive());
                shiftedRanges.put(node, shifted);
                work.consume("projection.shifted-range-bindings", 1);
                commit(commitment, shifted.canonicalMaterial(), work);
            } else {
                commit(commitment, "NO_SOURCE_RANGE", work);
            }

            if (node instanceof NumberExpr number) {
                Optional<ExactParsedTerm.LiteralOccurrence> literal =
                    root.literalFor(number);
                if (literal.isPresent()) {
                    ExactParsedTerm.LiteralOccurrence absolute =
                        literal.orElseThrow();
                    if (!selectedRange.contains(
                            absolute.startInclusive(),
                            absolute.endExclusive())) {
                        throw invariant(
                            "SUBTREE_LITERAL_ESCAPES_SELECTED_OCCURRENCE");
                    }
                    ExactParsedTerm.LiteralOccurrence shifted =
                        new ExactParsedTerm.LiteralOccurrence(
                            number,
                            absolute.startInclusive()
                                - selectedRange.startInclusive(),
                            absolute.endExclusive()
                                - selectedRange.startInclusive(),
                            absolute.sourceLexeme(),
                            absolute.evidence());
                    literals.add(shifted);
                    work.consume("projection.shifted-literal-bindings", 1);
                    commit(
                        commitment,
                        absolute.evidence().certificateHash(),
                        work);
                } else {
                    commit(commitment, "SYNTHETIC_NUMBER", work);
                }
            }

            if (node instanceof BinaryExpr binary) {
                pending.push(binary.right());
                pending.push(binary.left());
            } else if (node instanceof FunctionExpr function) {
                List<Expr> arguments = function.arguments();
                for (int index = arguments.size() - 1;
                        index >= 0;
                        index--) {
                    pending.push(arguments.get(index));
                }
            }
        }

        literals.sort(Comparator.comparingInt(
            ExactParsedTerm.LiteralOccurrence::startInclusive));
        return new Scan(
            nodeCount,
            shiftedRanges,
            List.copyOf(literals),
            commitment.finish());
    }

    private static Expr navigate(
        Expr root,
        List<Integer> path,
        Work work
    ) {
        Expr current = root;
        for (int index : path) {
            work.consume("projection.path-navigation-steps", 1);
            if (current instanceof BinaryExpr binary) {
                current = switch (index) {
                    case 0 -> binary.left();
                    case 1 -> binary.right();
                    default -> null;
                };
            } else if (current instanceof FunctionExpr function) {
                current = index < function.arguments().size()
                    ? function.arguments().get(index)
                    : null;
            } else {
                current = null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static List<Integer> validatePath(List<Integer> path) {
        List<Integer> stable = List.copyOf(
            Objects.requireNonNull(path, "path"));
        if (stable.stream().anyMatch(index -> index == null || index < 0)) {
            throw new IllegalArgumentException(
                "subterm projection path is invalid");
        }
        return stable;
    }

    private static void commitNodeKind(
        EvidenceDigest commitment,
        Expr node,
        Work work
    ) {
        if (node instanceof BinaryExpr binary) {
            commit(commitment, "BINARY", work);
            commit(commitment, binary.operator().name(), work);
        } else if (node instanceof FunctionExpr function) {
            commit(commitment, "FUNCTION", work);
            commit(commitment, function.name(), work);
            commit(
                commitment,
                Integer.toString(function.arguments().size()),
                work);
        } else if (node instanceof VariableExpr variable) {
            commit(commitment, "VARIABLE", work);
            commit(commitment, variable.name(), work);
        } else if (node instanceof NumberExpr) {
            commit(commitment, "NUMBER", work);
        } else {
            throw invariant("UNSUPPORTED_AST_NODE_IN_PROJECTION");
        }
    }

    private static void commit(
        EvidenceDigest digest,
        String value,
        Work work
    ) {
        work.consume(
            "projection.range-commitment-code-units",
            Math.multiplyExact(4L, value.length()));
        digest.append(value);
    }

    private Result success(
        List<Integer> path,
        String expected,
        String actual,
        String rootSourceHash,
        ExactParsedTerm.SourceRange selectedRange,
        String rangeCommitmentHash,
        ExactParsedTerm projected,
        Work work
    ) {
        return new Result(
            Status.PROJECTED,
            "EXACT_SUBTERM_PROJECTED",
            policy,
            path,
            expected,
            Optional.of(actual),
            Optional.of(rootSourceHash),
            Optional.of(selectedRange),
            Optional.of(rangeCommitmentHash),
            Optional.of(projected),
            work.ledger());
    }

    private Result failure(
        Status status,
        String detailCode,
        List<Integer> path,
        String expected,
        String rootSourceHash,
        Optional<String> actual,
        Optional<ExactParsedTerm.SourceRange> selectedRange,
        String rangeCommitmentHash,
        String projectedSource,
        Work work
    ) {
        Optional<ExactParsedTerm> projected = Optional.empty();
        if (!projectedSource.isEmpty()) {
            throw new IllegalArgumentException(
                "failed projection cannot retain projected source");
        }
        return new Result(
            status,
            detailCode,
            policy,
            path,
            expected,
            actual,
            rootSourceHash.isEmpty()
                ? Optional.empty()
                : Optional.of(rootSourceHash),
            selectedRange,
            rangeCommitmentHash.isEmpty()
                ? Optional.empty()
                : Optional.of(rangeCommitmentHash),
            projected,
            work.ledger());
    }

    private static ProjectionLimitReached limit(String detailCode) {
        return new ProjectionLimitReached(detailCode);
    }

    private static ProjectionInvariantFailure invariant(
        String detailCode
    ) {
        return new ProjectionInvariantFailure(detailCode);
    }

    private static String sha256(String value) {
        EvidenceDigest digest = new EvidenceDigest();
        digest.append(value);
        return digest.finish();
    }

    public enum Status {
        PROJECTED,
        POSITION_NOT_PRESENT,
        POSITION_STALE,
        UNSUPPORTED,
        BUDGET_INCONCLUSIVE,
        TECHNICAL_FAILURE
    }

    /** Explicit structural, representation and total-work bounds. */
    public record Policy(
        int maxPathDepth,
        int maxRootSourceCodeUnits,
        int maxSubtreeNodes,
        int maxSubtreeSourceCodeUnits,
        int maxFormattedCodeUnits,
        long maxWorkUnits
    ) {
        public Policy {
            if (maxPathDepth < 0
                    || maxPathDepth > MAX_PATH_DEPTH
                    || maxRootSourceCodeUnits < 1
                    || maxRootSourceCodeUnits
                        > MAX_ROOT_SOURCE_CODE_UNITS
                    || maxSubtreeNodes < 1
                    || maxSubtreeNodes > MAX_SUBTREE_NODES
                    || maxSubtreeSourceCodeUnits < 1
                    || maxSubtreeSourceCodeUnits
                        > MAX_SUBTREE_SOURCE_CODE_UNITS
                    || maxFormattedCodeUnits < 1
                    || maxFormattedCodeUnits > MAX_FORMATTED_CODE_UNITS
                    || maxWorkUnits < 1
                    || maxWorkUnits > MAX_WORK_UNITS) {
                throw new IllegalArgumentException(
                    "exact subterm projection policy is invalid");
            }
        }

        public static Policy boundedDefaults() {
            return new Policy(
                256,
                1_000_000,
                4_096,
                100_000,
                200_000,
                10_000_000L);
        }

        public String canonicalMaterial() {
            return maxPathDepth + ":"
                + maxRootSourceCodeUnits + ":"
                + maxSubtreeNodes + ":"
                + maxSubtreeSourceCodeUnits + ":"
                + maxFormattedCodeUnits + ":"
                + maxWorkUnits;
        }
    }

    /** Deterministic stage-separated work consumed by one projection. */
    public record WorkLedger(Map<String, Long> stages) {
        public WorkLedger {
            Objects.requireNonNull(stages, "stages");
            TreeMap<String, Long> canonical = new TreeMap<>();
            stages.forEach((stage, units) -> {
                if (stage == null
                        || stage.isBlank()
                        || units == null
                        || units < 0) {
                    throw new IllegalArgumentException(
                        "subterm projection work ledger is invalid");
                }
                canonical.merge(stage, units, Math::addExact);
            });
            checkedTotal(canonical.values());
            stages = Collections.unmodifiableMap(
                new LinkedHashMap<>(canonical));
        }

        public long totalWorkUnits() {
            return checkedTotal(stages.values());
        }

        public long units(String stage) {
            return stages.getOrDefault(stage, 0L);
        }

        public boolean within(long maximum) {
            return maximum >= 0 && totalWorkUnits() <= maximum;
        }

        public String canonicalMaterial() {
            EvidenceDigest digest = new EvidenceDigest();
            stages.forEach((stage, units) -> {
                digest.append(stage);
                digest.append(Long.toString(units));
            });
            digest.append(Long.toString(totalWorkUnits()));
            return digest.finish();
        }

        private static long checkedTotal(Iterable<Long> values) {
            try {
                long total = 0;
                for (long value : values) {
                    total = Math.addExact(total, value);
                }
                return total;
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                    "subterm projection work exceeds long range",
                    exception);
            }
        }
    }

    /** Issuer-owned exact projection result. */
    public static final class Result {
        private final Status status;
        private final String detailCode;
        private final Policy policy;
        private final List<Integer> path;
        private final String expectedFormattedText;
        private final Optional<String> actualFormattedText;
        private final Optional<String> rootSourceHash;
        private final Optional<ExactParsedTerm.SourceRange> selectedRange;
        private final Optional<String> rangeCommitmentHash;
        private final Optional<ExactParsedTerm> projected;
        private final WorkLedger work;
        private final String certificateHash;

        private Result(
            Status status,
            String detailCode,
            Policy policy,
            List<Integer> path,
            String expectedFormattedText,
            Optional<String> actualFormattedText,
            Optional<String> rootSourceHash,
            Optional<ExactParsedTerm.SourceRange> selectedRange,
            Optional<String> rangeCommitmentHash,
            Optional<ExactParsedTerm> projected,
            WorkLedger work
        ) {
            this.status = Objects.requireNonNull(status, "status");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "projection detail code must not be blank");
            }
            this.detailCode = detailCode;
            this.policy = Objects.requireNonNull(policy, "policy");
            this.path = List.copyOf(Objects.requireNonNull(path, "path"));
            this.expectedFormattedText = Objects.requireNonNull(
                expectedFormattedText,
                "expectedFormattedText");
            this.actualFormattedText = Objects.requireNonNull(
                actualFormattedText,
                "actualFormattedText");
            this.rootSourceHash = validateHash(
                Objects.requireNonNull(rootSourceHash, "rootSourceHash"));
            this.selectedRange = Objects.requireNonNull(
                selectedRange,
                "selectedRange");
            this.rangeCommitmentHash = validateHash(
                Objects.requireNonNull(
                    rangeCommitmentHash,
                    "rangeCommitmentHash"));
            this.projected = Objects.requireNonNull(
                projected,
                "projected");
            this.work = Objects.requireNonNull(work, "work");
            if ((status == Status.PROJECTED) != projected.isPresent()) {
                throw new IllegalArgumentException(
                    "projection status/payload mismatch");
            }
            if (status == Status.PROJECTED
                    && (actualFormattedText.isEmpty()
                        || rootSourceHash.isEmpty()
                        || selectedRange.isEmpty()
                        || rangeCommitmentHash.isEmpty())) {
                throw new IllegalArgumentException(
                    "successful projection lacks occurrence evidence");
            }
            if (!work.within(policy.maxWorkUnits())) {
                throw new IllegalArgumentException(
                    "projection work exceeds policy");
            }
            this.certificateHash = certificate();
        }

        private static Optional<String> validateHash(
            Optional<String> hash
        ) {
            hash.ifPresent(value -> {
                if (!value.matches("sha256:[0-9a-f]{64}")) {
                    throw new IllegalArgumentException(
                        "projection evidence hash is invalid");
                }
            });
            return hash;
        }

        public Status status() {
            return status;
        }

        public String detailCode() {
            return detailCode;
        }

        public Policy policy() {
            return policy;
        }

        public List<Integer> path() {
            return path;
        }

        public String expectedFormattedText() {
            return expectedFormattedText;
        }

        public Optional<String> actualFormattedText() {
            return actualFormattedText;
        }

        public Optional<String> rootSourceHash() {
            return rootSourceHash;
        }

        public Optional<ExactParsedTerm.SourceRange> selectedRange() {
            return selectedRange;
        }

        public Optional<String> rangeCommitmentHash() {
            return rangeCommitmentHash;
        }

        public Optional<ExactParsedTerm> projected() {
            return projected;
        }

        public WorkLedger work() {
            return work;
        }

        public String certificateHash() {
            return certificateHash;
        }

        public boolean successful() {
            return status == Status.PROJECTED;
        }

        private String certificate() {
            EvidenceDigest digest = new EvidenceDigest();
            digest.append(PROJECTOR_ID);
            digest.append(status.name());
            digest.append(detailCode);
            digest.append(policy.canonicalMaterial());
            digest.append(path.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(".")));
            digest.append(expectedFormattedText);
            digest.append(actualFormattedText.orElse(""));
            digest.append(rootSourceHash.orElse(""));
            digest.append(selectedRange.map(
                ExactParsedTerm.SourceRange::canonicalMaterial).orElse(""));
            digest.append(rangeCommitmentHash.orElse(""));
            digest.append(projected.map(ExactParsedTerm::source).orElse(""));
            projected.ifPresent(term -> term.literals().forEach(literal -> {
                digest.append(Integer.toString(literal.startInclusive()));
                digest.append(Integer.toString(literal.endExclusive()));
                digest.append(literal.sourceLexeme());
                digest.append(literal.evidence().certificateHash());
            }));
            digest.append(work.canonicalMaterial());
            return digest.finish();
        }
    }

    private record Scan(
        int nodeCount,
        Map<Expr, ExactParsedTerm.SourceRange> shiftedRanges,
        List<ExactParsedTerm.LiteralOccurrence> literals,
        String rangeCommitmentHash
    ) {
        private Scan {
            shiftedRanges = Collections.unmodifiableMap(
                new IdentityHashMap<>(shiftedRanges));
            literals = List.copyOf(literals);
            if (nodeCount < 1
                    || !rangeCommitmentHash.matches(
                        "sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "subterm scan result is invalid");
            }
        }
    }

    private static final class Work {
        private final long limit;
        private final Map<String, Long> stages = new TreeMap<>();
        private long total;

        private Work(long limit) {
            this.limit = limit;
        }

        private void consume(String stage, long units) {
            if (units < 0 || total > limit - units) {
                throw limit("SUBTERM_PROJECTION_WORK_BUDGET_EXCEEDED");
            }
            if (units == 0) {
                return;
            }
            total += units;
            stages.merge(stage, units, Math::addExact);
        }

        private WorkLedger ledger() {
            return new WorkLedger(stages);
        }
    }

    private static final class EvidenceDigest {
        private final MessageDigest digest;

        private EvidenceDigest() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception);
            }
        }

        private void append(String value) {
            byte[] bytes = Objects.requireNonNull(value, "value")
                .getBytes(StandardCharsets.UTF_8);
            byte[] length = Integer.toString(bytes.length)
                .getBytes(StandardCharsets.US_ASCII);
            digest.update(length);
            digest.update((byte) ':');
            digest.update(bytes);
            digest.update((byte) '\n');
        }

        private String finish() {
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        }
    }

    private static final class ProjectionLimitReached
            extends RuntimeException {
        private ProjectionLimitReached(String detailCode) {
            super(detailCode);
        }
    }

    private static final class ProjectionInvariantFailure
            extends RuntimeException {
        private ProjectionInvariantFailure(String detailCode) {
            super(detailCode);
        }
    }
}
