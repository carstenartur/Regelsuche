package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.docs.GenerationalRuleMiningCampaign.ActivatedRule;
import de.regelsuche.docs.GenerationalRuleMiningCampaign.CampaignReport;
import de.regelsuche.docs.GenerationalRuleMiningCampaign.CandidateStatus;
import de.regelsuche.docs.GenerationalRuleMiningCampaign.GenerationReport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class GenerationalRuleMiningCampaignTest {
    private static final String REPOSITORY_REVISION = "a".repeat(40);

    @Test
    @Timeout(240)
    void activatesOnlyNextGenerationExactRulesAndReachesADeeperForm()
            throws Exception {
        GenerationalRuleMiningCampaign campaign =
            new GenerationalRuleMiningCampaign();
        CampaignReport report = campaign.run(REPOSITORY_REVISION);
        String json = report.toJson();
        Path output = Path.of(
            "build",
            "reports",
            "generational-rule-mining",
            "campaign.json");
        campaign.write(output, report);

        assertEquals(GenerationalRuleMiningCampaign.SCHEMA, report.schema());
        assertEquals(3, report.generations().size());
        assertTrue(report.totalTasks() >= 7);
        assertTrue(report.totalActivatedRules() >= 4, report.finalRules().toString());

        GenerationReport seed = report.generations().get(0);
        GenerationReport firstComposition = report.generations().get(1);
        GenerationReport secondComposition = report.generations().get(2);
        assertTrue(seed.activatedRules().size() >= 2, seed.tasks().toString());
        assertTrue(firstComposition.activatedRules().size() >= 1,
            firstComposition.tasks().toString());
        assertTrue(secondComposition.activatedRules().size() >= 1,
            secondComposition.tasks().toString());
        assertEquals(seed.outputInventoryHash(),
            firstComposition.inputInventoryHash());
        assertEquals(firstComposition.outputInventoryHash(),
            secondComposition.inputInventoryHash());
        assertTrue(report.generations().stream()
            .allMatch(GenerationReport::sameGenerationFeedbackBlocked));

        assertTrue(report.finalRules().stream()
            .allMatch(rule -> rule.proofHash().matches("sha256:[0-9a-f]{64}")));
        assertTrue(report.finalRules().stream()
            .map(ActivatedRule::operatorRuleId)
            .allMatch(ruleId -> ruleId.startsWith("dynamic_hypothesis_")));
        assertTrue(report.generations().stream()
            .flatMap(generation -> generation.tasks().stream())
            .filter(task -> task.assessment().eligible())
            .allMatch(task -> task.assessment().status()
                == CandidateStatus.EXACT_SHADOW_ELIGIBLE));

        assertFalse(report.reachability().baselineReached());
        assertTrue(report.reachability().accumulatedReached(),
            report.reachability().toString());
        assertTrue(report.reachability().newlyReachableUnderBudget());
        assertTrue(report.reachability().accumulatedPath().size() >= 2);
        assertTrue(report.reachability().accumulatedRuleIds().stream()
            .anyMatch(ruleId -> ruleId.startsWith("dynamic_hypothesis_")));

        assertEquals(json, report.toJson());
        assertTrue(Files.isRegularFile(output));
        assertEquals(json, Files.readString(output, StandardCharsets.UTF_8));
        assertTrue(json.contains(
            "\"schema\":\"regelsuche.generational-rule-mining-campaign/v1\""));
        assertTrue(json.contains("\"sameGenerationFeedbackBlocked\":true"));
        assertTrue(json.contains("\"newlyReachableUnderBudget\":true"));
        assertFalse(json.contains("FORMALLY_PROVED"));
    }
}
