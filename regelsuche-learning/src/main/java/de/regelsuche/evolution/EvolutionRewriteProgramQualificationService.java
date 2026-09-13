package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramQualificationAssessment.GateStatus;
import de.regelsuche.evolution.EvolutionRewriteProgramQualificationAssessment.GeneAssessment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Explicit downstream assessment using existing native gates; never a runtime authorization or promotion call. */
public final class EvolutionRewriteProgramQualificationService {
    public EvolutionRewriteProgramQualificationAssessment assess(EvolutionRewriteProgramFinalTestPlan expectedPlan,
        FileEvolutionRewriteProgramFinalTestAttemptStore store, String repositoryRevision) throws IOException {
        LearnedPatternAuthorizationJson.requireRevision(repositoryRevision, "repositoryRevision");
        var evaluation = Objects.requireNonNull(store, "store").readEvaluation(expectedPlan);
        return EvolutionRewriteProgramQualificationAssessment.create(repositoryRevision, evaluation);
    }

    /** Replay mathematics and anchor the handoff to the independently expected revision and durable final evidence. */
    public EvolutionRewriteProgramQualificationAssessment verifyAssessment(String json,
        EvolutionRewriteProgramFinalTestPlan expectedPlan, FileEvolutionRewriteProgramFinalTestAttemptStore store,
        String repositoryRevision) throws IOException {
        LearnedPatternAuthorizationJson.requireRevision(repositoryRevision, "repositoryRevision");
        var finalTest = Objects.requireNonNull(store, "store").readEvaluation(expectedPlan);
        var assessed = EvolutionRewriteProgramQualificationAssessment.fromCanonicalJson(json);
        if (!assessed.repositoryRevision().equals(repositoryRevision) || !assessed.finalTest().equals(finalTest)) {
            throw new IllegalArgumentException("qualification handoff differs from expected revision or durable FINAL TEST evidence");
        }
        return assessed;
    }

    static NativeGates evaluateNative(EvolutionRewriteProgramFinalTestEvaluation evaluation, String revision) {
        LearnedPatternAuthorizationJson.requireRevision(revision, "repositoryRevision");
        var candidate = evaluation.plan().selectedConfiguration().candidate();
        var genome = candidate.genome();
        Set<String> programGenes = Set.copyOf(candidate.plan().referencedGeneIds());
        var genes = genome.rewrites().stream().sorted(Comparator.comparing(EvolutionGenome.RewriteGene::geneId)).toList();
        if (!evaluation.qualificationEligible()) {
            return notEvaluated(null, genes, programGenes, "FINAL_TEST_NOT_QUALIFIED");
        }
        var preflight = new EvolutionGenomeValidator().validate(genome);
        if (!preflight.accepted()) { return notEvaluated(preflight, genes, programGenes, "PREFLIGHT_REJECTED"); }
        var authorizer = new LearnedPatternRuleAuthorizationService();
        var verifier = new ExactPolynomialPatternIdentityVerifier();
        List<GeneAssessment> assessed = new ArrayList<>();
        // The native paired evaluator executes all flat genome sources, as well as the program's referenced sources.
        for (var gene : genes) {
            boolean referenced = programGenes.contains(gene.geneId());
            if (!gene.assumptions().isEmpty()) {
                assessed.add(unevaluated(gene, referenced, "CONDITIONAL_GENE_UNSUPPORTED"));
                continue;
            }
            ExactPolynomialPatternIdentityVerifier.Verification proof = null;
            LearnedPatternCounterexampleEvidence counterexample = null;
            GateStatus proofStatus = GateStatus.NOT_EVALUATED;
            GateStatus counterexampleStatus = GateStatus.NOT_EVALUATED;
            List<String> blockers = new ArrayList<>();
            try {
                proof = verifier.verify(EvolutionGenomeCompiler.parsePattern(gene.sourcePattern()),
                    EvolutionGenomeCompiler.parsePattern(gene.targetPattern()));
                proofStatus = proof.proved() ? GateStatus.PASSED : GateStatus.REJECTED;
                if (!proof.proved()) { blockers.add("PROOF:" + proof.detailCode()); }
            } catch (RuntimeException exception) {
                blockers.add("PROOF_FAILED:" + exception.getClass().getSimpleName());
            }
            try {
                counterexample = authorizer.evaluateCounterexamples(genome, gene.geneId(), revision);
                try {
                    authorizer.requireQualifiedCounterexamples(genome, gene.geneId(), revision, counterexample);
                    counterexampleStatus = GateStatus.PASSED;
                } catch (IllegalArgumentException exception) {
                    counterexampleStatus = GateStatus.REJECTED;
                    blockers.add("COUNTEREXAMPLE_NOT_QUALIFIED:" + counterexample.status());
                }
            } catch (RuntimeException exception) {
                blockers.add("COUNTEREXAMPLE_FAILED:" + exception.getClass().getSimpleName());
            }
            assessed.add(new GeneAssessment(gene.geneId(), referenced, proof, counterexample, proofStatus,
                counterexampleStatus, blockers.stream().sorted().toList()));
        }
        var blockers = assessed.stream().flatMap(item -> item.blockers().stream().map(value -> item.geneId() + ":" + value))
            .sorted().toList();
        return new NativeGates(preflight, List.copyOf(assessed), GateStatus.PASSED,
            aggregate(assessed.stream().map(GeneAssessment::proofStatus).toList()),
            aggregate(assessed.stream().map(GeneAssessment::counterexampleStatus).toList()), blockers);
    }

    private static NativeGates notEvaluated(EvolutionGenomeValidator.ValidationReport preflight,
        List<EvolutionGenome.RewriteGene> genes, Set<String> programGenes, String reason) {
        return new NativeGates(preflight, genes.stream().map(gene ->
            unevaluated(gene, programGenes.contains(gene.geneId()), reason)).toList(),
            preflight == null ? GateStatus.NOT_EVALUATED : GateStatus.REJECTED,
            GateStatus.NOT_EVALUATED, GateStatus.NOT_EVALUATED, List.of(reason));
    }

    private static GeneAssessment unevaluated(EvolutionGenome.RewriteGene gene, boolean referenced, String reason) {
        return new GeneAssessment(gene.geneId(), referenced, null, null,
            GateStatus.NOT_EVALUATED, GateStatus.NOT_EVALUATED, List.of(reason));
    }

    private static GateStatus aggregate(List<GateStatus> statuses) {
        if (statuses.contains(GateStatus.REJECTED)) { return GateStatus.REJECTED; }
        if (statuses.isEmpty() || statuses.contains(GateStatus.NOT_EVALUATED)) { return GateStatus.NOT_EVALUATED; }
        return GateStatus.PASSED;
    }

    record NativeGates(EvolutionGenomeValidator.ValidationReport preflight, List<GeneAssessment> genes,
        GateStatus preflightStatus, GateStatus proofStatus, GateStatus counterexampleStatus, List<String> blockers) { }
}
