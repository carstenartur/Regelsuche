package de.regelsuche.discovery.representation;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Classifies target-free search trace occurrences by structural
 * representation correspondence to a target expression, as distinct from
 * value/semantic equivalence.
 *
 * <p>The historical-rediscovery atlas previously qualified a hit through
 * {@code SymPyEquivalenceService.areEquivalent(candidate, target)} for
 * {@code VALUE_EQUIVALENT} cases. That relation is true whenever the source
 * and target denote the same value by a standing algebraic identity, which
 * for identities such as Sophie Germain's is true of the unchanged source
 * expression at depth 0. Crediting that depth-0 hit as a rediscovery
 * conflates semantic truth with representation occurrence: the search never
 * had to change the representation to satisfy value equivalence.</p>
 *
 * <p>This classifier instead compares the assumption-free algebraic-normal
 * form ({@link ExpressionCanonicalizer#canonicalize(String)}) of each trace
 * step to that of the target. Two expressions correspond to the same
 * representation class when they canonicalize identically, which happens
 * for permitted commutative/associative regrouping and reordering, but not
 * for arbitrary algebraically equivalent expanded/factorized forms, since
 * those have different top-level structure. A match found only at depth 0
 * is reported as a false-positive diagnostic rather than as a genuine
 * representation rediscovery.</p>
 */
public final class RepresentationCorrespondenceClassifier {
    private final ExpressionCanonicalizer canonicalizer;

    public RepresentationCorrespondenceClassifier() {
        this(new ExpressionCanonicalizer());
    }

    public RepresentationCorrespondenceClassifier(
        ExpressionCanonicalizer canonicalizer
    ) {
        this.canonicalizer = Objects.requireNonNull(
            canonicalizer, "canonicalizer");
    }

    /**
     * Whether two expressions belong to the same representation class, that
     * is, whether they canonicalize to the identical assumption-free
     * algebraic-normal form.
     */
    public enum Correspondence {
        SAME_REPRESENTATION_CLASS,
        DIFFERENT_REPRESENTATION_CLASS
    }

    /**
     * Terminal localization of a single trace evaluation against a target
     * representation class.
     */
    public enum RediscoveryStatus {
        /**
         * The frozen source expression, at depth 0, already belongs to the
         * target's representation class; no representation change was
         * required, so this is a false-positive rediscovery diagnostic.
         */
        SOURCE_ALREADY_MATCHES_FALSE_POSITIVE,
        /** A later trace step first reached the target representation class. */
        REPRESENTATION_REDISCOVERED,
        /** No trace step reached the target representation class. */
        NOT_REACHED
    }

    /** A single frozen search-trace occurrence at a given depth. */
    public record TraceStep(int depth, String expression) {
        public TraceStep {
            Objects.requireNonNull(expression, "expression");
            if (depth < 0) {
                throw new IllegalArgumentException(
                    "depth must not be negative");
            }
        }
    }

    /** Evidence produced by {@link #evaluateTrace(List, String)}. */
    public record RediscoveryEvidence(
        RediscoveryStatus status,
        Integer matchedDepth,
        String matchedExpression
    ) {
        public RediscoveryEvidence {
            Objects.requireNonNull(status, "status");
            if (status == RediscoveryStatus.NOT_REACHED) {
                if (matchedDepth != null || matchedExpression != null) {
                    throw new IllegalArgumentException(
                        "NOT_REACHED must not carry a matched occurrence");
                }
            } else {
                Objects.requireNonNull(matchedDepth, "matchedDepth");
                Objects.requireNonNull(matchedExpression, "matchedExpression");
            }
        }
    }

    public Correspondence classify(
        String candidateExpression,
        String targetExpression
    ) {
        Objects.requireNonNull(candidateExpression, "candidateExpression");
        Objects.requireNonNull(targetExpression, "targetExpression");
        String candidateCanonical =
            canonicalizer.canonicalize(candidateExpression);
        String targetCanonical = canonicalizer.canonicalize(targetExpression);
        return candidateCanonical.equals(targetCanonical)
            ? Correspondence.SAME_REPRESENTATION_CLASS
            : Correspondence.DIFFERENT_REPRESENTATION_CLASS;
    }

    /**
     * Evaluates a frozen, depth-ordered search trace (including the source
     * expression at depth 0) against the target's representation class,
     * reporting the shallowest occurrence and distinguishing a genuine
     * rediscovery (first match at depth &gt; 0) from a depth-0
     * value-equivalence false positive.
     */
    public RediscoveryEvidence evaluateTrace(
        List<TraceStep> trace,
        String targetExpression
    ) {
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(targetExpression, "targetExpression");
        if (trace.isEmpty()) {
            throw new IllegalArgumentException("trace must not be empty");
        }
        TraceStep match = trace.stream()
            .filter(step -> classify(step.expression(), targetExpression)
                == Correspondence.SAME_REPRESENTATION_CLASS)
            .min(Comparator.comparingInt(TraceStep::depth))
            .orElse(null);
        if (match == null) {
            return new RediscoveryEvidence(
                RediscoveryStatus.NOT_REACHED, null, null);
        }
        RediscoveryStatus status = match.depth() == 0
            ? RediscoveryStatus.SOURCE_ALREADY_MATCHES_FALSE_POSITIVE
            : RediscoveryStatus.REPRESENTATION_REDISCOVERED;
        return new RediscoveryEvidence(
            status, match.depth(), match.expression());
    }
}
