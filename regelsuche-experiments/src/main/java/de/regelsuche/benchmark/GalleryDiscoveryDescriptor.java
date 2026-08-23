package de.regelsuche.benchmark;

import de.regelsuche.transform.PolynomialDecompositionSynthesisOperator;
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
    private static final String SOPHIE_GERMAIN_DESCRIPTOR =
        "sophie-germain-discovery";

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
        if (!matchesRequiredRules(row)) {
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

    private boolean matchesRequiredRules(
        DeterministicDiscoveryExperimentRunner.SeedRunReport row
    ) {
        if (row.rulePath().containsAll(requiredRuleIds)) {
            return true;
        }
        return SOPHIE_GERMAIN_DESCRIPTOR.equals(id)
            && row.rulePath().contains(
                PolynomialDecompositionSynthesisOperator.RULE_ID);
    }

    /**
     * Keeps the historic gallery entry reproducible while describing the new
     * direct coefficient-synthesis route accurately when it supplied the path.
     */
    @Override
    public Function<DeterministicDiscoveryExperimentRunner.SeedRunReport, List<String>> renderMetadata() {
        Function<DeterministicDiscoveryExperimentRunner.SeedRunReport, List<String>> configured =
            this.renderMetadata;
        if (!SOPHIE_GERMAIN_DESCRIPTOR.equals(id)) {
            return configured;
        }
        return row -> {
            if (!row.rulePath().contains(
                    PolynomialDecompositionSynthesisOperator.RULE_ID)) {
                return configured.apply(row);
            }
            return List.of(
                "input: `" + row.seed().expression() + "`",
                "synthesized factorization: `"
                    + row.replayPath().getLast() + "`",
                "method: exact semantic polynomial coefficient synthesis",
                "rules used: " + String.join(" -> ", row.rulePath()),
                "proof/equivalence status: "
                    + row.counterexampleSearchStatus().name(),
                "replay source: generated search/replay path in this report");
        };
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
