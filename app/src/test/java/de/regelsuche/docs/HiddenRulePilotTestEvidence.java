package de.regelsuche.docs;

import de.regelsuche.docs.HiddenRulePilotCampaign.CaseReport;
import de.regelsuche.docs.HiddenRulePilotCampaign.PilotReport;

/** One deterministic, JVM-local execution of the expensive five-case pilot. */
final class HiddenRulePilotTestEvidence {
    private static final PilotReport REPORT =
        new HiddenRulePilotCampaign().run(HiddenRulePilotCatalog.cases());

    private HiddenRulePilotTestEvidence() {
    }

    static PilotReport report() {
        return REPORT;
    }

    static CaseReport caseReport(String opaqueCaseId) {
        return REPORT.cases().stream()
            .filter(report -> report.opaqueCaseId().equals(opaqueCaseId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "unknown pilot case: " + opaqueCaseId));
    }
}
