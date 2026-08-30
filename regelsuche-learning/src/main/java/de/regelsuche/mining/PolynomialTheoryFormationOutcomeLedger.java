package de.regelsuche.mining;

import de.regelsuche.transform.PolynomialTheorySubsumptionClassifier;
import de.regelsuche.validation.CandidateProofStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Deterministic bounded ledger for every polynomial theory classification
 * emitted at the rule-candidate post-formation boundary.
 *
 * <p>The ledger is evidence retention, not mathematical authority. It keeps
 * the immutable formation evidence distinct from theory classification and
 * records whether the sole configured cache owner retained a positive
 * theory-derived macro. Negative, unsupported, budget-inconclusive and
 * technical outcomes remain visible instead of being dropped.</p>
 */
public final class PolynomialTheoryFormationOutcomeLedger {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-formation-outcome/v1";
    public static final int DEFAULT_CAPACITY = 1_024;
    public static final int MAX_CAPACITY = 1_000_000;

    private final int capacity;
    private final Function<String, String> identityFunction;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long insertions;
    private long duplicates;
    private long evictions;

    public PolynomialTheoryFormationOutcomeLedger() {
        this(DEFAULT_CAPACITY);
    }

    public PolynomialTheoryFormationOutcomeLedger(int capacity) {
        this(capacity, PolynomialTheoryFormationOutcomeLedger::sha256);
    }

