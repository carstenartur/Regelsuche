package de.regelsuche.benchmark.polynomial;

import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Fail-closed binding for the frozen polynomial-theory utility study contract.
 *
 * <p>This class only exposes preregistered study structure. It does not execute
 * a profile, reveal held-out outcomes, activate factorization in search or
 * authorize a product default.</p>
 */
public final class PolynomialTheoryUtilityPreregistration {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-preregistration/v1";
    public static final String STUDY_ID =
        "polynomial-theory-held-out-utility-v1";
    public static final String EVIDENCE_STATUS = "FROZEN_NOT_EXECUTED";
    public static final String PROFILE_SELECTION_TIMING =
        "BEFORE_HELD_OUT_RESULTS";
    public static final String FILE_NAME =
        "polynomial-theory-utility-preregistration-v1.json";
    public static final String RESOURCE =
        "/de/regelsuche/benchmark/polynomial/" + FILE_NAME;
    public static final long BYTE_LENGTH = 3174L;
    public static final String CONTENT_HASH =
        "sha256:c1c0473eff02ed8df3aae330c3f50a3126f02755363895228c5c2f7e8a60fb5f";

    public static final List<String> PROFILES = List.of(
        "NO_FACTORIZATION",
        "ON_DEMAND_VERIFIED_FACTORIZATION",
        "VERIFIED_DERIVED_MACRO_CACHE",
        "SPECIALIZED_BINARY_QUARTIC_CONTROL",
        "OPTIONAL_EXTERNAL_VERIFIED_FACTORIZATION"
    );

    public static final List<String> STRATIFICATION_AXES = List.of(
        "DOMAIN",
        "DEGREE",
        "COEFFICIENT_SIZE",
        "DENSITY",
        "MULTIPLICITY",
        "REDUCIBILITY_STATUS",
        "REPEATED_REUSE",
        "OCCURRENCE_DEPTH"
    );

    public static final List<String> REQUIRED_CASE_OUTCOMES = List.of(
        "POSITIVE",
        "NEGATIVE",
        "UNSUPPORTED",
        "BUDGET_INCONCLUSIVE",
        "NEAR_MISS"
    );

    public static final List<String> DECISION_OUTCOMES = List.of(
        "ON_DEMAND_DEFAULT_JUSTIFIED",
        "VERIFIED_CACHE_DEFAULT_JUSTIFIED",
        "HYBRID_POLICY_JUSTIFIED",
        "KEEP_OPT_IN",
        "NULL_RESULT_NO_MATERIAL_UTILITY"
    );

    private static final List<String> ARTIFACT_REQUIREMENTS = List.of(
        "VERSIONED_PLAN",
        "VERSIONED_CONFIGURATION",
        "VERSIONED_CASES",
        "VERSIONED_RESULTS",
        "VERSIONED_CACHE_LINEAGE",
        "VERSIONED_DECISION",
        "TWO_CLEAN_CHECKOUT_REPRODUCTIONS",
        "ONE_PINNED_CONTAINER_REPRODUCTION",
        "RETAIN_NEGATIVE_NULL_AND_GAP_OUTCOMES"
    );

    private static final List<String> REQUIRED_MEASUREMENTS = List.of(
        "NEWLY_REACHED_VALIDATED_CONSEQUENCES",
        "PATH_DEPTH",
        "PRIMITIVE_EXPANSION_LENGTH",
        "FACTORIZATION_REQUESTS",
        "FACTORIZATION_CANDIDATES",
        "VERIFIER_OUTCOMES",
        "SOURCE_VALIDATION_WORK",
        "FACTORIZATION_WORK",
        "RENDER_REPARSE_WORK",
        "CACHE_LOOKUP_WORK",
        "CACHE_REPLAY_WORK",
        "GENERATED_TRANSITIONS",
        "EFFECTIVE_BRANCHING",
        "CACHE_HITS",
        "CACHE_MISSES",
        "CACHE_INSERTIONS",
        "CACHE_EVICTIONS",
        "CACHE_AMORTIZATION_BREAK_EVEN",
        "ASSUMPTION_OUTCOMES",
        "SOURCE_AST_GROWTH",
        "TRANSFORMED_AST_GROWTH",
        "HELD_OUT_OCCURRENCE_REUSE",
        "STRUCTURAL_FAMILY_REUSE",
        "NATIVE_EXTERNAL_SEMANTIC_AGREEMENT",
        "SPECIALIZED_CONTROL_COVERAGE"
    );

