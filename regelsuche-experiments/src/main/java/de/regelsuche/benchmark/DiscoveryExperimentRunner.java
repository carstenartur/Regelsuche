package de.regelsuche.benchmark;

import java.nio.file.Path;
import java.util.List;

/**
 * Stable port for running discovery experiments over a seed corpus.
 *
 * <p>Introduced as part of Teil 0 of the Discovery Epic (issue #41,
 * "Interfaces zuerst"): planned experiment-runner / replay / report
 * features depend on this abstraction so the corpus source, the search
 * driver and the result sink stay decoupled and individually replaceable.
 *
 * <p>The runner is intentionally minimal: it consumes seed expressions and
 * returns a result summary. Concrete implementations choose how to wire
 * search strategies, repositories and trace stores.
 */
public interface DiscoveryExperimentRunner {

    /**
     * Execute the experiment for the given {@code seedExpressions}.
     *
     * @return one {@link ExperimentResult} per seed, in the same order as
     *     the input list.
     */
    List<ExperimentResult> run(List<String> seedExpressions);

    /**
     * Checkout-owned command entry point for retained discovery experiments.
     *
     * <p>The first command is deliberately explicit so additional experiments
     * can share the entry point without inventing one public launcher class per
     * report.</p>
     */
    static void main(String[] args) {
        if (args.length == 0 || !"historical-rediscovery".equals(args[0])) {
            throw new IllegalArgumentException(
                "expected command: historical-rediscovery [output-directory]");
        }
        Path output = args.length >= 2
            ? Path.of(args[1])
            : Path.of("build/reports/historical-rediscovery");
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

    /**
     * Outcome of a single seed run.
     *
     * @param seedExpression the input the runner was started with
     * @param success whether the run reached a useful terminal state
     * @param summary short, human-readable result description
     */
    record ExperimentResult(String seedExpression, boolean success, String summary) {
    }
}
