package de.regelsuche.benchmark.polynomial;

import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Freezes the target-blind execution matrix for the polynomial utility study. */
public final class PolynomialTheoryUtilityExecutionPlan {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-execution-plan/v1";
    public static final String EVIDENCE_STATUS = "FROZEN_NOT_EXECUTED";
    public static final String RESULT_STATUS = "NOT_EXECUTED";
    public static final String PLAN_SELECTION_TIMING =
        "BEFORE_PROFILE_EXECUTION";
    public static final String QUALIFICATION_EXPOSURE =
        "HASH_ONLY_BEFORE_RESULT_FREEZE";
    public static final String FILE_NAME =
        "polynomial-theory-utility-execution-plan-v1.json";
    public static final int EXPECTED_ROW_COUNT = 600;
    public static final long EXPECTED_BYTE_LENGTH = 235_617L;
    public static final String EXPECTED_CONTENT_HASH =
        "sha256:0a9be9ab83076ac2e507aa7d0f3c343ec2840556441c7cf8ce750772f215855e";

    public static final String VERIFIER_ID =
        "regelsuche.factorization-verifier/v2";
    public static final String TRANSFORMATION_ID =
        "regelsuche.exact-factorization-transformation/v1";
    public static final String CACHE_SCHEMA =
        "regelsuche.polynomial-derived-macro-cache-entry/v2";
    public static final String CACHE_REVISION =
        "regelsuche.polynomial-theory-utility-derived-macro-cache/v1";
    public static final int CACHE_CAPACITY = 128;
    public static final String CACHE_LOOKUP =
        "EXACT_CONTENT_ADDRESSED_ENTRY";
    public static final String CACHE_REPLAY =
        "EXACT_VERIFIER_AUTHORIZED_TRANSFORMATION";
    public static final String CACHE_EVICTION = "FIFO_INSERTION_ORDER";
    public static final String EXTERNAL_RUNTIME_ID =
        "graalpy-25.1.3_sympy-1.14.0_mpmath-1.3.0";
    public static final String EXTERNAL_LOCK_PATH =
        "regelsuche-math-sympy/graalpy.lock";

    public static final String RUN_GROUPING = "PROFILE_AND_CHECKPOINT";
    public static final String CASE_ORDER = "FROZEN_FORMATION_ORDER";
    public static final String PROFILE_ISOLATION = "INDEPENDENT_RUNS";
    public static final String CHECKPOINT_ISOLATION = "INDEPENDENT_RUNS";
    public static final String CACHE_INITIAL_STATE = "EMPTY_AT_RUN_START";
    public static final String CACHE_LIFETIME =
        "WITHIN_PROFILE_CHECKPOINT_RUN";
    public static final String QUALIFICATION_ACCESS = "FORBIDDEN";
    public static final String BACKEND_SUBSTITUTION = "FORBIDDEN";
    public static final String FAILURE_RETENTION =
        "RETAIN_ALL_TERMINAL_OUTCOMES";

    public static final List<PolynomialTheoryUtilityExecutionProfile>
            PROFILES = List.of(
        new PolynomialTheoryUtilityExecutionProfile(
            "NO_FACTORIZATION",
            "regelsuche.polynomial-theory-utility.no-factorization/v1",
            "NO_FACTORIZATION",
            "DISABLED",
            "NONE",
            "NONE",
            "DISABLED",
            "NONE",
            "NONE"
        ),
        new PolynomialTheoryUtilityExecutionProfile(
            "ON_DEMAND_VERIFIED_FACTORIZATION",
            "regelsuche.polynomial-theory-utility."
                + "on-demand-verified-factorization/v1",
            "DECLARED_UNIVARIATE_ZX_QX",
            "ON_DEMAND",
            "regelsuche.factorization.native-univariate-rational/v1",
            TRANSFORMATION_ID,
            "DISABLED",
            "NONE",
            "EXPLICIT_INDEX_ASCENDING"
        ),
        new PolynomialTheoryUtilityExecutionProfile(
            "VERIFIED_DERIVED_MACRO_CACHE",
            "regelsuche.polynomial-theory-utility."
                + "verified-derived-macro-cache/v1",
            "DECLARED_UNIVARIATE_ZX_QX",
            "ON_CACHE_MISS",
            "regelsuche.factorization.native-univariate-rational/v1",
            TRANSFORMATION_ID,
            "READ_WRITE",
            "NATIVE_ON_CACHE_MISS_ONLY",
            "EXPLICIT_INDEX_ASCENDING"
        ),
        new PolynomialTheoryUtilityExecutionProfile(
            "SPECIALIZED_BINARY_QUARTIC_CONTROL",
            "regelsuche.polynomial-theory-utility."
                + "specialized-binary-quartic-control/v1",
            "BINARY_HOMOGENEOUS_QUARTIC_2X2_ONLY",
            "ON_DEMAND_SPECIALIZED_CONTROL",
            "regelsuche.factorization.binary-quartic-2x2/v1",
            "hypothesis_polynomial_decomposition_synthesis",
            "DISABLED",
            "NONE",
            "EXPLICIT_INDEX_ASCENDING"
        ),
        new PolynomialTheoryUtilityExecutionProfile(
            "OPTIONAL_EXTERNAL_VERIFIED_FACTORIZATION",
            "regelsuche.polynomial-theory-utility."
                + "optional-external-verified-factorization/v1",
            "DECLARED_UNIVARIATE_ZX_QX",
            "ON_DEMAND",
            "regelsuche.factorization.sympy-graalpy.rational/v1",
            TRANSFORMATION_ID,
            "DISABLED",
            "NONE",
            "EXPLICIT_INDEX_ASCENDING"
        )
    );

