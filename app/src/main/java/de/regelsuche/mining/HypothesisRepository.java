package de.regelsuche.mining;

import java.util.List;
import java.util.Optional;

/**
 * Stable port for persisting mined hypotheses (candidate rules that have
 * been generalised but not yet validated as reusable).
 *
 * <p>Introduced as part of Teil 0 of the Discovery Epic (issue #41,
 * "Interfaces zuerst"): hypothesis mining, counterexample search and
 * macro-rule learning depend on this abstraction so the workflow can be
 * wired against any backend (in-memory, JSON file, PostgreSQL, …) without
 * leaking persistence into the mathematical core.
 *
 * <p>The payload type is the existing {@link RuleCandidate} record so the
 * port can be adopted incrementally by current mining code.
 */
public interface HypothesisRepository {

    /** Persist a hypothesis under {@code hypothesisId}. */
    void save(String hypothesisId, RuleCandidate hypothesis);

    /** @return the hypothesis with the given id, if known. */
    Optional<RuleCandidate> findById(String hypothesisId);

    /** @return all stored hypotheses (insertion order is not guaranteed). */
    List<RuleCandidate> findAll();

    /** Remove a hypothesis, e.g. after promotion to a reusable rule. */
    void delete(String hypothesisId);
}
