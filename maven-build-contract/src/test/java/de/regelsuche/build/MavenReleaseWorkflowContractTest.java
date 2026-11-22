package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards the fail-closed publication semantics of the platform release workflow. */
class MavenReleaseWorkflowContractTest {
    private static final String OPENAPI_PATH =
        "app/src/main/resources/web/openapi/openapi.json";

    @Test
    void releaseSdkBuildPinsInputJarTimestampsToTheSourceCommit()
            throws IOException {
        String workflow = Files.readString(
            MavenPomTestSupport.repositoryRoot().resolve(".github/workflows/release.yml"),
            StandardCharsets.UTF_8
        );
        int start = workflow.indexOf("- name: Build independent Java SDK distribution");
        int end = workflow.indexOf("- name: Collect and inspect release artifacts", start);
        assertTrue(start >= 0 && end > start, "release SDK packaging step must exist");
        String sdkStep = workflow.substring(start, end);
        assertTrue(
            sdkStep.contains("SDK_SOURCE_EPOCH=$(git show -s --format=%ct HEAD)"),
            "SDK input timestamps must derive from the committed release source"
        );
        int build = sdkStep.indexOf("mvn --batch-mode --no-transfer-progress -Psdk-release");
        int packageSdk = sdkStep.indexOf("python3 -B scripts/package-student-sdk.py", build);
        assertTrue(build >= 0 && packageSdk > build, "SDK modules must be built before packaging");
        String install = sdkStep.substring(build, packageSdk);
        assertTrue(install.contains("-Dproject.build.outputTimestamp=\"$SDK_SOURCE_EPOCH\""),
            "SDK Maven build must receive the source timestamp used by the reproduction contract");
        assertTrue(install.contains("-DskipTests clean install"),
            "SDK packaging must rebuild its Maven inputs from clean module outputs");
    }

    @Test
    void releaseUsesCuratedNotesAndTheCompleteProductGate()
            throws IOException {
        Path root = MavenPomTestSupport.repositoryRoot();
        String workflow = Files.readString(
            root.resolve(".github/workflows/release.yml"),
            StandardCharsets.UTF_8
        );

        assertTrue(
            workflow.contains("- name: Validate curated release notes"),
            "release must reject a missing or malformed curated notes file"
        );
        assertTrue(
            workflow.contains("NOTES=\"docs/releases/${VERSION}.md\""),
            "release notes must be selected from the exact release version"
        );
        assertTrue(
            workflow.contains("mvn --batch-mode --no-transfer-progress -Pfull,sdk-release verify"),
            "release must repeat the complete Maven product and Docker contract"
        );
        assertTrue(
            workflow.contains("release_notes_sha256=$(sha256sum \"$NOTES\""),
            "release manifest must bind the curated notes bytes"
        );
        assertTrue(
            workflow.contains("openapi_sha256=$(sha256sum " + OPENAPI_PATH),
            "release manifest must bind the public OpenAPI bytes"
        );
        assertTrue(
            workflow.contains("--notes-file \"docs/releases/${VERSION}.md\""),
            "GitHub Release body must come from the curated notes file"
        );
        assertTrue(
            workflow.contains("Published release body differs from docs/releases/${VERSION}.md"),
            "publication verification must compare the remote body with the source file"
        );
        assertFalse(
            workflow.contains("--generate-notes"),
            "automatically generated notes would bypass the audited semantic interval"
        );

        long openApiReferences = workflow.lines()
            .filter(line -> line.contains(OPENAPI_PATH))
            .count();
        assertTrue(
            openApiReferences >= 3,
            "OpenAPI must be staged for the release commit, hashed in the manifest "
                + "and included in the next-development PR"
        );

        int notesValidation = workflow.indexOf(
            "- name: Validate curated release notes"
        );
        int releaseTag = workflow.indexOf(
            "- name: Create and push release tag"
        );
        assertTrue(
            notesValidation >= 0 && releaseTag > notesValidation,
            "curated notes must be validated before the first publication mutation"
        );
    }

    @Test
    void releaseMetadataContractIncludesThePublicOpenApiVersion(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        MavenReleaseMetadataTransitionTest.fixture(root);
        ReleaseMetadataTransition.write(root, "9.8.7", java.time.LocalDate.parse("2026-11-18"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var openApi = root.resolve(OPENAPI_PATH);
        org.junit.jupiter.api.Assertions.assertEquals("9.8.7", mapper.readTree(openApi.toFile()).at("/info/version").textValue());
        ReleaseMetadataTransition.write(root, "9.9.0-SNAPSHOT", null);
        org.junit.jupiter.api.Assertions.assertEquals("9.9.0-SNAPSHOT", mapper.readTree(openApi.toFile()).at("/info/version").textValue());
        Files.writeString(openApi, Files.readString(openApi).replace("9.9.0-SNAPSHOT", "9.8.7"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> MavenReleaseMetadataAlignmentTest.verify(root, "9.9.0-SNAPSHOT"));
    }
}
