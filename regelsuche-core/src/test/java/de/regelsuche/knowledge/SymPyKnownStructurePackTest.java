package de.regelsuche.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SymPyKnownStructurePackTest {
    private static final String PACK = "sympy-trigonometry";
    private static final String COMMIT =
        "fe935ceb303891d1f8bea4c03b19fd9ec9464b02";

    @Test
    void sympyKnowledgeRequiresExplicitSelectionAndPinnedProvenance() {
        KnowledgePackRegistry registry = new KnowledgePackRegistry();
        assertTrue(registry.enabledKnownStructures(KnowledgePackSelection.CORE)
            .stream().noneMatch(form -> form.id().startsWith("sympy.")));

        KnowledgePackSelection selected =
            KnowledgePackSelection.CORE.enablePack(PACK);
        List<KnownStructureDefinition> forms =
            registry.enabledKnownStructures(selected);
        List<String> rules = registry.enabledRules(selected).stream()
            .map(rule -> rule.id()).toList();

        assertEquals(7, forms.size());
        assertTrue(forms.stream().allMatch(form ->
            form.metadata().sourceReference().contains(COMMIT)
                && form.metadata().enabledRulePackIds().equals(List.of(PACK))
                && form.metadata().minimumEvidence()
                    == KnownStructureEvidence.SYMBOLICALLY_VERIFIED));
        assertTrue(rules.containsAll(List.of(
            "sympy.trig.sec_def", "sympy.trig.csc_def", "sympy.trig.cot_def")));
    }

    @Test
    void importedFormRequiresTranslationNotes() {
        assertThrows(IllegalArgumentException.class,
            () -> new KnownStructureMetadata(
                "SymPy", "BSD-3-Clause", "https://example.invalid", "1",
                "fixture", " ", List.of(PACK), List.of(),
                KnownStructureEvidence.SYMBOLICALLY_VERIFIED));
    }
}
