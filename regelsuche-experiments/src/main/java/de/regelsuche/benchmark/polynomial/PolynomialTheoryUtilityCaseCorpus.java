package de.regelsuche.benchmark.polynomial;

import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed target-blind case formation for the polynomial utility study.
 *
 * <p>The formation resource contains only inputs, strata, assumptions and
 * matched work budgets. It binds the separately sealed qualification by path,
 * byte length and SHA-256; this class never opens that resource.</p>
 */
public final class PolynomialTheoryUtilityCaseCorpus {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-formation-corpus/v1";
    public static final String STUDY_ID =
        PolynomialTheoryUtilityPreregistration.STUDY_ID;
    public static final String EVIDENCE_STATUS = "FROZEN_NOT_EXECUTED";
    public static final String CASE_SELECTION_TIMING =
        "BEFORE_PROFILE_EXECUTION";
    public static final String PROFILE_VISIBILITY =
        "IDENTICAL_ACROSS_PROFILES";
    public static final String QUALIFICATION_EXPOSURE =
        "HASH_ONLY_BEFORE_RESULT_FREEZE";

    public static final String FORMATION_FILE_NAME =
        "polynomial-theory-utility-formation-corpus-v1.json";
    public static final String FORMATION_RESOURCE =
        "/de/regelsuche/benchmark/polynomial/" + FORMATION_FILE_NAME;
    public static final long FORMATION_BYTE_LENGTH = 7346L;
    public static final String FORMATION_CONTENT_HASH =
        "sha256:2fd889c51b086afcf36ec450a38a3cbaf15b05cb0b27cf1fa5222b22e906636b";

    public static final String QUALIFICATION_FILE_NAME =
        "polynomial-theory-utility-qualification-corpus-v1.json";
    public static final String QUALIFICATION_RESOURCE =
        "/de/regelsuche/benchmark/polynomial/" + QUALIFICATION_FILE_NAME;
    public static final long QUALIFICATION_BYTE_LENGTH = 5146L;
    public static final String QUALIFICATION_CONTENT_HASH =
        "sha256:09455d9540547b48a741679f1d7b07bb1b35d2c44af4a5561b94b225c77963d6";

    public static final List<String> ORDERED_CASE_IDS = List.of(
        "z02-difference-of-squares",
        "z03-cubic-unity",
        "z04-four-linear-factors",
        "z04-repeated-factors",
        "q02-rational-linear-factors",
        "q03-rational-content",
        "z06-cyclotomic-mixture",
        "z08-even-sparse",
        "z10-unity-full-budget",
        "z05-eisenstein-irreducible",
        "z04-eisenstein-irreducible",
        "q02-no-rational-root",
        "near-miss-multivariate",
        "unsupported-rational-function",
        "unsupported-symbolic-exponent",
        "nested-single-occurrence",
        "two-identical-occurrences",
        "four-identical-occurrences",
        "z08-tiny-budget",
        "z10-tiny-budget"
    );

    private static final Set<String> DOMAINS = Set.of(
        "Z[x]", "Q[x]", "Z[x,y]", "Q(x)", "Z[x,n]"
    );
    private static final Set<String> COEFFICIENT_BUCKETS = Set.of(
        "SMALL", "MEDIUM", "RATIONAL_SMALL", "NOT_APPLICABLE"
    );
    private static final Set<String> DENSITY_BUCKETS = Set.of(
        "DENSE", "MIXED", "SPARSE", "NOT_APPLICABLE"
    );
    private static final Set<String> ASSUMPTION_SETS = Set.of(
        "NONE", "X_NONZERO", "N_POSITIVE_INTEGER"
    );
    private static final List<String> FORBIDDEN_FIELDS = List.of(
        "\"requiredOutcome\"",
        "\"reducibilityStatus\"",
        "\"multiplicityStatus\"",
        "\"referenceExpression\"",
        "\"expectedClassifierOutcome\""
    );
    private static final Pattern CASE_LINE = Pattern.compile(
        "^    \\{\"caseId\":\"([a-z0-9-]+)\","
            + "\"sourceExpression\":\"([^\"\\\\]+)\","
            + "\"declaredDomain\":\"([^\"]+)\","
            + "\"observedDegree\":(-?[0-9]+),"
            + "\"coefficientBucket\":\"([A-Z_]+)\","
            + "\"densityBucket\":\"([A-Z_]+)\","
            + "\"reuseCount\":([0-9]+),"
            + "\"occurrenceDepth\":([0-9]+),"
            + "\"occurrenceLayout\":\"([A-Z_]+)\","
            + "\"assumptionSetId\":\"([A-Z_]+)\","
            + "\"admittedPrimitiveWork\":([0-9]+),"
            + "\"totalMechanicalWork\":([0-9]+),"
            + "\"factorizationWork\":([0-9]+)\\}(,?)$"
    );

