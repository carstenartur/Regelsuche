/**
 * Student-facing, headless Java API for bounded mathematical discovery.
 *
 * <p>The package deliberately separates candidate formation, counterexample
 * search and certificate-bearing evaluation. It is a convenience layer over
 * Regelsuche's canonical discovery contracts, not a shortcut around them.</p>
 *
 * <p>Builders and callbacks are not thread-safe. Runs retain immutable evidence;
 * candidate objects must remain immutable as required by their canonical codecs.
 * Required arguments reject null. Optional values represent absent candidates or
 * certificates explicitly. Determinism requires deterministic callbacks and the
 * same seed, budgets and provider bytes. Search work is bounded by the published
 * counters; arbitrary callback CPU time is not a sandboxed wall-clock budget.
 */
@de.regelsuche.api.StableApi(since = "1")
package de.regelsuche.sdk.discovery;
