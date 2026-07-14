package de.regelsuche.mining;

import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit, versioned weight profiles for the rankable interestingness components. */
public enum InterestingnessProfile {
    THEORY_DISCOVERY(100, 190, 100, 70, 250, 190, 50, 50),
    SEARCH_REUSE(230, 100, 100, 230, 60, 100, 60, 120);

    public static final String WEIGHT_SCHEMA = "regelsuche.interestingness-profile/v1";

    private final int compression;
    private final int generalization;
    private final int independentEvidence;
    private final int reusability;
    private final int structuralSurprise;
    private final int crossFamilyTransfer;
    private final int assumptionSimplicity;
    private final int pairedUtility;

    InterestingnessProfile(
        int compression,
        int generalization,
        int independentEvidence,
        int reusability,
        int structuralSurprise,
        int crossFamilyTransfer,
        int assumptionSimplicity,
        int pairedUtility
    ) {
        this.compression = compression;
        this.generalization = generalization;
        this.independentEvidence = independentEvidence;
        this.reusability = reusability;
        this.structuralSurprise = structuralSurprise;
        this.crossFamilyTransfer = crossFamilyTransfer;
        this.assumptionSimplicity = assumptionSimplicity;
        this.pairedUtility = pairedUtility;
        int total = compression + generalization + independentEvidence + reusability
            + structuralSurprise + crossFamilyTransfer + assumptionSimplicity + pairedUtility;
        if (total != 1000) {
            throw new IllegalArgumentException("interestingness profile weights must sum to 1000");
        }
    }

    public int weight(String component) {
        return switch (component) {
            case "compression" -> compression;
            case "generalization" -> generalization;
            case "independentEvidence" -> independentEvidence;
            case "reusability" -> reusability;
            case "structuralSurprise" -> structuralSurprise;
            case "crossFamilyTransfer" -> crossFamilyTransfer;
            case "assumptionSimplicity" -> assumptionSimplicity;
            case "pairedUtility" -> pairedUtility;
            default -> throw new IllegalArgumentException("unknown interestingness component: " + component);
        };
    }

    public Map<String, Integer> weights() {
        LinkedHashMap<String, Integer> weights = new LinkedHashMap<>();
        weights.put("compression", compression);
        weights.put("generalization", generalization);
        weights.put("independentEvidence", independentEvidence);
        weights.put("reusability", reusability);
        weights.put("structuralSurprise", structuralSurprise);
        weights.put("crossFamilyTransfer", crossFamilyTransfer);
        weights.put("assumptionSimplicity", assumptionSimplicity);
        weights.put("pairedUtility", pairedUtility);
        return java.util.Collections.unmodifiableMap(weights);
    }
}
