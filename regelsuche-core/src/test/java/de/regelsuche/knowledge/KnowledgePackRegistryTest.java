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

    @Test
    void externalRulesRequireProvenance(@TempDir Path tempDir) throws Exception {
        Path pack = tempDir.resolve("missing-provenance.rules.yaml");
        Files.writeString(pack, """
                packId: bad-pack
                displayName: Bad Pack
                sourceProject: External
                license: BSD-3-Clause
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
        AstRewriteTransformationEngine engine = engineWithSympyPack(50);

        assertTrue(engine.transform("x^2 - y^2").stream().anyMatch(step ->
                step.rule().equals("sympy.poly.factor.difference_of_squares")
                        && step.transformedExpression().equals("(x - y) * (x + y)")));
        assertTrue(engine.transform("x^4 + 4*y^4").stream().anyMatch(step ->
                step.rule().equals("sympy.poly.factor.sophie_germain")));
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
