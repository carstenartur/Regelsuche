package de.regelsuche.benchmark;

import java.nio.file.Path;

/** Checkout-owned entry point for the retained historical rediscovery report. */
public final class HistoricalRediscoveryAtlasMain {
    private HistoricalRediscoveryAtlasMain() {
    }

    public static void main(String[] args) {
        Path output = args.length == 0
            ? Path.of("build/reports/historical-rediscovery")
            : Path.of(args[0]);
        HistoricalRediscoveryCorpus.Corpus corpus =
            HistoricalRediscoveryCorpus.load();
        HistoricalRediscoveryAtlas atlas = new HistoricalRediscoveryAtlas();
        HistoricalRediscoveryAtlas.AtlasReport report = atlas.run(corpus);
        HistoricalRediscoveryAtlas.WrittenArtifacts artifacts =
            atlas.write(output, report);
        System.out.println("historicalRediscoveryAssessment="
            + report.assessment().decision());
        System.out.println("historicalRediscoveryCases=" + report.cases().size());
        System.out.println("historicalRediscoveryJson="
            + artifacts.json().toAbsolutePath().normalize());
        System.out.println("historicalRediscoveryMarkdown="
            + artifacts.markdown().toAbsolutePath().normalize());
    }
}
