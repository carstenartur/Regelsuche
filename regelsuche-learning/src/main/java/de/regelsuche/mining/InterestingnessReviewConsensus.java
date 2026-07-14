package de.regelsuche.mining;

import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Aggregates privacy-preserving, blinded relevance reviews without feeding labels
 * into candidate formation or interestingness assessment.
 */
public final class InterestingnessReviewConsensus {
    public static final String SCHEMA = "regelsuche.interestingness-review-consensus/v1";
    public static final int MIN_EXPERT_REVIEWS = 2;
    public static final int MAX_CONSENSUS_SPREAD_PERMILLE = 250;
    public static final int MIN_MEAN_CONFIDENCE_PERMILLE = 500;

    public ConsensusReport evaluate(List<ReviewLabel> labels) {
        List<ReviewLabel> ordered = orderedLabels(labels);
        validateUniqueReviewIds(ordered);
        validateOneExpertReviewPerReviewerAndCandidate(ordered);
        Map<String, List<ReviewLabel>> byCandidate = ordered.stream()
            .collect(Collectors.groupingBy(
                ReviewLabel::candidateId,
                TreeMap::new,
                Collectors.toList()));
        List<CandidateConsensus> candidates = byCandidate.entrySet().stream()
            .map(entry -> consensus(entry.getKey(), entry.getValue()))
            .toList();
        String contentHash = hash(canonicalMaterial(candidates));
        return new ConsensusReport(SCHEMA, candidates, contentHash);
    }

