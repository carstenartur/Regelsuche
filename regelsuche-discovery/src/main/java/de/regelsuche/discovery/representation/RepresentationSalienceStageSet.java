package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation
    .RepresentationDiscoveryRunContractSupport.append;
import static de.regelsuche.discovery.representation
    .RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation
    .RepresentationDiscoveryRunContractSupport.sha256;

import de.regelsuche.json.JsonWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Stable correspondence identities retained at one salience-audit stage. */
public record RepresentationSalienceStageSet(
    List<String> representationIds,
    String contentHash
) {
    public RepresentationSalienceStageSet {
        representationIds = sortedHashes(
            representationIds,
            "representationIds"
        );
        contentHash = requireSha256(contentHash, "contentHash");
        String expected = stageSetHash(representationIds);
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException("stage-set content hash mismatch");
        }
    }

    public static RepresentationSalienceStageSet of(
        List<String> representationIds
    ) {
        List<String> retained = sortedHashes(
            representationIds,
            "representationIds"
        );
        return new RepresentationSalienceStageSet(
            retained,
            stageSetHash(retained)
        );
    }

    public static RepresentationSalienceStageSet empty() {
        return of(List.of());
    }

    public boolean isEmpty() {
        return representationIds.isEmpty();
    }

    boolean containsAll(RepresentationSalienceStageSet other) {
        return representationIds.containsAll(
            Objects.requireNonNull(other, "other").representationIds
        );
    }

    void writeJson(JsonWriter json) {
        json.stringArray("representationIds", representationIds)
            .property("contentHash", contentHash);
    }

    private static String stageSetHash(List<String> ids) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, RepresentationSalienceAudit.SCHEMA + "/stage-set");
        append(descriptor, Integer.toString(ids.size()));
        ids.forEach(value -> append(descriptor, value));
        return sha256(descriptor.toString());
    }

    private static List<String> sortedHashes(
        List<String> values,
        String field
    ) {
        List<String> result = List.copyOf(
            Objects.requireNonNull(values, field)
        ).stream()
            .map(value -> requireSha256(value, field))
            .sorted()
            .toList();
        if (new HashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException(field + " must be unique");
        }
        return result;
    }
}
