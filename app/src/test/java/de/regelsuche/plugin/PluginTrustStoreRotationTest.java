package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.plugin.PluginTrustStore.KeyStatus;
import de.regelsuche.plugin.PluginTrustStore.PublisherKey;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginTrustStoreRotationTest {
    private static final String PUBLIC_KEY_BASE64 =
        "MCowBQYDK2VwAyEAH0nHmwIVDzgfBwZMgSccK3RZqKbROm8/OPg5KGpDcZk=";
    private static final String PUBLISHER = "org.example.publisher";

    @Test
    void acceptsAcyclicRotationToKnownActiveKey() {
        PublisherKey retired = key("release-2026", KeyStatus.RETIRED, "release-2027");
        PublisherKey active = key("release-2027", KeyStatus.ACTIVE, "");

        assertDoesNotThrow(() -> new PluginTrustStore(
            PluginTrustStore.SCHEMA,
            List.of(retired, active),
            List.of()
        ));
    }

    @Test
    void rejectsMissingRevokedOrCyclicSuccessors() {
        PublisherKey missing = key("release-2026", KeyStatus.RETIRED, "release-2027");
        assertThrows(IllegalArgumentException.class, () -> new PluginTrustStore(
            PluginTrustStore.SCHEMA,
            List.of(missing),
            List.of()
        ));

        PublisherKey revoked = key("release-2027", KeyStatus.REVOKED, "");
        assertThrows(IllegalArgumentException.class, () -> new PluginTrustStore(
            PluginTrustStore.SCHEMA,
            List.of(missing, revoked),
            List.of()
        ));

        PublisherKey first = key("release-a", KeyStatus.RETIRED, "release-b");
        PublisherKey second = key("release-b", KeyStatus.RETIRED, "release-a");
        assertThrows(IllegalArgumentException.class, () -> new PluginTrustStore(
            PluginTrustStore.SCHEMA,
            List.of(first, second),
            List.of()
        ));
    }

    @Test
    void rejectsSuccessorMetadataOnNonRetiredKeys() {
        assertThrows(IllegalArgumentException.class, () ->
            key("release-active", KeyStatus.ACTIVE, "release-next"));
        assertThrows(IllegalArgumentException.class, () ->
            key("release-revoked", KeyStatus.REVOKED, "release-next"));
    }

    private PublisherKey key(String keyId, KeyStatus status, String successor) {
        return new PublisherKey(
            PUBLISHER,
            keyId,
            PluginSignatureManifest.ALGORITHM,
            PUBLIC_KEY_BASE64,
            status,
            successor
        );
    }
}
