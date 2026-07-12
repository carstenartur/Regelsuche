package de.regelsuche.docs;

import java.nio.file.Path;

/** CI entry point for the fixed leakage-isolated pilot corpus. */
public final class HiddenRulePilotCampaignRunner {
    private HiddenRulePilotCampaignRunner() {
    }

    public static void main(String[] args) {
        Path repositoryRoot = args.length == 0
            ? Path.of(".").toAbsolutePath().normalize()
            : Path.of(args[0]).toAbsolutePath().normalize();
        HiddenRulePilotCampaign campaign = new HiddenRulePilotCampaign();
        HiddenRulePilotCampaign.PilotReport report =
            campaign.run(HiddenRulePilotCatalog.cases());
        Path output = repositoryRoot.resolve(
            "app/build/reports/hidden-rule-pilot/report.json");
        campaign.write(output, report);

        if (report.cases().size() != 5
                || report.familyCount() < 2
                || report.acceptedCases() != report.cases().size()) {
            throw new IllegalStateException(
                "hidden-rule pilot acceptance failed: cases=" + report.cases().size()
                    + ", families=" + report.familyCount()
                    + ", accepted=" + report.acceptedCases());
        }
        System.out.println("Hidden-rule pilot accepted " + report.acceptedCases()
            + "/" + report.cases().size() + " cases across "
            + report.familyCount() + " families; report=" + output);
    }
}
