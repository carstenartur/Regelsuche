package de.regelsuche.mining;

import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

public class RuleCandidateMiner {
    private static final RuleCandidateFormationObserver NO_FORMATION_OBSERVER =
        (candidate, evidence) -> { };

    private final KnownRuleRepository knownRules;
    private final PatternGeneralizer patternGeneralizer;
    private final CandidateValidator validator;
    private final RuleCandidateFormationObserver formationObserver;
    private final BiFunction<String, String, String> canonicalHashFunction;

    public RuleCandidateMiner(KnownRuleRepository knownRules) {
        this(
            knownRules,
            new SymPyEquivalenceService(),
            NO_FORMATION_OBSERVER);
    }

    public RuleCandidateMiner(
        KnownRuleRepository knownRules,
        EquivalenceService equivalenceService
    ) {
        this(
            knownRules,
            equivalenceService,
            NO_FORMATION_OBSERVER);
    }

    public RuleCandidateMiner(
        KnownRuleRepository knownRules,
        EquivalenceService equivalenceService,
        RuleCandidateFormationObserver formationObserver
    ) {
        this(
            knownRules,
            equivalenceService,
            formationObserver,
            RulePatternCanonicalizer::hash);
    }

    RuleCandidateMiner(
        KnownRuleRepository knownRules,
        EquivalenceService equivalenceService,
        RuleCandidateFormationObserver formationObserver,
        BiFunction<String, String, String> canonicalHashFunction
    ) {
        this.knownRules = Objects.requireNonNull(
            knownRules,
            "knownRules");
        this.patternGeneralizer = new PatternGeneralizer();
        this.validator = new CandidateValidator(
            Objects.requireNonNull(
                equivalenceService,
                "equivalenceService"));
        this.formationObserver = Objects.requireNonNull(
            formationObserver,
            "formationObserver");
        this.canonicalHashFunction = Objects.requireNonNull(
            canonicalHashFunction,
            "canonicalHashFunction");
    }

    public List<RuleCandidate> mine(
        List<SuccessfulTransformationPath> paths
    ) {
        return mine(paths, DiscoverySettings.defaults());
    }

    public List<RuleCandidate> mine(
        List<SuccessfulTransformationPath> paths,
        DiscoverySettings settings
    ) {
        List<SuccessfulTransformationPath> checkedPaths = List.copyOf(
            Objects.requireNonNull(paths, "paths"));
        DiscoverySettings effective = settings == null
            ? DiscoverySettings.defaults()
            : settings;
        int minExamples = Math.max(
            1,
            effective.minExamplesPerCandidate());
        CandidateProofStatus minReusableStatus =
            effective.minReusableStatus();
        Map<String, List<SuccessfulTransformationPath>> clusters =
            new LinkedHashMap<>();
        for (SuccessfulTransformationPath path : checkedPaths) {
            SuccessfulTransformationPath checked = Objects.requireNonNull(
                path,
                "path");
            clusters.computeIfAbsent(
                patternGeneralizer.skeleton(checked),
                key -> new ArrayList<>()).add(checked);
            if (!checked.rules().isEmpty()) {
                clusters.computeIfAbsent(
                    "rules:" + String.join(">", checked.rules()),
                    key -> new ArrayList<>()).add(checked);
            }
        }

        Map<String, CandidateBucket> buckets = new LinkedHashMap<>();
        for (List<SuccessfulTransformationPath> cluster :
                clusters.values()) {
            if (cluster.size() < minExamples) {
                continue;
            }
            patternGeneralizer.generalize(cluster)
                .filter(validator::validate)
                .ifPresent(pattern -> {
                    CanonicalPatternIdentity identity = canonicalIdentity(
                        pattern.leftPattern(),
                        pattern.rightPattern());
                    CandidateBucket bucket = buckets.get(identity.hash());
                    if (bucket == null) {
                        bucket = new CandidateBucket(
                            pattern.leftPattern(),
                            pattern.rightPattern(),
                            pattern.parameterRelations(),
                            validator.proofStatus(pattern),
                            identity);
                        buckets.put(identity.hash(), bucket);
                    } else {
                        bucket.requireSameCanonicalContent(
                            pattern.parameterRelations(),
                            validator.proofStatus(pattern),
                            identity);
                    }
                    bucket.addAll(cluster);
                });
        }

        List<RuleCandidate> result = new ArrayList<>();
        for (CandidateBucket bucket : buckets.values()) {
            if (bucket.paths.size() < minExamples
                    || minReusableStatus != null
                        && bucket.proofStatus.ordinal()
                            < minReusableStatus.ordinal()) {
                continue;
            }
            RuleCandidate candidate = bucket.toCandidate(knownRules);
            observe(candidate, bucket.paths);
            result.add(candidate);
        }
        return List.copyOf(result);
    }

