package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Executes the explicit network-backed audit only on release-audit branches. */
class PublishedReleaseAuditTest {
    private static final Pattern AUDIT_BRANCH = Pattern.compile(
        "^release/audit-v([0-9]+\\.[0-9]+\\.[0-9]+)$");
    private static final Duration TIMEOUT = Duration.ofMinutes(20);

    @Test
    void auditScriptRetainsTheFailClosedPublicationContract()
            throws Exception {
        String content = Files.readString(
            auditScript(),
            StandardCharsets.UTF_8
        );
        assertTrue(content.startsWith("#!/usr/bin/env bash\nset -euo pipefail\n"));
        assertTrue(content.contains("gh release download \"$TAG\""));
        assertTrue(content.contains("sha256sum --check --strict"));
        assertTrue(content.contains(
            "A GitHub release asset lacks a valid declared SHA-256 digest"));
        assertTrue(content.contains("cmp -s"));
        assertTrue(content.contains(
            "Published release body differs byte-for-byte"));
        assertTrue(content.contains(
            "Release tag is lightweight instead of annotated"));
        assertTrue(content.contains("maintenance/${SERIES}.x"));
        assertTrue(content.contains("Implementation-Version: ${VERSION}"));
        assertTrue(content.contains("version=\"3.34.1\""));
        assertTrue(content.contains("ZIP symbolic link is forbidden"));
        assertTrue(content.contains("TAR symbolic/hard link is forbidden"));
        assertTrue(content.contains(
            "RREF(A|b) = [[1, 0 | 2], [0, 1 | 1]]"));
        assertTrue(content.contains("leftCoefficients()"));
        assertTrue(content.contains("rightCoefficients()"));
        assertTrue(content.contains("TARGET.equals(reconstructed)"));
        assertTrue(content.contains("POLYNOMIAL_RELEASE_SMOKE_OK"));
        assertTrue(content.contains("https://zenodo.org/api/records/"));
        assertTrue(content.contains("local page_size=25"));
        assertTrue(content.contains("local max_pages=10"));
        assertTrue(content.contains(
            "--data-urlencode \"page=${page}\""));
        assertTrue(content.contains("EXPECTED_CREATORS=$(jq"));
        assertTrue(content.contains("ACTUAL_CREATORS=$(jq"));
        assertFalse(content.contains("size=100"));
        assertFalse(content.contains("releases/latest"));
        assertTrue(content.contains(
            "regelsuche.published-release-audit/v1"));
        assertFalse(content.contains("--skip-tests"));
    }

    @Test
    void publishedReleaseMatchesTheCheckoutOwnedContract()
            throws Exception {
        Optional<String> requested = requestedVersion();
        assumeTrue(
            requested.isPresent(),
            "post-release audit is opt-in via -DreleaseAuditVersion or "
                + "a release/audit-vX.Y.Z pull-request branch"
        );

        Path root = MavenPomTestSupport.repositoryRoot();
        ProcessBuilder builder = new ProcessBuilder(
            "bash",
            auditScript().toString(),
            requested.orElseThrow()
        );
        builder.directory(root.toFile());
        builder.inheritIO();
        builder.environment().put(
            "REGELSUCHE_REPOSITORY_ROOT",
            root.toString()
        );
        builder.environment().putIfAbsent(
            "REGELSUCHE_RELEASE_AUDIT_REPOSITORY",
            firstNonBlank(
                System.getProperty("releaseAuditRepository"),
                System.getenv("GITHUB_REPOSITORY"),
                "carstenartur/Regelsuche"
            )
        );

        Process process = builder.start();
        boolean completed = process.waitFor(
            TIMEOUT.toMillis(),
            TimeUnit.MILLISECONDS
        );
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        assertTrue(completed, "published-release audit timed out");
        assertEquals(
            0,
            process.exitValue(),
            "published-release audit rejected the remote release"
        );

        Path report = root.resolve("build/reports/release-audit")
            .resolve(requested.orElseThrow() + ".json");
        assertTrue(
            Files.isRegularFile(report) && Files.size(report) > 0,
            () -> "post-release audit did not retain " + report
        );
    }

    private static Path auditScript() {
        Path script = MavenPomTestSupport.repositoryRoot().resolve(
            ".github/scripts/audit-published-release.sh"
        );
        assertTrue(
            Files.isRegularFile(script),
            () -> "missing published-release audit script: " + script
        );
        return script;
    }

    private static Optional<String> requestedVersion() {
        String explicit = firstNonBlank(
            System.getProperty("releaseAuditVersion"),
            System.getenv("REGELSUCHE_RELEASE_AUDIT_VERSION"),
            ""
        );
        if (!explicit.isBlank()) {
            return Optional.of(explicit);
        }
        String head = System.getenv("GITHUB_HEAD_REF");
        if (head == null) {
            return Optional.empty();
        }
        Matcher matcher = AUDIT_BRANCH.matcher(head.trim());
        return matcher.matches()
            ? Optional.of(matcher.group(1))
            : Optional.empty();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
