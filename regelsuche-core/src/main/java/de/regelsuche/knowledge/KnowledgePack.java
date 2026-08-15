package de.regelsuche.knowledge;

import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.PatternRewriteRule;
import java.util.List;
import java.util.Objects;

public record KnowledgePack(
        String packId,
        String displayName,
        String sourceProject,
        String license,
        String sourceUrl,
        String sourceVersion,
        String sourceReference,
        boolean enabledByDefault,
        KnowledgePackMaturity maturity,
        RuleTier tier,
        List<String> categories,
        List<PatternRewriteRule> rules,
        List<KnowledgePack.KnownStructureDefinition> knownStructures) {

    /** Backwards-compatible constructor without known-structure contributions. */
    public KnowledgePack(
            String packId,
            String displayName,
            String sourceProject,
            String license,
            String sourceUrl,
            String sourceVersion,
            String sourceReference,
            boolean enabledByDefault,
            KnowledgePackMaturity maturity,
            RuleTier tier,
            List<String> categories,
            List<PatternRewriteRule> rules) {
        this(packId, displayName, sourceProject, license, sourceUrl,
                sourceVersion, sourceReference, enabledByDefault, maturity,
                tier, categories, rules, List.of());
    }

    /** Backwards-compatible constructor defaulting to the first-party tier. */
    public KnowledgePack(
            String packId,
            String displayName,
            String sourceProject,
            String license,
            String sourceUrl,
            String sourceVersion,
            String sourceReference,
            boolean enabledByDefault,
            KnowledgePackMaturity maturity,
            List<String> categories,
            List<PatternRewriteRule> rules) {
        this(packId, displayName, sourceProject, license, sourceUrl,
                sourceVersion, sourceReference, enabledByDefault, maturity,
                RuleTier.FIRST_PARTY, categories, rules, List.of());
    }

    public KnowledgePack {
        if (isBlank(packId)) {
            throw new IllegalArgumentException("packId is required");
        }
        if (isBlank(displayName)) {
            throw new IllegalArgumentException("displayName is required");
        }
        if (isBlank(sourceProject)) {
            throw new IllegalArgumentException("sourceProject is required");
        }
        if (isBlank(license)) {
            throw new IllegalArgumentException("license is required");
        }
        if (isBlank(sourceUrl)) {
            throw new IllegalArgumentException("sourceUrl is required");
        }
        if (isBlank(sourceVersion)) {
            throw new IllegalArgumentException("sourceVersion is required");
        }
        if (isBlank(sourceReference)) {
            throw new IllegalArgumentException("sourceReference is required");
        }
        if (maturity == null) {
            throw new IllegalArgumentException("maturity is required");
        }
        tier = tier == null ? RuleTier.FIRST_PARTY : tier;
        if (tier == RuleTier.KERNEL && !enabledByDefault) {
            throw new IllegalArgumentException(
                "Kernel knowledge pack must be enabled by default: " + packId);
        }
        categories = categories == null ? List.of() : List.copyOf(categories);
        rules = rules == null ? List.of() : List.copyOf(rules);
        knownStructures = knownStructures == null
            ? List.of()
            : List.copyOf(knownStructures);
    }

    /** Minimum independent evidence before a known form unlocks capabilities. */
    public enum KnownStructureEvidence {
        OBSERVED,
        VALIDATED_BY_EXAMPLES,
        SYMBOLICALLY_VERIFIED,
        FORMALLY_PROVABLE,
        FORMALLY_PROVED
    }

    /** Source and policy metadata retained with one imported mathematical form. */
    public record KnownStructureMetadata(
        String sourceProject,
        String license,
        String sourceUrl,
        String sourceVersion,
        String sourceReference,
        String translationNotes,
        List<String> enabledRulePackIds,
        List<String> compatibleBackendIds,
        KnownStructureEvidence minimumEvidence
    ) {
        public KnownStructureMetadata {
            sourceProject = text(sourceProject, "sourceProject");
            license = text(license, "license");
            sourceUrl = text(sourceUrl, "sourceUrl");
            sourceVersion = text(sourceVersion, "sourceVersion");
            sourceReference = text(sourceReference, "sourceReference");
            translationNotes = text(translationNotes, "translationNotes");
            enabledRulePackIds = normalized(enabledRulePackIds);
            compatibleBackendIds = normalized(compatibleBackendIds);
            minimumEvidence = Objects.requireNonNull(
                minimumEvidence, "minimumEvidence");
        }

        public static KnownStructureMetadata legacy(String provenance) {
            return new KnownStructureMetadata(
                provenance,
                "UNSPECIFIED",
                "urn:regelsuche:legacy-known-structure",
                "legacy",
                provenance,
                "Legacy in-process structure.",
                List.of(),
                List.of(),
                KnownStructureEvidence.OBSERVED
            );
        }

        public String provenanceSummary() {
            return sourceProject + " " + sourceVersion + " — "
                + sourceReference + " [" + license + "]";
        }

        public String canonicalDescriptor() {
            StringBuilder value = new StringBuilder();
            append(value, sourceProject, license, sourceUrl, sourceVersion,
                sourceReference, translationNotes);
            appendList(value, enabledRulePackIds);
            appendList(value, compatibleBackendIds);
            append(value, minimumEvidence.name());
            return value.toString();
        }

        private static List<String> normalized(List<String> values) {
            return Objects.requireNonNull(values, "values").stream()
                .map(value -> text(value, "list entry"))
                .distinct()
                .sorted()
                .toList();
        }

        private static String text(String value, String field) {
            String result = Objects.requireNonNull(value, field).trim();
            if (result.isEmpty()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return result;
        }

        private static void appendList(
            StringBuilder target,
            List<String> values
        ) {
            append(target, Integer.toString(values.size()));
            values.forEach(value -> append(target, value));
        }

        private static void append(StringBuilder target, String... values) {
            for (String value : values) {
                target.append(value.length()).append(':').append(value);
            }
        }
    }

    /** Pack-neutral known form consumed by representation discovery. */
    public record KnownStructureDefinition(
        String id,
        String domainId,
        ExprMatcher matcher,
        List<String> requiredAssumptions,
        List<String> consequenceIds,
        KnownStructureMetadata metadata
    ) {
        public KnownStructureDefinition {
            if (id == null || id.isBlank()
                    || domainId == null || domainId.isBlank()) {
                throw new IllegalArgumentException("id and domainId are required");
            }
            matcher = Objects.requireNonNull(matcher, "matcher");
            requiredAssumptions = List.copyOf(
                Objects.requireNonNull(
                    requiredAssumptions, "requiredAssumptions"));
            consequenceIds = List.copyOf(
                Objects.requireNonNull(consequenceIds, "consequenceIds"));
            if (consequenceIds.isEmpty()) {
                throw new IllegalArgumentException("consequenceIds are required");
            }
            metadata = Objects.requireNonNull(metadata, "metadata");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
