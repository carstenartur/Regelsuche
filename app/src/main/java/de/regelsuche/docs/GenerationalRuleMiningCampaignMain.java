package de.regelsuche.docs;

import java.nio.file.Path;

/** Standalone entry point for the development rule-mining campaign. */
public final class GenerationalRuleMiningCampaignMain {
    private GenerationalRuleMiningCampaignMain() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                "usage: GenerationalRuleMiningCampaignMain <output-json> "
                    + "<repository-revision>");
        }
        GenerationalRuleMiningCampaign campaign =
            new GenerationalRuleMiningCampaign();
        GenerationalRuleMiningCampaign.CampaignReport report =
            campaign.run(args[1]);
        campaign.write(Path.of(args[0]), report);
        System.out.println(report.toJson());
    }
}
