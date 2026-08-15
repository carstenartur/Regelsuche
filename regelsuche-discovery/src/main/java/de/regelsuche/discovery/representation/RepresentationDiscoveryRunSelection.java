package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.WORKSPACE_SCHEMA;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.append;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.optionalText;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.sha256;

import java.util.List;

/** Immutable active selection correlated to one exact Run ID. */
public record RepresentationDiscoveryRunSelection(
    String runId,
    String candidateId,
    String stateId,
    String edgeId,
    String occurrencePath,
    String proofObligationId,
    String contentHash
) {
    public RepresentationDiscoveryRunSelection {
        runId = requireSha256(runId, "runId");
        candidateId = optionalText(candidateId, "candidateId");
        stateId = optionalText(stateId, "stateId");
        edgeId = optionalText(edgeId, "edgeId");
        occurrencePath = optionalText(
            occurrencePath, "occurrencePath");
        proofObligationId = optionalText(
            proofObligationId, "proofObligationId");
        if (allEmpty(
                candidateId,
                stateId,
                edgeId,
                occurrencePath,
                proofObligationId)) {
            throw new IllegalArgumentException(
                "a run selection must select at least one object");
        }
        contentHash = requireSha256(contentHash, "contentHash");
        String expected = selectionHash(
            runId,
            candidateId,
            stateId,
            edgeId,
            occurrencePath,
            proofObligationId
        );
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "run selection content hash mismatch");
        }
    }

    public static RepresentationDiscoveryRunSelection create(
        String runId,
        String candidateId,
        String stateId,
        String edgeId,
        String occurrencePath,
        String proofObligationId
    ) {
        String normalizedRunId = requireSha256(runId, "runId");
        String candidate = optionalText(candidateId, "candidateId");
        String state = optionalText(stateId, "stateId");
        String edge = optionalText(edgeId, "edgeId");
        String occurrence = optionalText(
            occurrencePath, "occurrencePath");
        String proof = optionalText(
            proofObligationId, "proofObligationId");
        return new RepresentationDiscoveryRunSelection(
            normalizedRunId,
            candidate,
            state,
            edge,
            occurrence,
            proof,
            selectionHash(
                normalizedRunId,
                candidate,
                state,
                edge,
                occurrence,
                proof
            )
        );
    }

    private static boolean allEmpty(String... values) {
        return List.of(values).stream().allMatch(String::isEmpty);
    }

    private static String selectionHash(
        String runId,
        String candidateId,
        String stateId,
        String edgeId,
        String occurrencePath,
        String proofObligationId
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, WORKSPACE_SCHEMA + "/selection");
        append(descriptor, runId);
        append(descriptor, candidateId);
        append(descriptor, stateId);
        append(descriptor, edgeId);
        append(descriptor, occurrencePath);
        append(descriptor, proofObligationId);
        return sha256(descriptor.toString());
    }
}