    private PolynomialTheoryUtilityCaseCorpus() {
    }

    /** Loads the formation corpus without opening the qualification resource. */
    public static FormationArtifact load() {
        byte[] bytes = readFormationResource();
        requireIdentity(bytes);
        String canonical = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, canonical.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException(
                "polynomial utility formation corpus is not canonical UTF-8"
            );
        }
        return parseCanonical(canonical);
    }

    static FormationArtifact parseCanonical(String canonical) {
        Objects.requireNonNull(canonical, "canonical");
        requireFraming(canonical);
        FORBIDDEN_FIELDS.forEach(field -> {
            if (canonical.contains(field)) {
                throw new IllegalStateException(
                    "formation corpus leaks sealed qualification field: " + field
                );
            }
        });

        String[] lines = canonical.split("\n", -1);
        if (lines.length != 37) {
            throw new IllegalStateException(
                "formation corpus line count differs: expected=37, actual="
                    + lines.length
            );
        }
        List<String> header = List.of(
            "{",
            "  \"schema\": \"" + SCHEMA + "\",",
            "  \"studyId\": \"" + STUDY_ID + "\",",
            "  \"evidenceStatus\": \"" + EVIDENCE_STATUS + "\",",
            "  \"caseSelectionTiming\": \"" + CASE_SELECTION_TIMING + "\",",
            "  \"profileVisibility\": \"" + PROFILE_VISIBILITY + "\",",
            "  \"qualificationExposure\": \"" + QUALIFICATION_EXPOSURE + "\",",
            "  \"caseCount\": 20,",
            "  \"qualificationBinding\": {",
            "    \"path\": \"" + QUALIFICATION_FILE_NAME + "\",",
            "    \"byteLength\": " + QUALIFICATION_BYTE_LENGTH + ",",
            "    \"contentHash\": \"" + QUALIFICATION_CONTENT_HASH + "\"",
            "  },",
            "  \"cases\": ["
        );
        for (int index = 0; index < header.size(); index++) {
            requireLine(lines, index, header.get(index));
        }
        requireLine(lines, 34, "  ]");
        requireLine(lines, 35, "}");
        requireLine(lines, 36, "");

        List<FormationCase> cases = new ArrayList<>(ORDERED_CASE_IDS.size());
        for (int index = 0; index < ORDERED_CASE_IDS.size(); index++) {
            Matcher matcher = CASE_LINE.matcher(lines[14 + index]);
            if (!matcher.matches()) {
                throw new IllegalStateException(
                    "formation case line is not canonical at index " + index
                );
            }
            if ((!matcher.group(14).isEmpty())
                    != (index + 1 < ORDERED_CASE_IDS.size())) {
                throw new IllegalStateException(
                    "formation case comma placement differs at index " + index
                );
            }
            FormationCase value = new FormationCase(
                matcher.group(1), matcher.group(2), matcher.group(3),
                Integer.parseInt(matcher.group(4)), matcher.group(5),
                matcher.group(6), Integer.parseInt(matcher.group(7)),
                Integer.parseInt(matcher.group(8)), matcher.group(9),
                matcher.group(10), Integer.parseInt(matcher.group(11)),
                Integer.parseInt(matcher.group(12)),
                Integer.parseInt(matcher.group(13))
            );
            validateCase(value);
            cases.add(value);
        }

        List<String> ids = cases.stream().map(FormationCase::caseId).toList();
        if (!ORDERED_CASE_IDS.equals(ids)
                || new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalStateException(
                "formation corpus ordered case identities differ"
            );
        }
        Set<String> domains = cases.stream()
            .map(FormationCase::declaredDomain)
            .collect(java.util.stream.Collectors.toSet());
        if (!DOMAINS.equals(domains)) {
            throw new IllegalStateException(
                "formation corpus domain coverage differs: " + domains
            );
        }
        return new FormationArtifact(
            new QualificationBinding(
                QUALIFICATION_FILE_NAME,
                QUALIFICATION_BYTE_LENGTH,
                QUALIFICATION_CONTENT_HASH
            ),
            cases,
            canonical
        );
    }

    /** Writes only the target-blind formation artifact. */
    public static FormationArtifact write(Path directory) throws IOException {
        Path root = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath().normalize();
        Files.createDirectories(root);
        FormationArtifact artifact = load();
        Path target = root.resolve(FORMATION_FILE_NAME);
        AtomicJsonFile.writeUtf8(target, artifact.canonicalJson());
        if (!Files.isRegularFile(target)
                || !artifact.canonicalJson().equals(
                    Files.readString(target, StandardCharsets.UTF_8))) {
            throw new IllegalStateException(
                "written polynomial utility formation corpus changed: " + target
            );
        }
        Path qualification = root.resolve(QUALIFICATION_FILE_NAME);
        if (Files.exists(qualification)) {
            throw new IllegalStateException(
                "formation export must not expose sealed qualification: "
                    + qualification
            );
        }
        return artifact;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: <output-directory>");
        }
        FormationArtifact artifact = write(Path.of(args[0]));
        System.out.println(
            "polynomialTheoryUtilityFormationHash=" + FORMATION_CONTENT_HASH
        );
        System.out.println(
            "polynomialTheoryUtilityFormationCases=" + artifact.cases().size()
        );
        System.out.println(
            "polynomialTheoryUtilityQualificationBinding="
                + artifact.qualificationBinding().contentHash()
        );
    }

    private static byte[] readFormationResource() {
        try (InputStream input = PolynomialTheoryUtilityCaseCorpus.class
                .getResourceAsStream(FORMATION_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                    "missing polynomial utility formation corpus "
                        + FORMATION_RESOURCE
                );
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(
                "cannot read polynomial utility formation corpus "
                    + FORMATION_RESOURCE,
                exception
            );
        }
    }

    private static void requireIdentity(byte[] bytes) {
        if (bytes.length != FORMATION_BYTE_LENGTH) {
            throw new IllegalStateException(
                "formation corpus byte length differs: expected="
                    + FORMATION_BYTE_LENGTH + ", actual=" + bytes.length
            );
        }
        String actual = sha256(bytes);
        if (!FORMATION_CONTENT_HASH.equals(actual)) {
            throw new IllegalStateException(
                "formation corpus hash differs: expected="
                    + FORMATION_CONTENT_HASH + ", actual=" + actual
            );
        }
    }

    private static void requireFraming(String canonical) {
        if (!canonical.startsWith("{\n")
                || !canonical.endsWith("}\n")
                || canonical.indexOf('\r') >= 0) {
            throw new IllegalStateException(
                "polynomial utility formation corpus has non-canonical "
                    + "framing; use UTF-8 with LF line endings, preserve the "
                    + "path-specific .gitattributes eol=lf rule and "
                    + "renormalize before updating length/hash"
            );
        }
    }

    private static void requireLine(String[] lines, int index, String expected) {
        if (!expected.equals(lines[index])) {
            throw new IllegalStateException(
                "formation corpus canonical line differs at index " + index
            );
        }
    }

    private static void validateCase(FormationCase value) {
        if (!DOMAINS.contains(value.declaredDomain())
                || !COEFFICIENT_BUCKETS.contains(value.coefficientBucket())
                || !DENSITY_BUCKETS.contains(value.densityBucket())
                || !ASSUMPTION_SETS.contains(value.assumptionSetId())) {
            throw new IllegalStateException(
                "formation case has an unknown frozen category: "
                    + value.caseId()
            );
        }
        boolean supported = "Z[x]".equals(value.declaredDomain())
            || "Q[x]".equals(value.declaredDomain());
        if (supported != (value.observedDegree() >= 0)) {
            throw new IllegalStateException(
                "formation case degree/domain boundary differs: "
                    + value.caseId()
            );
        }
        if (value.admittedPrimitiveWork() < value.factorizationWork()
                || value.totalMechanicalWork()
                    < value.admittedPrimitiveWork()) {
            throw new IllegalStateException(
                "formation case work budgets do not balance: " + value.caseId()
            );
        }
        boolean occurrenceValid = switch (value.occurrenceLayout()) {
            case "ROOT" -> value.occurrenceDepth() == 0
                && value.reuseCount() == 1;
            case "NESTED_RIGHT" -> value.occurrenceDepth() == 1
                && value.reuseCount() == 1;
            case "TWO_IDENTICAL_SIBLINGS" -> value.occurrenceDepth() == 1
                && value.reuseCount() == 2;
            case "FOUR_IDENTICAL_LEAVES" -> value.occurrenceDepth() == 2
                && value.reuseCount() == 4;
            default -> false;
        };
        if (!occurrenceValid) {
            throw new IllegalStateException(
                "formation case occurrence layout differs: " + value.caseId()
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

    public record QualificationBinding(
        String path,
        long byteLength,
        String contentHash
    ) {
        public QualificationBinding {
            path = Objects.requireNonNull(path, "path");
            contentHash = Objects.requireNonNull(contentHash, "contentHash");
            if (!QUALIFICATION_FILE_NAME.equals(path)
                    || byteLength != QUALIFICATION_BYTE_LENGTH
                    || !QUALIFICATION_CONTENT_HASH.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "qualification binding differs from the sealed contract"
                );
            }
        }
    }

    public record FormationCase(
        String caseId,
        String sourceExpression,
        String declaredDomain,
        int observedDegree,
        String coefficientBucket,
        String densityBucket,
        int reuseCount,
        int occurrenceDepth,
        String occurrenceLayout,
        String assumptionSetId,
        int admittedPrimitiveWork,
        int totalMechanicalWork,
        int factorizationWork
    ) {
        public FormationCase {
            caseId = requireText(caseId, "caseId");
            sourceExpression = requireText(sourceExpression, "sourceExpression");
            declaredDomain = requireText(declaredDomain, "declaredDomain");
            coefficientBucket = requireText(
                coefficientBucket, "coefficientBucket"
            );
            densityBucket = requireText(densityBucket, "densityBucket");
            occurrenceLayout = requireText(
                occurrenceLayout, "occurrenceLayout"
            );
            assumptionSetId = requireText(assumptionSetId, "assumptionSetId");
            if (observedDegree < -1
                    || reuseCount < 1
                    || occurrenceDepth < 0
                    || admittedPrimitiveWork < 1
                    || totalMechanicalWork < 1
                    || factorizationWork < 1) {
                throw new IllegalArgumentException(
                    "formation case numeric values are invalid: " + caseId
                );
            }
        }
    }

    public record FormationArtifact(
        QualificationBinding qualificationBinding,
        List<FormationCase> cases,
        String canonicalJson
    ) {
        public FormationArtifact {
            qualificationBinding = Objects.requireNonNull(
                qualificationBinding, "qualificationBinding"
            );
            cases = List.copyOf(cases);
            canonicalJson = requireText(canonicalJson, "canonicalJson");
            if (!ORDERED_CASE_IDS.equals(
                    cases.stream().map(FormationCase::caseId).toList())) {
                throw new IllegalArgumentException(
                    "formation artifact differs from its frozen contract"
                );
            }
        }

        public String schema() {
            return SCHEMA;
        }

        public String studyId() {
            return STUDY_ID;
        }

        public String evidenceStatus() {
            return EVIDENCE_STATUS;
        }

        public String caseSelectionTiming() {
            return CASE_SELECTION_TIMING;
        }

        public String profileVisibility() {
            return PROFILE_VISIBILITY;
        }

        public String qualificationExposure() {
            return QUALIFICATION_EXPOSURE;
        }

        public String contentHash() {
            return FORMATION_CONTENT_HASH;
        }

        public long byteLength() {
            return FORMATION_BYTE_LENGTH;
        }
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }
}
