package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramValidationPlan.Configuration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable evidence input for later gates; this artifact grants no downstream authority. */
public record EvolutionRewriteProgramValidationHandoff(String schema,
    EvolutionRewriteProgramValidationSelection selection, Configuration selectedConfiguration,
    String finalTestStatus, String proofStatus, String externalNoveltyStatus,
    String promotionStatus, String publicEvidenceStatus, String contentHash) {
    public static final String SCHEMA = "regelsuche.evolution-rewrite-program-validation-handoff/v1";
    private static final String NOT_EVALUATED = "NOT_EVALUATED";

    public EvolutionRewriteProgramValidationHandoff {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported program VALIDATION handoff");
        }
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(selectedConfiguration, "selectedConfiguration");
        if (!selection.hasSelection() || !selection.selectedConfigurationHash().equals(selectedConfiguration.contentHash())
                || !selection.plan().configurations().contains(selectedConfiguration)) {
            throw new IllegalArgumentException("handoff differs from the complete selected program configuration");
        }
        for (String status : List.of(finalTestStatus, proofStatus, externalNoveltyStatus,
                promotionStatus, publicEvidenceStatus)) {
            if (!NOT_EVALUATED.equals(status)) {
                throw new IllegalArgumentException("downstream gates must remain NOT_EVALUATED");
            }
        }
        EvolutionProgramValidationJson.requireHash(contentHash, material(selection, selectedConfiguration));
    }

    static EvolutionRewriteProgramValidationHandoff create(EvolutionRewriteProgramValidationSelection selection) {
        Configuration chosen = selection.plan().configurations().stream()
            .filter(item -> item.contentHash().equals(selection.selectedConfigurationHash())).findFirst().orElseThrow();
        return new EvolutionRewriteProgramValidationHandoff(SCHEMA, selection, chosen,
            NOT_EVALUATED, NOT_EVALUATED, NOT_EVALUATED, NOT_EVALUATED, NOT_EVALUATED,
            EvolutionProgramValidationJson.hash(material(selection, chosen)));
    }

    public String toCanonicalJson() { return EvolutionProgramValidationJson.write(this); }
    public static EvolutionRewriteProgramValidationHandoff fromCanonicalJson(String json) {
        return EvolutionProgramValidationJson.read(json, EvolutionRewriteProgramValidationHandoff.class);
    }

    private static Map<String, Object> material(EvolutionRewriteProgramValidationSelection selection,
        Configuration configuration) {
        return EvolutionProgramValidationJson.material(SCHEMA, "selection", selection,
            "selectedConfiguration", configuration, "finalTestStatus", NOT_EVALUATED,
            "proofStatus", NOT_EVALUATED, "externalNoveltyStatus", NOT_EVALUATED,
            "promotionStatus", NOT_EVALUATED, "publicEvidenceStatus", NOT_EVALUATED);
    }
}
