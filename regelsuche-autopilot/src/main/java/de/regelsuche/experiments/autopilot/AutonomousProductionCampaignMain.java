package de.regelsuche.experiments.autopilot;

import java.nio.file.Path;

/** Command-line entry point for the complete pinned production campaign. */
public final class AutonomousProductionCampaignMain {
    private AutonomousProductionCampaignMain() {
    }

    public static void main(String[] args) {
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
