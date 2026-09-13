package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;

import de.regelsuche.discovery.representation.ReferenceIndependentCandidateValidation.Artifact;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Post-validation diagnostic join. Historical labels cannot enter the validation runner. */
public final class ReferenceIndependentValidationHistoricalComparison {
    private ReferenceIndependentValidationHistoricalComparison() {
    }

    /** The caller supplies historical authority independently of the untrusted artifact bytes. */
    public static Comparison compare(Artifact validation, String historicalJson,
                                     String expectedHistoricalQualificationHash) {
        String expectedHash = requireSha256(
            expectedHistoricalQualificationHash, "expectedHistoricalQualificationHash");
        var historical = QualificationArtifact.fromCanonicalJson(historicalJson);
        if (!historical.contentHash().equals(expectedHash)) {
            throw new IllegalArgumentException("historical comparison content hash differs from expected authority");
        }
        if (!historical.content().candidateFreezeHash().equals(validation.content().candidateFreezeHash())) {
            throw new IllegalArgumentException("historical comparison refers to another candidate freeze");
        }
        List<RowComparison> rows = new ArrayList<>();
        for (int index = 0; index < validation.content().rows().size(); index++) {
            var row = validation.content().rows().get(index);
            var labels = historical.content().rows().get(index);
            if (!row.configurationId().equals(labels.configurationId())
                    || !row.candidateBatchHash().equals(labels.candidateBatchHash())
                    || !row.candidateSetHash().equals(labels.candidateSetHash())
                    || !row.candidateFreezeReceiptHash().equals(labels.candidateFreezeReceiptHash())
                    || !row.formationWorkHash().equals(labels.workLedgerHash())) {
                throw new IllegalArgumentException("historical comparison row authority mismatch");
            }
            Map<String, CandidateQualification> byHash = new HashMap<>();
            for (var label : labels.candidates()) {
                if (byHash.put(label.candidateHash(), label) != null) {
                    throw new IllegalArgumentException("duplicate historical candidate label");
                }
            }
            if (byHash.size() != row.outcomes().size()) {
                throw new IllegalArgumentException("historical and validation candidate sets differ");
            }
            int matched = 0;
            int agreements = 0;
            int formerlyNotRun = 0;
            Map<String, Integer> nonReferenceReasons = new TreeMap<>();
            for (var outcome : row.outcomes()) {
                var label = byHash.remove(outcome.candidate().candidateHash());
                if (label == null || !label.expression().equals(outcome.candidate().expression())
                        || !label.assumptions().equals(outcome.candidate().assumptions())) {
                    throw new IllegalArgumentException("historical candidate identity mismatch");
                }
                if (label.referenceMatched()) {
                    matched++;
                } else {
                    nonReferenceReasons.merge(outcome.terminalReason(), 1, Math::addExact);
                    if (outcome.validation().intrinsicallyVerified()) {
                        agreements++;
                    }
                    if (label.oracleStatus().equals("NOT_RUN_REFERENCE_MISS")) {
                        formerlyNotRun++;
                    }
                }
            }
            rows.add(new RowComparison(row.sequence(), row.configurationId(), row.outcomes().size(),
                matched, row.outcomes().size() - matched, agreements, formerlyNotRun, nonReferenceReasons));
        }
        return new Comparison("reference-independent-candidate-validation-historical-comparison/v1",
            validation.contentHash(), historical.contentHash(), validation.content().candidateFreezeHash(),
            ReferenceIndependentCandidateValidation.EVIDENCE_SCOPE,
            rows.stream().mapToInt(RowComparison::candidates).sum(),
            rows.stream().mapToInt(RowComparison::referenceMatchedCandidates).sum(),
            rows.stream().mapToInt(RowComparison::nonReferenceCandidates).sum(),
            rows.stream().mapToInt(RowComparison::nonReferenceOracleAgreements).sum(),
            rows.stream().mapToInt(RowComparison::nonReferenceHistoricalOracleNotRun).sum(), rows,
            "Historical correspondence is joined only after companion validation. Oracle agreement "
                + "is bounded numeric/quadratic evidence, not salience, utility, formal proof or external novelty. "
                + "Repeated lineages and checkpoints are not independent mathematical tasks.");
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: <validation.json> <historical-qualification.json[.gz]> "
                + "<expected-historical-qualification-hash> <output.json>");
        }
        var validation = Artifact.fromCanonicalJson(
            ReferenceIndependentCandidateValidationRunner.readArtifact(Path.of(args[0])));
        var comparison = compare(validation,
            ReferenceIndependentCandidateValidationRunner.readArtifact(Path.of(args[1])), args[2]);
        ReferenceIndependentCandidateValidationRunner.writeNewOrIdentical(Path.of(args[3]),
            TargetFreeHeldOutMatrixRunner.canonical(comparison));
    }

    public record RowComparison(int sequence, String configurationId, int candidates,
                                int referenceMatchedCandidates, int nonReferenceCandidates,
                                int nonReferenceOracleAgreements, int nonReferenceHistoricalOracleNotRun,
                                Map<String, Integer> nonReferenceTerminalReasons) {
        public RowComparison {
            nonReferenceTerminalReasons = Map.copyOf(nonReferenceTerminalReasons);
        }
    }

    public record Comparison(String schema, String validationHash, String historicalQualificationHash,
                             String candidateFreezeHash, String evidenceScope, int candidates,
                             int referenceMatchedCandidates, int nonReferenceCandidates,
                             int nonReferenceOracleAgreements, int nonReferenceHistoricalOracleNotRun,
                             List<RowComparison> rows, String claimBoundary) {
        public Comparison {
            rows = List.copyOf(rows);
        }
    }
}
