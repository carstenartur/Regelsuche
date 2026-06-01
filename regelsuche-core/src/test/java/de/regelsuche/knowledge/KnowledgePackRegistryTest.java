package de.regelsuche.knowledge;

import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgePackRegistryTest {
    private static final String SYMPY_PACK = "sympy-polynomial-basic";
    private static final String SYMPY_TRIG_PACK = "sympy-trigonometry-basic";
    private static final String SYMPY_RATIONAL_PACK = "sympy-rational-basic";
    private static final List<String> CANDIDATE_PACKS = List.of(SYMPY_TRIG_PACK, SYMPY_RATIONAL_PACK);

    @Test
    void externalRulesRequireProvenance(@TempDir Path tempDir) throws Exception {
        Path pack = tempDir.resolve("missing-provenance.rules.yaml");
        Files.writeString(pack, """
                packId: bad-pack
                displayName: Bad Pack
                sourceProject: External
                license: BSD-3-Clause
                sourceUrl: https://example.invalid/bad-pack
                sourceVersion: reviewed-test-fixture
                sourceReference: test fixture
                rules:
                  - id: bad.rule
                    status: VALIDATED
                    rule:
                      from: "?A^2"
                      to: "?A*?A"
                """);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new KnowledgePackLoader().load(pack));
        assertTrue(ex.getMessage().contains("derivationType"));
    }

    @Test
    void sympyPackIsDisabledByDefault() {
        KnowledgePackRegistry registry = new KnowledgePackRegistry();

        assertTrue(registry.allPacks().stream().map(KnowledgePack::packId).anyMatch(SYMPY_PACK::equals));
        assertTrue(registry.enabledRules(KnowledgePackSelection.CORE).isEmpty());
    }

    @Test
    void enablingSympyPolynomialBasicRegistersReviewedRules() {
        KnowledgePackRegistry registry = new KnowledgePackRegistry();
        List<RewriteRule> rules = registry.enabledRules(KnowledgePackSelection.CORE.enablePack(SYMPY_PACK))
                .stream()
                .map(rule -> (RewriteRule) rule)
                .toList();

        assertEquals(6, rules.size());
        assertTrue(rules.stream().map(RewriteRule::id).anyMatch("sympy.poly.factor.difference_of_squares"::equals));
        assertTrue(rules.stream().map(RewriteRule::id).anyMatch("sympy.poly.factor.sophie_germain"::equals));
        assertTrue(rules.stream().allMatch(rule -> SYMPY_PACK.equals(rule.descriptor().packId())));
        assertTrue(rules.stream().allMatch(rule -> "BSD-3-Clause".equals(rule.descriptor().license())));
        assertTrue(rules.stream().allMatch(rule -> rule.descriptor().derivationType() == DerivationType.REIMPLEMENTED_RULE));
        assertTrue(rules.stream().allMatch(rule -> rule.descriptor().eligibleForRegistration()));
    }


    @Test
    void validatedExternalRulesDeclareValidationExamples() {
        KnowledgePackRegistry registry = new KnowledgePackRegistry();

        assertTrue(registry.allPacks().stream()
                .filter(pack -> !pack.packId().equals("core"))
                .flatMap(pack -> pack.rules().stream())
                .filter(rule -> rule.descriptor().status() == RuleStatus.VALIDATED)
                .allMatch(rule -> !rule.descriptor().validationExamples().isEmpty()));
    }

    @Test
    void validatedExternalRuleWithoutExamplesIsRejected(@TempDir Path tempDir) throws Exception {
        Path pack = tempDir.resolve("missing-examples.rules.yaml");
        Files.writeString(pack, """
                packId: missing-examples
                displayName: Missing Examples
                sourceProject: External
                license: BSD-3-Clause
                sourceUrl: https://example.invalid/missing-examples
                sourceVersion: reviewed-test-fixture
                sourceReference: test fixture
                rules:
                  - id: missing.examples.rule
                    derivationType: REIMPLEMENTED_RULE
                    status: VALIDATED
                    rule:
                      from: "?A^2"
                      to: "?A*?A"
                """);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new KnowledgePackLoader().load(pack));
        assertTrue(ex.getMessage().contains("validation.examples"));
    }

    @Test
    void candidatePacksLoadButDoNotRegisterRules() {
        KnowledgePackRegistry registry = new KnowledgePackRegistry();

        List<KnowledgePack> candidatePacks = registry.allPacks().stream()
                .filter(pack -> CANDIDATE_PACKS.contains(pack.packId()))
                .toList();

        assertEquals(CANDIDATE_PACKS.size(), candidatePacks.size());
        assertTrue(candidatePacks.stream().noneMatch(KnowledgePack::enabledByDefault));
        assertEquals(7, candidatePacks.stream().mapToInt(pack -> pack.rules().size()).sum());
        assertTrue(candidatePacks.stream()
                .flatMap(pack -> pack.rules().stream())
                .allMatch(rule -> rule.descriptor().status() == RuleStatus.CANDIDATE || rule.descriptor().status() == RuleStatus.DISCOVERY_CANDIDATE));
        for (String candidatePack : CANDIDATE_PACKS) {
            assertTrue(registry.enabledRules(KnowledgePackSelection.CORE.enablePack(candidatePack)).isEmpty());
        }
    }

    @Test
    void candidateRulesStayOutOfAllProfileAndReplay() {
        KnowledgePackRegistry registry = new KnowledgePackRegistry();
        List<String> candidateRuleIds = registry.allPacks().stream()
                .filter(pack -> CANDIDATE_PACKS.contains(pack.packId()))
                .flatMap(pack -> pack.rules().stream())
                .map(RewriteRule::id)
                .toList();

        assertTrue(registry.enabledRules(KnowledgePackSelection.profile(RuleProfile.ALL)).stream()
                .map(RewriteRule::id)
                .noneMatch(candidateRuleIds::contains));

        AstRewriteTransformationEngine engine = AstRewriteTransformationEngine.withKnowledgePacks(
                KnowledgePackSelection.profile(RuleProfile.ALL));
        assertTrue(engine.rules().stream().map(RewriteRule::id).noneMatch(candidateRuleIds::contains));
        assertTrue(engine.transform("sin(x)^2 + cos(x)^2").stream()
                .map(Transformation::rule)
                .noneMatch(candidateRuleIds::contains));
        assertTrue(engine.transform("1/(x*(x+1))").stream()
                .map(Transformation::rule)
                .noneMatch(candidateRuleIds::contains));
    }

    @Test
    void sourceMetadataIsLoadedForPackAndRules() {
        KnowledgePack pack = new KnowledgePackRegistry().allPacks().stream()
                .filter(candidate -> candidate.packId().equals(SYMPY_PACK))
                .findFirst()
                .orElseThrow();

        assertEquals("SymPy 1.14.0 documentation", pack.sourceVersion());
        assertTrue(pack.sourceUrl().contains("sympy.polys.polytools.factor"));
        assertTrue(pack.sourceReference().contains("independently reimplemented"));
        assertTrue(pack.rules().stream().allMatch(rule -> !rule.descriptor().sourceVersion().isBlank()));
        assertTrue(pack.rules().stream().allMatch(rule -> !rule.descriptor().sourceReference().isBlank()));
    }

    @Test
    void enabledByDefaultPacksAreSelectedByCoreUnlessDisabled(@TempDir Path tempDir) throws Exception {
        Path pack = tempDir.resolve("default-enabled.rules.yaml");
        Files.writeString(pack, """
                packId: default-enabled
                displayName: Default Enabled
                sourceProject: External
                license: BSD-3-Clause
                sourceUrl: https://example.invalid/rules
                sourceVersion: reviewed-test-fixture
                sourceReference: test fixture
                enabledByDefault: true
                rules:
                  - id: default.enabled.rule
                    derivationType: REIMPLEMENTED_RULE
                    status: VALIDATED
                    rule:
                      from: "?A^2"
                      to: "?A*?A"
                    validation:
                      examples:
                        - from: "x^2"
                          to: "x * x"
                """);
        KnowledgePackRegistry registry = new KnowledgePackRegistry(new KnowledgePackLoader().loadAll(tempDir));

        assertTrue(registry.enabledRules(KnowledgePackSelection.CORE).stream()
                .map(RewriteRule::id)
                .anyMatch("default.enabled.rule"::equals));
        assertTrue(registry.enabledRules(KnowledgePackSelection.CORE.disablePack("default-enabled")).isEmpty());
    }

    @Test
    void disablingPackPreventsRuleUseEvenForAllProfile() {
        KnowledgePackSelection selection = KnowledgePackSelection.profile(RuleProfile.ALL).disablePack(SYMPY_PACK);
        AstRewriteTransformationEngine engine = AstRewriteTransformationEngine.withKnowledgePacks(selection);

        assertFalse(engine.rules().stream().map(RewriteRule::id).anyMatch("sympy.poly.factor.difference_of_squares"::equals));
    }

    @Test
    void replayMetadataContainsPackIdAndLicense() {
        AstRewriteTransformationEngine engine = AstRewriteTransformationEngine.withKnowledgePacks(
                KnowledgePackSelection.CORE.enablePack(SYMPY_PACK));

        Transformation transformation = engine.transform("x^3 + y^3").stream()
                .filter(step -> step.rule().equals("sympy.poly.factor.sum_of_cubes"))
                .findFirst()
                .orElseThrow();

        assertEquals(SYMPY_PACK, transformation.packId());
        assertEquals("BSD-3-Clause", transformation.license());
    }

    @Test
    void importedRulesValidateEquivalenceOnExamples() {
        AstRewriteTransformationEngine engine = engineWithSympyPack(80);
        List<RewriteRule> rules = new KnowledgePackRegistry().enabledRules(KnowledgePackSelection.CORE.enablePack(SYMPY_PACK))
                .stream()
                .map(rule -> (RewriteRule) rule)
                .toList();

        for (RewriteRule rule : rules) {
            for (ValidationExample example : rule.descriptor().validationExamples()) {
                assertTrue(engine.transform(example.from()).stream().anyMatch(step ->
                        step.rule().equals(rule.id()) && step.transformedExpression().equals(example.to())),
                        () -> "Expected " + rule.id() + " to transform " + example.from() + " to " + example.to());
            }
        }
    }

    private AstRewriteTransformationEngine engineWithSympyPack(int maxAstSizeIncreasePerStep) {
        List<RewriteRule> rules = new ArrayList<>(AstRewriteTransformationEngine.defaultRules());
        rules.addAll(new KnowledgePackRegistry().enabledRules(KnowledgePackSelection.CORE.enablePack(SYMPY_PACK)));
        return new AstRewriteTransformationEngine(rules, maxAstSizeIncreasePerStep, 120);
    }

    @Test
    void externalRulesAreNotPromotedToCoreSilently() {
        assertTrue(AstRewriteTransformationEngine.defaultRules().stream().allMatch(rule ->
                rule.descriptor().packId().equals("core") && !rule.id().startsWith("sympy.")));
    }
}
