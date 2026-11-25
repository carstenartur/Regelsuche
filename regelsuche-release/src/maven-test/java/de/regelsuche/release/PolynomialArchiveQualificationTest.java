package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Replay the producing revision's public Maven archives with an offline raw verifier. */
@EnabledIfSystemProperty(named = "regelsuche.polynomialArchives.enabled", matches = "true")
class PolynomialArchiveQualificationTest {
    private static final Pattern WORKER_DIGEST = Pattern.compile("([0-9a-f]{64})  /worker/evidence\\.tgz\\n");

    @Test
    void verifiesBothTransportedStudyArchives(@TempDir Path extracted) throws Exception {
        Path source = Path.of("..").toRealPath();
        Path evidence = source.resolve("build/polynomial-archives");
        Path report = evidence.resolve("qualification.json");
        Files.deleteIfExists(report);
        var identity = Files.exists(source.resolve(".git")) ? QualityCollectionCheckout.identity(source)
            : PolynomialArchiveProvenanceMain.verify(source);
        assertEquals(BooleanNode.FALSE, identity.required("trackedChangesPresent"));
        String revision = identity.required("revision").textValue();
        assertEquals(System.getenv("EVIDENCE_REVISION"), revision);
        String tree = identity.required("tree").textValue();

        Path typed = evidence.resolve("typed/regelsuche-release/target/reports/typed-external-polynomial-comparison");
        Path nativeStudy = evidence.resolve("native/regelsuche-release/target/reports/native-polynomial-comparison");
        var witness = ReleaseEvidenceJson.parse(Files.readAllBytes(typed.resolve("typed-source-witness.json")),
            "typed polynomial source witness");
        var proof = PolynomialSourceProof.verify(source, witness, identity);
        assertEquals(BooleanNode.TRUE, proof.required("ancestors").required(PolynomialSourceProof.TYPED));
        String typedDigest = extractVerifiedArchive(typed, extracted.resolve("typed"));
        String nativeDigest = extractVerifiedArchive(nativeStudy, extracted.resolve("native"));

        try (var worker = PolynomialParityQualificationTest.offlineVerifier(source, extracted)) {
            worker.start();
            var result = worker.execInContainer("python", "-m", "external_polynomial_comparison.qualify_archives",
                "/evidence/typed/typed-external-polynomial-comparison", "/evidence/native/native-polynomial-comparison",
                "--revision", revision, "--tree", tree);
            assertEquals(0, result.getExitCode(), result::getStderr);
            var receipt = (ObjectNode) QualityAcceptanceCollection.JSON.readTree(result.getStdout());
            assertEquals("regelsuche.polynomial-archive-qualification/v1", receipt.required("schema").textValue());
            assertEquals(revision, receipt.required("sourceRevision").textValue());
            assertEquals(tree, receipt.required("sourceTree").textValue());
            assertEquals(729, receipt.required("typed").required("rows").intValue());
            assertEquals(5, receipt.required("typed").required("modelHashes").size());
            assertEquals(675, receipt.required("native").required("rows").intValue());
            assertEquals(3, receipt.required("native").required("modelHashes").size());
            assertEquals(9, receipt.required("native").required("sequences").intValue());
            assertEquals(4, receipt.required("rejectedControls").size());
            ((ObjectNode) receipt.required("typed")).put("archiveSha256", typedDigest);
            ((ObjectNode) receipt.required("native")).put("archiveSha256", nativeDigest);
            Files.writeString(report, receipt + "\n");
        }
    }

    private static String extractVerifiedArchive(Path report, Path destination) throws Exception {
        var digest = WORKER_DIGEST.matcher(Files.readString(report.resolve("archive-digest.stdout")));
        assertTrue(digest.matches(), "expected the pinned worker's archive digest");
        Path archive = report.resolve("evidence.tgz");
        FrozenPolynomialStudyContainer.verifyArchiveDigest(archive, digest.group(1));
        String study = report.getFileName().toString();
        FrozenPolynomialStudyContainer.extract(archive, destination, study);
        return digest.group(1);
    }
}
