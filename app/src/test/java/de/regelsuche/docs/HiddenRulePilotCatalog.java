package de.regelsuche.docs;

import de.regelsuche.docs.HiddenRulePilotCampaign.PilotCase;
import de.regelsuche.docs.HiddenRulePilotEvaluator.HiddenReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evaluation-only manifest; this source set is absent from the production runtime. */
final class HiddenRulePilotCatalog {
    private HiddenRulePilotCatalog() {
    }

    static List<PilotCase> cases() {
        Map<String, HiddenReference> references = references();
        return HiddenRulePilotRuntimeCatalog.tasks().stream()
            .map(task -> {
                HiddenReference reference = references.get(task.opaqueCaseId());
                if (reference == null) {
                    throw new IllegalStateException(
                        "missing post-hoc hidden reference for " + task.opaqueCaseId());
                }
                return new PilotCase(task, reference);
            })
            .toList();
    }

    static Map<String, HiddenReference> references() {
        Map<String, HiddenReference> references = new LinkedHashMap<>();
        references.put("case-001", reference(
            "hidden_neutral_element_macro",
            "neutral-element-simplification",
            "(A + 0) * 1", "A"));
        references.put("case-002", reference(
            "hidden_sophie_germain_macro",
            "quartic-factorization",
            "A^4 + 4*B^4",
            "(A^2 + 2*A*B + 2*B^2) * (A^2 - 2*A*B + 2*B^2)"));
        references.put("case-003", reference(
            "hidden_multiply_then_add_neutral_macro",
            "neutral-element-simplification",
            "(A * 1) + 0", "A"));
        references.put("case-004", reference(
            "hidden_subtract_then_divide_neutral_macro",
            "neutral-element-simplification",
            "(A - 0) / 1", "A"));
        references.put("case-005", reference(
            "hidden_quartic_normalization_macro",
            "power-normalization",
            "(A * A) * (A * A)", "A^4"));
        return Map.copyOf(references);
    }

    private static HiddenReference reference(
        String id,
        String family,
        String left,
        String right
    ) {
        return new HiddenReference(id, family, left, right, List.of(), List.of(family));
    }
}
