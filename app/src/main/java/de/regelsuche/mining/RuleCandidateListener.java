package de.regelsuche.mining;

public interface RuleCandidateListener {
    RuleCandidateListener NOOP = event -> {};

    void onRuleCandidateDiscovered(RuleCandidateDiscoveredEvent event);
}
