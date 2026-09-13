package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks required host bindings; real script processes are exercised in the Release module. */
class MavenReleaseReadinessRetirementContractTest {
    private static final Path ROOT = Path.of(System.getProperty("regelsuche.repositoryRoot"));

    @Test
    void requiredJavaConsumerWaitsForProductionWhileRetainedReaderDoesNotGenerate() throws Exception {
        String release = Files.readString(ROOT.resolve("regelsuche-release/build.gradle"));
        String required = task(release, "verifyQualifiedReleaseReadinessJava");
        assertTrue(required.contains("JavaExec"));
        assertTrue(required.contains("tasks.named('runQualifiedReleaseReadinessWithHiddenRuleEvidence')"),
            "the consumer itself must depend on generation, not a sibling root task");
        for (String block : List.of(required, task(release, "verifyRetainedReleaseReadinessJava"))) {
            assertTrue(block.contains("mainClass = 'de.regelsuche.release.ReleaseReadinessEvidenceVerifier'"));
            assertTrue(block.contains("classpath = sourceSets.main.runtimeClasspath"));
            assertTrue(block.contains("--enable-native-access=ALL-UNNAMED"));
            assertTrue(block.contains("docs/schemas"));
        }
        String retained = task(release, "verifyRetainedReleaseReadinessJava");
        assertFalse(retained.contains("runQualified"));
        assertTrue(retained.contains("dependsOn tasks.named('classes')"));

        String root = Files.readString(ROOT.resolve("build.gradle"));
        String gate = task(root, "verifyReleaseReadinessEvidence");
        assertTrue(gate.contains("repositoryTest"));
        assertTrue(gate.contains("testReleaseReadinessEvidence"));
        assertTrue(gate.contains(":regelsuche-release:verifyQualifiedReleaseReadinessJava"));
        assertTrue(task(root, "testReleaseReadinessEvidence").contains(":regelsuche-release:test"));
        assertTrue(root.contains("mustRunAfter repositoryTest"));
        String reproduction = task(root, "verifyReleaseReadinessDockerReproduction");
        assertTrue(reproduction.contains(":regelsuche-release:installDist"));
        assertTrue(reproduction.contains("REGELSUCHE_RELEASE_VERIFIER_JAVA"));
        assertTrue(reproduction.contains("javaLauncher.get().executablePath.asFile.absolutePath"));
        for (String block : List.of(gate, task(root, "testReleaseReadinessEvidence"), reproduction)) {
            assertFalse(block.contains("prepareVerificationEnvironment"));
            assertFalse(block.contains("PYTHON"));
        }
    }

    @Test
    void fourObsoleteFilesAreRetiredAndTheSharedCollectorReaderRemains() throws Exception {
        for (String name : List.of("verify-release-readiness-evidence.py", "test-release-readiness-evidence.py",
                "release_readiness_bindings.py", "release_readiness_identities.py")) {
            assertFalse(Files.exists(ROOT.resolve("scripts").resolve(name)), name);
        }
        assertTrue(Files.isRegularFile(ROOT.resolve("scripts/release_readiness_files.py")));
        assertTrue(Files.readString(ROOT.resolve("scripts/collect-quality-acceptance-evidence.py"))
            .contains("from release_readiness_files import EvidenceFiles"));
    }

    @Test
    void currentMavenEvidenceIsRetainedBeforeLaterReleaseAssemblyCleansIt() throws Exception {
        String ordinary = Files.readString(ROOT.resolve(".github/workflows/gradle.yml"));
        String release = Files.readString(ROOT.resolve(".github/workflows/release.yml"));
        for (String workflow : List.of(ordinary, release)) {
            assertTrue(workflow.contains("app/target/reports/hidden-rule-pilot/**"));
            assertTrue(workflow.contains("regelsuche-quality-aggregate/target/reports/release-readiness-qualified/**"));
        }
        int verified = release.indexOf("- name: Verify the complete Maven product and Docker contract");
        int retained = release.indexOf("- name: Retain Maven release-readiness evidence");
        int assembled = release.indexOf("- name: Run emergency assembly-only verification");
        assertTrue(verified >= 0 && retained > verified && assembled > retained,
            "upload the verify-phase output before any later package lifecycle removes it");
    }

    private static String task(String source, String name) {
        int start = source.indexOf("tasks.register('" + name + "'");
        assertTrue(start >= 0, "missing task " + name);
        int end = source.indexOf("\n}", start);
        assertTrue(end > start, "missing task boundary " + name);
        return source.substring(start, end);
    }
}
