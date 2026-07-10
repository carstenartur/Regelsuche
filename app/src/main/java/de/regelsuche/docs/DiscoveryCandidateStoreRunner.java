package de.regelsuche.docs;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
        DiscoveryCandidateStore.CandidateStoreReport storeReport =
            new DiscoveryCandidateStore().write(outputDirectory, pipeline.promotionRecords());
        PatternHypothesisMiner.PatternHypothesisReport patternReport = new PatternHypothesisMiner().write(outputDirectory, storeReport);
        GeneralizedHypothesisValidationRunner.ValidationReport validationReport =
            new GeneralizedHypothesisValidationRunner().write(outputDirectory, patternReport);
        List<PromotionRecord> publicEvidenceRecords = Stream.concat(
                pipeline.promotionRecords().stream(),
                validationReport.promotionRecords().stream())
            .toList();
        Map<String, NoveltyStatus> novelty = noveltyMapFrom(storeReport);
        validationReport.promotionRecords().forEach(record -> novelty.put(record.candidateId(), NoveltyStatus.NEW));
        new PublicEvidenceGate().write(outputDirectory, publicEvidenceRecords, novelty);
        return storeReport;
    }

    private static Map<String, NoveltyStatus> noveltyMapFrom(DiscoveryCandidateStore.CandidateStoreReport storeReport) {
        Map<String, NoveltyStatus> novelty = new LinkedHashMap<>();
        for (DiscoveryCandidateStore.CandidateEntry entry : storeReport.candidates()) {
            novelty.put(entry.candidateId(), entry.noveltyStatus());
            for (DiscoveryCandidateStore.ConcreteExample example : entry.concreteExamples()) {
                novelty.put(example.exampleId(), example.noveltyStatus());
            }
        }
        return novelty;
    }
}
