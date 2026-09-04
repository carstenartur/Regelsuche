package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoveryDomainDescriptorTest {
    @Test
    void descriptorRetainsEveryPluggableSemanticRole() {
        var expression = new ExpressionRewriteDiscoveryDomain("x").descriptor();
        var sequence = new FiniteDifferenceSequenceDomain().descriptor();

        assertEquals(DiscoveryDomainDescriptor.SCHEMA, expression.schema());
        assertTrue(expression.semanticRoles().contains("GENERATION"));
        assertTrue(expression.semanticRoles().contains("CANONICALIZATION"));
        assertTrue(expression.semanticRoles().contains("INVARIANT_CHECKING"));
        assertTrue(expression.semanticRoles().contains("SEARCH"));
        assertTrue(expression.semanticRoles().contains("CANDIDATE_FORMATION"));
        assertTrue(expression.semanticRoles().contains("COUNTEREXAMPLE_SEARCH"));
        assertTrue(expression.semanticRoles().contains("VALIDATION"));
        assertTrue(expression.semanticRoles().contains("CERTIFICATE_RENDERING"));
        assertTrue(expression.semanticRoles().contains("EVIDENCE_ADAPTATION"));
        assertTrue(expression.deterministic());
        assertTrue(sequence.deterministic());
        assertNotEquals(expression.domainId(), sequence.domainId());
        assertNotEquals(expression.contentHash(), sequence.contentHash());
        assertTrue(expression.toCanonicalJson().contains(
            "\"contentHash\":\"" + expression.contentHash() + "\""));
    }

    @Test
    void canonicalComponentIdentityRejectsDuplicatesAndHiddenNormalization() {
        assertThrows(
            IllegalArgumentException.class,
            () -> DomainCanonical.sortedUniqueIdentifiers(
                List.of("domain/operator/v1", "domain/operator/v1"),
                "operator id"
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> DomainCanonical.sortedUniqueIdentifiers(
                List.of(" domain/operator/v1"),
                "operator id"
            )
        );
    }
}
