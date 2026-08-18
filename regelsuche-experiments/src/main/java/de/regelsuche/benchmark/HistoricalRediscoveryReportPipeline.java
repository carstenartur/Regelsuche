package de.regelsuche.benchmark;

import de.regelsuche.benchmark.DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.AtlasReport;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.CaseResult;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Executes the retained historical diagnostics from one in-memory atlas run.
 */
public final class HistoricalRediscoveryReportPipeline {
    private HistoricalRediscoveryReportPipeline() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || !"historical-rediscovery".equals(args[0])) {
            throw new IllegalArgumentException(
                "expected command: historical-rediscovery [output-directory]"
            );
        }
        Path output = args.length >= 2
            ? Path.of(args[1])
            : Path.of("build/reports/historical-rediscovery");
        Corpus corpus = HistoricalRediscoveryCorpus.load();
        HistoricalRediscoveryAtlas atlas = new HistoricalRediscoveryAtlas();
        AtlasReport report = atlas.run(corpus);
        print(execute(output, corpus, atlas, report));
    }

    static Execution execute(
        Path output,
        Corpus corpus,
        HistoricalRediscoveryAtlas atlas,
        AtlasReport report
    ) {
        verifyRetainedClaims(report);

        HistoricalWitnessPruningDiagnostic pruning =
            new HistoricalWitnessPruningDiagnostic();
        List<HistoricalWitnessPruningDiagnostic.CaseDiagnostic> pruningCases =
            pruning.run(corpus, report);
        verifyWitnessPruning(report, pruningCases);

        HistoricalRediscoveryRunArtifact.begin(output);
        HistoricalRediscoveryAtlas.WrittenArtifacts artifacts =
            atlas.write(output, report);
        HistoricalRediscoveryRunArtifact.VerifiedRun verifiedRun =
            HistoricalRediscoveryRunArtifact.commit(
                output,
                corpus,
                report,
                artifacts
            );
        Path pruningArtifact = pruning.write(
            siblingOutput(output, "-witness-pruning"),
            corpus,
            report,
            pruningCases
        );
        String pruningContentHash = pruning.contentHash(
            corpus,
            report,
            pruningCases
        );

        HistoricalProductionSearchComparison productionComparison =
            new HistoricalProductionSearchComparison();
        HistoricalProductionSearchComparison.Report productionReport =
            productionComparison.run(corpus, report, pruningCases);
        Path productionArtifact = productionComparison.write(
            siblingOutput(output, "-production-search-comparison"),
            productionReport
        );

        HistoricalEqualWorkSearchComparison equalWorkComparison =
            new HistoricalEqualWorkSearchComparison();
        HistoricalEqualWorkSearchComparison.Report equalWorkReport =
            equalWorkComparison.run(corpus, report);
        Path equalWorkArtifact = equalWorkComparison.write(
            siblingOutput(output, "-equal-work-search-comparison"),
            equalWorkReport
        );

        return new Execution(
            report,
            artifacts,
            verifiedRun,
            pruningArtifact,
            pruningContentHash,
            productionArtifact,
            productionReport,
            equalWorkArtifact,
            equalWorkReport
        );
    }

    private static void print(Execution execution) {
        System.out.println("historicalRediscoveryAssessment="
            + execution.report().assessment().decision());
        System.out.println("historicalRediscoveryCases="
            + execution.report().cases().size());
        System.out.println("historicalRediscoveryJson="
            + execution.artifacts().json().toAbsolutePath().normalize());
        System.out.println("historicalRediscoveryMarkdown="
            + execution.artifacts().markdown().toAbsolutePath().normalize());
        System.out.println("historicalRediscoveryRun="
            + execution.verifiedRun().manifestPath());
        System.out.println("historicalRediscoveryRunHash="
            + execution.verifiedRun().manifest().contentHash());
        System.out.println("historicalWitnessPruning="
            + execution.pruningArtifact());
        System.out.println("historicalWitnessPruningHash="
            + execution.pruningContentHash());
        System.out.println("historicalProductionSearchComparison="
            + execution.productionArtifact());
        System.out.println("historicalProductionSearchComparisonHash="
            + execution.productionReport().contentHash());
        System.out.println("historicalEqualWorkSearchComparison="
            + execution.equalWorkArtifact());
        System.out.println("historicalEqualWorkSearchComparisonHash="
            + execution.equalWorkReport().contentHash());
    }

    private static void verifyRetainedClaims(AtlasReport report) {
        boolean targetBlindDiversitySignal = false;
        for (CaseResult result : report.cases()) {
            switch (result.status()) {
                case GENERIC_BRIDGE_REQUIRED_AND_FOUND,
                     CURATED_CONTROL_ONLY_MISSING_PRODUCTION_PRIMITIVE -> {
                    if (!result.production().oracle()
                            .completeClosureExhausted()) {
                        throw new IllegalStateException(
                            "claim requires complete production closure: "
                                + result.benchmarkCase().id()
                                + " has production oracle status "
                                + result.production().oracle().status()
                        );
                    }
                }
                case REACHABLE_BUT_SCALAR_MISSED_DIVERSITY_FOUND ->
                    targetBlindDiversitySignal = true;
                default -> {
                    // No additional cross-case claim invariant.
                }
            }
        }
        if (report.assessment().searchPolicyDifferenceIdentified()
                != targetBlindDiversitySignal) {
            throw new IllegalStateException(
                "search-policy claim must be backed by a target-blind "
                    + "structural-diversity result"
            );
        }
    }

    private static void verifyWitnessPruning(
        AtlasReport atlas,
        List<HistoricalWitnessPruningDiagnostic.CaseDiagnostic> diagnostics
    ) {
        Map<String, HistoricalWitnessPruningDiagnostic.CaseDiagnostic> byId =
            diagnostics.stream().collect(Collectors.toMap(
                HistoricalWitnessPruningDiagnostic.CaseDiagnostic::id,
                Function.identity()
            ));
        if (byId.size() != atlas.cases().size()) {
            throw new IllegalStateException(
                "witness-pruning diagnostic must balance every atlas case"
            );
        }
        for (CaseResult result : atlas.cases()) {
            HistoricalWitnessPruningDiagnostic.CaseDiagnostic retained =
                byId.get(result.benchmarkCase().id());
            if (retained == null) {
                throw new IllegalStateException(
                    "missing witness-pruning case "
                        + result.benchmarkCase().id()
                );
            }
            boolean policySignal = result.status()
                == HistoricalRediscoveryAtlas.PrimaryStatus
                    .REACHABLE_BUT_SCALAR_MISSED_DIVERSITY_FOUND;
            if (policySignal
                    && !HistoricalWitnessPruningDiagnostic.WITNESS_PREFIX_LOST
                        .equals(retained.status())) {
                throw new IllegalStateException(
                    "target-blind diversity signal requires a retained "
                        + "scalar witness-prefix loss: "
                        + result.benchmarkCase().id()
                );
            }
        }
    }

    private static Path siblingOutput(Path atlasOutput, String suffix) {
        Path normalized = atlasOutput.toAbsolutePath().normalize();
        Path name = normalized.getFileName();
        if (name == null) {
            throw new IllegalArgumentException(
                "historical rediscovery output must have a file name"
            );
        }
        return normalized.resolveSibling(name + suffix);
    }

    record Execution(
        AtlasReport report,
        HistoricalRediscoveryAtlas.WrittenArtifacts artifacts,
        HistoricalRediscoveryRunArtifact.VerifiedRun verifiedRun,
        Path pruningArtifact,
        String pruningContentHash,
        Path productionArtifact,
        HistoricalProductionSearchComparison.Report productionReport,
        Path equalWorkArtifact,
        HistoricalEqualWorkSearchComparison.Report equalWorkReport
    ) {
    }
}
