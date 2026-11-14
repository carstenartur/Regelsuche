package de.regelsuche.experiments.autopilot;

import java.nio.file.Path;

/** Command-line entry point for the complete pinned production campaign. */
public final class AutonomousProductionCampaignMain {
    private AutonomousProductionCampaignMain() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && "--compare-controllers".equals(args[0])) {
            if (args.length != 2) {
                throw new IllegalArgumentException("Expected --compare-controllers <output-file>");
            }
            CampaignControllerComparison.write(Path.of(args[1]));
            return;
        }
        Path output = args.length > 0
            ? Path.of(args[0])
            : Path.of("build", "reports", "autopilot-production-campaign");
        int parallelism = args.length > 1
            ? Math.max(1, Integer.parseInt(args[1]))
            : Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        AutonomousProductionCampaignRunner runner =
            new AutonomousProductionCampaignRunner();
        var run = runner.runPinned(parallelism);
        runner.write(output, run);
        System.out.println(run.contentHash());
    }
}
