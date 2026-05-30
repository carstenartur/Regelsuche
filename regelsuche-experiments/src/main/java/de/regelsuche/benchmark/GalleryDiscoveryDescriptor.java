package de.regelsuche.benchmark;

import de.regelsuche.validation.DiscoveryEvidenceKind;
import de.regelsuche.validation.DiscoveryResultKind;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Data-driven eligibility and rendering metadata for generated discovery gallery entries. */
public record GalleryDiscoveryDescriptor(
    String id,
    String title,
    Optional<Pattern> requiredInputPattern,
    List<String> requiredRuleIds,
    DiscoveryResultKind minimumResultKind,
    Set<DiscoveryEvidenceKind> requiredEvidenceKinds,
    List<Function<DeterministicDiscoveryExperimentRunner.SeedRunReport, Boolean>> requiredPredicates,
    Function<DeterministicDiscoveryExperimentRunner.SeedRunReport, List<String>> renderMetadata
) {
    public GalleryDiscoveryDescriptor {
        if (id == null || id.isBlank() || title == null || title.isBlank()) {
            throw new IllegalArgumentException("id and title are required");
        }
        requiredInputPattern = requiredInputPattern == null ? Optional.empty() : requiredInputPattern;
        requiredRuleIds = requiredRuleIds == null ? List.of() : List.copyOf(requiredRuleIds);
        minimumResultKind = minimumResultKind == null ? DiscoveryResultKind.NO_CANDIDATE : minimumResultKind;
        requiredEvidenceKinds = requiredEvidenceKinds == null ? Set.of() : Set.copyOf(requiredEvidenceKinds);
        requiredPredicates = requiredPredicates == null ? List.of() : List.copyOf(requiredPredicates);
        renderMetadata = renderMetadata == null ? row -> List.of() : renderMetadata;
    }

    public boolean matches(DeterministicDiscoveryExperimentRunner.SeedRunReport row) {
        if (row == null || row.replayPath().isEmpty()) {
            return false;
        }
        if (!row.rulePath().containsAll(requiredRuleIds)) {
            return false;
        }
        if (rank(row.resultKind()) < rank(minimumResultKind)) {
            return false;
        }
        if (!row.evidence().containsAll(requiredEvidenceKinds)) {
            return false;
        }
        if (requiredInputPattern.isPresent() && !requiredInputPattern.get().matcher(row.seed().expression()).matches()) {
            return false;
        }
        return requiredPredicates.stream().allMatch(predicate -> Boolean.TRUE.equals(predicate.apply(row)));
    }

    private int rank(DiscoveryResultKind kind) {
        return switch (kind == null ? DiscoveryResultKind.NO_CANDIDATE : kind) {
            case NO_CANDIDATE -> 0;
            case HYPOTHESIS_ONLY -> 1;
            case BRIDGE_FOUND -> 2;
            case TRANSFORMED -> 3;
            case FALSE_POSITIVE -> -1;
        };
    }
}
