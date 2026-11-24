package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.sha256;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.JSON;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.canonical;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole;
import de.regelsuche.discovery.representation.RepresentationDiscoveryInformationBoundary.Track;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunOutcome.TerminalState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class ProspectiveFormationArchiveTest {
    private static final String SCHEMA = "prospective-all-candidate-freeze/v1";
    private static final String SOURCE_ID = "p750-f06096a9325a4c68";
    private static final String ROW_PATH = "rows/" + SOURCE_ID + "-SCALAR-CATALOG_BLIND.json.gz";
    private static final String CANDIDATE = sha256("candidate");
    private static final String LABEL = sha256("label");
    private static final String STATE = sha256("state");
    private static final String EDGE = sha256("edge");

    @TempDir
    Path directory;

    @Test
    void retrievesRankAndOccurrenceWithExistingWorkspaceIdentity() throws IOException {
        Fixture fixture = archive(artifacts -> { }, bundle -> { });
        var run = fixture.archive().read(fixture.runId());
        assertEquals(List.of(fixture.runId()), fixture.archive().runIds());
        assertEquals(fixture.runId(), run.workspace().runId());
        assertEquals(List.of(CANDIDATE), run.rankings().get("SCALAR"));
        assertEquals("/0/1", run.selections().get(CANDIDATE).occurrencePath());
        assertEquals(EDGE, run.selections().get(CANDIDATE).edgeId());
        assertEquals(fixture.runId(), run.selections().get(CANDIDATE).runId());
        assertTrue(run.artifactJson(ArtifactRole.RULE_RADAR).contains(CANDIDATE));
        assertTrue(run.artifactJson(ArtifactRole.PATH_REPLAY).contains(EDGE));
    }

    @Test
    void rejectsUnpinnedManifestUnknownRunAndChangedRowBytes() throws IOException {
        Fixture fixture = archive(artifacts -> { }, bundle -> { });
        assertRejected("unexpected historical manifest bytes", () -> ProspectiveFormationArchive.open(directory));
        assertRejected("absent from the historical archive", () -> fixture.archive().read(sha256("unknown")));

        Path row = directory.resolve(ROW_PATH);
        byte[] bytes = Files.readAllBytes(row);
        bytes[bytes.length - 1] ^= 1;
        Files.write(row, bytes);
        assertRejected("row bytes differ", () -> fixture.archive().read(fixture.runId()));
    }

    @Test
    void rejectsForeignRankEvenWhenWorkspaceAndManifestHaveBeenRehashed() throws IOException {
        Fixture fixture = archive(artifacts -> ((ObjectNode) artifacts.path("radar").path("rankings"))
            .putArray("SCALAR").add(sha256("foreign candidate")), bundle -> { });
        assertRejected("not a permutation", () -> fixture.archive().read(fixture.runId()));
    }

    @Test
    void rejectsChangedOccurrenceWithRehashedManifest() throws IOException {
        Fixture fixture = archive(artifacts -> { },
            bundle -> ((ObjectNode) bundle.path("artifacts").path("selections").get(0)).put("occurrencePath", "/99"));
        assertRejected("selection disagrees with its workspace or dossier", () -> fixture.archive().read(fixture.runId()));
    }

    @Test
    void rejectsAClaimThatQualificationWasUsedDuringFormation() throws IOException {
        Fixture fixture = archive(artifacts -> { },
            bundle -> ((ObjectNode) bundle.path("formation")).put("qualificationOrReferenceUsed", true));
        assertRejected("outside the source-only information boundary", () -> fixture.archive().read(fixture.runId()));
    }

    @Test
    void rejectsADifferentSourceWithRehashedManifest() throws IOException {
        Fixture fixture = archive(artifacts -> { },
            bundle -> ((ObjectNode) bundle.path("formation")).put("source", "y + 0"));
        assertRejected("workspace input differs from the frozen source", () -> fixture.archive().read(fixture.runId()));
    }

    private Fixture archive(Consumer<ObjectNode> artifactChange, Consumer<ObjectNode> bundleChange) throws IOException {
        ObjectNode artifacts = JSON.createObjectNode();
        ObjectNode graph = artifacts.putObject("searchGraph");
        graph.putArray("states").addObject().put("labelId", LABEL).put("stateId", STATE);
        var transitions = graph.putArray("transitions");
        transitions.addObject().put("edgeId", EDGE).putArray("occurrencePath").add(0).add(1);
        ObjectNode candidates = artifacts.putObject("candidates");
        candidates.putArray("expressions").addObject().put("candidateId", CANDIDATE)
            .put("incomingEdgeId", EDGE).put("labelId", LABEL).put("stateId", STATE)
            .put("primitiveProofStatus", "CHECKED_PRIMITIVE_UNCONDITIONAL").putArray("usedAssumptions");
        candidates.putArray("sharing");
        var dossier = artifacts.putArray("dossiers").addObject();
        dossier.put("candidateId", CANDIDATE).put("sourceId", SOURCE_ID).put("graphEdgeId", EDGE)
            .put("labelId", LABEL).put("stateId", STATE)
            .put("proofRootHash", sha256(canonical(transitions)));
        dossier.set("proofRoot", transitions);
        dossier.set("primitiveLineage", transitions);
        dossier.putArray("assumptions");
        dossier.putArray("sourceOccurrence").add(0).add(1);
        artifacts.putObject("replay").set("transitions", transitions);
        var rankings = artifacts.putObject("radar").putObject("rankings");
        for (String ranking : List.of("CAPABILITY_ONLY_PUBLIC_VISIBILITY", "CHECKED_REUSE", "COMPRESSION_ONLY",
            "PARETO_DIVERSITY", "SCALAR", "STRUCTURAL_SURPRISE")) {
            rankings.putArray(ranking).add(CANDIDATE);
        }
        artifacts.putArray("obligations");
        artifacts.putObject("work").put("nativeEngineCalls", 1);
        artifactChange.accept(artifacts);

        var workspace = workspace(artifacts);
        artifacts.putArray("selections").add(JSON.valueToTree(workspace.selection(
            CANDIDATE, STATE, EDGE, "/0/1", sha256(canonical(transitions)))));
        ObjectNode bundle = JSON.createObjectNode();
        bundle.set("workspace", JSON.readTree(workspace.toCanonicalJson()));
        bundle.set("artifacts", artifacts);
        String formationHash = sha256("formation");
        var formation = bundle.putObject("formation").put("contentHash", formationHash).put("sourceId", SOURCE_ID)
            .put("policy", "SCALAR").put("visibility", "CATALOG_BLIND").put("source", "x + 0")
            .put("qualificationDisclosure", "NOT_DISCLOSED").put("qualificationOrReferenceUsed", false);
        formation.putArray("initialAssumptions");
        formation.set("rankings", rankings);
        bundleChange.accept(bundle);

        Path row = directory.resolve(ROW_PATH);
        Files.createDirectories(row.getParent());
        try (var gzip = new GZIPOutputStream(Files.newOutputStream(row))) {
            gzip.write(canonical(bundle).getBytes(StandardCharsets.UTF_8));
        }
        byte[] rowBytes = Files.readAllBytes(row);
        var manifest = JSON.createObjectNode().put("schemaVersion", SCHEMA)
            .put("qualificationDisclosure", "NOT_DISCLOSED").put("rowCount", 1);
        manifest.putArray("rows").addObject().put("path", ROW_PATH)
            .put("workspaceId", workspace.runId()).put("byteLength", rowBytes.length)
            .put("sha256", TargetFreeRepresentationEvaluationPlan.sha256(rowBytes))
            .put("formationHash", formationHash).put("sourceId", SOURCE_ID)
            .put("policy", "SCALAR").put("visibility", "CATALOG_BLIND");
        String json = canonical(manifest);
        Files.writeString(directory.resolve("manifest.json"), json);
        return new Fixture(new ProspectiveFormationArchive(directory, sha256(json), 1), workspace.runId());
    }

    private static RepresentationDiscoveryRunWorkspace workspace(ObjectNode artifacts) {
        Map<ArtifactRole, String> keys = Map.of(
            ArtifactRole.SEARCH_GRAPH, "searchGraph",
            ArtifactRole.REPRESENTATION_CANDIDATES, "candidates",
            ArtifactRole.CANDIDATE_DOSSIERS, "dossiers",
            ArtifactRole.PATH_REPLAY, "replay",
            ArtifactRole.RULE_RADAR, "radar",
            ArtifactRole.PROOF_OBLIGATIONS, "obligations",
            ArtifactRole.EXPORT_BUNDLE, "dossiers",
            ArtifactRole.PROGRESS_LEDGER, "work");
        var references = Arrays.stream(ArtifactRole.values()).map(role -> RepresentationDiscoveryArtifactReference.available(
            role, SCHEMA + "/" + keys.get(role), sha256(canonical(artifacts.get(keys.get(role)))))).toList();
        return RepresentationDiscoveryRunWorkspace.create(
            RepresentationDiscoveryRunInput.expression("x + 0", List.of()),
            RepresentationDiscoveryRunPlan.create(Track.R2_CATALOG_BLIND_POST_HOC_BRIDGE, sha256("boundary"),
                sha256("inventory"), sha256("selection"), sha256("catalog"), "SCALAR", "profile", "objective",
                sha256("budget"), 1, List.of("backend")),
            RepresentationDiscoveryRunOutcome.create(TerminalState.COMPLETED, "exhausted", 1, 1,
                sha256(canonical(artifacts.path("work"))), sha256("no runtime")),
            references,
            RepresentationDiscoveryRevisionEvidence.create("0123456789012345678901234567890123456789", SCHEMA));
    }

    private static void assertRejected(String reason, Executable attempt) {
        var exception = assertThrows(IllegalArgumentException.class, attempt);
        assertTrue(exception.getMessage().contains(reason), exception::getMessage);
    }

    private record Fixture(ProspectiveFormationArchive archive, String runId) {
    }
}
