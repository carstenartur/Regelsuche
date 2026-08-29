package de.regelsuche.polynomial;

import de.regelsuche.parse.ExactParsedTerm;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Bounded deterministic store for verifier-authorized polynomial
 * transformations and their cache replay evidence.
 *
 * <p>The store never authorizes mathematics. It accepts only a successful
 * {@link ExactFactorizationTransformationPipeline.Result}, retains that exact
 * transformation identity and primitive evidence chain, and exposes exact
 * cache-revision lookups. A replay therefore reuses the original verifier
 * authority instead of turning a cached expression pair into an unexplained
 * shortcut.</p>
 *
 * <p>Lookup validity is bound to this store instance and one concrete
 * retention generation. Eviction invalidates an outstanding lookup even when
 * the same content-addressed entry is inserted again later. Entry capacity,
 * lineage capacity and observation material are all explicitly bounded. The
 * complete immutable {@link LookupRequest} tuple is the in-memory index key;
 * its SHA-256 {@link LookupRequest#keyId()} is evidence, not a substitute for
 * exact tuple equality.</p>
 */
public final class VerifiedPolynomialTransitionCacheStore {
    public static final String SCHEMA =
        "regelsuche.verified-polynomial-transition-cache/v1";
    public static final String PURPOSE =
        "PERFORMANCE_CACHE_FOR_VERIFIER_AUTHORIZED_POLYNOMIAL_TRANSITIONS";
    public static final int DEFAULT_CAPACITY = 128;
    public static final int MAX_CAPACITY = 1_000_000;
    public static final int DEFAULT_MAX_LINEAGES_PER_ENTRY = 256;
    public static final int MAX_LINEAGES_PER_ENTRY = 65_536;
    public static final int MAX_OBSERVATION_VALUES = 256;

    private static final int MAX_PRIMITIVE_STEPS = 64;
    private static final int MAX_IDENTITY_CODE_UNITS = 512;
    private static final int MAX_SOURCE_CODE_UNITS = 1_000_000;
    private static final int MAX_OBSERVATION_CODE_UNITS = 65_536;

    private final int capacity;
    private final int maxLineagesPerEntry;
    private final Map<LookupRequest, Entry> entries = new LinkedHashMap<>();
    private final Object replayAuthority = new Object();
    private long insertions;
    private long hits;
    private long misses;
    private long evictions;
    private long replays;

    public VerifiedPolynomialTransitionCacheStore() {
        this(DEFAULT_CAPACITY, DEFAULT_MAX_LINEAGES_PER_ENTRY);
    }

    public VerifiedPolynomialTransitionCacheStore(int capacity) {
        this(capacity, DEFAULT_MAX_LINEAGES_PER_ENTRY);
    }

    public VerifiedPolynomialTransitionCacheStore(
        int capacity,
        int maxLineagesPerEntry
    ) {
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException(
                "verified transition cache capacity is invalid");
        }
        if (maxLineagesPerEntry < 1
                || maxLineagesPerEntry > MAX_LINEAGES_PER_ENTRY) {
            throw new IllegalArgumentException(
                "verified transition lineage capacity is invalid");
        }
        this.capacity = capacity;
        this.maxLineagesPerEntry = maxLineagesPerEntry;
    }

    /**
     * Retains one already-authorized transformation under an exact cache
     * revision and observation lineage.
     */
    public synchronized RetentionResult retain(
        ExactFactorizationTransformationPipeline.Result transformation,
        String cacheId,
        String cacheRevision,
        Observation observation
    ) {
        VerifiedTransition transition = VerifiedTransition.from(
            Objects.requireNonNull(transformation, "transformation"));
        LookupRequest request = new LookupRequest(
            cacheId,
            cacheRevision,
            transition.sourceEvidenceHash(),
            transition.sourceExpression());
        Lineage lineage = Lineage.create(transition, observation);
        Entry existing = entries.get(request);
        if (existing != null) {
            if (!existing.transition().id().equals(transition.id())) {
                throw new IllegalStateException(
                    "exact cache lookup request is already bound to another verified transition");
            }
            if (existing.containsLineage(lineage.id())) {
                return RetentionResult.create(
                    RetentionStatus.UNCHANGED,
                    existing,
                    Optional.empty());
            }
            if (existing.lineages().size() >= maxLineagesPerEntry) {
                return RetentionResult.create(
                    RetentionStatus.LINEAGE_LIMIT_REACHED,
                    existing,
                    Optional.empty());
            }
            Entry updated = existing.withLineage(lineage);
            entries.put(request, updated);
            return RetentionResult.create(
                RetentionStatus.LINEAGE_ADDED,
                updated,
                Optional.empty());
        }

        long retentionGeneration = increment(insertions, "insertions");
        Entry retained = Entry.create(
            request,
            transition,
            lineage,
            retentionGeneration);
        Optional<Eviction> eviction = Optional.empty();
        long nextEvictions = evictions;
        if (entries.size() == capacity) {
            nextEvictions = increment(evictions, "evictions");
            LookupRequest oldest = entries.keySet().iterator().next();
            Entry removed = entries.remove(oldest);
            eviction = Optional.of(Eviction.from(removed));
        }
        entries.put(request, retained);
        insertions = retentionGeneration;
        evictions = nextEvictions;
        return RetentionResult.create(
            RetentionStatus.INSERTED,
            retained,
            eviction);
    }

    /** Exact lookup; cache id and revision are never normalized or widened. */
    public synchronized LookupResult lookup(LookupRequest request) {
        Objects.requireNonNull(request, "request");
        Entry entry = entries.get(request);
        PolynomialWorkLedger work = lookupWork(request, entry != null);
        if (entry == null) {
            misses = increment(misses, "misses");
            return LookupResult.miss(request, work, replayAuthority);
        }
        hits = increment(hits, "hits");
        return LookupResult.hit(request, entry, work, replayAuthority);
    }

    /**
     * Replays a prior exact lookup only while it was issued by this store and
     * the same retention generation is still present. Eviction, reinsertion,
     * substitution or a lookup issued by another store invalidates it
     * fail-closed.
     */
    public synchronized ReplayResult replay(LookupResult lookup) {
        Objects.requireNonNull(lookup, "lookup");
        if (!lookup.issuedBy(replayAuthority)) {
            return ReplayResult.failure(
                ReplayStatus.FOREIGN_LOOKUP,
                lookup,
                replayWork(false, false, false, Optional.empty()));
        }
        if (!lookup.hit()) {
            return ReplayResult.failure(
                ReplayStatus.LOOKUP_MISS,
                lookup,
                replayWork(false, false, false, Optional.empty()));
        }

        Entry lookedUp = lookup.retainedEntry();
        Entry current = entries.get(lookup.request());
        if (current == null) {
            return ReplayResult.failure(
                ReplayStatus.STALE_LOOKUP,
                lookup,
                replayWork(true, false, false, Optional.empty()));
        }
        if (!current.replayBindingId().equals(
                lookedUp.replayBindingId())) {
            return ReplayResult.failure(
                ReplayStatus.STALE_LOOKUP,
                lookup,
                replayWork(true, true, false, Optional.empty()));
        }
        if (!current.transition().id().equals(
                lookedUp.transition().id())) {
            throw new IllegalStateException(
                "retained entry changed transition identity");
        }

        PolynomialWorkLedger work = replayWork(
            true,
            true,
            true,
            Optional.of(current));
        replays = increment(replays, "replays");
        return ReplayResult.replayed(lookup, current, work);
    }

    public synchronized Stats stats() {
        return new Stats(
            entries.size(),
            insertions,
            hits,
            misses,
            evictions,
            replays);
    }

    public synchronized int size() {
        return entries.size();
    }

    public int capacity() {
        return capacity;
    }

    public int maxLineagesPerEntry() {
        return maxLineagesPerEntry;
    }

    private static PolynomialWorkLedger lookupWork(
        LookupRequest request,
        boolean hit
    ) {
        long codeUnits = sumCodeUnits(
            request.cacheId(),
            request.cacheRevision(),
            request.sourceEvidenceHash(),
            request.sourceExpression());
        return new PolynomialWorkLedger(Map.of(
            "verified-cache.lookup.exact-key-code-units",
            codeUnits,
            "verified-cache.lookup.index-probes",
            1L,
            "verified-cache.lookup.retained-entry-checks",
            hit ? 1L : 0L));
    }

    private static PolynomialWorkLedger replayWork(
        boolean entryChecked,
        boolean retentionBindingChecked,
        boolean transitionIdentityChecked,
        Optional<Entry> releasedEntry
    ) {
        long primitiveSteps = releasedEntry.map(value ->
            (long) value.transition().primitiveExpansion().size())
            .orElse(0L);
        long outputCodeUnits = releasedEntry.map(value ->
            (long) value.transition().transformedExpression().length())
            .orElse(0L);
        return new PolynomialWorkLedger(Map.of(
            "verified-cache.replay.lookup-authority-checks",
            1L,
            "verified-cache.replay.entry-rechecks",
            entryChecked ? 1L : 0L,
            "verified-cache.replay.output-code-units",
            outputCodeUnits,
            "verified-cache.replay.primitive-evidence-steps",
            primitiveSteps,
            "verified-cache.replay.retention-binding-checks",
            retentionBindingChecked ? 1L : 0L,
            "verified-cache.replay.transition-identity-checks",
            transitionIdentityChecked ? 1L : 0L));
    }

    private static PolynomialWorkLedger merge(
        PolynomialWorkLedger first,
        PolynomialWorkLedger second
    ) {
        Map<String, Long> merged = new LinkedHashMap<>(first.stages());
        second.stages().forEach((stage, units) -> merged.merge(
            stage,
            units,
            Math::addExact));
        return new PolynomialWorkLedger(merged);
    }

    private static long sumCodeUnits(String... values) {
        try {
            long total = 0L;
            for (String value : values) {
                total = Math.addExact(total, value.length());
            }
            return total;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                "cache work exceeds long range",
                exception);
        }
    }

    private static long increment(long current, String counter) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(
                "verified cache " + counter + " counter overflow",
                exception);
        }
    }

    private static String transitionId(
        String sourceExpression,
        String transformedExpression,
        String sourceEvidenceHash,
        String factorizationCertificateHash,
        String candidateCertificateHash,
        String authorityCertificateHash,
        ExactFactorizationTransformationPipeline.Kind kind,
        List<PrimitiveStep> primitiveExpansion,
        PolynomialWorkLedger derivationWork
    ) {
        EvidenceDigest digest = new EvidenceDigest();
        digest.append(SCHEMA + ".transition");
        digest.append(ExactFactorizationTransformationPipeline.TRANSFORMATION_ID);
        digest.append(sourceExpression);
        digest.append(transformedExpression);
        digest.append(sourceEvidenceHash);
        digest.append(factorizationCertificateHash);
        digest.append(candidateCertificateHash);
        digest.append(authorityCertificateHash);
        digest.append(kind.name());
        digest.append(Integer.toString(primitiveExpansion.size()));
        primitiveExpansion.forEach(step -> digest.append(
            step.canonicalMaterial()));
        digest.append(derivationWork.canonicalMaterial());
        return digest.finish();
    }

    private static String entryId(
        LookupRequest request,
        VerifiedTransition transition
    ) {
        EvidenceDigest digest = new EvidenceDigest();
        digest.append(SCHEMA + ".entry");
        digest.append(PURPOSE);
        digest.append(request.keyId());
        digest.append(transition.id());
        return digest.finish();
    }

    private static String replayBindingId(
        String entryId,
        long retentionGeneration
    ) {
        EvidenceDigest digest = new EvidenceDigest();
        digest.append(SCHEMA + ".retention-binding");
        digest.append(entryId);
        digest.append(Long.toString(retentionGeneration));
        return digest.finish();
    }

    private static String lineageId(
        VerifiedTransition transition,
        Observation observation
    ) {
        EvidenceDigest digest = new EvidenceDigest();
        digest.append(SCHEMA + ".lineage");
        digest.append(transition.id());
        digest.append(observation.canonicalMaterial());
        return digest.finish();
    }

    private static String resultCertificate(String schema, String... values) {
        EvidenceDigest digest = new EvidenceDigest();
        digest.append(schema);
        for (String value : values) {
            digest.append(value);
        }
        return digest.finish();
    }

    private static String exactReparseEvidenceHash(ExactParsedTerm reparsed) {
        EvidenceDigest digest = new EvidenceDigest();
        digest.append(SCHEMA + ".exact-reparse");
        digest.append(reparsed.source());
        digest.append(Integer.toString(reparsed.literals().size()));
        reparsed.literals().forEach(literal -> {
            digest.append(Integer.toString(literal.startInclusive()));
            digest.append(Integer.toString(literal.endExclusive()));
            digest.append(literal.sourceLexeme());
            digest.append(literal.evidence().certificateHash());
        });
        return digest.finish();
    }

    private static String requireIdentity(String value, String name) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.strip())
                || value.length() > MAX_IDENTITY_CODE_UNITS) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static String requireSource(String value, String name) {
        if (value == null
                || value.isBlank()
                || value.length() > MAX_SOURCE_CODE_UNITS) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static String requireHash(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static List<String> requireTextList(
        List<String> values,
        String name,
        boolean emptyAllowed
    ) {
        Objects.requireNonNull(values, name);
        if (!emptyAllowed && values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        if (values.size() > MAX_OBSERVATION_VALUES) {
            throw new IllegalArgumentException(name + " has too many entries");
        }
        List<String> result = new ArrayList<>(values.size());
        long totalCodeUnits = 0L;
        for (String value : values) {
            String retained = requireIdentity(value, name + " entry");
            totalCodeUnits = Math.addExact(
                totalCodeUnits,
                retained.length());
            if (totalCodeUnits > MAX_OBSERVATION_CODE_UNITS) {
                throw new IllegalArgumentException(
                    name + " exceeds its code-unit limit");
            }
            result.add(retained);
        }
        return List.copyOf(result);
    }

    /** One stable verifier-authorized mathematical transformation. */
    public record VerifiedTransition(
        String id,
        String sourceExpression,
        String transformedExpression,
        String sourceEvidenceHash,
        String factorizationCertificateHash,
        String candidateCertificateHash,
        String authorityCertificateHash,
        ExactFactorizationTransformationPipeline.Kind kind,
        List<PrimitiveStep> primitiveExpansion,
        PolynomialWorkLedger derivationWork
    ) {
        public VerifiedTransition {
            sourceExpression = requireSource(
                sourceExpression,
                "sourceExpression");
            transformedExpression = requireSource(
                transformedExpression,
                "transformedExpression");
            sourceEvidenceHash = requireHash(
                sourceEvidenceHash,
                "sourceEvidenceHash");
            factorizationCertificateHash = requireHash(
                factorizationCertificateHash,
                "factorizationCertificateHash");
            candidateCertificateHash = requireHash(
                candidateCertificateHash,
                "candidateCertificateHash");
            authorityCertificateHash = requireHash(
                authorityCertificateHash,
                "authorityCertificateHash");
            kind = Objects.requireNonNull(kind, "kind");
            primitiveExpansion = List.copyOf(
                Objects.requireNonNull(
                    primitiveExpansion,
                    "primitiveExpansion"));
            if (kind == ExactFactorizationTransformationPipeline.Kind.NONE
                    || primitiveExpansion.isEmpty()
                    || primitiveExpansion.size() > MAX_PRIMITIVE_STEPS) {
                throw new IllegalArgumentException(
                    "verified transition kind or primitive expansion is invalid");
            }
            derivationWork = Objects.requireNonNull(
                derivationWork,
                "derivationWork");
            String expected = transitionId(
                sourceExpression,
                transformedExpression,
                sourceEvidenceHash,
                factorizationCertificateHash,
                candidateCertificateHash,
                authorityCertificateHash,
                kind,
                primitiveExpansion,
                derivationWork);
            if (!expected.equals(id)) {
                throw new IllegalArgumentException(
                    "verified transition id does not match its evidence");
            }
        }

        private static VerifiedTransition from(
            ExactFactorizationTransformationPipeline.Result result
        ) {
            if (!result.transformed()) {
                throw new IllegalArgumentException(
                    "only transformed verifier-authorized results may be cached");
            }
            ExactParsedTerm reparsed = result.reparsed().orElseThrow();
            String sourceExpression = result.occurrence().sourceText();
            String transformedExpression = result.transformedExpression()
                .orElseThrow();
            String sourceEvidenceHash = result.occurrence()
                .sourceEvidenceHash();
            String factorizationCertificateHash = result.factorization()
                .certificateHash();
            String candidateCertificateHash = result
                .candidateCertificateHash();
            String authorityCertificateHash = result.certificateHash();
            List<PrimitiveStep> expansion = List.of(
                new PrimitiveStep(
                    "EXACT_SOURCE_EVIDENCE",
                    sourceEvidenceHash),
                new PrimitiveStep(
                    "EXACT_FACTORIZATION_PIPELINE",
                    factorizationCertificateHash),
                new PrimitiveStep(
                    "VERIFIER_SELECTED_CANDIDATE",
                    candidateCertificateHash),
                new PrimitiveStep(
                    "EXACT_FACTOR_RENDERING",
                    result.rendering().orElseThrow().certificateHash()),
                new PrimitiveStep(
                    "EXACT_REPARSE",
                    exactReparseEvidenceHash(reparsed)),
                new PrimitiveStep(
                    "EXACT_POLYNOMIAL_RECONSTRUCTION",
                    result.reconstruction().orElseThrow()
                        .certificateHash()),
                new PrimitiveStep(
                    "VERIFIER_BOUND_TRANSFORMATION",
                    authorityCertificateHash));
            String id = transitionId(
                sourceExpression,
                transformedExpression,
                sourceEvidenceHash,
                factorizationCertificateHash,
                candidateCertificateHash,
                authorityCertificateHash,
                result.kind(),
                expansion,
                result.totalWork());
            return new VerifiedTransition(
                id,
                sourceExpression,
                transformedExpression,
                sourceEvidenceHash,
                factorizationCertificateHash,
                candidateCertificateHash,
                authorityCertificateHash,
                result.kind(),
                expansion,
                result.totalWork());
        }

        public String canonicalMaterial() {
            return id;
        }
    }

    /** One ordered primitive evidence step retained for transparent replay. */
    public record PrimitiveStep(String stageId, String evidenceHash) {
        public PrimitiveStep {
            stageId = requireIdentity(stageId, "stageId");
            evidenceHash = requireHash(evidenceHash, "evidenceHash");
        }

        public String canonicalMaterial() {
            return stageId + ":" + evidenceHash;
        }
    }

    /** Exact lookup material. No revision fallback is permitted. */
    public record LookupRequest(
        String cacheId,
        String cacheRevision,
        String sourceEvidenceHash,
        String sourceExpression
    ) {
        public LookupRequest {
            cacheId = requireIdentity(cacheId, "cacheId");
            cacheRevision = requireIdentity(
                cacheRevision,
                "cacheRevision");
            sourceEvidenceHash = requireHash(
                sourceEvidenceHash,
                "sourceEvidenceHash");
            sourceExpression = requireSource(
                sourceExpression,
                "sourceExpression");
        }

        public String keyId() {
            EvidenceDigest digest = new EvidenceDigest();
            digest.append(SCHEMA + ".lookup-key");
            digest.append(cacheId);
            digest.append(cacheRevision);
            digest.append(sourceEvidenceHash);
            digest.append(sourceExpression);
            return digest.finish();
        }

        public String canonicalMaterial() {
            return keyId();
        }
    }

    /** One deterministic observation lineage attached to a transition. */
    public record Observation(
        String observationId,
        List<String> sourceProvenance,
        List<String> assumptions
    ) {
        public Observation {
            observationId = requireIdentity(
                observationId,
                "observationId");
            sourceProvenance = requireTextList(
                sourceProvenance,
                "sourceProvenance",
                false);
            assumptions = requireTextList(
                assumptions,
                "assumptions",
                true);
        }

        public String canonicalMaterial() {
            EvidenceDigest digest = new EvidenceDigest();
            digest.append(SCHEMA + ".observation");
            digest.append(observationId);
            digest.append(Integer.toString(sourceProvenance.size()));
            sourceProvenance.forEach(digest::append);
            digest.append(Integer.toString(assumptions.size()));
            assumptions.forEach(digest::append);
            return digest.finish();
        }
    }

    /** Content-addressed observation lineage retained only inside the store. */
    private record Lineage(String id, Observation observation) {
        private Lineage {
            id = requireHash(id, "lineage id");
            observation = Objects.requireNonNull(
                observation,
                "observation");
        }

        private static Lineage create(
            VerifiedTransition transition,
            Observation observation
        ) {
            Objects.requireNonNull(transition, "transition");
            Objects.requireNonNull(observation, "observation");
            return new Lineage(
                lineageId(transition, observation),
                observation);
        }
    }

    /** One exact cache-revision binding retained only inside the store. */
    private record Entry(
        String id,
        long retentionGeneration,
        LookupRequest request,
        VerifiedTransition transition,
        List<Lineage> lineages,
        String purpose
    ) {
        private Entry {
            id = requireHash(id, "entry id");
            if (retentionGeneration < 1) {
                throw new IllegalArgumentException(
                    "retention generation must be positive");
            }
            request = Objects.requireNonNull(request, "request");
            transition = Objects.requireNonNull(
                transition,
                "transition");
            lineages = List.copyOf(
                Objects.requireNonNull(lineages, "lineages"));
            if (lineages.isEmpty()
                    || lineages.size() > MAX_LINEAGES_PER_ENTRY
                    || !PURPOSE.equals(purpose)
                    || !request.sourceEvidenceHash().equals(
                        transition.sourceEvidenceHash())
                    || !request.sourceExpression().equals(
                        transition.sourceExpression())
                    || !id.equals(entryId(request, transition))) {
                throw new IllegalArgumentException(
                    "verified transition cache entry is invalid");
            }
            Set<String> lineageIds = new HashSet<>();
            for (Lineage lineage : lineages) {
                Objects.requireNonNull(lineage, "lineage");
                if (!lineage.id().equals(lineageId(
                        transition,
                        lineage.observation()))
                        || !lineageIds.add(lineage.id())) {
                    throw new IllegalArgumentException(
                        "verified transition lineage is invalid");
                }
            }
        }

        private static Entry create(
            LookupRequest request,
            VerifiedTransition transition,
            Lineage lineage,
            long retentionGeneration
        ) {
            return new Entry(
                entryId(request, transition),
                retentionGeneration,
                request,
                transition,
                List.of(lineage),
                PURPOSE);
        }

        private boolean containsLineage(String lineageId) {
            return lineages.stream().anyMatch(existing ->
                existing.id().equals(lineageId));
        }

        private Entry withLineage(Lineage lineage) {
            Objects.requireNonNull(lineage, "lineage");
            List<Lineage> updated = new ArrayList<>(lineages);
            updated.add(lineage);
            return new Entry(
                id,
                retentionGeneration,
                request,
                transition,
                updated,
                purpose);
        }

        private String replayBindingId() {
            return VerifiedPolynomialTransitionCacheStore.replayBindingId(
                id,
                retentionGeneration);
        }
    }

    /** Opaque evidence that one entry lifetime was evicted. */
    public record Eviction(
        String entryId,
        String lookupKeyId,
        long retentionGeneration,
        String replayBindingId
    ) {
        public Eviction {
            entryId = requireHash(entryId, "eviction entryId");
            lookupKeyId = requireHash(lookupKeyId, "eviction lookupKeyId");
            if (retentionGeneration < 1) {
                throw new IllegalArgumentException(
                    "eviction retentionGeneration must be positive");
            }
            replayBindingId = requireHash(
                replayBindingId,
                "eviction replayBindingId");
            if (!replayBindingId.equals(
                    VerifiedPolynomialTransitionCacheStore.replayBindingId(
                        entryId,
                        retentionGeneration))) {
                throw new IllegalArgumentException(
                    "eviction replay binding does not match its entry lifetime");
            }
        }

        private static Eviction from(Entry entry) {
            Objects.requireNonNull(entry, "entry");
            return new Eviction(
                entry.id(),
                entry.request().keyId(),
                entry.retentionGeneration(),
                entry.replayBindingId());
        }

        private String canonicalMaterial() {
            return resultCertificate(
                SCHEMA + ".eviction",
                entryId,
                lookupKeyId,
                Long.toString(retentionGeneration),
                replayBindingId);
        }
    }

    public enum RetentionStatus {
        INSERTED,
        LINEAGE_ADDED,
        UNCHANGED,
        LINEAGE_LIMIT_REACHED
    }

    /** Issuer-owned retention evidence; retained entries remain private. */
    public static final class RetentionResult {
        private final RetentionStatus status;
        private final LookupRequest lookupRequest;
        private final String entryId;
        private final String transitionId;
        private final long retentionGeneration;
        private final String replayBindingId;
        private final int lineageCount;
        private final Optional<Eviction> eviction;
        private final String certificateHash;

        private RetentionResult(
            RetentionStatus status,
            LookupRequest lookupRequest,
            String entryId,
            String transitionId,
            long retentionGeneration,
            String replayBindingId,
            int lineageCount,
            Optional<Eviction> eviction,
            String certificateHash
        ) {
            this.status = Objects.requireNonNull(status, "status");
            this.lookupRequest = Objects.requireNonNull(
                lookupRequest,
                "lookupRequest");
            this.entryId = requireHash(entryId, "retention entryId");
            this.transitionId = requireHash(
                transitionId,
                "retention transitionId");
            if (retentionGeneration < 1) {
                throw new IllegalArgumentException(
                    "retention generation must be positive");
            }
            this.retentionGeneration = retentionGeneration;
            this.replayBindingId = requireHash(
                replayBindingId,
                "retention replayBindingId");
            if (!replayBindingId.equals(
                    VerifiedPolynomialTransitionCacheStore.replayBindingId(
                        entryId,
                        retentionGeneration))) {
                throw new IllegalArgumentException(
                    "retention replay binding does not match its entry lifetime");
            }
            if (lineageCount < 1
                    || lineageCount > MAX_LINEAGES_PER_ENTRY) {
                throw new IllegalArgumentException(
                    "retention lineageCount is invalid");
            }
            this.lineageCount = lineageCount;
            this.eviction = Objects.requireNonNull(eviction, "eviction");
            if (status != RetentionStatus.INSERTED
                    && eviction.isPresent()) {
                throw new IllegalArgumentException(
                    "only insertion may evict a cache entry");
            }
            this.certificateHash = requireHash(
                certificateHash,
                "retention certificateHash");
            String expected = certificate(
                status,
                lookupRequest,
                entryId,
                transitionId,
                retentionGeneration,
                replayBindingId,
                lineageCount,
                eviction);
            if (!expected.equals(certificateHash)) {
                throw new IllegalArgumentException(
                    "retention certificate does not match its evidence");
            }
        }

        private static RetentionResult create(
            RetentionStatus status,
            Entry entry,
            Optional<Eviction> eviction
        ) {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(eviction, "eviction");
            return new RetentionResult(
                status,
                entry.request(),
                entry.id(),
                entry.transition().id(),
                entry.retentionGeneration(),
                entry.replayBindingId(),
                entry.lineages().size(),
                eviction,
                certificate(
                    status,
                    entry.request(),
                    entry.id(),
                    entry.transition().id(),
                    entry.retentionGeneration(),
                    entry.replayBindingId(),
                    entry.lineages().size(),
                    eviction));
        }

        private static String certificate(
            RetentionStatus status,
            LookupRequest lookupRequest,
            String entryId,
            String transitionId,
            long retentionGeneration,
            String replayBindingId,
            int lineageCount,
            Optional<Eviction> eviction
        ) {
            return resultCertificate(
                SCHEMA + ".retention-result",
                status.name(),
                lookupRequest.keyId(),
                entryId,
                transitionId,
                Long.toString(retentionGeneration),
                replayBindingId,
                Integer.toString(lineageCount),
                eviction.map(Eviction::canonicalMaterial).orElse(""));
        }

        public RetentionStatus status() {
            return status;
        }

        public LookupRequest lookupRequest() {
            return lookupRequest;
        }

        public String entryId() {
            return entryId;
        }

        public String transitionId() {
            return transitionId;
        }

        public long retentionGeneration() {
            return retentionGeneration;
        }

        public String replayBindingId() {
            return replayBindingId;
        }

        public int lineageCount() {
            return lineageCount;
        }

        public Optional<Eviction> eviction() {
            return eviction;
        }

        public String certificateHash() {
            return certificateHash;
        }

        @Override
        public String toString() {
            return "RetentionResult[status=" + status
                + ", entryId=" + entryId
                + ", retentionGeneration=" + retentionGeneration
                + ", lineageCount=" + lineageCount
                + ", certificateHash=" + certificateHash + "]";
        }
    }

    public enum LookupStatus {
        HIT,
        MISS
    }

    /** Issuer-owned exact lookup evidence; retained entries remain private. */
    public static final class LookupResult {
        private final LookupStatus status;
        private final LookupRequest request;
        private final Optional<Entry> retainedEntry;
        private final PolynomialWorkLedger lookupWork;
        private final String certificateHash;
        private final Object issuerAuthority;

        private LookupResult(
            LookupStatus status,
            LookupRequest request,
            Optional<Entry> retainedEntry,
            PolynomialWorkLedger lookupWork,
            String certificateHash,
            Object issuerAuthority
        ) {
            this.status = Objects.requireNonNull(status, "status");
            this.request = Objects.requireNonNull(request, "request");
            this.retainedEntry = Objects.requireNonNull(
                retainedEntry,
                "retainedEntry");
            this.lookupWork = Objects.requireNonNull(
                lookupWork,
                "lookupWork");
            this.certificateHash = requireHash(
                certificateHash,
                "lookup certificateHash");
            this.issuerAuthority = Objects.requireNonNull(
                issuerAuthority,
                "issuerAuthority");
            if ((status == LookupStatus.HIT) != retainedEntry.isPresent()) {
                throw new IllegalArgumentException(
                    "lookup status and retained entry disagree");
            }
            String expected = certificate(
                status,
                request,
                retainedEntry,
                lookupWork);
            if (!expected.equals(certificateHash)) {
                throw new IllegalArgumentException(
                    "lookup certificate does not match its evidence");
            }
        }

        private static LookupResult hit(
            LookupRequest request,
            Entry entry,
            PolynomialWorkLedger work,
            Object issuerAuthority
        ) {
            return create(
                LookupStatus.HIT,
                request,
                Optional.of(entry),
                work,
                issuerAuthority);
        }

        private static LookupResult miss(
            LookupRequest request,
            PolynomialWorkLedger work,
            Object issuerAuthority
        ) {
            return create(
                LookupStatus.MISS,
                request,
                Optional.empty(),
                work,
                issuerAuthority);
        }

        private static LookupResult create(
            LookupStatus status,
            LookupRequest request,
            Optional<Entry> retainedEntry,
            PolynomialWorkLedger work,
            Object issuerAuthority
        ) {
            return new LookupResult(
                status,
                request,
                retainedEntry,
                work,
                certificate(status, request, retainedEntry, work),
                issuerAuthority);
        }

        private static String certificate(
            LookupStatus status,
            LookupRequest request,
            Optional<Entry> retainedEntry,
            PolynomialWorkLedger work
        ) {
            return resultCertificate(
                SCHEMA + ".lookup-result",
                status.name(),
                request.keyId(),
                retainedEntry.map(Entry::replayBindingId).orElse(""),
                work.canonicalMaterial());
        }

        private boolean issuedBy(Object authority) {
            return issuerAuthority == authority;
        }

        private Entry retainedEntry() {
            return retainedEntry.orElseThrow(() ->
                new IllegalStateException(
                    "lookup miss has no retained entry"));
        }

        public LookupStatus status() {
            return status;
        }

        public LookupRequest request() {
            return request;
        }

        public PolynomialWorkLedger lookupWork() {
            return lookupWork;
        }

        public String certificateHash() {
            return certificateHash;
        }

        public boolean hit() {
            return status == LookupStatus.HIT;
        }

        @Override
        public String toString() {
            return "LookupResult[status=" + status
                + ", request=" + request.keyId()
                + ", certificateHash=" + certificateHash + "]";
        }
    }

    public enum ReplayStatus {
        REPLAYED,
        LOOKUP_MISS,
        STALE_LOOKUP,
        FOREIGN_LOOKUP
    }

    /** Issuer-owned replay evidence; only successful replay releases a transition. */
    public static final class ReplayResult {
        private final ReplayStatus status;
        private final LookupResult lookup;
        private final Optional<Entry> releasedEntry;
        private final PolynomialWorkLedger replayWork;
        private final PolynomialWorkLedger actualExecutionWork;
        private final String certificateHash;

        private ReplayResult(
            ReplayStatus status,
            LookupResult lookup,
            Optional<Entry> releasedEntry,
            PolynomialWorkLedger replayWork,
            PolynomialWorkLedger actualExecutionWork,
            String certificateHash
        ) {
            this.status = Objects.requireNonNull(status, "status");
            this.lookup = Objects.requireNonNull(lookup, "lookup");
            this.releasedEntry = Objects.requireNonNull(
                releasedEntry,
                "releasedEntry");
            this.replayWork = Objects.requireNonNull(
                replayWork,
                "replayWork");
            this.actualExecutionWork = Objects.requireNonNull(
                actualExecutionWork,
                "actualExecutionWork");
            this.certificateHash = requireHash(
                certificateHash,
                "replay certificateHash");
            if ((status == ReplayStatus.REPLAYED)
                    != releasedEntry.isPresent()) {
                throw new IllegalArgumentException(
                    "replay status and released entry disagree");
            }
            PolynomialWorkLedger expectedWork = merge(
                lookup.lookupWork(),
                replayWork);
            if (!actualExecutionWork.equals(expectedWork)) {
                throw new IllegalArgumentException(
                    "actual replay work does not match lookup plus replay");
            }
            String expectedCertificate = certificate(
                status,
                lookup,
                releasedEntry,
                actualExecutionWork);
            if (!expectedCertificate.equals(certificateHash)) {
                throw new IllegalArgumentException(
                    "replay certificate does not match its evidence");
            }
        }

        private static ReplayResult replayed(
            LookupResult lookup,
            Entry entry,
            PolynomialWorkLedger replayWork
        ) {
            return create(
                ReplayStatus.REPLAYED,
                lookup,
                Optional.of(entry),
                replayWork);
        }

        private static ReplayResult failure(
            ReplayStatus status,
            LookupResult lookup,
            PolynomialWorkLedger replayWork
        ) {
            if (status == ReplayStatus.REPLAYED) {
                throw new IllegalArgumentException(
                    "successful replay status cannot describe failure");
            }
            return create(
                status,
                lookup,
                Optional.empty(),
                replayWork);
        }

        private static ReplayResult create(
            ReplayStatus status,
            LookupResult lookup,
            Optional<Entry> releasedEntry,
            PolynomialWorkLedger replayWork
        ) {
            PolynomialWorkLedger actual = merge(
                lookup.lookupWork(),
                replayWork);
            return new ReplayResult(
                status,
                lookup,
                releasedEntry,
                replayWork,
                actual,
                certificate(status, lookup, releasedEntry, actual));
        }

        private static String certificate(
            ReplayStatus status,
            LookupResult lookup,
            Optional<Entry> releasedEntry,
            PolynomialWorkLedger actual
        ) {
            return resultCertificate(
                SCHEMA + ".replay-result",
                status.name(),
                lookup.certificateHash(),
                releasedEntry.map(Entry::replayBindingId).orElse(""),
                releasedEntry.map(value ->
                    value.transition().id()).orElse(""),
                actual.canonicalMaterial());
        }

        public ReplayStatus status() {
            return status;
        }

        public LookupResult lookup() {
            return lookup;
        }

        public PolynomialWorkLedger replayWork() {
            return replayWork;
        }

        public PolynomialWorkLedger actualExecutionWork() {
            return actualExecutionWork;
        }

        public String certificateHash() {
            return certificateHash;
        }

        public boolean replayed() {
            return status == ReplayStatus.REPLAYED;
        }

        public Optional<VerifiedTransition> transition() {
            return releasedEntry.map(Entry::transition);
        }

        public List<PrimitiveStep> primitiveExpansion() {
            return transition().map(VerifiedTransition::primitiveExpansion)
                .orElse(List.of());
        }

        /** Original derivation work retained as provenance, not replay work. */
        public Optional<PolynomialWorkLedger> retainedDerivationWork() {
            return transition().map(VerifiedTransition::derivationWork);
        }

        @Override
        public String toString() {
            return "ReplayResult[status=" + status
                + ", lookup=" + lookup.certificateHash()
                + ", certificateHash=" + certificateHash + "]";
        }
    }

    /** Deterministic operational counters; they carry no mathematical claim. */
    public record Stats(
        int retainedEntries,
        long insertions,
        long hits,
        long misses,
        long evictions,
        long replays
    ) {
        public Stats {
            if (retainedEntries < 0
                    || insertions < 0
                    || hits < 0
                    || misses < 0
                    || evictions < 0
                    || replays < 0) {
                throw new IllegalArgumentException(
                    "verified cache statistics are invalid");
            }
        }
    }

    private static final class EvidenceDigest {
        private final MessageDigest digest;

        private EvidenceDigest() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception);
            }
        }

        private void append(String value) {
            byte[] bytes = Objects.requireNonNull(value, "value")
                .getBytes(StandardCharsets.UTF_8);
            byte[] length = Integer.toString(bytes.length)
                .getBytes(StandardCharsets.US_ASCII);
            digest.update(length);
            digest.update((byte) ':');
            digest.update(bytes);
            digest.update((byte) '\n');
        }

        private String finish() {
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        }
    }
}
