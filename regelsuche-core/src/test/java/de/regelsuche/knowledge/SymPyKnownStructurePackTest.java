package de.regelsuche.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymPyKnownStructurePackTest {
    private static final String PACK_ID = "sympy-trigonometry";
    private static final String SYMPY_COMMIT =
        "fe935ceb303891d1f8bea4c03b19fd9ec9464b02";

    @Test
    void sympyStructuresStayDisabledUntilPackIsExplicitlySelected() {
        KnowledgePackRegistry registry = new KnowledgePackRegistry();

        assertTrue(registry.enabledKnownStructures(
            KnowledgePackSelection.CORE).stream()
            .noneMatch(structure -> structure.id().startsWith("sympy.")));

        List<KnownStructureDefinition> enabled =
            registry.enabledKnownStructures(
                KnowledgePackSelection.CORE.enablePack(PACK_ID));

        assertEquals(7, enabled.size());
        assertTrue(enabled.stream().allMatch(structure ->
            structure.metadata().sourceVersion().equals("1.14.0")));
        assertTrue(enabled.stream().allMatch(structure ->
            structure.metadata().sourceReference().contains(SYMPY_COMMIT)));
        assertTrue(enabled.stream().allMatch(structure ->
            structure.metadata().enabledRulePackIds().equals(
                List.of(PACK_ID))));
        assertTrue(enabled.stream().allMatch(structure ->
            structure.metadata().minimumEvidence()
                == KnownStructureEvidence.SYMBOLICALLY_VERIFIED));
    }

    @Test
    void selectedAtomicFuRulesAreIndependentlyRegistered() {
        List<String> ids = new KnowledgePackRegistry()
            .enabledRules(KnowledgePackSelection.CORE.enablePack(PACK_ID))
            .stream()
            .map(rule -> rule.id())
            .toList();

        assertTrue(ids.contains("sympy.trig.sec_def"));
        assertTrue(ids.contains("sympy.trig.csc_def"));
        assertTrue(ids.contains("sympy.trig.cot_def"));
    }

    @Test
    void externalKnownStructureRequiresConsequencesAndTranslationNotes(
        @TempDir Path tempDir
    ) throws Exception {
        Path missingNotes = tempDir.resolve("missing-notes.rules.yaml");
        Files.writeString(missingNotes, """
            packId: bad-structure-pack
            displayName: Bad structure pack
            sourceProject: External
            license: BSD-3-Clause
            sourceUrl: https://example.invalid/source
            sourceVersion: "1"
            sourceReference: fixture
            maturity: EXPERIMENTAL
            knownStructures:
              - id: external.structure
                domainId: algebra
                pattern: "?X^2"
                recognition: EXACT
                consequenceIds: [rule:external.square]
                minimumEvidence: SYMBOLICALLY_VERIFIED
            """);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new KnowledgePackLoader().load(missingNotes)
        );

        assertTrue(exception.getMessage().contains("translationNotes"));
    }
}
