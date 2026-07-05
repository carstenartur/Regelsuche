package de.regelsuche.docs;

import java.nio.file.Path;

/** Generates the cross-campaign discovery candidate store from the promotion pipeline records. */
public final class DiscoveryCandidateStoreRunner {
    public static void main(String[] args) {
        Path repoRoot = args.length == 0
            ? Path.of(".").toAbsolutePath().normalize()
            : Path.of(args[0]).toAbsolutePath().normalize();
        new DiscoveryCandidateStoreRunner()
            .writeReport(repoRoot.resolve("app/build/reports/discovery-candidate-store"));
    }

    DiscoveryCandidateStore.CandidateStoreReport run() {
        DiscoveryPromotionPipelineRunner.PipelineReport pipeline = new DiscoveryPromotionPipelineRunner().run();
        return new DiscoveryCandidateStore().build(pipeline.promotionRecords());
    }

    DiscoveryCandidateStore.CandidateStoreReport writeReport(Path outputDirectory) {
        DiscoveryPromotionPipelineRunner.PipelineReport pipeline = new DiscoveryPromotionPipelineRunner().run();
        return new DiscoveryCandidateStore().write(outputDirectory, pipeline.promotionRecords());
    }
}
