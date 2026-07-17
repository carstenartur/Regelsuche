package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import org.junit.jupiter.api.Test;

class DiscoverySeedCanonicalizationTest {
    @Test
    void lengthPrefixedFieldsPreventDelimiterInjectionCollisions() {
        DiscoverySeed first = DiscoverySeed.create(
            "canonical-seed",
            "canonical-domain",
            "x\nsourceReference=y",
            "z");
        DiscoverySeed second = DiscoverySeed.create(
            "canonical-seed",
            "canonical-domain",
            "x",
            "y\nsourceReference=z");

        assertNotEquals(first.contentHash(), second.contentHash());
        assertEquals(first.contentHash(), DiscoverySeed.create(
            first.seedId(),
            first.domainId(),
            first.payload(),
            first.sourceReference()).contentHash());
        assertThrows(IllegalArgumentException.class, () -> new DiscoverySeed(
            DiscoverySeed.SCHEMA,
            second.seedId(),
            second.domainId(),
            second.payload(),
            second.sourceReference(),
            first.contentHash()));
    }
}
