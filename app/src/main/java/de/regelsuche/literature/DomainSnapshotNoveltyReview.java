package de.regelsuche.literature;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.discovery.domain.DomainDiscoveryExportVerifier;
import de.regelsuche.discovery.domain.DomainExportWorkspace;
import de.regelsuche.discovery.domain.DomainSnapshotAssessment;
import de.regelsuche.literature.ExternalNoveltyReview.Decision;
import de.regelsuche.literature.ExternalNoveltyReview.Reviewer;
import de.regelsuche.literature.LiteratureReviewRepository.Subject;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Receiver-side composition of a verified scalar domain claim and independently signed, frozen literature review. */
public final class DomainSnapshotNoveltyReview {
    private static final String SCHEMA = "regelsuche.domain-snapshot-external-review/v1";
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private DomainSnapshotNoveltyReview() { }

    public record FrozenSubject(Subject subject, String equation, List<String> assumptions, String formationEvidenceRoot) {
        public FrozenSubject { assumptions = List.copyOf(assumptions); }
    }

    public record Receipt(String schema, Subject subject, String formationEvidenceRoot, String workspaceHash,
            String equation, List<String> assumptions, String freezeRoot, String catalogueRoot, String packetRoot,
            String intakeRoot, String reviewerRosterRoot, Decision decision, String promotionStatus, String publicEvidenceStatus) { }

    public static FrozenSubject subject(DomainExportWorkspace workspace) throws IOException {
        var checked = DomainSnapshotAssessment.assess(workspace);
        var result = JSON.readTree(checked.toCanonicalJson());
        if (!"PROVED_POLYNOMIAL_IDENTITY".equals(result.at("/proof/status").asText())) {
            throw new IllegalArgumentException("scalar review requires an independently checked polynomial identity");
        }
        var proof = result.required("proof");
        var subject = new Subject(workspace.runId(),workspace.evidence().selectedCandidateHash(),workspace.evidence().contentHash());
        var assumptions = new ArrayList<String>();
        assumptions.add(proof.required("scope").textValue());
        for (var assumption : proof.required("rewriteAssumptions")) assumptions.add(assumption.textValue());
        return new FrozenSubject(subject, proof.required("originalSource").textValue() + " = " + proof.required("requestedTarget").textValue(),
            assumptions, checked.contentHash());
    }

    public static Receipt verify(DomainExportWorkspace workspace, String expectedFreeze, String expectedCatalogue,
            List<Reviewer> eligible, byte[] encodedRun) throws IOException {
        var source = subject(workspace);
        var roster = List.copyOf(eligible);
        var review = new ExternalNoveltyReview().replay(expectedFreeze,expectedCatalogue,roster,encodedRun);
        var plan = review.packet().plan();
        if (!source.subject().equals(plan.subject()) || !source.equation().equals(plan.equation())
                || !source.formationEvidenceRoot().equals(plan.formationEvidenceRoot()) || !source.assumptions().equals(plan.assumptions())) {
            throw new IllegalArgumentException("frozen review subject, equation, assumptions or formation evidence differs from the checked source");
        }
        var outcome = review.outcome();
        return new Receipt(SCHEMA,source.subject(),source.formationEvidenceRoot(),workspace.contentHash(),source.equation(),
            source.assumptions(),expectedFreeze,expectedCatalogue,outcome.packetRoot(),outcome.intakeRoot(),
            ExternalNoveltyReview.identity("domain-review-receiver-roster/v1",roster),outcome.decision(),"NOT_EVALUATED","NOT_EVALUATED");
    }

    /** All authorities are supplied locally by the receiver, independently of the submitted run. */
    public static int command(String[] args, PrintStream out) {
        boolean inspect = args.length == 2 && args[0].equals("review-subject");
        if (!inspect && !(args.length == 6 && args[0].equals("verify-review"))) {
            out.println("Usage: domains review-subject EXPORT | verify-review EXPORT PINNED_FREEZE PINNED_CATALOGUE TRUSTED_ROSTER.json REVIEW.json");
            return 1;
        }
        try {
            var source = DomainExportWorkspace.fromVerified(new DomainDiscoveryExportVerifier().requireVerified(Path.of(args[1])));
            Object result;
            if (inspect) result = subject(source);
            else {
                List<Reviewer> roster = JSON.readValue(readBounded(Path.of(args[4]), 128 * 1024), new TypeReference<List<Reviewer>>() {});
                result = verify(source,args[2],args[3],roster,readBounded(Path.of(args[5]),1024 * 1024));
            }
            out.println(JSON.writeValueAsString(result));
            return 0;
        } catch (IOException | RuntimeException failure) {
            out.println("Domain review failed: " + failure.getMessage());
            return 1;
        }
    }

    private static byte[] readBounded(Path file, int maxBytes) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("expected a regular review input file");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IOException("review input exceeds its size limit");
            return bytes;
        }
    }
}