    public static final List<PolynomialTheoryUtilityExecutionCheckpoint>
            CHECKPOINTS = List.of(
        new PolynomialTheoryUtilityExecutionCheckpoint(
            "CP01_1_OF_12", 1, 1, 12),
        new PolynomialTheoryUtilityExecutionCheckpoint(
            "CP02_1_OF_6", 2, 1, 6),
        new PolynomialTheoryUtilityExecutionCheckpoint(
            "CP03_1_OF_3", 3, 1, 3),
        new PolynomialTheoryUtilityExecutionCheckpoint(
            "CP04_1_OF_2", 4, 1, 2),
        new PolynomialTheoryUtilityExecutionCheckpoint(
            "CP05_3_OF_4", 5, 3, 4),
        new PolynomialTheoryUtilityExecutionCheckpoint(
            "CP06_FULL", 6, 1, 1)
    );

    private PolynomialTheoryUtilityExecutionPlan() {
    }

    public static PolynomialTheoryUtilityExecutionArtifact freeze() {
        var preregistration = PolynomialTheoryUtilityPreregistration.load();
        var formation = PolynomialTheoryUtilityCaseCorpus.load();
        requireBindings(preregistration, formation);
        List<PolynomialTheoryUtilityExecutionRow> rows =
            new ArrayList<>(EXPECTED_ROW_COUNT);
        for (var profile : PROFILES) {
            for (var checkpoint : CHECKPOINTS) {
                String runId =
                    PolynomialTheoryUtilityExecutionIdentity.runId(
                        profile,
                        checkpoint
                    );
                for (var studyCase : formation.cases()) {
                    int primitive = scale(
                        studyCase.admittedPrimitiveWork(),
                        checkpoint
                    );
                    int mechanical = scale(
                        studyCase.totalMechanicalWork(),
                        checkpoint
                    );
                    int factorization = scale(
                        studyCase.factorizationWork(),
                        checkpoint
                    );
                    rows.add(new PolynomialTheoryUtilityExecutionRow(
                        PolynomialTheoryUtilityExecutionIdentity.rowId(
                            runId,
                            studyCase.caseId(),
                            profile,
                            checkpoint,
                            primitive,
                            mechanical,
                            factorization
                        ),
                        runId,
                        studyCase.caseId(),
                        profile.profileId(),
                        checkpoint.checkpointId(),
                        primitive,
                        mechanical,
                        factorization,
                        RESULT_STATUS
                    ));
                }
            }
        }
        requireMatrix(formation.cases(), rows);
        return new PolynomialTheoryUtilityExecutionArtifact(
            rows,
            PolynomialTheoryUtilityExecutionJson.canonical(rows)
        );
    }

