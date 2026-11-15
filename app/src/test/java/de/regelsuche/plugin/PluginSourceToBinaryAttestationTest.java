package de.regelsuche.plugin;

import static de.regelsuche.plugin.PluginArtifactVerifier.sha256;
import static de.regelsuche.plugin.PluginDistributionFixtures.key;
import static de.regelsuche.plugin.PluginDistributionFixtures.sign;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginSourceToBinaryAttestationTest {
    private static final String UNSIGNED = Base64.getEncoder().encodeToString(new byte[64]);
    private static final String BUILDER = "independent-builder";

    @Test
    void verifiesIndependentJava25JarBuiltFromTheBoundArchiveAndRecipe(@TempDir Path workspace) throws Exception {
        byte[] source = sourceArchive();
        byte[] recipe = (Runtime.version() + "\n" + """
            javac --release 25 -g:none -proc:none -encoding UTF-8 -classpath classes -d classes RebuildExample.java
            jar --create --file example.jar --no-manifest --no-compress --date=2026-01-01T00:00:00Z -C classes example/RebuildExample.class
            """).getBytes(UTF_8);
        byte[] published = build(workspace.resolve("publisher"), source, recipe);
        byte[] rebuilt = build(workspace.resolve("independent-builder"), source, recipe);
        assertArrayEquals(published, rebuilt);

        var fixture = new SignedInputs(source, recipe, published);
        var attestation = fixture.attestation(fixture.provenance, BUILDER, fixture.builder, sha256(rebuilt));
        var decoded = PluginSourceToBinaryAttestation.read(attestation.toCanonicalJson().getBytes(UTF_8));
        assertEquals(attestation, decoded);
        assertEquals(attestation.toCanonicalJson(), decoded.toCanonicalJson());
        assertEquals(sha256(decoded.toCanonicalJson().getBytes(UTF_8)), decoded.contentHash());
        assertDoesNotThrow(() -> decoded.requireVerified(fixture.entry, fixture.provenance, fixture.publishers,
            fixture.builderPin, source, recipe, rebuilt));
    }

    @Test
    void rejectsChangedSourceRecipeAndRebuiltBytes() throws Exception {
        var fixture = new SignedInputs();
        var receipt = fixture.attestation();
        byte[] replaced = "different input".getBytes(UTF_8);

        assertThrows(SecurityException.class, () -> receipt.requireVerified(fixture.entry, fixture.provenance,
            fixture.publishers, fixture.builderPin, replaced, fixture.recipe, fixture.artifact));
        assertThrows(SecurityException.class, () -> receipt.requireVerified(fixture.entry, fixture.provenance,
            fixture.publishers, fixture.builderPin, fixture.source, replaced, fixture.artifact));
        assertThrows(SecurityException.class, () -> receipt.requireVerified(fixture.entry, fixture.provenance,
            fixture.publishers, fixture.builderPin, fixture.source, fixture.recipe, replaced));

        assertThrows(SecurityException.class, () -> receipt.requireVerified(fixture.entry, fixture.provenance,
            fixture.publishers, fixture.builderPin, fixture.source, fixture.recipe, null));

        var differentBuild = fixture.attestation(fixture.provenance, BUILDER, fixture.builder, sha256(replaced));
        assertThrows(SecurityException.class, () -> differentBuild.requireVerified(fixture.entry, fixture.provenance,
            fixture.publishers, fixture.builderPin, fixture.source, fixture.recipe, replaced));
    }

    @Test
    void rejectsReceiptReplayedAgainstAnotherAuthenticatedProvenanceOrEntry() throws Exception {
        var fixture = new SignedInputs();
        var receipt = fixture.attestation();

        var replacementProvenance = fixture.provenance(fixture.entry, "revision-2", fixture.publisher);
        assertDoesNotThrow(() -> replacementProvenance.requireTrusted(fixture.entry, fixture.publishers));
        assertThrows(SecurityException.class, () -> receipt.requireVerified(fixture.entry, replacementProvenance,
            fixture.publishers, fixture.builderPin, fixture.source, fixture.recipe, fixture.artifact));

        var replacementEntry = entry("2.0.0", fixture.artifact);
        var replacementEntryProvenance = fixture.provenance(replacementEntry, "revision-1", fixture.publisher);
        assertDoesNotThrow(() -> replacementEntryProvenance.requireTrusted(replacementEntry, fixture.publishers));
        assertThrows(SecurityException.class, () -> receipt.requireVerified(replacementEntry, replacementEntryProvenance,
            fixture.publishers, fixture.builderPin, fixture.source, fixture.recipe, fixture.artifact));
    }

    @Test
    void requiresAnAuthenticatedAndUnrevokedPublisherAssertion() throws Exception {
        var fixture = new SignedInputs();
        var impostorProvenance = fixture.provenance(fixture.entry, "revision-1", fixture.builder);
        var signedByBuilder = fixture.attestation(impostorProvenance, BUILDER, fixture.builder, fixture.entry.artifactSha256());
        assertThrows(SecurityException.class, () -> signedByBuilder.requireVerified(fixture.entry, impostorProvenance,
            fixture.publishers, fixture.builderPin, fixture.source, fixture.recipe, fixture.artifact));

        var revokedPublishers = new PluginTrustStore(PluginTrustStore.SCHEMA,
            List.of(key("publisher", fixture.publisher, PluginTrustStore.KeyStatus.REVOKED)), List.of());
        assertThrows(SecurityException.class, () -> fixture.attestation().requireVerified(fixture.entry, fixture.provenance,
            revokedPublishers, fixture.builderPin, fixture.source, fixture.recipe, fixture.artifact));
    }

    @Test
    void requiresThePinnedIndependentBuilderIdentityAndKey() throws Exception {
        var fixture = new SignedInputs();
        var receipt = fixture.attestation();
        assertThrows(SecurityException.class, () -> receipt.requireVerified(fixture.entry, fixture.provenance,
            fixture.publishers, null, fixture.source, fixture.recipe, fixture.artifact));
        var unpinned = newKeyPair();
        for (var pin : List.of(key("elsewhere", fixture.builder, PluginTrustStore.KeyStatus.ACTIVE),
                key(BUILDER, unpinned, PluginTrustStore.KeyStatus.ACTIVE),
                key(BUILDER, fixture.builder, PluginTrustStore.KeyStatus.REVOKED))) {
            assertThrows(SecurityException.class, () -> receipt.requireVerified(fixture.entry, fixture.provenance,
                fixture.publishers, pin, fixture.source, fixture.recipe, fixture.artifact));
        }

        var aliasedPublisherPin = key(BUILDER, fixture.publisher, PluginTrustStore.KeyStatus.ACTIVE);
        var aliasReceipt = fixture.attestation(fixture.provenance, BUILDER, fixture.publisher, fixture.entry.artifactSha256());
        assertThrows(SecurityException.class, () -> aliasReceipt.requireVerified(fixture.entry, fixture.provenance,
            fixture.publishers, aliasedPublisherPin, fixture.source, fixture.recipe, fixture.artifact));

        var reusedPublisherIdentity = key("publisher", fixture.builder, PluginTrustStore.KeyStatus.ACTIVE);
        var sameIdentity = fixture.attestation(fixture.provenance, "publisher", fixture.builder, fixture.entry.artifactSha256());
        assertThrows(SecurityException.class, () -> sameIdentity.requireVerified(fixture.entry, fixture.provenance,
            fixture.publishers, reusedPublisherIdentity, fixture.source, fixture.recipe, fixture.artifact));
    }

    @Test
    void rejectsInvalidSchemaAmbiguousJsonAndNoncanonicalSignatures() throws Exception {
        var fixture = new SignedInputs();
        var receipt = fixture.attestation();
        String json = receipt.toCanonicalJson();
        for (String changed : List.of(json + "{}", json.replace("{", "{\"schema\":\"unknown\","),
                json.replace("{", "{\"unknown\":\"extra\","),
                json.replace("\"builderId\":\"" + BUILDER + "\"", "\"builderId\":123"),
                json.replace("\"keyId\":\"key-1\",", ""), json.replace("/v1", "/v2"),
                json.replace("Ed25519", "unsupported"), json.replace("==", ""))) {
            assertThrows(IllegalArgumentException.class, () -> PluginSourceToBinaryAttestation.read(changed.getBytes(UTF_8)));
        }

        String signature = receipt.signatureBase64();
        String alteredSignature = (signature.startsWith("A") ? "B" : "A") + signature.substring(1);
        var corrupted = PluginSourceToBinaryAttestation.read(json.replace(signature, alteredSignature).getBytes(UTF_8));
        assertThrows(SecurityException.class, () -> corrupted.requireVerified(fixture.entry, fixture.provenance,
            fixture.publishers, fixture.builderPin, fixture.source, fixture.recipe, fixture.artifact));
    }

    private static byte[] sourceArchive() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var archive = new ZipOutputStream(bytes)) {
            var file = new ZipEntry("RebuildExample.java");
            file.setTime(0);
            archive.putNextEntry(file);
            archive.write("""
                package example;
                public final class RebuildExample {
                    public static int value() { return 42; }
                }
                """.getBytes(UTF_8));
        }
        return bytes.toByteArray();
    }

    private static byte[] build(Path workspace, byte[] source, byte[] recipe) throws Exception {
        Files.createDirectories(workspace.resolve("classes"));
        try (var archive = new ZipInputStream(new ByteArrayInputStream(source))) {
            assertEquals("RebuildExample.java", archive.getNextEntry().getName());
            Files.copy(archive, workspace.resolve("RebuildExample.java"));
            assertNull(archive.getNextEntry());
        }

        List<String> steps = new String(recipe, UTF_8).lines().toList();
        assertEquals(Runtime.version().toString(), steps.getFirst());
        runBuildStep(workspace, "javac", steps.get(1));
        runBuildStep(workspace, "jar", steps.get(2));
        return Files.readAllBytes(workspace.resolve("example.jar"));
    }

    private static void runBuildStep(Path workspace, String tool, String step) throws Exception {
        var command = new ArrayList<>(List.of(step.split(" ")));
        assertEquals(tool, command.getFirst());
        String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        command.set(0, Path.of(System.getProperty("java.home"), "bin", tool + suffix).toString());
        Path log = workspace.resolve(tool + ".log");
        var invocation = new ProcessBuilder(command).directory(workspace.toFile())
            .redirectErrorStream(true).redirectOutput(log.toFile());
        for (String variable : List.of("JDK_JAVAC_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS")) {
            invocation.environment().remove(variable);
        }
        Process process = invocation.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), tool + " timed out");
            assertEquals(0, process.exitValue(), tool + " failed:\n" + Files.readString(log));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static KeyPair newKeyPair() throws Exception {
        return KeyPairGenerator.getInstance(PluginSignatureManifest.ALGORITHM).generateKeyPair();
    }

    private static PluginArtifactIndex.Entry entry(String version, byte[] artifact) {
        return PluginArtifactIndex.Entry.create("example-" + version, PluginArtifactIndex.ArtifactKind.JAVA_PLUGIN,
            "example", version, "2", "1.0.0", "", List.of(), List.of(), "example.jar", sha256(artifact),
            "https://catalog.example.test/example.jar", "https://catalog.example.test/example.jar.sig.json",
            "https://catalog.example.test/example.jar.provenance.json", "publisher");
    }

    private static final class SignedInputs {
        final byte[] source;
        final byte[] recipe;
        final byte[] artifact;
        final KeyPair publisher = newKeyPair();
        final KeyPair builder = newKeyPair();
        final PluginTrustStore publishers = new PluginTrustStore(PluginTrustStore.SCHEMA,
            List.of(key("publisher", publisher, PluginTrustStore.KeyStatus.ACTIVE)), List.of());
        final PluginTrustStore.PublisherKey builderPin = key(BUILDER, builder, PluginTrustStore.KeyStatus.ACTIVE);
        final PluginArtifactIndex.Entry entry;
        final PluginArtifactProvenance provenance;

        SignedInputs() throws Exception {
            this("source archive".getBytes(UTF_8), "build recipe".getBytes(UTF_8), "artifact".getBytes(UTF_8));
        }

        SignedInputs(byte[] source, byte[] recipe, byte[] artifact) throws Exception {
            this.source = source;
            this.recipe = recipe;
            this.artifact = artifact;
            entry = entry("1.0.0", artifact);
            provenance = provenance(entry, "revision-1", publisher);
        }

        PluginArtifactProvenance provenance(PluginArtifactIndex.Entry selected, String revision, KeyPair signer)
                throws Exception {
            var unsigned = provenanceEnvelope(selected, revision, UNSIGNED);
            return provenanceEnvelope(selected, revision, sign(signer, unsigned.signedPayload()));
        }

        private PluginArtifactProvenance provenanceEnvelope(PluginArtifactIndex.Entry selected, String revision,
                String signature) {
            return new PluginArtifactProvenance(PluginArtifactProvenance.SCHEMA, selected.identityHash(),
                selected.artifactSha256(), selected.provenanceUri(), "https://source.example.test/project.zip",
                revision, sha256(source), sha256(recipe), "publisher", "key-1", PluginSignatureManifest.ALGORITHM,
                signature);
        }

        PluginSourceToBinaryAttestation attestation() throws Exception {
            return attestation(provenance, BUILDER, builder, entry.artifactSha256());
        }

        PluginSourceToBinaryAttestation attestation(PluginArtifactProvenance assertion, String builderId,
                KeyPair signer, String rebuiltArtifactSha256) throws Exception {
            var unsigned = attestationEnvelope(assertion, builderId, rebuiltArtifactSha256, UNSIGNED);
            return attestationEnvelope(assertion, builderId, rebuiltArtifactSha256,
                sign(signer, unsigned.signedPayload()));
        }

        private PluginSourceToBinaryAttestation attestationEnvelope(PluginArtifactProvenance assertion,
                String builderId, String rebuiltArtifactSha256, String signature) {
            return new PluginSourceToBinaryAttestation(PluginSourceToBinaryAttestation.SCHEMA, assertion.contentHash(),
                assertion.artifactIdentityHash(), assertion.sourceSha256(), assertion.buildRecipeSha256(),
                rebuiltArtifactSha256, builderId, "key-1", PluginSignatureManifest.ALGORITHM, signature);
        }
    }
}
