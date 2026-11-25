package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Cheap structural failures must stop costly authorities without serializing them against each other. */
class MavenFastPreflightWorkflowContractTest {
    private static final List<String> AUTHORITIES = List.of(
        "gradle-verification", "jmh-verification", "sympy-runtime-verification", "maven-product-verification",
        "maven-git-free-verification", "maven-student-sdk-archive-qualification", "maven-polynomial-qualification",
        "maven-native-polynomial-study", "maven-typed-polynomial-study", "maven-schema-polynomial-study",
        "checked-proofs", "native-scalar-reproduction");

    @Test
    void staticPreflightIsAFailClosedPrerequisiteForEveryRequiredAuthority() throws Exception {
        String text = Files.readString(MavenPomTestSupport.repositoryRoot().resolve(".github/workflows/gradle.yml"));
        var jobs = new ObjectMapper(new YAMLFactory()).readTree(text).path("jobs");
        var preflight = jobs.path("preflight-contracts");
        assertFalse(preflight.isMissingNode(), "a failing cheap contract must be noticed before expensive execution");
        assertFalse(preflight.has("needs"));
        assertFalse(preflight.path("continue-on-error").asBoolean());
        assertTrue(preflight.path("timeout-minutes").asInt() > 0 && preflight.path("timeout-minutes").asInt() <= 10);
        String commands = preflight.path("steps").toString();
        assertTrue(commands.contains("-pl maven-build-contract,regelsuche-core"));
        assertTrue(commands.contains("Maven*ContractTest,StrategyCodeReferencesTest"));
        assertFalse(commands.contains("-DskipTests"));
        assertFalse(commands.contains("continue-on-error"));
        for (String name : AUTHORITIES) {
            assertEquals("preflight-contracts", jobs.path(name).path("needs").asText(), name);
            assertFalse(jobs.path(name).path("if").asText().contains("always()"), name);
        }
        var archives = jobs.required("typed-external-polynomial-comparison");
        assertEquals(List.of("preflight-contracts", "maven-typed-polynomial-study", "maven-native-polynomial-study"),
            archives.required("needs").valueStream().map(n -> n.asText()).toList());
        assertFalse(archives.required("if").asText().contains("always()"));
        var aggregate = jobs.path("verification");
        assertTrue(aggregate.path("needs").toString().contains("preflight-contracts"));
        assertTrue(aggregate.path("if").asText().contains("always()"));
        assertTrue(aggregate.path("steps").toString().contains("needs.preflight-contracts.result != 'success'"));
    }
}
