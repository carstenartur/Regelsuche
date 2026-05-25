package de.regelsuche.mining;

import java.time.Instant;

public record RuleCandidateDiscoveredEvent(RuleCandidate candidate, Instant timestamp) {
}
