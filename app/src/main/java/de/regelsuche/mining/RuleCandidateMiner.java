package de.regelsuche.mining;

import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RuleCandidateMiner {
    private static final int MIN_EXAMPLES = 3;
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
        Map<String, List<SuccessfulTransformationPath>> clusters = new LinkedHashMap<>();
        for (SuccessfulTransformationPath path : paths) {
            clusters.computeIfAbsent(patternGeneralizer.skeleton(path), key -> new ArrayList<>()).add(path);
        }

        Map<String, CandidateBucket> buckets = new LinkedHashMap<>();
        for (List<SuccessfulTransformationPath> cluster : clusters.values()) {
            if (cluster.size() < MIN_EXAMPLES) {
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

        return buckets.values().stream().map(bucket -> bucket.toCandidate(knownRules)).toList();
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
            this.paths.addAll(paths);
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
