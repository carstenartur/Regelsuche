package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class ProofCarryingShowcaseDrandChainInfoTest {
    @Test
    void committedDefaultChainInfoIsCanonicalAndPlanCompatible()
            throws Exception {
        ProofCarryingShowcaseDrandChainInfo expected =
            ProofCarryingShowcaseDrandChainInfo.createDefault();
        var path = ProofCarryingShowcaseTestFixtures.repositoryRoot()
            .resolve("research/showcase/proof-carrying-self-improvement")
            .resolve("drand-default-chain-info-v1.json");
        ProofCarryingShowcaseDrandChainInfo committed =
            ProofCarryingShowcaseDrandChainInfo.read(path);

        assertEquals(expected, committed);
        assertEquals(
            "sha256:0871308f3f1e10013f616323178ceeb0f4a5e00b6c9b9a90af40934dbed50bb2",
            committed.contentHash());
        assertEquals(
            committed.toCanonicalJson(),
            Files.readString(path, StandardCharsets.UTF_8));
        committed.requireCompatible(ProofCarryingShowcaseTestFixtures.plan());
    }

    @Test
    void computesOnlyTheFirstEligibleScheduledRoundAfterTheBoundary() {
        ProofCarryingShowcaseDrandChainInfo chain =
            ProofCarryingShowcaseDrandChainInfo.createDefault();

        assertEquals(1L, chain.firstEligibleScheduledRound(0L));
        assertEquals(2L, chain.firstEligibleScheduledRound(chain.genesisTime()));
        assertEquals(
            2L,
            chain.firstEligibleScheduledRound(chain.genesisTime() + 29));
        assertEquals(
            3L,
            chain.firstEligibleScheduledRound(chain.genesisTime() + 30));
        assertEquals(chain.genesisTime(), chain.roundUnixTime(1));
        assertEquals(chain.genesisTime() + 30, chain.roundUnixTime(2));
        assertThrows(
            IllegalArgumentException.class,
            () -> chain.firstEligibleScheduledRound(-1));
        assertThrows(
            IllegalArgumentException.class,
            () -> chain.roundUnixTime(0));
    }

    @Test
    void rejectsPublicKeyOrContentHashDrift() {
        ProofCarryingShowcaseDrandChainInfo chain =
            ProofCarryingShowcaseDrandChainInfo.createDefault();

        assertThrows(
            IllegalArgumentException.class,
            () -> new ProofCarryingShowcaseDrandChainInfo(
                chain.schema(),
                chain.network(),
                chain.chainHash(),
                "00".repeat(48),
                chain.periodSeconds(),
                chain.genesisTime(),
                chain.genesisSeed(),
                chain.scheme(),
                chain.beaconId(),
                chain.sourceApi(),
                chain.status(),
                chain.contentHash()));
        assertThrows(
            IllegalArgumentException.class,
            () -> ProofCarryingShowcaseDrandChainInfo.fromCanonicalJson(
                chain.toCanonicalJson().replace(
                    chain.contentHash(),
                    "sha256:" + "00".repeat(32))));
    }
}