    public Optional<RuleCandidate> mineFromSinglePathForValidatedSchema(
        SuccessfulTransformationPath path
    ) {
        return formFromSinglePath(path).map(formed -> {
            observe(formed.candidate(), formed.sourcePaths());
            return formed.candidate();
        });
    }

    public List<RuleCandidate> mineFromSinglePathForValidatedSchema(
        List<SuccessfulTransformationPath> paths
    ) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        Map<String, FormedCandidate> deduplicated = new LinkedHashMap<>();
        for (SuccessfulTransformationPath path : paths) {
            formFromSinglePath(path).ifPresent(formed ->
                deduplicated.merge(
                    formed.candidate().canonicalHash(),
                    formed,
                    FormedCandidate::merge));
        }
        List<RuleCandidate> result = new ArrayList<>(
            deduplicated.size());
        for (FormedCandidate formed : deduplicated.values()) {
            observe(formed.candidate(), formed.sourcePaths());
            result.add(formed.candidate());
        }
        return List.copyOf(result);
    }

    private void observe(
        RuleCandidate candidate,
        List<SuccessfulTransformationPath> sourcePaths
    ) {
        if (formationObserver == NO_FORMATION_OBSERVER) {
            return;
        }
        formationObserver.onCandidateFormed(
            candidate,
            RuleCandidateFormationObserver.Evidence.fromPaths(sourcePaths));
    }

    private Optional<FormedCandidate> formFromSinglePath(
        SuccessfulTransformationPath path
    ) {
        if (path == null || !path.equivalenceVerified()) {
            return Optional.empty();
        }
        return patternGeneralizer.generalizeSingleExampleSchema(path)
            .filter(pattern ->
                !pattern.expressionPlaceholderValues().isEmpty())
            .filter(validator::validateGeneratedExpressionInstantiations)
            .filter(validator::validate)
            .map(pattern -> new FormedCandidate(
                toSinglePathCandidate(path, pattern),
                List.of(path)))
            .filter(formed ->
                formed.candidate().status() == RuleStatus.NEW);
    }

    private RuleCandidate toSinglePathCandidate(
        SuccessfulTransformationPath path,
        GeneralizedPattern pattern
    ) {
        CandidateProofStatus proofStatus = validator.proofStatus(pattern);
        CanonicalPatternIdentity identity = canonicalIdentity(
            pattern.leftPattern(),
            pattern.rightPattern());
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
            knownRules.statusFor(
                pattern.leftPattern(),
                pattern.rightPattern()),
            proofStatus,
            identity.hash(),
            List.of(path.id()));
    }

    private CanonicalPatternIdentity canonicalIdentity(
        String leftPattern,
        String rightPattern
    ) {
        String hash = canonicalHashFunction.apply(
            leftPattern,
            rightPattern);
        if (hash == null || hash.isBlank()) {
            throw new IllegalStateException(
                "canonical candidate hash must not be blank");
        }
        return CanonicalPatternIdentity.from(
            leftPattern,
            rightPattern,
            hash);
    }

    private record FormedCandidate(
        RuleCandidate candidate,
        List<SuccessfulTransformationPath> sourcePaths
    ) {
        private FormedCandidate {
            candidate = Objects.requireNonNull(candidate, "candidate");
            sourcePaths = List.copyOf(
                Objects.requireNonNull(sourcePaths, "sourcePaths"));
        }

        private FormedCandidate merge(FormedCandidate other) {
            Objects.requireNonNull(other, "other");
            CanonicalPatternIdentity.from(candidate)
                .requireSameHashContent(
                    CanonicalPatternIdentity.from(other.candidate));
            requireSameSemanticContent(candidate, other.candidate);
            List<SuccessfulTransformationPath> merged = new ArrayList<>(
                sourcePaths.size() + other.sourcePaths.size());
            merged.addAll(sourcePaths);
            merged.addAll(other.sourcePaths);
            return new FormedCandidate(candidate, merged);
        }

        private static void requireSameSemanticContent(
            RuleCandidate candidate,
            RuleCandidate other
        ) {
            if (!candidate.parameterRelations().equals(
                        other.parameterRelations())
                    || candidate.equivalenceVerified()
                        != other.equivalenceVerified()
                    || candidate.generalizationPlausible()
                        != other.generalizationPlausible()
                    || candidate.containsFreeParameters()
                        != other.containsFreeParameters()
                    || candidate.status() != other.status()
                    || candidate.proofStatus() != other.proofStatus()) {
                throw new IllegalStateException(
                    "canonical candidates have conflicting semantic content");
            }
        }
    }

    private record CanonicalPatternIdentity(
        String canonicalLeftPattern,
        String canonicalRightPattern,
        String hash
    ) {
        private CanonicalPatternIdentity {
            canonicalLeftPattern = Objects.requireNonNull(
                canonicalLeftPattern,
                "canonicalLeftPattern");
            canonicalRightPattern = Objects.requireNonNull(
                canonicalRightPattern,
                "canonicalRightPattern");
            hash = Objects.requireNonNull(hash, "hash");
        }

        private static CanonicalPatternIdentity from(
            String leftPattern,
            String rightPattern,
            String hash
        ) {
            return new CanonicalPatternIdentity(
                RulePatternCanonicalizer.canonicalize(leftPattern),
                RulePatternCanonicalizer.canonicalize(rightPattern),
                hash);
        }

        private static CanonicalPatternIdentity from(
            RuleCandidate candidate
        ) {
            Objects.requireNonNull(candidate, "candidate");
            return from(
                candidate.leftPattern(),
                candidate.rightPattern(),
                candidate.canonicalHash());
        }

        private void requireSameHashContent(
            CanonicalPatternIdentity other
        ) {
            Objects.requireNonNull(other, "other");
            if (!hash.equals(other.hash)
                    || !canonicalLeftPattern.equals(
                        other.canonicalLeftPattern)
                    || !canonicalRightPattern.equals(
                        other.canonicalRightPattern)) {
                throw new IllegalStateException(
                    "canonical candidate hash collision for key " + hash);
            }
        }
    }

    private static final class CandidateBucket {
        private final String leftPattern;
        private final String rightPattern;
        private final List<String> parameterRelations;
        private final CandidateProofStatus proofStatus;
        private final CanonicalPatternIdentity identity;
        private final List<SuccessfulTransformationPath> paths =
            new ArrayList<>();

        private CandidateBucket(
            String leftPattern,
            String rightPattern,
            List<String> parameterRelations,
            CandidateProofStatus proofStatus,
            CanonicalPatternIdentity identity
        ) {
            this.leftPattern = leftPattern;
            this.rightPattern = rightPattern;
            this.parameterRelations = List.copyOf(parameterRelations);
            this.proofStatus = proofStatus;
            this.identity = identity;
        }

        private void requireSameCanonicalContent(
            List<String> candidateParameterRelations,
            CandidateProofStatus candidateProofStatus,
            CanonicalPatternIdentity candidateIdentity
        ) {
            identity.requireSameHashContent(candidateIdentity);
            if (!parameterRelations.equals(candidateParameterRelations)
                    || proofStatus != candidateProofStatus) {
                throw new IllegalStateException(
                    "canonical candidate bucket has conflicting content");
            }
        }

        private void addAll(List<SuccessfulTransformationPath> paths) {
            for (SuccessfulTransformationPath path : paths) {
                boolean alreadyPresent = this.paths.stream()
                    .anyMatch(existing ->
                        existing.id() != null
                            && existing.id().equals(path.id()));
                if (!alreadyPresent) {
                    this.paths.add(path);
                }
            }
        }

        private RuleCandidate toCandidate(
            KnownRuleRepository knownRules
        ) {
            double average = paths.stream()
                .mapToInt(SuccessfulTransformationPath::scoreImprovement)
                .average()
                .orElse(0);
            int maximum = paths.stream()
                .mapToInt(SuccessfulTransformationPath::scoreImprovement)
                .max()
                .orElse(0);
            boolean equivalenceVerified = paths.stream()
                .allMatch(
                    SuccessfulTransformationPath::equivalenceVerified);
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
                leftPattern.contains("A")
                    || rightPattern.contains("A"),
                parameterRelations,
                knownRules.statusFor(leftPattern, rightPattern),
                proofStatus,
                identity.hash(),
                supportingIds);
        }
    }
}
