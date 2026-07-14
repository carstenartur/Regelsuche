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
        return HiddenRulePilotRuntimeCatalog.benchmarkTasks().stream()
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
        references.put("case-006", reference(
            "hidden_left_add_then_multiply_neutral_macro",
            "neutral-element-simplification",
            "(0 + A) * 1", "A"));
        references.put("case-007", reference(
            "hidden_left_multiply_then_add_neutral_macro",
            "neutral-element-simplification",
            "1 * (A + 0)", "A"));
        references.put("case-008", reference(
            "hidden_left_multiply_then_outer_add_macro",
            "neutral-element-simplification",
            "(1 * A) + 0", "A"));
        references.put("case-009", reference(
            "hidden_subtract_then_add_neutral_macro",
            "neutral-element-simplification",
            "(A - 0) + 0", "A"));
        references.put("case-010", reference(
            "hidden_divide_then_subtract_neutral_macro",
            "neutral-element-simplification",
            "(A / 1) - 0", "A"));
        references.put("case-011", reference(
            "hidden_left_add_then_divide_neutral_macro",
            "neutral-element-simplification",
            "(0 + A) / 1", "A"));
        references.put("case-012", reference(
            "hidden_left_multiply_then_subtract_neutral_macro",
            "neutral-element-simplification",
            "1 * (A - 0)", "A"));
        references.put("case-013", reference(
            "hidden_three_step_right_neutral_macro",
            "neutral-element-simplification",
            "((A + 0) - 0) / 1", "A"));
        references.put("case-014", reference(
            "hidden_three_step_mixed_neutral_macro",
            "neutral-element-simplification",
            "((1 * A) / 1) + 0", "A"));
        references.put("case-015", reference(
            "hidden_right_zero_then_add_macro",
            "zero-annihilation",
            "(A * 0) + 0", "0"));
        references.put("case-016", reference(
            "hidden_left_zero_then_subtract_macro",
            "zero-annihilation",
            "(0 * A) - 0", "0"));
        references.put("case-017", reference(
            "hidden_right_zero_then_divide_macro",
            "zero-annihilation",
            "(A * 0) / 1", "0"));
        references.put("case-018", reference(
            "hidden_nested_left_zero_macro",
            "zero-annihilation",
            "(1 * 0) * A", "0"));
        references.put("case-019", reference(
            "hidden_product_square_power_macro",
            "power-normalization",
            "(A * A)^2", "A^4"));
        references.put("case-020", reference(
            "hidden_mixed_product_power_macro",
            "power-normalization",
            "A^2 * A * A", "A^4"));
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
