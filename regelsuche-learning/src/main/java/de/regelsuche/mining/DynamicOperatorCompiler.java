package de.regelsuche.mining;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Compiles a {@link de.regelsuche.docs.PatternHypothesisMiner.GeneralizedHypothesis}
 * (or any left/right pattern pair) into an executable {@link DynamicPatternOperator}
 * without requiring a hand-written Java operator class.
 *
 * <p>Validation checks performed before compilation:
 * <ol>
 *   <li><b>Parseable</b> – both left and right patterns must be parseable by
 *       {@link RulePatternParser}.</li>
 *   <li><b>Has placeholders</b> – the left pattern must contain at least one
 *       single upper-case letter placeholder (A–Z), otherwise there is nothing
 *       to bind and every expression would trivially "match".</li>
 *   <li><b>Non-trivial</b> – left and right patterns must differ (checked via
 *       their canonical hash).</li>
 *   <li><b>Right-hand completeness</b> – every placeholder in the right pattern
 *       must also appear in the left pattern, so that instantiation always
 *       succeeds.</li>
 *   <li><b>Cycle guard</b> – the compiled operator's rule ID is hypothesis-scoped
 *       and contains the {@code dynamic_hypothesis_} prefix, preventing
 *       self-activation via normal rule dispatch.</li>
 * </ol>
 * </p>
 *
 * <p>The resulting operator is in <em>CANDIDATE</em> (quarantined) state; it
 * cannot become globally active until it is promoted by
 * {@link DynamicCandidateRegistry} after positive holdout validation.</p>
 */
public final class DynamicOperatorCompiler {

    /** Single upper-case letter placeholder (A–Z). Matches the PatternGeneralizer convention. */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\b([A-Z])\\b");

    private static final int DEFAULT_MAX_CANDIDATES = 4;

    private final RulePatternParser patternParser = new RulePatternParser();

    /**
     * Compiles a generalized hypothesis into an executable {@link DynamicPatternOperator}.
     *
     * @param hypothesisId       the unique hypothesis identifier (used to build the rule ID
     *                           and provenance hash)
     * @param hypothesisRevision an optional revision tag attached to every emitted
     *                           transformation for traceability
     * @param leftPattern        the source pattern (e.g. {@code "A * B + A * C"})
     * @param rightPattern       the target template (e.g. {@code "A * (B + C)"})
     * @return the compiled operator, or empty together with a rejection reason if
     *         any validation check fails
     */
    public CompilationResult compile(
        String hypothesisId,
        String hypothesisRevision,
        String leftPattern,
        String rightPattern
    ) {
        return compile(hypothesisId, hypothesisRevision, leftPattern, rightPattern, DEFAULT_MAX_CANDIDATES);
    }

