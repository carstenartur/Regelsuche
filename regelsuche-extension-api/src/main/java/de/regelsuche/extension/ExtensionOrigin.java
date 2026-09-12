package de.regelsuche.extension;

import de.regelsuche.api.StableApi;
import java.util.Objects;

/** Host-owned provenance attached to one accepted contribution. */
@StableApi(since = "2")
public record ExtensionOrigin(
    OriginKind kind,
    String sourceId,
    String sourceVersion,
    String sourceReference,
    String artifactSha256,
    String trustEvidenceSha256
) {
    /** Initial contribution source kinds. */
    public enum OriginKind {
        CORE,
        CLASSPATH_PLUGIN,
        EXTERNAL_PLUGIN,
        RULE_FILE
    }

    public ExtensionOrigin {
        Objects.requireNonNull(kind, "kind");
        sourceId = ExtensionIdentifiers.identifier(sourceId, "sourceId");
        sourceVersion = ExtensionIdentifiers.requiredText(sourceVersion, "sourceVersion");
        sourceReference = ExtensionIdentifiers.optionalText(sourceReference);
        artifactSha256 = ExtensionIdentifiers.hash(
            artifactSha256,
            "artifactSha256",
            kind != OriginKind.EXTERNAL_PLUGIN
        );
        trustEvidenceSha256 = ExtensionIdentifiers.hash(
            trustEvidenceSha256,
            "trustEvidenceSha256",
            true
        );
        if (!trustEvidenceSha256.isEmpty() && artifactSha256.isEmpty()) {
            throw new IllegalArgumentException("trust evidence requires artifact hash");
        }
    }

    /** Host provenance for a classpath or explicitly supplied in-process plugin. */
    public static ExtensionOrigin classpathPlugin(
        String id,
        String version,
        String reference
    ) {
        return new ExtensionOrigin(
            OriginKind.CLASSPATH_PLUGIN,
            id,
            version,
            reference,
            "",
            ""
        );
    }

    /** Host provenance for an externally admitted artifact. */
    public static ExtensionOrigin externalPlugin(
        String id,
        String version,
        String reference,
        String artifactSha256,
        String trustEvidenceSha256
    ) {
        return new ExtensionOrigin(
            OriginKind.EXTERNAL_PLUGIN,
            id,
            version,
            reference,
            artifactSha256,
            trustEvidenceSha256
        );
    }
}
