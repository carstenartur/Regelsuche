package de.regelsuche.release;

import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.discovery.domain.DomainDiscoveryExportVerifier;
import de.regelsuche.discovery.domain.DomainExportWorkspace;
import de.regelsuche.discovery.domain.DomainSnapshotAssessment;
import de.regelsuche.discovery.domain.ExpressionRewriteDiscoveryDomain;
import de.regelsuche.discovery.domain.FiniteDifferenceSequenceDomain;
import de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomain;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/** Binds research-record release to independently reproduced assessments, separate from v1 architecture qualification. */
final class DomainSnapshotReleaseReport {
    static final String FILE = "snapshot-product-release.json";
    private static final JsonMapper JSON = new JsonMapper();
    private record Source(String name, String domain) { }
    private static final List<Source> SOURCES = List.of(
        new Source("expression", ExpressionRewriteDiscoveryDomain.DOMAIN_ID),
        new Source("sequence", FiniteDifferenceSequenceDomain.DOMAIN_ID),
        new Source("recurrence", LinearRecurrenceSequenceDomain.DOMAIN_ID));
    private DomainSnapshotReleaseReport() { }

    static String reconstruct(Path root, String architectureHash) throws IOException {
        var report = JSON.createObjectNode().put("schema", "regelsuche.domain-snapshot-product-release/v1")
            .put("architectureRunHash", architectureHash).put("domainCount", SOURCES.size());
        var records = report.putArray("records");
        var first = new LinkedHashMap<String,String>();
        boolean identical = true;
        var verifier = new DomainDiscoveryExportVerifier();
        for (int run = 1; run <= DomainGenericQualificationRunner.CLEAN_RUN_COUNT; run++) {
            Path directory = root.resolve("runs/run-" + run);
            for (var source : SOURCES) {
                var snapshot = verifier.requireVerified(directory.resolve(source.name()));
                if (!source.domain().equals(snapshot.manifest().domainId())) {
                    throw new IllegalArgumentException("Domain differs at retained release input " + source.name());
                }
                DomainGenericQualificationRunner.requireOriginal(directory.resolve("verification-receipts").resolve(source.name() + ".json"),
                    snapshot.verification().toCanonicalJson());
                var assessment = DomainSnapshotAssessment.assess(DomainExportWorkspace.fromVerified(snapshot));
                DomainGenericQualificationRunner.requireOriginal(directory.resolve("assessments").resolve(source.name() + ".json"),
                    assessment.toCanonicalJson());
                var checked = JSON.readTree(assessment.toCanonicalJson());
                var record = records.addObject().put("run", run).put("domainId", source.domain())
                    .put("assessmentHash", assessment.contentHash()).put("architectureRunHash", architectureHash);
                record.set("source", checked.required("source"));
                for (String gate : List.of("validation","proof","lifecycle","externalNovelty","promotion","publicEvidence","release")) {
                    record.put(gate, checked.required(gate).required("status").asText());
                }
                String previous = first.putIfAbsent(source.domain(), assessment.contentHash());
                identical &= (previous == null || previous.equals(assessment.contentHash()))
                    && "REPRODUCIBLE_RESEARCH_RECORD".equals(record.required("release").asText());
            }
        }
        report.put("status",identical ? "IDENTICAL_RESEARCH_RECORDS" : "BLOCKED")
            .put("publicMathematicalClaimAuthorized", false);
        report.put("contentHash",AutonomousResearchBriefV2.hash(JSON.writeValueAsString(report)));
        return JSON.writeValueAsString(report);
    }
}
