package de.regelsuche.evolution;

import java.nio.file.Path;
import java.util.Map;

/**
 * Preregistered root-of-trust metadata for the drand default mainnet chain.
 *
 * <p>This artifact freezes chain identity and timing before any eligible
 * showcase randomness round is selected. It deliberately contains no beacon
 * round, signature, randomness value, or FINAL TEST material.</p>
 */
public record ProofCarryingShowcaseDrandChainInfo(
    String schema,
    String network,
    String chainHash,
    String publicKey,
    int periodSeconds,
    long genesisTime,
    String genesisSeed,
    String scheme,
    String beaconId,
    String sourceApi,
    String status,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.proof-carrying-showcase-drand-chain-info/v1";
    public static final String NETWORK = "default";
    public static final String CHAIN_HASH =
        "8990e7a9aaed2ffed73dbd7092123d6f289930540d7651336225dc172e51b2ce";
    public static final String PUBLIC_KEY =
        "868f005eb8e6e4ca0a47c8a77ceaa5309a47978a7c71bc5cce96366b5d7a569937c529eeda66c7293784a9402801af31";
    public static final int PERIOD_SECONDS = 30;
    public static final long GENESIS_TIME = 1_595_431_050L;
    public static final String GENESIS_SEED =
        "176f93498eac9ca337150b46d21dd58673ea4e3581185f869672e59fa4cb390a";
    public static final String SCHEME = "pedersen-bls-chained";
    public static final String BEACON_ID = "default";
    public static final String SOURCE_API =
        "https://api.drand.sh/v2/chains/" + CHAIN_HASH + "/info";
    public static final String STATUS = "PINNED_BEFORE_PUBLIC_RANDOMNESS";

    public ProofCarryingShowcaseDrandChainInfo {
        if (!SCHEMA.equals(schema)
                || !NETWORK.equals(network)
                || !CHAIN_HASH.equals(chainHash)
                || periodSeconds != PERIOD_SECONDS
                || genesisTime != GENESIS_TIME
                || !GENESIS_SEED.equals(genesisSeed)
                || !SCHEME.equals(scheme)
                || !BEACON_ID.equals(beaconId)
                || !SOURCE_API.equals(sourceApi)
                || !STATUS.equals(status)) {
            throw new IllegalArgumentException(
                "drand default-chain root of trust drift");
        }
        ProofCarryingShowcaseJsonSupport.requireBoundedHex(
            publicKey, 96, 96, "publicKey");
        if (!PUBLIC_KEY.equals(publicKey)) {
            throw new IllegalArgumentException("drand public key drift");
        }
        ProofCarryingShowcaseJsonSupport.requireHex64(
            chainHash, "chainHash");
        ProofCarryingShowcaseJsonSupport.requireHex64(
            genesisSeed, "genesisSeed");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            contentHash, "contentHash");
        String expected = ProofCarryingShowcaseJsonSupport.hashPayload(
            payload(
                schema,
                network,
                chainHash,
                publicKey,
                periodSeconds,
                genesisTime,
                genesisSeed,
                scheme,
                beaconId,
                sourceApi,
                status));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "drand chain-info contentHash mismatch");
        }
    }

    public static ProofCarryingShowcaseDrandChainInfo createDefault() {
        Map<String, Object> payload = payload(
            SCHEMA,
            NETWORK,
            CHAIN_HASH,
            PUBLIC_KEY,
            PERIOD_SECONDS,
            GENESIS_TIME,
            GENESIS_SEED,
            SCHEME,
            BEACON_ID,
            SOURCE_API,
            STATUS);
        return new ProofCarryingShowcaseDrandChainInfo(
            SCHEMA,
            NETWORK,
            CHAIN_HASH,
            PUBLIC_KEY,
            PERIOD_SECONDS,
            GENESIS_TIME,
            GENESIS_SEED,
            SCHEME,
            BEACON_ID,
            SOURCE_API,
            STATUS,
            ProofCarryingShowcaseJsonSupport.hashPayload(payload));
    }

    public static ProofCarryingShowcaseDrandChainInfo read(Path path) {
        return ProofCarryingShowcaseJsonSupport.read(
            path,
            ProofCarryingShowcaseDrandChainInfo.class,
            "showcase drand chain info");
    }

    public static ProofCarryingShowcaseDrandChainInfo fromCanonicalJson(
        String json
    ) {
        return ProofCarryingShowcaseJsonSupport.read(
            json,
            ProofCarryingShowcaseDrandChainInfo.class,
            "showcase drand chain info");
    }

    public String toCanonicalJson() {
        return ProofCarryingShowcaseJsonSupport.toCanonicalJson(this);
    }

    public void requireCompatible(ProofCarryingShowcasePlan plan) {
        if (!NETWORK.equals(plan.publicRandomness().network())
                || !CHAIN_HASH.equals(plan.publicRandomness().chainHash())) {
            throw new IllegalArgumentException(
                "drand chain info does not match showcase plan");
        }
    }

    /** Unix time of a drand round according to the pinned 30-second schedule. */
    public long roundUnixTime(long round) {
        if (round < 1) {
            throw new IllegalArgumentException("drand round must be positive");
        }
        return Math.addExact(
            genesisTime,
            Math.multiplyExact(round - 1, (long) periodSeconds));
    }

    /**
     * First scheduled round whose Unix time is strictly greater than the
     * supplied frozen candidate boundary. No network access is performed.
     */
    public long firstRoundStrictlyAfter(long unixTime) {
        if (unixTime < 0) {
            throw new IllegalArgumentException("boundary must not be negative");
        }
        if (unixTime < genesisTime) {
            return 1L;
        }
        long elapsed = Math.subtractExact(unixTime, genesisTime);
        return Math.addExact(elapsed / periodSeconds, 2L);
    }

    private static Map<String, Object> payload(
        String schema,
        String network,
        String chainHash,
        String publicKey,
        int periodSeconds,
        long genesisTime,
        String genesisSeed,
        String scheme,
        String beaconId,
        String sourceApi,
        String status
    ) {
        return ProofCarryingShowcaseJsonSupport.payload(
            "schema", schema,
            "network", network,
            "chainHash", chainHash,
            "publicKey", publicKey,
            "periodSeconds", periodSeconds,
            "genesisTime", genesisTime,
            "genesisSeed", genesisSeed,
            "scheme", scheme,
            "beaconId", beaconId,
            "sourceApi", sourceApi,
            "status", status);
    }
}
