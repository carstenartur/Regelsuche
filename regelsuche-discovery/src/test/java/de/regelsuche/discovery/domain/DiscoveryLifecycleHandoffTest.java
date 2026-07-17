package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiscoveryLifecycleHandoffTest {
    @Test
    void createsRepresentationFreeHandoffsForBothDomains() throws Exception {
        DomainDiscoveryRunner runner = new DomainDiscoveryRunner();

        ExpressionRewriteDiscoveryDomain expressionDomain =
            new ExpressionRewriteDiscoveryDomain("x");
        var expressionRun = runner.run(
            "lifecycle-expression-reference",
            expressionDomain,
            DiscoverySeed.create(
                "lifecycle-expression-seed",
                expressionDomain.domainId(),
                "x + 0",
                "domain-lifecycle-handoff/v1"),
            new DiscoveryBudget(2, 20, 100, 80, 5, 20));
        DiscoveryLifecycleHandoff expression = DiscoveryLifecycleHandoff.from(
            expressionRun.evidence());

        FiniteDifferenceSequenceDomain sequenceDomain =
            new FiniteDifferenceSequenceDomain();
        var sequenceRun = runner.run(
            "lifecycle-sequence-reference",
            sequenceDomain,
            DiscoverySeed.create(
                "lifecycle-sequence-seed",
                sequenceDomain.domainId(),
                "observed=1,4,9,16;holdout=25,36",
                "domain-lifecycle-handoff/v1"),
            new DiscoveryBudget(4, 20, 20, 10, 5, 20));
        DiscoveryLifecycleHandoff sequence = DiscoveryLifecycleHandoff.from(
            sequenceRun.evidence());

        assertEquals(DiscoveryLifecycleHandoff.Disposition.CONFIRMED,
            expression.disposition());
        assertEquals(DiscoveryLifecycleHandoff.Disposition.CONFIRMED,
            sequence.disposition());
        assertEquals(expressionRun.evidence().contentHash(),
            expression.sourceEvidenceHash());
        assertEquals(sequenceRun.evidence().contentHash(),
            sequence.sourceEvidenceHash());
        assertTrue(expression.resources().stream().allMatch(account ->
            account.configured() == account.executed()
                + account.skipped() + account.remaining()));
        assertTrue(sequence.resources().stream().allMatch(account ->
            account.configured() == account.executed()
                + account.skipped() + account.remaining()));

        String expressionJson = expression.toCanonicalJson();
        String sequenceJson = sequence.toCanonicalJson();
        assertFalse(expressionJson.contains("x + 0"));
        assertFalse(expressionJson.contains("\"payload\""));
        assertFalse(sequenceJson.contains("observed=1,4,9,16"));
        assertFalse(sequenceJson.contains("25,36"));
        assertTrue(expressionJson.contains("\"proofStatus\":\"NOT_EVALUATED\""));
        assertTrue(sequenceJson.contains("\"externalNoveltyStatus\":\"NOT_EVALUATED\""));

        Path root = Path.of("build", "reports", "domain-lifecycle-handoff");
        write(root.resolve("expression-handoff.json"), expressionJson);
        write(root.resolve("sequence-handoff.json"), sequenceJson);
        assertEquals(expressionJson,
            Files.readString(root.resolve("expression-handoff.json")));
        assertEquals(sequenceJson,
            Files.readString(root.resolve("sequence-handoff.json")));

        assertEquals(expression, DiscoveryLifecycleHandoff.from(expressionRun.evidence()));
        assertEquals(sequence, DiscoveryLifecycleHandoff.from(sequenceRun.evidence()));
    }

    @Test
    void rejectsTamperedHandoffHash() {
        ExpressionRewriteDiscoveryDomain domain =
            new ExpressionRewriteDiscoveryDomain("x");
        var run = new DomainDiscoveryRunner().run(
            "lifecycle-tamper-reference",
            domain,
            DiscoverySeed.create(
                "lifecycle-tamper-seed",
                domain.domainId(),
                "x + 0",
                "domain-lifecycle-handoff/v1"),
            new DiscoveryBudget(2, 20, 100, 80, 5, 20));
        DiscoveryLifecycleHandoff handoff = DiscoveryLifecycleHandoff.from(run.evidence());

        assertThrows(IllegalArgumentException.class, () ->
            new DiscoveryLifecycleHandoff(
                handoff.schema(),
                handoff.handoffId(),
                handoff.sourceKind(),
                handoff.campaignId(),
                handoff.domainId(),
                handoff.domainRevision(),
                handoff.domainContractHash(),
                handoff.inputHash(),
                handoff.inputHash(),
                handoff.stage(),
                handoff.disposition(),
                handoff.selectedCandidateHash(),
                handoff.certificateHash(),
                handoff.resources(),
                handoff.metadata(),
                handoff.proofStatus(),
                handoff.externalNoveltyStatus(),
                handoff.promotionStatus(),
                handoff.publicEvidenceStatus(),
                handoff.contentHash()));
    }

    @Test
    void rejectsCompletedDiscoveryValidationHandoff() {
        assertThrows(IllegalArgumentException.class, () ->
            DiscoveryLifecycleHandoff.create(
                "invalid-completed-discovery-handoff",
                DiscoveryLifecycleHandoff.SourceKind.DOMAIN_DISCOVERY_EVIDENCE,
                "invalid-completed-campaign",
                "invalid-completed-domain",
                "v1",
                DomainCanonical.sha256("domain-contract"),
                DomainCanonical.sha256("input"),
                DomainCanonical.sha256("source-evidence"),
                DiscoveryLifecycleHandoff.Stage.DISCOVERY_VALIDATION,
                DiscoveryLifecycleHandoff.Disposition.COMPLETED,
                "",
                "",
                List.of(new DiscoveryLifecycleHandoff.ResourceAccount(
                    "STATES", 1, 1, 0, 0)),
                Map.of(),
                DiscoveryLifecycleHandoff.NOT_EVALUATED,
                DiscoveryLifecycleHandoff.NOT_EVALUATED,
                DiscoveryLifecycleHandoff.NOT_EVALUATED,
                DiscoveryLifecycleHandoff.NOT_EVALUATED));
    }

    @Test
    void rejectsWhitespaceOptionalHash() {
        assertThrows(IllegalArgumentException.class, () ->
            DiscoveryLifecycleHandoff.create(
                "invalid-whitespace-hash-handoff",
                DiscoveryLifecycleHandoff.SourceKind.DOMAIN_DISCOVERY_EVIDENCE,
                "invalid-whitespace-campaign",
                "invalid-whitespace-domain",
                "v1",
                DomainCanonical.sha256("domain-contract"),
                DomainCanonical.sha256("input"),
                DomainCanonical.sha256("source-evidence"),
                DiscoveryLifecycleHandoff.Stage.DISCOVERY_VALIDATION,
                DiscoveryLifecycleHandoff.Disposition.CONFIRMED,
                " ",
                DomainCanonical.sha256("certificate"),
                List.of(new DiscoveryLifecycleHandoff.ResourceAccount(
                    "STATES", 1, 1, 0, 0)),
                Map.of(),
                DiscoveryLifecycleHandoff.NOT_EVALUATED,
                DiscoveryLifecycleHandoff.NOT_EVALUATED,
                DiscoveryLifecycleHandoff.NOT_EVALUATED,
                DiscoveryLifecycleHandoff.NOT_EVALUATED));
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
