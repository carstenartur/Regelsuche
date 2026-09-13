package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.knowledge.RuleProfile;
import de.regelsuche.transform.PatternRewriteRule;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AblatableRuleOrderEncodingReviewTest {
    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void differentAdmittedIdCodeunitsCannotAliasBudgetSensitiveExecutionOrder() {
        var nativeRules = new KnowledgePackRegistry().enabledRules(KnowledgePackSelection.profile(RuleProfile.ALL));
        var pythagorean = (PatternRewriteRule) nativeRules.stream()
            .filter(rule -> rule.id().equals("sympy.trig.pythagorean")).findFirst().orElseThrow();
        var squares = (PatternRewriteRule) nativeRules.stream()
            .filter(rule -> rule.id().equals("sympy.poly.factor.diff_squares")).findFirst().orElseThrow();
        var rules = List.of(withId(pythagorean, "principal_\uD800"), withId(squares, "principal_?"));
        assertNotEquals(rules.get(0).id(), rules.get(1).id());
        assertArrayEquals(rules.get(0).id().getBytes(StandardCharsets.UTF_8),
            rules.get(1).id().getBytes(StandardCharsets.UTF_8), "ordinary replacement encoding loses the ID distinction");
        var defaults = AblatableRulePreparationRunner.Budget.publicControls();
        var budget = new AblatableRulePreparationRunner.Budget(defaults.bridge(), defaults.maxNodes(),
            defaults.maxExactAttempts(), 25, defaults.maxExactWorkUnits());
        var forward = new AblatableRulePreparationRunner(rules, List.of(), REVISION, budget);
        var reverse = new AblatableRulePreparationRunner(rules.reversed(), List.of(), REVISION, budget);
        var source = new AblatableRulePreparationRunner.Source("sin(x)^2 + cos(x)^2", List.of());
        var first = forward.analyze(AblatableRulePreparationRunner.Profile.DIRECT_ONLY, source);
        var second = reverse.analyze(AblatableRulePreparationRunner.Profile.DIRECT_ONLY, source);
        assertEquals(RuleInventoryFingerprint.contentHash(rules), RuleInventoryFingerprint.contentHash(rules.reversed()));
        assertNotEquals(AmplificationJson.read(first.canonicalJson()).get("stages"),
            AmplificationJson.read(second.canonicalJson()).get("stages"));
        assertNotEquals(first.configurationHash(), second.configurationHash(),
            "distinct permitted ID codeunits and actual execution orders require distinct configuration identities");
        var remapped = List.of(withId(squares, rules.get(0).id()), withId(pythagorean, rules.get(1).id()));
        assertNotEquals(RuleInventoryFingerprint.contentHash(rules), RuleInventoryFingerprint.contentHash(remapped),
            "the existing raw-ID sort already binds each distinct ID to its typed principal content");
        var remappedRunner = new AblatableRulePreparationRunner(remapped, List.of(), REVISION, budget);
        var third = remappedRunner.analyze(AblatableRulePreparationRunner.Profile.DIRECT_ONLY, source);
        assertNotEquals(AmplificationJson.read(first.canonicalJson()).get("stages"),
            AmplificationJson.read(third.canonicalJson()).get("stages"));
        assertNotEquals(first.configurationHash(), third.configurationHash(),
            "the unchanged inventory fingerprint must also distinguish which typed rule owns each ordered ID");
        assertTrue(forward.verify(first));
        assertFalse(reverse.verify(first));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lead_\uD800", "tail_\uDFFF", "two_\uD800_\uDFFF"})
    void localCanonicalTransportPreservesEveryUnpairedCodeunit(String value) {
        String canonical = AmplificationJson.canonical(AmplificationJson.fields("id", value));
        String transported = new String(canonical.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertEquals(canonical, transported, "canonical UTF-8 transport cannot replace a retained ID codeunit");
        assertEquals(value, AmplificationJson.read(transported).get("id"));
        String replaced = value.replace('\uD800', '?').replace('\uDFFF', '?');
        assertNotEquals(AmplificationJson.hash(value), AmplificationJson.hash(replaced));
    }

    @Test
    void validUnicodeAndExistingStringEscapesKeepTheirCanonicalBytes() {
        String value = "?; \uFFFD; Gr\u00FCnde; \u03BB; \uD83D\uDD12; \"; \\; \n";
        String expected = "{\"id\":\"?; \uFFFD; Gr\u00FCnde; \u03BB; \uD83D\uDD12; \\\"; \\\\; \\n\"}";
        String canonical = AmplificationJson.canonical(AmplificationJson.fields("id", value));
        assertEquals(expected, canonical);
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), canonical.getBytes(StandardCharsets.UTF_8));
        assertEquals(value, AmplificationJson.read(canonical).get("id"));
    }

    private static PatternRewriteRule withId(PatternRewriteRule rule, String id) {
        return new PatternRewriteRule(id, rule.source(), rule.target(), rule.kind(), rule.mayIncreaseComplexity(),
            rule.estimatedCostDelta(), rule.isEquivalencePreservingByConstruction(), rule.descriptor(), rule.recognitionProfile());
    }
}