    private static final List<String> REQUIRED_FIELD_BINDINGS = List.of(
        "\"schema\": \"" + SCHEMA + "\"",
        "\"studyId\": \"" + STUDY_ID + "\"",
        "\"evidenceStatus\": \"" + EVIDENCE_STATUS + "\"",
        "\"profileSelectionTiming\": \""
            + PROFILE_SELECTION_TIMING + "\"",
        "\"corpusFreeze\": "
            + "\"CONTENT_ADDRESSED_BEFORE_PROFILE_EXECUTION\"",
        "\"visibleInventory\": \"IDENTICAL_ACROSS_PROFILES\"",
        "\"assumptions\": \"IDENTICAL_ACROSS_PROFILES\"",
        "\"budgets\": \"IDENTICAL_AT_EVERY_POLICY_CHECKPOINT\"",
        "\"hiddenBestOfSelection\": \"FORBIDDEN\"",
        "\"admittedPrimitiveWork\": "
            + "\"MATCH_AT_EVERY_POLICY_CHECKPOINT\"",
        "\"totalMechanicalWork\": "
            + "\"MATCH_AT_EVERY_POLICY_CHECKPOINT\"",
        "\"nonResettableAuthority\": \"REQUIRED\"",
        "\"runtimeRole\": "
            + "\"ENVIRONMENT_QUALIFIED_DIAGNOSTIC_ONLY\"",
        "\"additionalValidatedCoverageOrLowerCanonicalWork\": "
            + "\"REQUIRED\"",
        "\"wallClockOnly\": \"INSUFFICIENT\""
    );

    private PolynomialTheoryUtilityPreregistration() {
    }

    public static Artifact load() {
        byte[] bytes = readResource();
        if (bytes.length != BYTE_LENGTH) {
            throw new IllegalStateException(
                "polynomial utility preregistration byte length differs: "
                    + "expected=" + BYTE_LENGTH + ", actual=" + bytes.length
            );
        }
        String actualHash = sha256(bytes);
        if (!CONTENT_HASH.equals(actualHash)) {
            throw new IllegalStateException(
                "polynomial utility preregistration hash differs: expected="
                    + CONTENT_HASH + ", actual=" + actualHash
            );
        }
        String canonicalJson = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(
                bytes,
                canonicalJson.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException(
                "polynomial utility preregistration is not canonical UTF-8"
            );
        }
        validateStructure(canonicalJson);

        return new Artifact(
            SCHEMA,
            STUDY_ID,
            EVIDENCE_STATUS,
            PROFILE_SELECTION_TIMING,
            PROFILES,
            STRATIFICATION_AXES,
            REQUIRED_CASE_OUTCOMES,
            DECISION_OUTCOMES,
            CONTENT_HASH,
            BYTE_LENGTH,
            canonicalJson
        );
    }

    static void validateStructure(String canonicalJson) {
        Objects.requireNonNull(canonicalJson, "canonicalJson");
        if (!canonicalJson.startsWith("{\n")
                || !canonicalJson.endsWith("}\n")
                || canonicalJson.indexOf('\r') >= 0) {
            throw new IllegalStateException(
                "polynomial utility preregistration has non-canonical "
                    + "framing; the frozen resource must use UTF-8 with LF "
                    + "line endings. Preserve the path-specific "
                    + ".gitattributes eol=lf rule and renormalize the file "
                    + "before updating its byte length/hash"
            );
        }
        REQUIRED_FIELD_BINDINGS.forEach(binding ->
            requireExactlyOnce(canonicalJson, binding));
        requireExactArray(
            canonicalJson,
            "artifactRequirements",
            ARTIFACT_REQUIREMENTS
        );
        requireExactArray(canonicalJson, "decisionOutcomes", DECISION_OUTCOMES);
        requireExactArray(canonicalJson, "profiles", PROFILES);
        requireExactArray(
            canonicalJson,
            "requiredCaseOutcomes",
            REQUIRED_CASE_OUTCOMES
        );
        requireExactArray(
            canonicalJson,
            "requiredMeasurements",
            REQUIRED_MEASUREMENTS
        );
        requireExactArray(
            canonicalJson,
            "stratificationAxes",
            STRATIFICATION_AXES
        );
    }

