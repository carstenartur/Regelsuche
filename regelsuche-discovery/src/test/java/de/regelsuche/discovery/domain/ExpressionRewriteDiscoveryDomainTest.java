package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.Outcome;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import org.junit.jupiter.api.Test;

class ExpressionRewriteDiscoveryDomainTest {
    @Test
    void existingAstRewriteEngineRunsThroughGenericDomainWithoutProofInflation() {
        var engine = new AstRewriteTransformationEngine();
        assertTrue(engine.transform("x + 0").stream().anyMatch(transformation ->
            transformation.rule().equals("ast_add_zero_right")
                && transformation.transformedExpression().equals("x")));

        var domain = new ExpressionRewriteDiscoveryDomain("x", engine);
        var seed = DiscoverySeed.create(
            "expression-add-zero",
            domain.domainId(),
            "x + 0",
            "existing-algebra-characterization/v1");
        var budget = new DiscoveryBudget(2, 20, 100, 80, 5, 20);

        var run = new DomainDiscoveryRunner().run(
            "expression-domain-confirmed", domain, seed, budget);

        assertEquals(Outcome.CONFIRMED, run.evidence().outcome());
        var candidate = run.selectedCandidate().orElseThrow();
        assertEquals("x + 0", candidate.sourceExpression());
        assertEquals("x", candidate.targetExpression());
        assertTrue(candidate.ruleIds().contains("ast_add_zero_right"));
        assertTrue(candidate.allSemanticsPreserving());
        assertEquals("VALIDATION_EVIDENCE_NOT_FORMAL_PROOF",
            run.selectedCertificate().orElseThrow().evidenceStrength());
        assertEquals("CANONICAL_EQUIVALENCE_TRACE",
            run.evidence().certificate().kind());
        assertEquals("NOT_EVALUATED", run.evidence().proofStatus());
        assertEquals("NOT_EVALUATED", run.evidence().externalNoveltyStatus());
        assertEquals("NOT_EVALUATED", run.evidence().promotionStatus());
        assertFalse(run.evidence().selectedCandidateHash().isBlank());
    }

    @Test
    void repeatedRunIsByteDeterministic() {
        var domain = new ExpressionRewriteDiscoveryDomain("x");
        var seed = DiscoverySeed.create(
            "expression-add-zero-repeat",
            domain.domainId(),
            "x + 0",
            "existing-algebra-characterization/v1");
        var budget = new DiscoveryBudget(2, 20, 100, 80, 5, 20);
        var runner = new DomainDiscoveryRunner();

        var first = runner.run("expression-domain-repeat", domain, seed, budget);
        var second = runner.run("expression-domain-repeat", domain, seed, budget);

        assertEquals(first.evidence().contentHash(), second.evidence().contentHash());
        assertEquals(first.evidence().toCanonicalJson(),
            second.evidence().toCanonicalJson());
    }
}
