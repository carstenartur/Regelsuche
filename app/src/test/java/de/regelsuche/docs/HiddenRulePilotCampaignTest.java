package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HiddenRulePilotCampaignTest {
    @Test
    void emitsStableFiveCaseEvidenceWithoutHiddenIdsOrWallClockTime() {
        HiddenRulePilotCampaign campaign = new HiddenRulePilotCampaign();
        HiddenRulePilotCampaign.PilotReport report =
            campaign.run(HiddenRulePilotCatalog.cases());
        String json = report.toJson();
        Path output = Path.of("build", "reports", "hidden-rule-pilot", "report.json");
        campaign.write(output, report);

        assertEquals(HiddenRulePilotCampaign.SCHEMA, report.schema());
        assertEquals(5, report.cases().size());
        assertEquals(3, report.familyCount());
        assertEquals(5, report.frozenCandidates());
        assertEquals(5, report.materialAblations());
        assertEquals(5, report.acceptedCases());
        assertEquals(json, report.toJson());
        assertTrue(Files.isRegularFile(output));
        assertTrue(json.contains("\"schema\":\"regelsuche.hidden-rule-pilot/v1\""));
        assertTrue(json.contains("\"splitPassed\":true"));
        assertTrue(json.contains("\"materialBenefit\":true"));
        assertTrue(json.indexOf("case-001") < json.indexOf("case-005"));
        assertFalse(json.contains("hidden_"));
        assertFalse(json.contains("elapsedNanos"));
    }
}
