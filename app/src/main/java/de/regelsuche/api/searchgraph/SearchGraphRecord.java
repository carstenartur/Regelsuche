package de.regelsuche.api.searchgraph;

import de.regelsuche.api.IdentityReportDto;
import de.regelsuche.api.PathReplayDto;
import de.regelsuche.mining.MacroRuleCandidate;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Persistable snapshot of a complete Visual Search Graph session.
 *
 * <p>Bundles the {@link SearchGraphDto} (nodes/edges/clusters/stats) together
 * with replays, mined macro-rule candidates, identity reports, generated
 * exports, the timestamp of the run, the active search profile and the
 * selected rule domains.</p>
 *
 * <p>Persisted via {@link SearchGraphRepository}.</p>
 */
public record SearchGraphRecord(
    String id,
    Instant createdAt,
    String searchProfile,
    List<String> domains,
    SearchGraphDto graph,
    List<PathReplayDto> replays,
    List<MacroRuleCandidate> macroRules,
    List<IdentityReportDto> identities,
    Map<String, String> exports
) {
    public SearchGraphRecord {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
        searchProfile = searchProfile == null ? "" : searchProfile;
        domains = domains == null ? List.of() : List.copyOf(domains);
        replays = replays == null ? List.of() : List.copyOf(replays);
        macroRules = macroRules == null ? List.of() : List.copyOf(macroRules);
        identities = identities == null ? List.of() : List.copyOf(identities);
        exports = exports == null ? Map.of() : Map.copyOf(exports);
    }
}