    public static Artifact write(Path directory) throws IOException {
        Path root = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath().normalize();
        Files.createDirectories(root);
        Artifact artifact = load();
        Path target = root.resolve(FILE_NAME);
        AtomicJsonFile.writeUtf8(target, artifact.canonicalJson());
        if (!Files.isRegularFile(target)
                || !artifact.canonicalJson().equals(Files.readString(
                    target,
                    StandardCharsets.UTF_8))) {
            throw new IllegalStateException(
                "written polynomial utility preregistration changed: "
                    + target
            );
        }
        return artifact;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: <output-directory>");
        }
        Artifact artifact = write(Path.of(args[0]));
        System.out.println(
            "polynomialTheoryUtilityPreregistrationHash="
                + artifact.contentHash()
        );
        System.out.println(
            "polynomialTheoryUtilityProfiles=" + artifact.profiles().size()
        );
        System.out.println(
            "polynomialTheoryUtilityEvidenceStatus="
                + artifact.evidenceStatus()
        );
    }

    private static byte[] readResource() {
        try (InputStream input =
                PolynomialTheoryUtilityPreregistration.class
                    .getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                    "missing polynomial utility preregistration " + RESOURCE
                );
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(
                "cannot read polynomial utility preregistration " + RESOURCE,
                exception
            );
        }
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void requireExactArray(
        String source,
        String field,
        List<String> values
    ) {
        StringBuilder expected = new StringBuilder();
        expected.append("  \"").append(field).append("\": [\n");
        for (int index = 0; index < values.size(); index++) {
            expected.append("    \"").append(values.get(index)).append('"');
            if (index + 1 < values.size()) {
                expected.append(',');
            }
            expected.append('\n');
        }
        expected.append("  ]");
        String exact = expected.toString();
        int first = source.indexOf(exact);
        if (first < 0 || source.indexOf(exact, first + exact.length()) >= 0) {
            throw new IllegalStateException(
                "polynomial utility preregistration must contain exactly "
                    + "the frozen ordered array: " + field
            );
        }
    }

    private static void requireExactlyOnce(String source, String value) {
        int first = source.indexOf(value);
        if (first < 0 || source.indexOf(value, first + value.length()) >= 0) {
            throw new IllegalStateException(
                "polynomial utility preregistration must contain exactly once: "
                    + value
            );
        }
    }

    public record Artifact(
        String schema,
        String studyId,
        String evidenceStatus,
        String profileSelectionTiming,
        List<String> profiles,
        List<String> stratificationAxes,
        List<String> requiredCaseOutcomes,
        List<String> decisionOutcomes,
        String contentHash,
        long byteLength,
        String canonicalJson
    ) {
        public Artifact {
            schema = Objects.requireNonNull(schema, "schema");
            studyId = Objects.requireNonNull(studyId, "studyId");
            evidenceStatus = Objects.requireNonNull(
                evidenceStatus,
                "evidenceStatus"
            );
            profileSelectionTiming = Objects.requireNonNull(
                profileSelectionTiming,
                "profileSelectionTiming"
            );
            profiles = List.copyOf(profiles);
            stratificationAxes = List.copyOf(stratificationAxes);
            requiredCaseOutcomes = List.copyOf(requiredCaseOutcomes);
            decisionOutcomes = List.copyOf(decisionOutcomes);
            contentHash = Objects.requireNonNull(contentHash, "contentHash");
            canonicalJson = Objects.requireNonNull(
                canonicalJson,
                "canonicalJson"
            );
            if (byteLength < 1L) {
                throw new IllegalArgumentException(
                    "byteLength must be positive"
                );
            }
        }
    }
}
