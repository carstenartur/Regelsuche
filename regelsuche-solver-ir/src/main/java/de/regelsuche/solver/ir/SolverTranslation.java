package de.regelsuche.solver.ir;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.solver.ir.SolverIr.BackendDescriptor;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Canonical source-to-backend translation for one exact obligation. */
public record SolverTranslation(
    String schema,
    String obligationHash,
    String backendId,
    String backendVersion,
    TranslationStatus status,
    List<String> issues,
    Map<String, String> termMapping,
    String contentHash
) {
    public static final String SCOPED_SCHEMA = "regelsuche.solver-translation/v2";
    public static final String SCHEMA = "regelsuche.solver-translation/v1";

    public SolverTranslation {
        if (!SCHEMA.equals(schema) && !SCOPED_SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported solver translation schema");
        }
        requireSha(obligationHash, "obligationHash");
        requireText(backendId, "backendId");
        requireText(backendVersion, "backendVersion");
        Objects.requireNonNull(status, "status");
        issues = issues == null ? List.of() : issues.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct().sorted().toList();
        TreeMap<String, String> ordered = new TreeMap<>();
        if (termMapping != null) {
            termMapping.forEach((key, value) -> {
                requireText(key, "termMapping key");
                requireText(value, "termMapping value");
                ordered.put(key, value);
            });
        }
        // Map.copyOf does not preserve sorted iteration order across JVM processes.
        termMapping = Collections.unmodifiableMap(ordered);
        if (status == TranslationStatus.REJECTED && issues.isEmpty()) {
            throw new IllegalArgumentException(
                "rejected translation requires visible issues");
        }
        if (status == TranslationStatus.LOSSLESS
                && !(SCOPED_SCHEMA.equals(schema) && termMapping.containsKey("goal.proposition"))
                && (!termMapping.containsKey("goal.left")
                    || !termMapping.containsKey("goal.right"))) {
            throw new IllegalArgumentException(
                "lossless translation requires both goal term mappings");
        }
        requireSha(contentHash, "contentHash");
        String expected = hash(
            schema, obligationHash, backendId, backendVersion, status, issues, termMapping);
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "solver translation hash does not match canonical fields");
        }
    }

    public static SolverTranslation create(
        Obligation obligation,
        BackendDescriptor descriptor,
        TranslationStatus status,
        List<String> issues,
        Map<String, String> termMapping
    ) {
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(descriptor, "descriptor");
        List<String> orderedIssues = issues == null ? List.of() : issues.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct().sorted().toList();
        TreeMap<String, String> orderedTerms = new TreeMap<>();
        if (termMapping != null) {
            termMapping.forEach(orderedTerms::put);
        }
        String schema = SolverIr.SCOPED_OBLIGATION_SCHEMA.equals(obligation.schema()) ? SCOPED_SCHEMA : SCHEMA;
        if (status == TranslationStatus.LOSSLESS && obligation.goal().relation() == SolverIr.Relation.HOLDS
                && !orderedTerms.containsKey("goal.proposition"))
            throw new IllegalArgumentException("a proposition requires its exact term mapping");
        if (status == TranslationStatus.LOSSLESS && obligation.goal().relation() != SolverIr.Relation.HOLDS
                && (!orderedTerms.containsKey("goal.left") || !orderedTerms.containsKey("goal.right")))
            throw new IllegalArgumentException("a binary relation requires both term mappings");
        String contentHash = hash(
            schema, obligation.contentHash(), descriptor.backendId(),
            descriptor.backendVersion(), status, orderedIssues, orderedTerms);
        return new SolverTranslation(
            schema,
            obligation.contentHash(),
            descriptor.backendId(),
            descriptor.backendVersion(),
            status,
            orderedIssues,
            orderedTerms,
            contentHash);
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("schema", schema)
            .property("obligationHash", obligationHash)
            .property("backendId", backendId)
            .property("backendVersion", backendVersion)
            .property("status", status.name())
            .stringArray("issues", issues)
            .object("termMapping", object -> termMapping.forEach(object::property))
            .property("contentHash", contentHash)
            .endObject()
            .toString();
    }

    private static String hash(
        String schema, String obligationHash,
        String backendId,
        String backendVersion,
        TranslationStatus status,
        List<String> issues,
        Map<String, String> termMapping
    ) {
        return SolverIr.sha256(
            schema
                + "\nobligation=" + obligationHash
                + "\nbackend=" + backendId + '@' + backendVersion
                + "\nstatus=" + status.name()
                + "\nissues=" + issues
                + "\nterms=" + new TreeMap<>(termMapping));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireSha(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }
}