    private static List<ReviewLabel> orderedLabels(List<ReviewLabel> labels) {
        return labels == null
            ? List.of()
            : labels.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ReviewLabel::candidateId)
                    .thenComparing(label -> label.source().name())
                    .thenComparing(ReviewLabel::reviewerHash)
                    .thenComparing(ReviewLabel::reviewId))
                .toList();
    }

    private static void validateUniqueReviewIds(List<ReviewLabel> labels) {
        Set<String> ids = new LinkedHashSet<>();
        for (ReviewLabel label : labels) {
            if (!ids.add(label.reviewId())) {
                throw new IllegalArgumentException(
                    "duplicate review ID: " + label.reviewId());
            }
        }
    }

    private static void validateOneExpertReviewPerReviewerAndCandidate(
        List<ReviewLabel> labels
    ) {
        Set<String> reviewerCases = new LinkedHashSet<>();
        for (ReviewLabel label : labels) {
            if (label.source() != ReviewSource.EXPERT_REVIEW) {
                continue;
            }
            String key = label.candidateId() + "\u0001" + label.reviewerHash();
            if (!reviewerCases.add(key)) {
                throw new IllegalArgumentException(
                    "duplicate expert reviewer for candidate: " + label.candidateId());
            }
        }
    }

    private static CandidateConsensus consensus(
        String candidateId,
        List<ReviewLabel> labels
    ) {
        List<ReviewLabel> ordered = labels.stream()
            .sorted(Comparator.comparing(ReviewLabel::reviewId))
            .toList();
        List<ReviewLabel> experts = ordered.stream()
            .filter(label -> label.source() == ReviewSource.EXPERT_REVIEW)
            .toList();
        boolean fixturePresent = ordered.stream().anyMatch(label ->
            label.source() == ReviewSource.TEST_FIXTURE);
        int relevance = median((experts.isEmpty() ? ordered : experts).stream()
            .map(ReviewLabel::relevancePermille)
            .toList());
        int spread = spread(experts.stream()
            .map(ReviewLabel::relevancePermille)
            .toList());
        int confidence = mean(experts.stream()
            .map(ReviewLabel::confidencePermille)
            .toList());
        int blindExperts = (int) experts.stream().filter(ReviewLabel::blindReview).count();
        ConsensusStatus status = status(
            fixturePresent, experts.size(), blindExperts, spread, confidence);
        return new CandidateConsensus(
            candidateId,
            status,
            experts.size(),
            blindExperts,
            relevance,
            spread,
            confidence,
            ordered.stream().map(ReviewLabel::source).distinct().sorted().toList(),
            ordered.stream().map(ReviewLabel::reviewerHash).distinct().sorted().toList(),
            ordered.stream().flatMap(label -> label.rationaleCodes().stream())
                .distinct().sorted().toList());
    }

    private static ConsensusStatus status(
        boolean fixturePresent,
        int expertCount,
        int blindExpertCount,
        int spread,
        int meanConfidence
    ) {
        if (fixturePresent) {
            return ConsensusStatus.DEVELOPMENT_ONLY;
        }
        if (expertCount < MIN_EXPERT_REVIEWS) {
            return expertCount == 0
                ? ConsensusStatus.DEVELOPMENT_ONLY
                : ConsensusStatus.INSUFFICIENT_REVIEWS;
        }
        if (blindExpertCount != expertCount
                || spread > MAX_CONSENSUS_SPREAD_PERMILLE
                || meanConfidence < MIN_MEAN_CONFIDENCE_PERMILLE) {
            return ConsensusStatus.UNCERTAIN;
        }
        return ConsensusStatus.CONSENSUS;
    }

    private static int median(List<Integer> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
            ? sorted.get(middle)
            : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    private static int spread(List<Integer> values) {
        if (values.size() < 2) {
            return 0;
        }
        int minimum = values.stream().mapToInt(Integer::intValue).min().orElse(0);
        int maximum = values.stream().mapToInt(Integer::intValue).max().orElse(0);
        return maximum - minimum;
    }

    private static int mean(List<Integer> values) {
        return values.isEmpty()
            ? 0
            : (int) Math.round(values.stream().mapToInt(Integer::intValue).average().orElse(0));
    }

    private static String canonicalMaterial(List<CandidateConsensus> candidates) {
        StringBuilder material = new StringBuilder(SCHEMA)
            .append("\nminExpertReviews=").append(MIN_EXPERT_REVIEWS)
            .append("\nmaxSpread=").append(MAX_CONSENSUS_SPREAD_PERMILLE)
            .append("\nminConfidence=").append(MIN_MEAN_CONFIDENCE_PERMILLE);
        candidates.forEach(candidate -> material.append("\ncandidate=")
            .append(candidate.canonicalMaterial()));
        return material.toString();
    }

    private static String hash(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public enum ReviewSource {
        EXPERT_REVIEW,
        CONTROL_ASSIGNMENT,
        TEST_FIXTURE
    }

    public enum ConsensusStatus {
        CONSENSUS,
        UNCERTAIN,
        INSUFFICIENT_REVIEWS,
        DEVELOPMENT_ONLY
    }

    public record ReviewLabel(
        String reviewId,
        String candidateId,
        String reviewerHash,
        ReviewSource source,
        boolean blindReview,
        int relevancePermille,
        int confidencePermille,
        List<String> rationaleCodes
    ) {
        public ReviewLabel {
            requireText(reviewId, "reviewId");
            requireText(candidateId, "candidateId");
            requireSha256(reviewerHash, "reviewerHash");
            Objects.requireNonNull(source, "source");
            requirePermille(relevancePermille, "relevancePermille");
            requirePermille(confidencePermille, "confidencePermille");
            rationaleCodes = orderedStrings(rationaleCodes);
        }
    }

    public record CandidateConsensus(
        String candidateId,
        ConsensusStatus status,
        int countedExpertReviews,
        int blindExpertReviews,
        int consensusRelevancePermille,
        int spreadPermille,
        int meanConfidencePermille,
        List<ReviewSource> sources,
        List<String> reviewerHashes,
        List<String> rationaleCodes
    ) {
        public CandidateConsensus {
            requireText(candidateId, "candidateId");
            Objects.requireNonNull(status, "status");
            requireNonNegative(countedExpertReviews, "countedExpertReviews");
            requireNonNegative(blindExpertReviews, "blindExpertReviews");
            requirePermille(consensusRelevancePermille, "consensusRelevancePermille");
            requirePermille(spreadPermille, "spreadPermille");
            requirePermille(meanConfidencePermille, "meanConfidencePermille");
            sources = sources == null
                ? List.of()
                : sources.stream().distinct().sorted().toList();
            reviewerHashes = orderedStrings(reviewerHashes);
            rationaleCodes = orderedStrings(rationaleCodes);
        }

        String canonicalMaterial() {
            return candidateId + '|'
                + status.name() + '|'
                + countedExpertReviews + '|'
                + blindExpertReviews + '|'
                + consensusRelevancePermille + '|'
                + spreadPermille + '|'
                + meanConfidencePermille + '|'
                + sources + '|'
                + reviewerHashes + '|'
                + rationaleCodes;
        }
    }

    public record ConsensusReport(
        String schema,
        List<CandidateConsensus> candidates,
        String contentHash
    ) {
        public ConsensusReport {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported review-consensus schema");
            }
            candidates = candidates == null
                ? List.of()
                : candidates.stream()
                    .sorted(Comparator.comparing(CandidateConsensus::candidateId))
                    .toList();
            requireSha256(contentHash, "contentHash");
        }

        public CandidateConsensus requireCandidate(String candidateId) {
            return candidates.stream()
                .filter(candidate -> candidate.candidateId().equals(candidateId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                    "review consensus missing for candidate: " + candidateId));
        }

        public long count(ConsensusStatus status) {
            return candidates.stream().filter(candidate -> candidate.status() == status).count();
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("minimumExpertReviews", MIN_EXPERT_REVIEWS)
                .property("maximumConsensusSpreadPermille", MAX_CONSENSUS_SPREAD_PERMILLE)
                .property("minimumMeanConfidencePermille", MIN_MEAN_CONFIDENCE_PERMILLE)
                .array("candidates", array -> candidates.forEach(candidate ->
                    array.objectValue(object -> object
                        .property("candidateId", candidate.candidateId())
                        .property("status", candidate.status().name())
                        .property("countedExpertReviews", candidate.countedExpertReviews())
                        .property("blindExpertReviews", candidate.blindExpertReviews())
                        .property("consensusRelevancePermille",
                            candidate.consensusRelevancePermille())
                        .property("spreadPermille", candidate.spreadPermille())
                        .property("meanConfidencePermille",
                            candidate.meanConfidencePermille())
                        .stringArray("sources", candidate.sources().stream()
                            .map(Enum::name).toList())
                        .stringArray("reviewerHashes", candidate.reviewerHashes())
                        .stringArray("rationaleCodes", candidate.rationaleCodes()))))
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }

        public void write(Path output) {
            try {
                Path parent = output.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(output, toCanonicalJson(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private static List<String> orderedStrings(List<String> values) {
        return values == null
            ? List.of()
            : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hash");
        }
    }

    private static void requirePermille(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " must be in [0,1000]");
        }
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
