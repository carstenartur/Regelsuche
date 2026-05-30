package de.regelsuche.mining;

import de.regelsuche.validation.CandidateProofStatus;

import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RuleCandidateMiner {
    private final KnownRuleRepository knownRules;
    private final PatternGeneralizer patternGeneralizer;
    private final CandidateValidator validator;

    public RuleCandidateMiner(KnownRuleRepository knownRules) {
        this(knownRules, new SymPyEquivalenceService());
    }

    public RuleCandidateMiner(KnownRuleRepository knownRules, EquivalenceService equivalenceService) {
        this.knownRules = knownRules;
        this.patternGeneralizer = new PatternGeneralizer();
        this.validator = new CandidateValidator(equivalenceService);
    }

    public List<RuleCandidate> mine(List<SuccessfulTransformationPath> paths) {
        return mine(paths, DiscoverySettings.defaults());
    }

    public List<RuleCandidate> mine(List<SuccessfulTransformationPath> paths, DiscoverySettings settings) {
        DiscoverySettings effective = settings == null ? DiscoverySettings.defaults() : settings;
        int minExamples = Math.max(1, effective.minExamplesPerCandidate());
        CandidateProofStatus minReusableStatus = effective.minReusableStatus();
        Map<String, List<SuccessfulTransformationPath>> clusters = new LinkedHashMap<>();
        for (SuccessfulTransformationPath path : paths) {
            clusters.computeIfAbsent(patternGeneralizer.skeleton(path), key -> new ArrayList<>()).add(path);
            if (path.rules() != null && !path.rules().isEmpty()) {
                clusters.computeIfAbsent("rules:" + String.join(">", path.rules()), key -> new ArrayList<>()).add(path);
            }
        }

        Map<String, CandidateBucket> buckets = new LinkedHashMap<>();
        for (List<SuccessfulTransformationPath> cluster : clusters.values()) {
            if (cluster.size() < minExamples) {
                continue;
            }
            patternGeneralizer.generalize(cluster)
                .filter(validator::validate)
                .ifPresent(pattern -> {
                    String hash = RulePatternCanonicalizer.hash(pattern.leftPattern(), pattern.rightPattern());
                    buckets.computeIfAbsent(
                        hash,
                        key -> new CandidateBucket(
                            pattern.leftPattern(),
                            pattern.rightPattern(),
                            pattern.parameterRelations(),
                            validator.proofStatus(pattern),
                            hash
                        )
                    ).addAll(cluster);
                });
        }

        return buckets.values().stream()
            .filter(bucket -> bucket.paths.size() >= minExamples)
            .filter(bucket -> minReusableStatus == null
                || bucket.proofStatus.ordinal() >= minReusableStatus.ordinal())
            .map(bucket -> bucket.toCandidate(knownRules))
            .toList();
    }

    public Optional<RuleCandidate> mineFromSinglePathForValidatedSchema(SuccessfulTransformationPath path) {
        if (path == null || !path.equivalenceVerified()) {
            return Optional.empty();
        }
        return patternGeneralizer.generalizeSingleExampleSchema(path)
            .filter(pattern -> !pattern.expressionPlaceholderValues().isEmpty())
            .filter(validator::validateGeneratedExpressionInstantiations)
            .filter(validator::validate)
            .map(pattern -> toSinglePathCandidate(path, pattern))
            .filter(candidate -> candidate.status() == RuleStatus.NEW);
    }

    public List<RuleCandidate> mineFromSinglePathForValidatedSchema(List<SuccessfulTransformationPath> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        Map<String, RuleCandidate> deduplicated = new LinkedHashMap<>();
        for (SuccessfulTransformationPath path : paths) {
            mineFromSinglePathForValidatedSchema(path)
                .ifPresent(candidate -> deduplicated.putIfAbsent(candidate.canonicalHash(), candidate));
        }
        return List.copyOf(deduplicated.values());
    }

    private RuleCandidate toSinglePathCandidate(SuccessfulTransformationPath path, GeneralizedPattern pattern) {
        CandidateProofStatus proofStatus = validator.proofStatus(pattern);
        String hash = RulePatternCanonicalizer.hash(pattern.leftPattern(), pattern.rightPattern());
        return new RuleCandidate(
            pattern.leftPattern(),
            pattern.rightPattern(),
            1,
            path.scoreImprovement(),
            path.scoreImprovement(),
            path.equivalenceVerified(),
            true,
            !pattern.expressionPlaceholderValues().isEmpty(),
            pattern.parameterRelations(),
            knownRules.statusFor(pattern.leftPattern(), pattern.rightPattern()),
            proofStatus,
            hash,
            List.of(path.id())
        );
    }

    private static final class CandidateBucket {
        private final String leftPattern;
        private final String rightPattern;
        private final List<String> parameterRelations;
        private final CandidateProofStatus proofStatus;
        private final String hash;
        private final List<SuccessfulTransformationPath> paths = new ArrayList<>();

        private CandidateBucket(
            String leftPattern,
            String rightPattern,
            List<String> parameterRelations,
            CandidateProofStatus proofStatus,
            String hash
        ) {
            this.leftPattern = leftPattern;
            this.rightPattern = rightPattern;
            this.parameterRelations = List.copyOf(parameterRelations);
            this.proofStatus = proofStatus;
            this.hash = hash;
        }

        private void addAll(List<SuccessfulTransformationPath> paths) {
            for (SuccessfulTransformationPath path : paths) {
                boolean alreadyPresent = this.paths.stream()
                    .anyMatch(existing -> existing.id() != null && existing.id().equals(path.id()));
                if (!alreadyPresent) {
                    this.paths.add(path);
                }
            }
        }

        private RuleCandidate toCandidate(KnownRuleRepository knownRules) {
            double average = paths.stream().mapToInt(SuccessfulTransformationPath::scoreImprovement).average().orElse(0);
            int maximum = paths.stream().mapToInt(SuccessfulTransformationPath::scoreImprovement).max().orElse(0);
            boolean equivalenceVerified = paths.stream().allMatch(SuccessfulTransformationPath::equivalenceVerified);
            List<String> supportingIds = paths.stream()
                .map(SuccessfulTransformationPath::id)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
            return new RuleCandidate(
                leftPattern,
                rightPattern,
                paths.size(),
                average,
                maximum,
                equivalenceVerified,
                true,
                leftPattern.contains("A") || rightPattern.contains("A"),
                parameterRelations,
                knownRules.statusFor(leftPattern, rightPattern),
                proofStatus,
                hash,
                supportingIds
            );
        }
    }
}
