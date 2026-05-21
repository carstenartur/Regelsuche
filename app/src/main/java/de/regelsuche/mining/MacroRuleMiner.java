package de.regelsuche.mining;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Mines recurring atomic-rule sequences from discovered transformations.
 *
 * <p>Algorithm (sliding-window frequency analysis):
 * <ol>
 *   <li>Collect each {@link DiscoveredTransformation}'s rule-id sequence.</li>
 *   <li>For each window length {@code n &gt;= minSequenceLength} up to
 *       {@code maxSequenceLength}, count the number of occurrences of every
 *       contiguous subsequence across all transformations.</li>
 *   <li>Emit a {@link MacroRuleCandidate} for every distinct subsequence that
 *       occurs at least {@code minOccurrences} times.</li>
 * </ol>
 *
 * <p>For each candidate the witnessed start/end expressions form the
 * {@code leftPattern}/{@code rightPattern}. Future PRs may apply
 * {@link RulePatternParser anti-unification} to abstract these into generic
 * patterns; the current concrete-witness form is enough for the
 * {@code identity report} use-case.</p>
 *
 * <p>Defaults are tunable via the constructor:
 * {@code minOccurrences = 2}, {@code minSequenceLength = 2},
 * {@code maxSequenceLength = 4}.</p>
 */
public final class MacroRuleMiner {

    /** Default lower bound for how often a sequence must occur. */
    public static final int DEFAULT_MIN_OCCURRENCES = 2;

    /** Default minimum subsequence length (a single step is not a macro). */
    public static final int DEFAULT_MIN_LENGTH = 2;

    /** Default maximum subsequence length to keep search bounded. */
    public static final int DEFAULT_MAX_LENGTH = 4;

    private final int minOccurrences;
    private final int minSequenceLength;
    private final int maxSequenceLength;

    public MacroRuleMiner() {
        this(DEFAULT_MIN_OCCURRENCES, DEFAULT_MIN_LENGTH, DEFAULT_MAX_LENGTH);
    }

    public MacroRuleMiner(int minOccurrences, int minSequenceLength, int maxSequenceLength) {
        if (minOccurrences < 1) {
            throw new IllegalArgumentException("minOccurrences must be >= 1");
        }
        if (minSequenceLength < 1) {
            throw new IllegalArgumentException("minSequenceLength must be >= 1");
        }
        if (maxSequenceLength < minSequenceLength) {
            throw new IllegalArgumentException("maxSequenceLength must be >= minSequenceLength");
        }
        this.minOccurrences = minOccurrences;
        this.minSequenceLength = minSequenceLength;
        this.maxSequenceLength = maxSequenceLength;
    }

    public List<MacroRuleCandidate> mine(List<DiscoveredTransformation> transformations) {
        Objects.requireNonNull(transformations, "transformations");
        if (transformations.isEmpty()) {
            return List.of();
        }

        // Group occurrences by the sequence key.
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        for (DiscoveredTransformation transformation : transformations) {
            List<TransformationStep> steps = transformation.steps();
            if (steps.size() < minSequenceLength) {
                continue;
            }
            List<String> ruleIds = steps.stream().map(TransformationStep::ruleId).toList();

            int upper = Math.min(maxSequenceLength, ruleIds.size());
            for (int length = minSequenceLength; length <= upper; length++) {
                for (int start = 0; start + length <= ruleIds.size(); start++) {
                    List<String> window = ruleIds.subList(start, start + length);
                    String key = String.join(">", window);
                    Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(List.copyOf(window)));
                    bucket.occurrences++;
                    bucket.addExample(
                        transformation.id(),
                        steps.get(start).beforeExpression(),
                        steps.get(start + length - 1).afterExpression()
                    );
                }
            }
        }

        // Emit candidates that occur often enough.
        List<MacroRuleCandidate> result = new ArrayList<>();
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            Bucket bucket = entry.getValue();
            if (bucket.occurrences < minOccurrences) {
                continue;
            }
            String id = "macro:" + entry.getKey();
            result.add(new MacroRuleCandidate(
                id,
                bucket.sequence,
                bucket.occurrences,
                bucket.firstLeftPattern,
                bucket.firstRightPattern,
                (double) bucket.sequence.size(),
                CandidateProofStatus.OBSERVED,
                List.copyOf(bucket.supportingIds)
            ));
        }
        return result;
    }

    private static final class Bucket {
        final List<String> sequence;
        int occurrences = 0;
        String firstLeftPattern = "";
        String firstRightPattern = "";
        final List<String> supportingIds = new ArrayList<>();

        Bucket(List<String> sequence) {
            this.sequence = sequence;
        }

        void addExample(String pathId, String left, String right) {
            if (firstLeftPattern.isEmpty()) {
                firstLeftPattern = left;
                firstRightPattern = right;
            }
            if (pathId != null && !pathId.isBlank() && !supportingIds.contains(pathId)) {
                supportingIds.add(pathId);
            }
        }
    }
}