    public static PolynomialTheoryUtilityExecutionArtifact write(
        Path directory
    ) throws IOException {
        Path root = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (Files.exists(root.resolve(
                PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_FILE_NAME))) {
            throw new IllegalStateException(
                "execution plan must not expose sealed qualification"
            );
        }
        var artifact = freeze();
        Path target = root.resolve(FILE_NAME);
        AtomicJsonFile.writeUtf8(target, artifact.canonicalJson());
        if (!Files.isRegularFile(target)
                || !java.util.Arrays.equals(
                    Files.readAllBytes(target),
                    artifact.canonicalJson().getBytes(StandardCharsets.UTF_8)
                )) {
            throw new IllegalStateException("written execution plan changed");
        }
        return artifact;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: <output-directory>");
        }
        var artifact = write(Path.of(args[0]));
        System.out.println("polynomialTheoryUtilityExecutionPlanHash="
            + artifact.contentHash());
        System.out.println("polynomialTheoryUtilityExecutionPlanRows="
            + artifact.rows().size());
        System.out.println("polynomialTheoryUtilityExecutionPlanStatus="
            + artifact.evidenceStatus());
    }

    static int scale(
        int fullBudget,
        PolynomialTheoryUtilityExecutionCheckpoint checkpoint
    ) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (fullBudget < 1) {
            throw new IllegalArgumentException("budget must be positive");
        }
        long numerator = Math.multiplyExact(
            (long) fullBudget,
            checkpoint.numerator()
        );
        return Math.toIntExact(Math.max(
            1L,
            Math.addExact(numerator, checkpoint.denominator() - 1L)
                / checkpoint.denominator()
        ));
    }

    private static void requireBindings(
        PolynomialTheoryUtilityPreregistration.Artifact preregistration,
        PolynomialTheoryUtilityCaseCorpus.FormationArtifact formation
    ) {
        if (!PolynomialTheoryUtilityPreregistration.STUDY_ID.equals(
                preregistration.studyId())
                || !PolynomialTheoryUtilityPreregistration.STUDY_ID.equals(
                    formation.studyId())
                || !PolynomialTheoryUtilityPreregistration.CONTENT_HASH.equals(
                    preregistration.contentHash())
                || PolynomialTheoryUtilityPreregistration.BYTE_LENGTH
                    != preregistration.byteLength()
                || !PolynomialTheoryUtilityCaseCorpus.FORMATION_CONTENT_HASH
                    .equals(formation.contentHash())
                || PolynomialTheoryUtilityCaseCorpus.FORMATION_BYTE_LENGTH
                    != formation.byteLength()
                || !PolynomialTheoryUtilityCaseCorpus
                    .QUALIFICATION_CONTENT_HASH.equals(
                        formation.qualificationBinding().contentHash())
                || PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_BYTE_LENGTH
                    != formation.qualificationBinding().byteLength()
                || !preregistration.profiles().equals(
                    PROFILES.stream()
                        .map(
                            PolynomialTheoryUtilityExecutionProfile::profileId
                        )
                        .toList())) {
            throw new IllegalStateException("execution plan binding differs");
        }
    }

    private static void requireMatrix(
        List<PolynomialTheoryUtilityCaseCorpus.FormationCase> cases,
        List<PolynomialTheoryUtilityExecutionRow> rows
    ) {
        if (rows.size() != EXPECTED_ROW_COUNT
                || rows.size()
                    != cases.size() * PROFILES.size() * CHECKPOINTS.size()) {
            throw new IllegalStateException("execution matrix size differs");
        }
        Set<String> rowIds = new HashSet<>();
        Map<String, Integer> runRows = new HashMap<>();
        int offset = 0;
        for (var profile : PROFILES) {
            for (var checkpoint : CHECKPOINTS) {
                String expectedRunId =
                    PolynomialTheoryUtilityExecutionIdentity.runId(
                        profile,
                        checkpoint
                    );
                for (var studyCase : cases) {
                    var row = rows.get(offset++);
                    runRows.merge(row.runId(), 1, Integer::sum);
                    if (!expectedRunId.equals(row.runId())
                            || !studyCase.caseId().equals(row.caseId())
                            || !profile.profileId().equals(row.profileId())
                            || !checkpoint.checkpointId().equals(
                                row.checkpointId())
                            || !rowIds.add(row.rowId())) {
                        throw new IllegalStateException(
                            "execution matrix order differs at " + (offset - 1)
                        );
                    }
                }
            }
        }
        if (runRows.size() != PROFILES.size() * CHECKPOINTS.size()
                || runRows.values().stream().anyMatch(count ->
                    count != cases.size())) {
            throw new IllegalStateException("execution run grouping differs");
        }
    }
}
