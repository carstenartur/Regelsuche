package de.regelsuche.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AiKnowledgeClaimsSeedTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<List<Map<String, Object>>> CLAIM_LIST = new TypeReference<>() { };
    private static final Path REPO_ROOT = locateRepoRoot();

    @Test
    void architectureClaimsUseMachineCheckableRuleFields() throws Exception {
        List<Map<String, Object>> claims = readClaims(REPO_ROOT.resolve("ai-knowledge/claims.seed.yaml"), YAML);
        Map<String, Map<String, Object>> claimsById = claimsById(claims);
        List<String> claimIds = claims.stream().map(claim -> String.valueOf(claim.get("id"))).toList();

        assertEquals(
            Set.of(
                "no-infrastructure-in-core",
                "search-kernel-clean",
                "validation-kernel-clean",
                "persistence-port-clean",
                "acyclic-module-graph",
                "port-interfaces-first",
                "deterministic-discovery",
                "hibernate-isolated"
            ),
            Set.copyOf(claimIds)
        );
        assertEquals(claimIds.size(), claimsById.size());

        claimsById.values().forEach(this::assertHasStructuralRule);

        assertEquals("error", claimsById.get("no-infrastructure-in-core").get("severity"));
        assertEquals(List.of(), claimsById.get("no-infrastructure-in-core").get("allowedTargetModules"));

        assertEquals("error", claimsById.get("search-kernel-clean").get("severity"));
        assertEquals(
            List.of("regelsuche-core", "regelsuche-egraph"),
            claimsById.get("search-kernel-clean").get("allowedTargetModules")
        );

        assertEquals("error", claimsById.get("validation-kernel-clean").get("severity"));
        assertEquals(
            List.of("regelsuche-core"),
            claimsById.get("validation-kernel-clean").get("allowedTargetModules")
        );

        assertEquals("error", claimsById.get("persistence-port-clean").get("severity"));
        assertEquals(
            List.of("regelsuche-core"),
            claimsById.get("persistence-port-clean").get("allowedTargetModules")
        );

        assertEquals("error", claimsById.get("acyclic-module-graph").get("severity"));
        assertTrue(asStringList(claimsById.get("acyclic-module-graph").get("requiredDocs")).contains("docs/dependency-rules.md"));
        assertTrue(asStringList(claimsById.get("acyclic-module-graph").get("verifiedBy"))
            .contains("de.regelsuche.architecture.ArchitectureBoundariesTest"));

        assertEquals("warning", claimsById.get("port-interfaces-first").get("severity"));
        assertTrue(asStringList(claimsById.get("port-interfaces-first").get("requiredDocs"))
            .contains("docs/adr/0001-logical-module-boundaries.md"));

        assertEquals("error", claimsById.get("deterministic-discovery").get("severity"));
        assertEquals(
            List.of("discovery-evidence"),
            claimsById.get("deterministic-discovery").get("requiredEvidenceTypes")
        );
        assertTrue(asStringList(claimsById.get("deterministic-discovery").get("requiredTests"))
            .contains("de.regelsuche.docs.DiscoveryCampaignSevenRunnerTest"));
        assertTrue(asStringList(claimsById.get("deterministic-discovery").get("scopeModules"))
            .contains("app"));

        assertEquals("error", claimsById.get("hibernate-isolated").get("severity"));
        assertFalse(asStringList(claimsById.get("hibernate-isolated").get("scopeModules"))
            .contains("regelsuche-persistence-hibernate"));
    }

    @Test
    void jsonClaimSnapshotMatchesYamlSeeds() throws Exception {
        List<Map<String, Object>> yamlClaims = readClaims(REPO_ROOT.resolve("ai-knowledge/claims.seed.yaml"), YAML);
        List<Map<String, Object>> jsonClaims = readClaims(REPO_ROOT.resolve("ai-knowledge/claims.json"), JSON);

        assertEquals(yamlClaims, jsonClaims);
    }

    private void assertHasStructuralRule(Map<String, Object> claim) {
        boolean hasRule = claim.containsKey("scopeModules")
            || claim.containsKey("forbiddenReferences")
            || claim.containsKey("forbiddenDependencies")
            || claim.containsKey("allowedTargetModules")
            || claim.containsKey("verifiedBy")
            || claim.containsKey("requiredTests")
            || claim.containsKey("requiredEvidenceTypes")
            || claim.containsKey("requiredDocs")
            || Boolean.TRUE.equals(claim.get("mustBeAcyclic"));

        assertTrue(hasRule, () -> "Claim must define at least one machine-checkable rule: " + claim.get("id"));
        assertTrue(claim.containsKey("severity"), () -> "Claim must declare severity: " + claim.get("id"));
    }

    private static List<Map<String, Object>> readClaims(Path path, ObjectMapper mapper) throws IOException {
        return mapper.readValue(Files.readString(path), CLAIM_LIST);
    }

    private static Map<String, Map<String, Object>> claimsById(List<Map<String, Object>> claims) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> claim : claims) {
            result.put(String.valueOf(claim.get("id")), claim);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    private static Path locateRepoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("README.md"))
                && Files.exists(current.resolve("settings.gradle"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root from working directory");
    }
}
