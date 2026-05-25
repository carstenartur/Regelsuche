package de.regelsuche.benchmark;

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
     * Outcome of a single seed run.
     *
     * @param seedExpression the input the runner was started with
     * @param success whether the run reached a useful terminal state
     * @param summary short, human-readable result description
     */
    record ExperimentResult(String seedExpression, boolean success, String summary) {
    }
}
