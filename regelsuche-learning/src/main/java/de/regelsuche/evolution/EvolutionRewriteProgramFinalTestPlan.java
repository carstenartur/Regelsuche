package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramValidationPlan.Configuration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact selected genome, program and all work limits, frozen before FINAL TEST reveal. */
public record EvolutionRewriteProgramFinalTestPlan(String schema,
    EvolutionRewriteProgramValidationHandoff validationHandoff,
    EvolutionRewriteProgramHeldOutCommitment commitment,
    List<EvolutionSplitManifest.CaseReference> cases, String contentHash) {
    public static final String SCHEMA = "regelsuche.evolution-rewrite-program-final-test-plan/v1";

    public EvolutionRewriteProgramFinalTestPlan {
        if (!SCHEMA.equals(schema)) { throw new IllegalArgumentException("unsupported program FINAL TEST plan"); }
        Objects.requireNonNull(validationHandoff, "validationHandoff");
        Objects.requireNonNull(commitment, "commitment");
        var validation = validationHandoff.selection().plan();
        if (commitment.split() != Split.FINAL_TEST
                || !commitment.studyId().equals(validation.commitment().studyId())) {
            throw new IllegalArgumentException("FINAL TEST commitment differs from selected study");
        }
        cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        if (!cases.stream().map(EvolutionSplitManifest.CaseReference::caseId).toList().equals(
                commitment.cases().stream().map(EvolutionRewriteProgramHeldOutCommitment.CaseCommitment::caseId).toList())) {
            throw new IllegalArgumentException("FINAL TEST cases differ from committed order");
        }
        for (int index = 0; index < cases.size(); index++) {
            var reference = cases.get(index);
            var committed = commitment.cases().get(index);
            if (!EvolutionRewriteProgramHeldOutCommitment.familyCommitment(reference.familyId())
                    .equals(committed.familyCommitmentHash())
                    || !reference.inputHash().equals(committed.inputHash())
                    || !reference.hiddenTargetHash().equals(committed.targetHash())
                    || !reference.exactSignatureHash().equals(committed.exactSignatureHash())
                    || !reference.alphaSignatureHash().equals(committed.alphaSignatureHash())) {
                throw new IllegalArgumentException("FINAL TEST case material differs from commitment");
            }
        }
        EvolutionProgramValidationJson.requireHash(contentHash, material(validationHandoff, commitment, cases));
    }

    public static EvolutionRewriteProgramFinalTestPlan create(EvolutionRewriteProgramStudyPlan study,
        EvolutionSplitManifest manifest, ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun train,
        EvolutionRewriteProgramValidationHandoff handoff, EvolutionRewriteProgramHeldOutCommitment commitment) {
        Objects.requireNonNull(handoff, "handoff").selection().plan().requireInputs(study, manifest, train);
        Objects.requireNonNull(commitment, "commitment").requireMatches(manifest);
        var cases = manifest.finalTestCases().stream()
            .sorted(Comparator.comparing(EvolutionSplitManifest.CaseReference::caseId)).toList();
        return new EvolutionRewriteProgramFinalTestPlan(SCHEMA, handoff, commitment, cases,
            EvolutionProgramValidationJson.hash(material(handoff, commitment, cases)));
    }

    public void requireInputs(EvolutionRewriteProgramStudyPlan study, EvolutionSplitManifest manifest,
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun train) {
        if (!equals(create(study, manifest, train, validationHandoff, commitment))) {
            throw new IllegalArgumentException("FINAL TEST plan differs from frozen study and retained selection");
        }
    }

    public Configuration selectedConfiguration() { return validationHandoff.selectedConfiguration(); }

    /** Deliberately identical to the genome-only ledger: an adapter change cannot buy another attempt. */
    public String runIdentity() {
        var validation = validationHandoff.selection().plan();
        return EvolutionFinalTestReservation.runIdentity(validation.studyPlanHash(), validation.splitManifestHash());
    }

    public String toCanonicalJson() { return EvolutionProgramValidationJson.write(this); }
    public static EvolutionRewriteProgramFinalTestPlan fromCanonicalJson(String json) {
        return EvolutionProgramValidationJson.read(json, EvolutionRewriteProgramFinalTestPlan.class);
    }

    private static Map<String, Object> material(EvolutionRewriteProgramValidationHandoff handoff,
        EvolutionRewriteProgramHeldOutCommitment commitment, List<EvolutionSplitManifest.CaseReference> cases) {
        return EvolutionProgramValidationJson.material(SCHEMA, "validationHandoff", handoff,
            "commitment", commitment, "cases", cases);
    }
}