    PolynomialTheoryFormationOutcomeLedger(
        int capacity,
        Function<String, String> identityFunction
    ) {
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException(
                "polynomial theory outcome ledger capacity is invalid");
        }
        this.capacity = capacity;
        this.identityFunction = Objects.requireNonNull(
            identityFunction,
            "identityFunction");
    }

    public synchronized RetentionResult retain(
        RuleCandidate candidate,
        RuleCandidateFormationObserver.Evidence formationEvidence,
        PolynomialTheorySubsumptionClassifier.Classification classification,
        Optional<String> macroEntryId
    ) {
        Entry proposed = Entry.create(
            candidate,
            formationEvidence,
            classification,
            macroEntryId,
            identityFunction);
        Entry existing = entries.get(proposed.id());
        if (existing != null) {
            if (!existing.sameContent(proposed)) {
                throw new IllegalStateException(
                    "polynomial theory outcome identity collision for "
                        + proposed.id());
            }
            duplicates = increment(duplicates, "duplicates");
            return RetentionResult.unchanged(existing);
        }

        Optional<String> evictedEntryId = Optional.empty();
        if (entries.size() == capacity) {
            String oldest = entries.keySet().iterator().next();
            entries.remove(oldest);
            evictions = increment(evictions, "evictions");
            evictedEntryId = Optional.of(oldest);
        }
        entries.put(proposed.id(), proposed);
        insertions = increment(insertions, "insertions");
        return RetentionResult.inserted(proposed, evictedEntryId);
    }

    public synchronized Optional<Entry> find(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public synchronized int size() {
        return entries.size();
    }

    public int capacity() {
        return capacity;
    }

    public synchronized Stats stats() {
        return new Stats(
            entries.size(),
            insertions,
            duplicates,
            evictions);
    }

    public enum Disposition {
        DERIVED_MACRO_CACHE,
        RETAINED_NOT_SUBSUMED,
        RETAINED_UNSUPPORTED,
        RETAINED_BUDGET_INCONCLUSIVE,
        RETAINED_TECHNICAL_FAILURE
    }

    public enum RetentionStatus {
        INSERTED,
        UNCHANGED
    }

    /** Immutable retained observation with all evidence axes kept separate. */
    public static final class Entry {
        private final String id;
        private final RuleCandidate candidate;
        private final RuleCandidateFormationObserver.Evidence formationEvidence;
        private final PolynomialTheorySubsumptionClassifier.Classification
            classification;
        private final Disposition disposition;
        private final Optional<String> macroEntryId;

        private Entry(
            String id,
            RuleCandidate candidate,
            RuleCandidateFormationObserver.Evidence formationEvidence,
            PolynomialTheorySubsumptionClassifier.Classification classification,
            Disposition disposition,
            Optional<String> macroEntryId
        ) {
            this.id = requireHash(id, "outcome id");
            this.candidate = requireCandidate(candidate);
            this.formationEvidence = Objects.requireNonNull(
                formationEvidence,
                "formationEvidence");
            this.classification = Objects.requireNonNull(
                classification,
                "classification");
            this.disposition = Objects.requireNonNull(
                disposition,
                "disposition");
            this.macroEntryId = Objects.requireNonNull(
                macroEntryId,
                "macroEntryId");
            validateDisposition(
                classification,
                disposition,
                this.macroEntryId);
        }

        private static Entry create(
            RuleCandidate candidate,
            RuleCandidateFormationObserver.Evidence formationEvidence,
            PolynomialTheorySubsumptionClassifier.Classification classification,
            Optional<String> macroEntryId,
            Function<String, String> identityFunction
        ) {
            RuleCandidate checkedCandidate = requireCandidate(candidate);
            RuleCandidateFormationObserver.Evidence checkedEvidence =
                Objects.requireNonNull(
                    formationEvidence,
                    "formationEvidence");
            if (checkedEvidence.sourceProvenance().isEmpty()) {
                throw new IllegalArgumentException(
                    "polynomial theory observation requires source provenance");
            }
            PolynomialTheorySubsumptionClassifier.Classification
                checkedClassification = Objects.requireNonNull(
                    classification,
                    "classification");
            Optional<String> checkedMacroEntryId = Objects.requireNonNull(
                macroEntryId,
                "macroEntryId");
            checkedMacroEntryId.ifPresent(value ->
                requireHash(value, "macroEntryId"));
            Disposition disposition = dispositionFor(checkedClassification);
            validateDisposition(
                checkedClassification,
                disposition,
                checkedMacroEntryId);
            String material = material(
                checkedCandidate,
                checkedEvidence,
                checkedClassification,
                disposition,
                checkedMacroEntryId);
            String id = identityFunction.apply(material);
            if (id == null || !id.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalStateException(
                    "polynomial theory outcome identity is invalid");
            }
            return new Entry(
                id,
                checkedCandidate,
                checkedEvidence,
                checkedClassification,
                disposition,
                checkedMacroEntryId);
        }

        public String id() {
            return id;
        }

        public RuleCandidate candidate() {
            return candidate;
        }

        public RuleCandidateFormationObserver.Evidence formationEvidence() {
            return formationEvidence;
        }

        public PolynomialTheorySubsumptionClassifier.Classification
                classification() {
            return classification;
        }

        public Disposition disposition() {
            return disposition;
        }

        public Optional<String> macroEntryId() {
            return macroEntryId;
        }

        private boolean sameContent(Entry other) {
            return candidate.equals(other.candidate)
                && formationEvidence.equals(other.formationEvidence)
                && classification.equals(other.classification)
                && disposition == other.disposition
                && macroEntryId.equals(other.macroEntryId);
        }

        @Override
        public String toString() {
            return "Entry[id=" + id
                + ", status=" + classification.status()
                + ", disposition=" + disposition + "]";
        }
    }

    public record RetentionResult(
        RetentionStatus status,
        Entry entry,
        Optional<String> evictedEntryId
    ) {
        public RetentionResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(entry, "entry");
            evictedEntryId = Objects.requireNonNull(
                evictedEntryId,
                "evictedEntryId");
            evictedEntryId.ifPresent(value ->
                requireHash(value, "evictedEntryId"));
            if (status == RetentionStatus.UNCHANGED
                    && evictedEntryId.isPresent()) {
                throw new IllegalArgumentException(
                    "unchanged outcome cannot evict another entry");
            }
        }

        private static RetentionResult inserted(
            Entry entry,
            Optional<String> evictedEntryId
        ) {
            return new RetentionResult(
                RetentionStatus.INSERTED,
                entry,
                evictedEntryId);
        }

        private static RetentionResult unchanged(Entry entry) {
            return new RetentionResult(
                RetentionStatus.UNCHANGED,
                entry,
                Optional.empty());
        }
    }

    public record Stats(
        int size,
        long insertions,
        long duplicates,
        long evictions
    ) {
        public Stats {
            if (size < 0
                    || insertions < 0
                    || duplicates < 0
                    || evictions < 0) {
                throw new IllegalArgumentException(
                    "polynomial theory outcome stats must not be negative");
            }
        }
    }

    private static RuleCandidate requireCandidate(RuleCandidate candidate) {
        RuleCandidate checked = Objects.requireNonNull(
            candidate,
            "candidate");
        requireText(checked.leftPattern(), "candidate leftPattern");
        requireText(checked.rightPattern(), "candidate rightPattern");
        requireText(checked.canonicalHash(), "candidate canonicalHash");
        Objects.requireNonNull(checked.parameterRelations(),
            "candidate parameterRelations");
        Objects.requireNonNull(checked.status(), "candidate status");
        Objects.requireNonNull(checked.proofStatus(), "candidate proofStatus");
        Objects.requireNonNull(
            checked.supportingTransformationIds(),
            "candidate supportingTransformationIds");
        if (checked.examplesCount() < 1
                || !Double.isFinite(checked.averageScoreImprovement())
                || !checked.equivalenceVerified()
                || !checked.proofStatus().atLeast(
                    CandidateProofStatus.VALIDATED_BY_EXAMPLES)) {
            throw new IllegalArgumentException(
                "candidate is not eligible for polynomial theory observation");
        }
        return checked;
    }

    private static Disposition dispositionFor(
        PolynomialTheorySubsumptionClassifier.Classification classification
    ) {
        return switch (classification.status()) {
            case THEORY_SUBSUMED -> Disposition.DERIVED_MACRO_CACHE;
            case NOT_SUBSUMED -> Disposition.RETAINED_NOT_SUBSUMED;
            case UNSUPPORTED -> Disposition.RETAINED_UNSUPPORTED;
            case BUDGET_INCONCLUSIVE ->
                Disposition.RETAINED_BUDGET_INCONCLUSIVE;
            case TECHNICAL_FAILURE ->
                Disposition.RETAINED_TECHNICAL_FAILURE;
        };
    }

    private static void validateDisposition(
        PolynomialTheorySubsumptionClassifier.Classification classification,
        Disposition disposition,
        Optional<String> macroEntryId
    ) {
        boolean positive = classification.subsumed();
        if (positive != (disposition == Disposition.DERIVED_MACRO_CACHE)
                || positive != macroEntryId.isPresent()) {
            throw new IllegalArgumentException(
                "classification, disposition and macro retention disagree");
        }
        macroEntryId.ifPresent(value ->
            requireHash(value, "macroEntryId"));
    }

    private static String material(
        RuleCandidate candidate,
        RuleCandidateFormationObserver.Evidence evidence,
        PolynomialTheorySubsumptionClassifier.Classification classification,
        Disposition disposition,
        Optional<String> macroEntryId
    ) {
        StringBuilder result = new StringBuilder();
        append(result, SCHEMA);
        append(result, candidate.leftPattern());
        append(result, candidate.rightPattern());
        append(result, Integer.toString(candidate.examplesCount()));
        append(result, Long.toUnsignedString(Double.doubleToRawLongBits(
            candidate.averageScoreImprovement())));
        append(result, Integer.toString(candidate.maximumScoreImprovement()));
        append(result, Boolean.toString(candidate.equivalenceVerified()));
        append(result, Boolean.toString(candidate.generalizationPlausible()));
        append(result, Boolean.toString(candidate.containsFreeParameters()));
        appendList(result, candidate.parameterRelations());
        append(result, candidate.status().name());
        append(result, candidate.proofStatus().name());
        append(result, candidate.canonicalHash());
        appendList(result, candidate.supportingTransformationIds());
        appendList(result, evidence.appliedRuleIds());
        appendList(result, evidence.sourceProvenance());
        appendList(result, evidence.assumptions());
        appendList(result, evidence.validationEvidence());
        append(result, classification.status().name());
        append(result, classification.detailCode());
        append(result, classification.theoryMethodId());
        append(result, classification.sourceExpression());
        append(result, classification.certificateHash());
        append(result, classification.derivedExpression());
        append(result, classification.applicationKey());
        append(result, Long.toString(classification.workUnits()));
        append(result, classification.projectInventoryNovelty().name());
        append(result, classification.retentionDisposition().name());
        append(result, disposition.name());
        append(result, macroEntryId.orElse(""));
        return result.toString();
    }

    private static void appendList(
        StringBuilder target,
        List<String> values
    ) {
        Objects.requireNonNull(values, "values");
        append(target, Integer.toString(values.size()));
        values.forEach(value -> append(target,
            Objects.requireNonNull(value, "list entry")));
    }

    private static void append(StringBuilder target, String value) {
        Objects.requireNonNull(value, "value");
        target.append(value.length()).append(':').append(value);
    }

    private static String requireHash(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static long increment(long value, String counter) {
        try {
            return Math.addExact(value, 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(
                "polynomial theory outcome " + counter + " overflow",
                exception);
        }
    }

    private static String sha256(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "SHA-256 unavailable",
                exception);
        }
    }
}
