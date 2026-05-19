package de.regelsuche.export;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.RuleCandidate;
import java.util.List;

/**
 * Self-contained discovery export payload that can be serialized and
 * deserialized as a single unit (JSON round-trip).
 */
public record ExportBundle(
    String schemaVersion,
    List<DiscoveredTransformation> transformations,
    List<RuleCandidate> ruleCandidates,
    List<ReusableRule> reusableRules
) {
    public static final String CURRENT_SCHEMA_VERSION = "1.0";

    public ExportBundle {
        schemaVersion = schemaVersion == null ? CURRENT_SCHEMA_VERSION : schemaVersion;
        transformations = List.copyOf(transformations);
        ruleCandidates = List.copyOf(ruleCandidates);
        reusableRules = List.copyOf(reusableRules);
    }

    public static ExportBundle of(
        List<DiscoveredTransformation> transformations,
        List<RuleCandidate> ruleCandidates,
        List<ReusableRule> reusableRules
    ) {
        return new ExportBundle(CURRENT_SCHEMA_VERSION, transformations, ruleCandidates, reusableRules);
    }
}
