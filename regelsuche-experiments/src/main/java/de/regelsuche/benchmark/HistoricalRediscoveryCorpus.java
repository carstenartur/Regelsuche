package de.regelsuche.benchmark;

import de.regelsuche.json.JsonReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict loader for the frozen historical rediscovery diagnostic corpus. */
public final class HistoricalRediscoveryCorpus {
    public static final String RESOURCE =
        "/de/regelsuche/benchmark/historical-rediscovery-corpus.json";
    public static final String SCHEMA =
        "regelsuche.historical-rediscovery-corpus/v1";

    private static final Set<String> ROOT_KEYS = Set.of(
        "schema",
        "evidenceStatus",
        "inventoryRevision",
        "claimBoundary",
        "cases"
    );
    private static final Set<String> CASE_KEYS = Set.of(
        "id",
        "family",
        "source",
        "target",
        "relation",
        "role",
        "diagnosticPurpose",
        "provenance",
        "targetRelation",
        "oracleMaxDepth",
        "oracleMaxVisitedStates",
        "searchMaxDepth",
        "searchMaxVisitedStates",
        "maxCandidatesPerState",
        "maxExpandingSteps",
        "beamWidth"
    );

    private HistoricalRediscoveryCorpus() {
    }

    public static Corpus load() {
        try (InputStream input = HistoricalRediscoveryCorpus.class
                .getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing corpus resource " + RESOURCE);
            }
            byte[] bytes = input.readAllBytes();
            String json = new String(bytes, StandardCharsets.UTF_8);
            return parse(json, sha256(bytes));
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read corpus " + RESOURCE, exception);
        }
    }

    static Corpus parse(String json, String contentSha256) {
        Map<String, Object> root = new JsonReader(
            requireText(json, "json")).readObject();
        requireKeys(root, ROOT_KEYS, "corpus root");
        String schema = string(root, "schema");
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported corpus schema " + schema);
        }
        String evidenceStatus = string(root, "evidenceStatus");
        if (!"FROZEN_DIAGNOSTIC_CORPUS".equals(evidenceStatus)) {
            throw new IllegalArgumentException(
                "unexpected corpus evidenceStatus " + evidenceStatus);
        }
        String inventoryRevision = string(root, "inventoryRevision");
        String claimBoundary = string(root, "claimBoundary");
        Object rawCases = root.get("cases");
        if (!(rawCases instanceof List<?> values)) {
            throw new IllegalArgumentException("cases must be a JSON array");
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("corpus must contain at least one case");
        }

        List<Case> cases = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Object rawCase : values) {
            if (!(rawCase instanceof Map<?, ?> rawMap)) {
                throw new IllegalArgumentException(
                    "each corpus case must be a JSON object");
            }
            Map<String, Object> caseValues = stringKeyed(rawMap);
            requireKeys(caseValues, CASE_KEYS, "corpus case");
            Case benchmarkCase = new Case(
                string(caseValues, "id"),
                string(caseValues, "family"),
                string(caseValues, "source"),
                string(caseValues, "target"),
                enumValue(Relation.class, caseValues, "relation"),
                enumValue(Role.class, caseValues, "role"),
                string(caseValues, "diagnosticPurpose"),
                string(caseValues, "provenance"),
                enumValue(TargetRelation.class, caseValues, "targetRelation"),
                nonNegativeInt(caseValues, "oracleMaxDepth"),
                positiveInt(caseValues, "oracleMaxVisitedStates"),
                nonNegativeInt(caseValues, "searchMaxDepth"),
                positiveInt(caseValues, "searchMaxVisitedStates"),
                positiveInt(caseValues, "maxCandidatesPerState"),
                nonNegativeInt(caseValues, "maxExpandingSteps"),
                positiveInt(caseValues, "beamWidth")
            );
            if (!ids.add(benchmarkCase.id())) {
                throw new IllegalArgumentException(
                    "duplicate corpus case id " + benchmarkCase.id());
            }
            if (benchmarkCase.role() == Role.NEGATIVE_CONTROL
                    && benchmarkCase.relation() != Relation.NOT_EQUIVALENT) {
                throw new IllegalArgumentException(
                    "negative control must declare NOT_EQUIVALENT: "
                        + benchmarkCase.id());
            }
            cases.add(benchmarkCase);
        }
        return new Corpus(
            schema,
            evidenceStatus,
            inventoryRevision,
            claimBoundary,
            requireSha256(contentSha256),
            cases
        );
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("JSON object key must be a string");
            }
            result.put(text, value);
        });
        return result;
    }

    private static void requireKeys(
        Map<String, Object> values,
        Set<String> expected,
        String label
    ) {
        if (!values.keySet().equals(expected)) {
            throw new IllegalArgumentException(
                label + " keys differ: expected=" + expected
                    + ", actual=" + values.keySet());
        }
    }

    private static String string(Map<String, Object> values, String key) {
        Object raw = values.get(key);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(
                "missing, non-string or blank " + key + " in " + values);
        }
        return text.trim();
    }

    private static int positiveInt(Map<String, Object> values, String key) {
        int value = integer(values, key);
        if (value < 1) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }

    private static int nonNegativeInt(Map<String, Object> values, String key) {
        int value = integer(values, key);
        if (value < 0) {
            throw new IllegalArgumentException(key + " must not be negative");
        }
        return value;
    }

    private static int integer(Map<String, Object> values, String key) {
        Object raw = values.get(key);
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be numeric");
        }
        double decimal = number.doubleValue();
        int integer = number.intValue();
        if (!Double.isFinite(decimal) || decimal != integer) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return integer;
    }

    private static <E extends Enum<E>> E enumValue(
        Class<E> type,
        Map<String, Object> values,
        String key
    ) {
        String value = string(values, key);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "unsupported " + key + " value " + value,
                exception);
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static String requireSha256(String value) {
        String text = requireText(value, "contentSha256").trim();
        if (!text.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                "contentSha256 must be lowercase hexadecimal SHA-256");
        }
        return text;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public enum Relation {
        EQUIVALENT,
        NOT_EQUIVALENT
    }

    public enum Role {
        HISTORICAL_POSITIVE,
        NEGATIVE_CONTROL,
        SEARCH_POLICY_CONTROL
    }

    public enum TargetRelation {
        SYNTAX_EXACT,
        VALUE_EQUIVALENT
    }

    public record Corpus(
        String schema,
        String evidenceStatus,
        String inventoryRevision,
        String claimBoundary,
        String contentSha256,
        List<Case> cases
    ) {
        public Corpus {
            requireText(schema, "schema");
            requireText(evidenceStatus, "evidenceStatus");
            requireText(inventoryRevision, "inventoryRevision");
            requireText(claimBoundary, "claimBoundary");
            contentSha256 = requireSha256(contentSha256);
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
            if (cases.isEmpty()) {
                throw new IllegalArgumentException("cases must not be empty");
            }
        }
    }

    public record Case(
        String id,
        String family,
        String source,
        String target,
        Relation relation,
        Role role,
        String diagnosticPurpose,
        String provenance,
        TargetRelation targetRelation,
        int oracleMaxDepth,
        int oracleMaxVisitedStates,
        int searchMaxDepth,
        int searchMaxVisitedStates,
        int maxCandidatesPerState,
        int maxExpandingSteps,
        int beamWidth
    ) {
        public Case {
            id = requireText(id, "id").trim();
            family = requireText(family, "family").trim();
            source = requireText(source, "source").trim();
            target = requireText(target, "target").trim();
            Objects.requireNonNull(relation, "relation");
            Objects.requireNonNull(role, "role");
            diagnosticPurpose = requireText(
                diagnosticPurpose,
                "diagnosticPurpose").trim();
            provenance = requireText(provenance, "provenance").trim();
            Objects.requireNonNull(targetRelation, "targetRelation");
            if (oracleMaxDepth < 0 || searchMaxDepth < 0
                    || oracleMaxVisitedStates < 1
                    || searchMaxVisitedStates < 1
                    || maxCandidatesPerState < 1
                    || maxExpandingSteps < 0
                    || beamWidth < 1) {
                throw new IllegalArgumentException(
                    "case budgets are outside their declared finite ranges: " + id);
            }
        }
    }
}
