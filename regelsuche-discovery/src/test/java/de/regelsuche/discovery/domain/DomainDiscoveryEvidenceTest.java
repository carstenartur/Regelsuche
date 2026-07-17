package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DomainDiscoveryEvidenceTest {
    @Test
    void writesCanonicalEvidenceForExpressionAndSecondDomain() throws Exception {
        var runner = new DomainDiscoveryRunner();

        var expressionDomain = new ExpressionRewriteDiscoveryDomain("x");
        var expressionRun = runner.run(
            "generic-domain-expression-reference",
            expressionDomain,
            DiscoverySeed.create(
                "expression-reference",
                expressionDomain.domainId(),
                "x + 0",
                "generic-domain-reference/v1"),
            new DiscoveryBudget(2, 20, 100, 80, 5, 20));

        var sequenceDomain = new FiniteDifferenceSequenceDomain();
        var sequenceRun = runner.run(
            "generic-domain-sequence-reference",
            sequenceDomain,
            DiscoverySeed.create(
                "sequence-reference",
                sequenceDomain.domainId(),
                "observed=1,4,9,16;holdout=25,36",
                "generic-domain-reference/v1"),
            new DiscoveryBudget(4, 20, 20, 10, 5, 20));

        Path root = Path.of("build", "reports", "domain-discovery");
        write(root.resolve("expression/report.json"),
            expressionRun.evidence().toCanonicalJson());
        write(root.resolve("sequence/report.json"),
            sequenceRun.evidence().toCanonicalJson());
        write(root.resolve("expression/domain.json"),
            expressionDomain.descriptor().toCanonicalJson());
        write(root.resolve("sequence/domain.json"),
            sequenceDomain.descriptor().toCanonicalJson());

        assertEquals(expressionRun.evidence().toCanonicalJson(),
            Files.readString(root.resolve("expression/report.json")));
        assertEquals(sequenceRun.evidence().toCanonicalJson(),
            Files.readString(root.resolve("sequence/report.json")));
        assertTrue(expressionRun.evidence().toCanonicalJson().contains(
            "\"proofStatus\":\"NOT_EVALUATED\""));
        assertTrue(sequenceRun.evidence().toCanonicalJson().contains(
            "\"type\":\"finite-difference-sequence-evidence/v1\""));
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
