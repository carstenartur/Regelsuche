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
            workflow.contains("mvn --batch-mode --no-transfer-progress -Pfull verify"),
            "release must repeat the complete Maven product and Docker contract"
        );
        assertTrue(
            workflow.contains("release_notes_sha256=$(sha256sum \"$NOTES\""),
            "release manifest must bind the curated notes bytes"
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
    void releaseMetadataContractIncludesThePublicOpenApiVersion()
            throws IOException {
        Path root = MavenPomTestSupport.repositoryRoot();
        String script = Files.readString(
            root.resolve(".github/scripts/update-release-metadata.py"),
            StandardCharsets.UTF_8
        );
        String releaseProperties = Files.readString(
            root.resolve("release.properties"),
            StandardCharsets.UTF_8
        );
        String version = releaseProperties.lines()
            .filter(line -> line.startsWith("version="))
            .map(line -> line.substring("version=".length()).trim())
            .findFirst()
            .orElseThrow();
        String openApi = Files.readString(
            root.resolve("app/src/main/resources/web/openapi/openapi.json"),
            StandardCharsets.UTF_8
        );

        assertTrue(
            script.contains("OPENAPI_PATH = ROOT / 'app/src/main/resources/web/openapi/openapi.json'"),
            "the coordinated metadata helper must own the embedded OpenAPI document"
        );
        assertTrue(
            script.contains("update_openapi(args.version)"),
            "release and next-development transitions must update OpenAPI"
        );
        assertTrue(
            script.contains("actual_openapi != version"),
            "metadata checking must fail closed on OpenAPI version drift"
        );
        assertTrue(
            openApi.contains("\"version\": \"" + version + "\""),
            "the checked-in OpenAPI version must equal release.properties"
        );
    }
}
