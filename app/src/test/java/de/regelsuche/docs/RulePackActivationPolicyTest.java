package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.transform.RewriteKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class RulePackActivationPolicyTest {
    @Test
    void candidateRulesAreNeverActivatedAndValidatedRequireDefaultEnablement() {
        ScenarioRule candidate = new ScenarioRule(
                "candidate_rule",
                "a + b",
                "b + a",
                RewriteKind.NORMALIZE,
                0,
                List.of(),
                "synthetic",
                ScenarioRuleStatus.CANDIDATE,
                true,
                List.of("a + b"));
        ScenarioRule validatedDisabled = new ScenarioRule(
                "validated_disabled",
                "a + b",
                "x",
                RewriteKind.NORMALIZE,
                0,
                List.of(),
                "synthetic",
                ScenarioRuleStatus.VALIDATED,
                false,
                List.of("a + b"));
        ScenarioRule validatedActive = new ScenarioRule(
                "validated_active",
                "a + b",
                "a + b",
                RewriteKind.NORMALIZE,
                0,
                List.of(),
                "synthetic",
                ScenarioRuleStatus.VALIDATED,
                true,
                List.of("a + b"));

        ScenarioRuleTransformationEngine engine = new ScenarioRuleTransformationEngine(
                List.of(new ScenarioRulePack("synthetic-pack", List.of(candidate, validatedDisabled, validatedActive))));
        List<String> ids = engine.transform("a+b").stream().map(t -> t.rule()).toList();
        assertEquals(List.of("validated_active"), ids);
    }
}
