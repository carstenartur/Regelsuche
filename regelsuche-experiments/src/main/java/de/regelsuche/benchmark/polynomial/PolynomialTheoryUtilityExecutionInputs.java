package de.regelsuche.benchmark.polynomial;

import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Freezes target-blind adapter inputs for every execution-plan row. */
public final class PolynomialTheoryUtilityExecutionInputs {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-execution-inputs/v1";
    public static final String EVIDENCE_STATUS = "READY_NOT_EXECUTED";
    public static final String INPUT_STATUS = "READY_NOT_EXECUTED";
    public static final String INPUT_SELECTION_TIMING =
        "AFTER_PLAN_FREEZE_BEFORE_PROFILE_EXECUTION";
    public static final String QUALIFICATION_EXPOSURE =
        "HASH_ONLY_BEFORE_RESULT_FREEZE";
    public static final String FORMATION_RESOLUTION =
        "FROZEN_CASE_ID_LOOKUP";
    public static final String PROFILE_POLICY_SOURCE =
        "FROZEN_EXECUTION_PLAN";
    public static final String RESULT_VISIBILITY = "NONE";
    public static final String DECISION_AUTHORITY = "NONE";
    public static final String ADAPTER_OUTPUT_AUTHORITY =
        "VERSIONED_CANDIDATE_FREEZE_ONLY";
    public static final String FILE_NAME =
        "polynomial-theory-utility-execution-inputs-v1.json";
    public static final int EXPECTED_INPUT_COUNT = 600;
    public static final long EXPECTED_BYTE_LENGTH = 336_406L;
    public static final String EXPECTED_CONTENT_HASH =
        "sha256:d93e2d3c4c3e72d435fa37d6bc988d2a8d873a3d1bc5584232fb1383b64d62c8";

    private PolynomialTheoryUtilityExecutionInputs() {
    }

    public static PolynomialTheoryUtilityExecutionInputArtifact freeze() {
        var plan = PolynomialTheoryUtilityExecutionPlan.freeze();
        var formation = PolynomialTheoryUtilityCaseCorpus.load();
        requireBindings(plan, formation);

        List<PolynomialTheoryUtilityExecutionInput> inputs =
            new ArrayList<>(EXPECTED_INPUT_COUNT);
        for (var row : plan.rows()) {
            var profile = profile(row.profileId());
            inputs.add(new PolynomialTheoryUtilityExecutionInput(
                PolynomialTheoryUtilityExecutionInputIdentity.inputId(
                    row,
                    profile
                ),
                row.rowId(),
                row.runId(),
                row.caseId(),
                row.profileId(),
                row.checkpointId(),
                profile.adapterId(),
                row.admittedPrimitiveWork(),
                row.totalMechanicalWork(),
                row.factorizationWork(),
                INPUT_STATUS
            ));
        }
        requireInputs(plan.rows(), inputs);
        return new PolynomialTheoryUtilityExecutionInputArtifact(
            inputs,
            PolynomialTheoryUtilityExecutionInputJson.canonical(inputs)
        );
    }

    public static PolynomialTheoryUtilityExecutionInputArtifact write(
        Path directory
    ) throws IOException {
        Path root = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path qualification = root.resolve(
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_FILE_NAME
        );
        if (Files.exists(qualification)) {
            throw new IllegalStateException(
                "execution inputs must not expose sealed qualification"
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
            throw new IllegalStateException(
                "written polynomial utility execution inputs changed"
            );
        }
        if (Files.exists(qualification)) {
            throw new IllegalStateException(
                "execution-input export exposed sealed qualification"
            );
        }
        return artifact;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: <output-directory>");
        }
        var artifact = write(Path.of(args[0]));
        System.out.println(
            "polynomialTheoryUtilityExecutionInputsHash="
                + artifact.contentHash()
        );
        System.out.println(
            "polynomialTheoryUtilityExecutionInputs="
                + artifact.inputs().size()
        );
        System.out.println(
            "polynomialTheoryUtilityExecutionInputsStatus="
                + artifact.evidenceStatus()
        );
    }

    static PolynomialTheoryUtilityExecutionProfile profile(
        String profileId
    ) {
        return PolynomialTheoryUtilityExecutionPlan.PROFILES.stream()
            .filter(value -> value.profileId().equals(profileId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "unknown frozen polynomial utility profile: " + profileId
            ));
    }

    private static void requireBindings(
        PolynomialTheoryUtilityExecutionArtifact plan,
        PolynomialTheoryUtilityCaseCorpus.FormationArtifact formation
    ) {
        if (!PolynomialTheoryUtilityExecutionPlan.EXPECTED_CONTENT_HASH.equals(
                plan.contentHash())
                || PolynomialTheoryUtilityExecutionPlan.EXPECTED_BYTE_LENGTH
                    != plan.byteLength()
                || plan.rows().size() != EXPECTED_INPUT_COUNT
                || !PolynomialTheoryUtilityCaseCorpus.FORMATION_CONTENT_HASH
                    .equals(formation.contentHash())
                || PolynomialTheoryUtilityCaseCorpus.FORMATION_BYTE_LENGTH
                    != formation.byteLength()
                || !PolynomialTheoryUtilityCaseCorpus
                    .QUALIFICATION_CONTENT_HASH.equals(
                        formation.qualificationBinding().contentHash())
                || PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_BYTE_LENGTH
                    != formation.qualificationBinding().byteLength()) {
            throw new IllegalStateException(
                "execution input binding differs from frozen contracts"
            );
        }
    }

    private static void requireInputs(
        List<PolynomialTheoryUtilityExecutionRow> rows,
        List<PolynomialTheoryUtilityExecutionInput> inputs
    ) {
        if (inputs.size() != rows.size()
                || inputs.size() != EXPECTED_INPUT_COUNT) {
            throw new IllegalStateException(
                "execution input count differs from frozen plan");
        }
        Set<String> identities = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var input = inputs.get(index);
            var profile = profile(row.profileId());
            if (!identities.add(input.inputId())
                    || !row.rowId().equals(input.rowId())
                    || !row.runId().equals(input.runId())
                    || !row.caseId().equals(input.caseId())
                    || !row.profileId().equals(input.profileId())
                    || !row.checkpointId().equals(input.checkpointId())
                    || !profile.adapterId().equals(input.adapterId())
                    || row.admittedPrimitiveWork()
                        != input.admittedPrimitiveWork()
                    || row.totalMechanicalWork()
                        != input.totalMechanicalWork()
                    || row.factorizationWork()
                        != input.factorizationWork()
                    || !INPUT_STATUS.equals(input.inputStatus())
                    || !PolynomialTheoryUtilityExecutionInputIdentity.inputId(
                        row,
                        profile
                    ).equals(input.inputId())) {
                throw new IllegalStateException(
                    "execution input differs from plan row " + index
                );
            }
        }
    }
}
