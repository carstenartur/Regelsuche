package de.regelsuche.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.PatternRewriteRule;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class KnowledgePackCiGateTest {
    private static final String SYMPY_TRIGONOMETRY = "sympy-trigonometry";
    private static final String SYMPY_COMMIT =
        "fe935ceb303891d1f8bea4c03b19fd9ec9464b02";

    @Test
    void externalRulesSatisfyRegistrationGates() throws URISyntaxException {
        Path packDirectory = Path.of(Thread.currentThread()
                .getContextClassLoader()
                .getResource("rules/packs")
                .toURI());
        List<KnowledgePack> packs =
            new KnowledgePackLoader().loadAll(packDirectory);
        List<PatternRewriteRule> rules = packs.stream()
            .flatMap(pack -> pack.rules().stream())
            .toList();
        List<PatternRewriteRule> registrable = rules.stream()
            .filter(rule -> rule.descriptor().eligibleForRegistration())
            .toList();

        assertTrue(registrable.size() >= 30,
            "expected at least 30 external registrable rules");
        for (PatternRewriteRule rule : rules) {
            RuleDescriptor descriptor = rule.descriptor();
            assertTrue(!descriptor.eligibleForRegistration()
                || descriptor.status() == RuleStatus.VALIDATED
                || descriptor.status() == RuleStatus.REVIEWED);
            assertFalse(descriptor.originProject().isBlank(),
                rule.id() + " missing provenance origin");
            assertFalse(descriptor.packId().isBlank(),
                rule.id() + " missing pack id");
            assertFalse(descriptor.sourceReference().isBlank(),
                rule.id() + " missing source reference");
            assertFalse(descriptor.license().isBlank(),
                rule.id() + " missing license");
            if (descriptor.status() == RuleStatus.VALIDATED) {
                assertFalse(descriptor.validationExamples().isEmpty(),
                    rule.id() + " missing validation examples");
                assertTrue(descriptor.counterExamples().isEmpty(),
                    rule.id() + " has counterexamples");
            }
        }
    }

    @Test
    void sympyKnowledgeRequiresExplicitSelectionAndPinnedProvenance() {
        KnowledgePackRegistry registry = new KnowledgePackRegistry();
        assertTrue(registry.enabledKnownStructures(KnowledgePackSelection.CORE)
            .stream().noneMatch(form -> form.id().startsWith("sympy.")));

        KnowledgePackSelection selected = KnowledgePackSelection.CORE
            .enablePack(SYMPY_TRIGONOMETRY);
        List<KnowledgePack.KnownStructureDefinition> forms =
            registry.enabledKnownStructures(selected);
        List<String> rules = registry.enabledRules(selected).stream()
            .map(rule -> rule.id())
            .toList();

        assertEquals(7, forms.size());
        assertTrue(forms.stream().allMatch(form ->
            form.metadata().sourceReference().contains(SYMPY_COMMIT)
                && form.metadata().enabledRulePackIds().equals(
                    List.of(SYMPY_TRIGONOMETRY))
                && form.metadata().minimumEvidence()
                    == KnowledgePack.KnownStructureEvidence
                        .SYMBOLICALLY_VERIFIED));
        assertTrue(rules.containsAll(List.of(
            "sympy.trig.sec_def",
            "sympy.trig.csc_def",
            "sympy.trig.cot_def"
        )));
    }

    @Test
    void importedFormRequiresTranslationNotes() {
        assertThrows(IllegalArgumentException.class,
            () -> new KnowledgePack.KnownStructureMetadata(
                "SymPy",
                "BSD-3-Clause",
                "https://example.invalid",
                "1",
                "fixture",
                " ",
                List.of(SYMPY_TRIGONOMETRY),
                List.of(),
                KnowledgePack.KnownStructureEvidence.SYMBOLICALLY_VERIFIED
            ));
    }
}
