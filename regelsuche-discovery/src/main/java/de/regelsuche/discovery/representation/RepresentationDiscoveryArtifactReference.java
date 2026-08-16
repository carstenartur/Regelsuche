package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.WORKSPACE_SCHEMA;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.append;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.optionalText;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.sha256;

import java.util.Objects;

/** One explicit product-artifact role in a run workspace. */
public record RepresentationDiscoveryArtifactReference(
    ArtifactRole role,
    ArtifactStatus status,
    String artifactSchema,
    String targetContentHash,
    String detail,
    String contentHash
) {
    private static final String NOT_AVAILABLE_SCHEMA = "NOT_AVAILABLE";

    public RepresentationDiscoveryArtifactReference {
        role = Objects.requireNonNull(role, "role");
        status = Objects.requireNonNull(status, "status");
        artifactSchema = requireText(artifactSchema, "artifactSchema");
        targetContentHash = requireSha256(
            targetContentHash, "targetContentHash");
        detail = optionalText(detail, "detail");
        if (status == ArtifactStatus.AVAILABLE) {
            if (NOT_AVAILABLE_SCHEMA.equals(artifactSchema)
                    || !detail.isEmpty()) {
                throw new IllegalArgumentException(
                    "available artifacts require a real schema and no "
                        + "unavailability detail");
            }
        } else {
            if (!NOT_AVAILABLE_SCHEMA.equals(artifactSchema)
                    || detail.isEmpty()) {
                throw new IllegalArgumentException(
                    "unavailable artifacts require NOT_AVAILABLE schema "
                        + "and a detail");
            }
            String expectedTarget = unavailableTargetHash(
                role, status, detail);
            if (!expectedTarget.equals(targetContentHash)) {
                throw new IllegalArgumentException(
                    "unavailable artifact target hash mismatch");
            }
        }
        contentHash = requireSha256(contentHash, "contentHash");
        String expected = referenceHash(
            role,
            status,
            artifactSchema,
            targetContentHash,
            detail
        );
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "artifact reference content hash mismatch");
        }
    }

    public static RepresentationDiscoveryArtifactReference available(
        ArtifactRole role,
        String artifactSchema,
        String targetContentHash
    ) {
        return create(
            role,
            ArtifactStatus.AVAILABLE,
            requireText(artifactSchema, "artifactSchema"),
            requireSha256(targetContentHash, "targetContentHash"),
            ""
        );
    }

    public static RepresentationDiscoveryArtifactReference notProduced(
        ArtifactRole role
    ) {
        return unavailable(
            role,
            ArtifactStatus.NOT_PRODUCED,
            "NOT_PRODUCED_FOR_THIS_RUN"
        );
    }

    public static RepresentationDiscoveryArtifactReference unavailable(
        ArtifactRole role,
        ArtifactStatus status,
        String detail
    ) {
        ArtifactRole normalizedRole = Objects.requireNonNull(role, "role");
        ArtifactStatus normalizedStatus = Objects.requireNonNull(
            status, "status");
        if (normalizedStatus == ArtifactStatus.AVAILABLE) {
            throw new IllegalArgumentException(
                "use available(...) for available artifacts");
        }
        String normalizedDetail = requireText(detail, "detail");
        return create(
            normalizedRole,
            normalizedStatus,
            NOT_AVAILABLE_SCHEMA,
            unavailableTargetHash(
                normalizedRole,
                normalizedStatus,
                normalizedDetail
            ),
            normalizedDetail
        );
    }

    private static RepresentationDiscoveryArtifactReference create(
        ArtifactRole role,
        ArtifactStatus status,
        String artifactSchema,
        String targetContentHash,
        String detail
    ) {
        String hash = referenceHash(
            role,
            status,
            artifactSchema,
            targetContentHash,
            detail
        );
        return new RepresentationDiscoveryArtifactReference(
            role,
            status,
            artifactSchema,
            targetContentHash,
            detail,
            hash
        );
    }

    private static String unavailableTargetHash(
        ArtifactRole role,
        ArtifactStatus status,
        String detail
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, WORKSPACE_SCHEMA + "/unavailable-artifact");
        append(descriptor, role.name());
        append(descriptor, status.name());
        append(descriptor, detail);
        return sha256(descriptor.toString());
    }

    private static String referenceHash(
        ArtifactRole role,
        ArtifactStatus status,
        String artifactSchema,
        String targetHash,
        String detail
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, WORKSPACE_SCHEMA + "/artifact-reference");
        append(descriptor, role.name());
        append(descriptor, status.name());
        append(descriptor, artifactSchema);
        append(descriptor, targetHash);
        append(descriptor, detail);
        return sha256(descriptor.toString());
    }

    public enum ArtifactRole {
        SEARCH_GRAPH,
        REPRESENTATION_CANDIDATES,
        CANDIDATE_DOSSIERS,
        PATH_REPLAY,
        RULE_RADAR,
        PROOF_OBLIGATIONS,
        EXPORT_BUNDLE,
        PROGRESS_LEDGER
    }

    public enum ArtifactStatus {
        AVAILABLE,
        NOT_PRODUCED,
        UNSUPPORTED,
        FAILED
    }
}