    /**
     * Compiles a generalized hypothesis into an executable {@link DynamicPatternOperator}.
     *
     * @param hypothesisId       the unique hypothesis identifier
     * @param hypothesisRevision optional revision tag
     * @param leftPattern        source pattern
     * @param rightPattern       target template
     * @param maxCandidates      maximum number of candidates to emit per call
     * @return a {@link CompilationResult} wrapping either the compiled operator or a
     *         rejection reason
     */
    public CompilationResult compile(
        String hypothesisId,
        String hypothesisRevision,
        String leftPattern,
        String rightPattern,
        int maxCandidates
    ) {
        if (hypothesisId == null || hypothesisId.isBlank()) {
            return CompilationResult.rejected("hypothesis-id-blank", "Hypothesis ID must not be blank");
        }
        if (leftPattern == null || leftPattern.isBlank()) {
            return CompilationResult.rejected(hypothesisId, "left-pattern-blank");
        }
        if (rightPattern == null || rightPattern.isBlank()) {
            return CompilationResult.rejected(hypothesisId, "right-pattern-blank");
        }

        RulePatternNode leftNode;
        RulePatternNode rightNode;
        try {
            leftNode = patternParser.parse(leftPattern);
        } catch (IllegalArgumentException ex) {
            return CompilationResult.rejected(hypothesisId, "left-pattern-unparseable: " + ex.getMessage());
        }
        try {
            rightNode = patternParser.parse(rightPattern);
        } catch (IllegalArgumentException ex) {
            return CompilationResult.rejected(hypothesisId, "right-pattern-unparseable: " + ex.getMessage());
        }

        List<String> leftPlaceholders = extractPlaceholders(leftPattern);
        if (leftPlaceholders.isEmpty()) {
            return CompilationResult.rejected(hypothesisId, "left-pattern-no-placeholders");
        }

        List<String> rightPlaceholders = extractPlaceholders(rightPattern);
        for (String rp : rightPlaceholders) {
            if (!leftPlaceholders.contains(rp)) {
                return CompilationResult.rejected(hypothesisId,
                    "right-placeholder-unbound:" + rp + " does not appear in left pattern");
            }
        }

        String leftHash = hashPattern(leftPattern);
        String rightHash = hashPattern(rightPattern);
        if (leftHash.equals(rightHash)) {
            return CompilationResult.rejected(hypothesisId, "trivial-rewrite:left==right");
        }

        String ruleId = DynamicPatternOperator.RULE_ID_PREFIX + sanitize(hypothesisId);
        String provenanceHash = hashProvenance(hypothesisId, leftPattern, rightPattern);

        DynamicPatternOperator operator = new DynamicPatternOperator(
            ruleId,
            hypothesisId,
            hypothesisRevision == null ? "" : hypothesisRevision,
            provenanceHash,
            leftPattern,
            rightPattern,
            leftNode,
            rightNode,
            maxCandidates
        );
        return CompilationResult.success(operator);
    }

    private List<String> extractPlaceholders(String pattern) {
        java.util.regex.Matcher m = PLACEHOLDER_PATTERN.matcher(pattern);
        List<String> result = new java.util.ArrayList<>();
        while (m.find()) {
            String name = m.group(1);
            if (!result.contains(name)) {
                result.add(name);
            }
        }
        return result;
    }

    private String hashPattern(String pattern) {
        return pattern.replaceAll("\\s+", "");
    }

    private String hashProvenance(String hypothesisId, String leftPattern, String rightPattern) {
        String combined = hypothesisId + "|" + leftPattern.replaceAll("\\s+", "") + "|" + rightPattern.replaceAll("\\s+", "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            return "hash-unavailable";
        }
    }

    /** Sanitizes a hypothesis ID for use as a Java-safe rule ID suffix. */
    private static String sanitize(String id) {
        return id.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The result of a compilation attempt. Either a compiled operator ({@link #isSuccess()}) or
     * a rejection with a human-readable reason.
     */
    public static final class CompilationResult {
        private final DynamicPatternOperator operator;
        private final String hypothesisId;
        private final String rejectionReason;

        private CompilationResult(DynamicPatternOperator operator, String hypothesisId, String rejectionReason) {
            this.operator = operator;
            this.hypothesisId = hypothesisId;
            this.rejectionReason = rejectionReason;
        }

        static CompilationResult success(DynamicPatternOperator operator) {
            return new CompilationResult(operator, operator.hypothesisId(), null);
        }

        static CompilationResult rejected(String hypothesisId, String reason) {
            return new CompilationResult(null, hypothesisId, reason);
        }

        /** Returns {@code true} if compilation succeeded. */
        public boolean isSuccess() {
            return operator != null;
        }

        /** Returns the compiled operator, or empty if compilation failed. */
        public Optional<DynamicPatternOperator> operator() {
            return Optional.ofNullable(operator);
        }

        /** The hypothesis ID this result pertains to. */
        public String hypothesisId() {
            return hypothesisId;
        }

        /** The human-readable rejection reason, or empty string if compilation succeeded. */
        public String rejectionReason() {
            return rejectionReason == null ? "" : rejectionReason;
        }
    }
}
