package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.sha256;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.JSON;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.canonical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Read-only workspace access to the committed November 2026 source-only formation freeze. */
public final class ProspectiveFormationArchive {
    private static final String SCHEMA = "prospective-all-candidate-freeze/v1";
    private static final String MANIFEST_FILE_SHA256 =
        "sha256:2a5878a372c3a3a6c5aedb8f1e3fa033fc6861fb59ba260080ce93e1ab691711";
    private static final int ROW_COUNT = 224;
    private static final int MAX_COMPRESSED_BYTES = 512 * 1024;
    private static final int MAX_ROW_BYTES = 4 * 1024 * 1024;
    private static final Pattern ROW_PATH = Pattern.compile("rows/p750-[0-9a-f]{16}-[A-Z_]+-CATALOG_(BLIND|VISIBLE)\\.json\\.gz");
    private static final Map<ArtifactRole, String> ARTIFACT_KEYS = Map.of(
        ArtifactRole.SEARCH_GRAPH, "searchGraph",
        ArtifactRole.REPRESENTATION_CANDIDATES, "candidates",
        ArtifactRole.CANDIDATE_DOSSIERS, "dossiers",
        ArtifactRole.PATH_REPLAY, "replay",
        ArtifactRole.RULE_RADAR, "radar",
        ArtifactRole.PROOF_OBLIGATIONS, "obligations",
        ArtifactRole.EXPORT_BUNDLE, "dossiers",
        ArtifactRole.PROGRESS_LEDGER, "work"
    );
    private static final Set<String> RANKINGS = Set.of(
        "CAPABILITY_ONLY_PUBLIC_VISIBILITY", "CHECKED_REUSE", "COMPRESSION_ONLY",
        "PARETO_DIVERSITY", "SCALAR", "STRUCTURAL_SURPRISE"
    );

    private final Path root;
    private final Map<String, Row> rowsByRunId;

    /** Opens the published historical freeze only. Caller-supplied manifests cannot establish authority. */
    public static ProspectiveFormationArchive open(Path freezeDirectory) throws IOException {
        return new ProspectiveFormationArchive(freezeDirectory, MANIFEST_FILE_SHA256, ROW_COUNT);
    }

    ProspectiveFormationArchive(Path freezeDirectory, String expectedHash, int expectedRows) throws IOException {
        root = freezeDirectory.toAbsolutePath().normalize();
        byte[] bytes = readBoundedFile(root.resolve("manifest.json"), MAX_COMPRESSED_BYTES);
        require(TargetFreeRepresentationEvaluationPlan.sha256(bytes).equals(expectedHash), "unexpected historical manifest bytes");
        JsonNode manifest = JSON.readTree(bytes);
        require(SCHEMA.equals(text(manifest, "schemaVersion")) && "NOT_DISCLOSED".equals(text(manifest, "qualificationDisclosure")),
            "expected a source-only formation manifest");
        Map<String, Row> bindings = new LinkedHashMap<>();
        Set<String> paths = new HashSet<>();
        for (JsonNode entry : array(manifest.required("rows"))) {
            String path = text(entry, "path");
            require(ROW_PATH.matcher(path).matches() && paths.add(path), "invalid or duplicate historical row path");
            Row row = new Row(path, digest(entry, "workspaceId"), digest(entry, "sha256"),
                entry.required("byteLength").asInt(-1), digest(entry, "formationHash"),
                text(entry, "sourceId"), text(entry, "policy"), text(entry, "visibility"));
            require(row.byteLength() > 0 && row.byteLength() <= MAX_COMPRESSED_BYTES, "invalid compressed row length");
            require(bindings.putIfAbsent(row.runId(), row) == null, "duplicate historical Run ID");
        }
        require(bindings.size() == expectedRows && manifest.required("rowCount").asInt(-1) == expectedRows,
            "incomplete historical row index");
        rowsByRunId = Collections.unmodifiableMap(bindings);
    }

    public List<String> runIds() {
        return List.copyOf(rowsByRunId.keySet());
    }

    /** Reads one committed row and independently checks its workspace artifact and selection bindings. */
    public ArchivedRun read(String runId) throws IOException {
        Row binding = rowsByRunId.get(requireSha256(runId, "runId"));
        require(binding != null, "Run ID is absent from the historical archive");
        require(Files.isDirectory(root.resolve("rows"), LinkOption.NOFOLLOW_LINKS), "historical rows directory is missing");
        byte[] compressed = readBoundedFile(root.resolve(binding.path()), binding.byteLength());
        require(compressed.length == binding.byteLength()
                && TargetFreeRepresentationEvaluationPlan.sha256(compressed).equals(binding.sha256()),
            "historical row bytes differ from the manifest");
        byte[] bytes;
        try (var input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            bytes = input.readNBytes(MAX_ROW_BYTES + 1);
        }
        require(bytes.length <= MAX_ROW_BYTES, "historical row exceeds the decoded size limit");
        return decode(binding, JSON.readTree(bytes));
    }

    private static ArchivedRun decode(Row binding, JsonNode bundle) {
        var workspace = RepresentationDiscoveryRunWorkspace.fromCanonicalJson(canonical(bundle.required("workspace")));
        var formation = bundle.required("formation");
        var artifacts = bundle.required("artifacts");
        require(workspace.runId().equals(binding.runId()) && binding.formationHash().equals(digest(formation, "contentHash"))
                && binding.sourceId().equals(text(formation, "sourceId")) && binding.policy().equals(text(formation, "policy"))
                && binding.visibility().equals(text(formation, "visibility")),
            "historical row does not bind its indexed workspace and configuration");
        require("NOT_DISCLOSED".equals(text(formation, "qualificationDisclosure"))
                && formation.required("qualificationOrReferenceUsed").isBoolean()
                && !formation.required("qualificationOrReferenceUsed").booleanValue(),
            "formation is outside the source-only information boundary");
        List<String> assumptions = array(formation.required("initialAssumptions")).valueStream()
            .map(JsonNode::textValue).toList();
        require(workspace.input().equals(RepresentationDiscoveryRunInput.expression(text(formation, "source"), assumptions)),
            "historical workspace input differs from the frozen source");
        for (var entry : ARTIFACT_KEYS.entrySet()) {
            String key = entry.getValue();
            var reference = workspace.requireArtifact(entry.getKey(), SCHEMA + "/" + key);
            require(reference.targetContentHash().equals(sha256(canonical(artifacts.required(key)))),
                "historical workspace artifact hash mismatch for " + entry.getKey());
        }
        require(artifacts.required("radar").required("rankings").equals(formation.required("rankings")),
            "archived radar ranks differ from the frozen formation ranks");

        JsonNode graph = artifacts.required("searchGraph");
        Map<String, JsonNode> states = index(graph.required("states"), "labelId");
        Map<String, JsonNode> edges = index(graph.required("transitions"), "edgeId");
        Map<String, JsonNode> candidates = index(artifacts.required("candidates").required("expressions"), "candidateId");
        Map<String, JsonNode> dossiers = index(artifacts.required("dossiers"), "candidateId");
        Map<String, JsonNode> selectionNodes = index(artifacts.required("selections"), "candidateId");
        require(dossiers.keySet().equals(candidates.keySet()) && selectionNodes.keySet().equals(candidates.keySet()),
            "archived candidates, dossiers and selections cover different candidate sets");
        Map<String, RepresentationDiscoveryRunSelection> selections = new LinkedHashMap<>();
        for (var entry : dossiers.entrySet()) {
            String id = entry.getKey();
            JsonNode dossier = entry.getValue();
            JsonNode candidate = candidates.get(id);
            String state = digest(dossier, "stateId");
            String edge = digest(dossier, "graphEdgeId");
            String proof = digest(dossier, "proofRootHash");
            String labelId = digest(candidate, "labelId");
            JsonNode graphState = states.get(labelId);
            require(binding.sourceId().equals(text(dossier, "sourceId"))
                    && state.equals(digest(candidate, "stateId")) && edge.equals(digest(candidate, "incomingEdgeId"))
                    && labelId.equals(digest(dossier, "labelId")) && graphState != null
                    && state.equals(digest(graphState, "stateId"))
                    && dossier.required("assumptions").equals(candidate.required("usedAssumptions"))
                    && proof.equals(sha256(canonical(dossier.required("proofRoot")))),
                "archived candidate disagrees with its dossier state, edge, premises or proof root");
            verifyLineage(candidate, dossier, edges, artifacts.required("replay"));
            var selection = workspace.selection(id, state, edge, occurrence(dossier.required("sourceOccurrence")), proof);
            require(selectionNodes.get(id).equals(JSON.valueToTree(selection)),
                "archived selection disagrees with its workspace or dossier");
            selections.put(id, selection);
        }

        Map<String, List<String>> rankings = verifiedRankings(artifacts.required("radar").required("rankings"), candidates.keySet());
        return new ArchivedRun(workspace, artifacts, selections, rankings);
    }

    private static void verifyLineage(JsonNode candidate, JsonNode dossier, Map<String, JsonNode> edges, JsonNode replay) {
        var lineage = array(dossier.required("primitiveLineage"));
        var proof = dossier.required("proofRoot");
        if ("CHECKED_EGRAPH_EXTRACTION".equals(text(candidate, "primitiveProofStatus"))) {
            require(lineage.isEmpty() && proof.equals(replay.required("egraphProof"))
                    && digest(dossier, "graphEdgeId").equals(digest(dossier, "proofRootHash")),
                "archived e-graph candidate does not bind its detached proof");
            return;
        }
        require(!lineage.isEmpty() && lineage.equals(proof)
                && digest(lineage.get(lineage.size() - 1), "edgeId").equals(digest(dossier, "graphEdgeId"))
                && lineage.get(0).required("occurrencePath").equals(dossier.required("sourceOccurrence")),
            "archived candidate does not bind its primitive proof and source occurrence");
        for (JsonNode edge : lineage) {
            require(edge.equals(edges.get(digest(edge, "edgeId"))), "archived lineage has an edge outside its run graph");
        }
    }

    private static Map<String, List<String>> verifiedRankings(JsonNode ranks, Set<String> candidateIds) {
        Map<String, List<String>> orderings = new LinkedHashMap<>();
        require(ranks.isObject(), "historical ranking map is missing");
        for (var entry : ranks.properties()) {
            List<String> ids = array(entry.getValue()).valueStream()
                .map(id -> requireSha256(id.textValue(), "ranked candidate id")).toList();
            require(ids.size() == candidateIds.size() && new HashSet<>(ids).equals(candidateIds),
                "historical ranking is not a permutation of the retained candidate IDs");
            orderings.put(entry.getKey(), ids);
        }
        require(orderings.keySet().equals(RANKINGS), "historical ranking set is incomplete");
        return Map.copyOf(orderings);
    }

    private static String occurrence(JsonNode path) {
        var occurrence = new StringJoiner("/", "/", "");
        for (JsonNode index : array(path)) {
            require(index.isIntegralNumber() && index.canConvertToInt() && index.intValue() >= 0, "invalid occurrence index");
            occurrence.add(index.asText());
        }
        return occurrence.toString();
    }

    private static Map<String, JsonNode> index(JsonNode values, String key) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode value : array(values)) {
            require(result.putIfAbsent(digest(value, key), value) == null, "duplicate historical " + key);
        }
        return result;
    }

    private static byte[] readBoundedFile(Path path, int limit) throws IOException {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "historical evidence file is missing: " + path.getFileName());
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            require(bytes.length <= limit, "historical evidence exceeds its size limit: " + path.getFileName());
            return bytes;
        }
    }

    private static ArrayNode array(JsonNode value) {
        require(value.isArray(), "historical evidence array is missing");
        return (ArrayNode) value;
    }

    private static String digest(JsonNode value, String key) {
        return requireSha256(text(value, key), key);
    }

    private static String text(JsonNode value, String key) {
        JsonNode field = value.required(key);
        require(field.isTextual() && !field.textValue().isBlank(), "invalid historical field " + key);
        return field.textValue();
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalArgumentException(detail);
        }
    }

    private record Row(String path, String runId, String sha256, int byteLength, String formationHash,
                       String sourceId, String policy, String visibility) {
    }

    /** A verified run with the original, content-addressed workspace and source-only ranks. */
    public static final class ArchivedRun {
        private final RepresentationDiscoveryRunWorkspace workspace;
        private final JsonNode artifacts;
        private final Map<String, RepresentationDiscoveryRunSelection> selections;
        private final Map<String, List<String>> rankings;

        private ArchivedRun(RepresentationDiscoveryRunWorkspace workspace, JsonNode artifacts,
                            Map<String, RepresentationDiscoveryRunSelection> selections, Map<String, List<String>> rankings) {
            this.workspace = workspace;
            this.artifacts = artifacts;
            this.selections = Map.copyOf(selections);
            this.rankings = rankings;
        }

        public RepresentationDiscoveryRunWorkspace workspace() {
            return workspace;
        }

        public String artifactJson(ArtifactRole role) {
            return canonical(artifacts.required(ARTIFACT_KEYS.get(role)));
        }

        public Map<String, RepresentationDiscoveryRunSelection> selections() {
            return selections;
        }

        public Map<String, List<String>> rankings() {
            return rankings;
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && "verify".equals(args[0])) {
            var archive = open(Path.of(args[1]));
            int candidates = 0;
            int emptyRows = 0;
            for (String runId : archive.runIds()) {
                int retained = archive.read(runId).selections().size();
                candidates += retained;
                if (retained == 0) {
                    emptyRows++;
                }
            }
            System.out.printf("verifiedWorkspaces=%d retainedCandidates=%d emptyCandidateRows=%d%n",
                archive.runIds().size(), candidates, emptyRows);
        } else if (args.length == 3 && "ranks".equals(args[0])) {
            var run = open(Path.of(args[1])).read(args[2]);
            var output = JSON.createObjectNode();
            output.put("runId", run.workspace().runId());
            output.set("rankings", JSON.valueToTree(run.rankings()));
            output.set("selections", JSON.valueToTree(run.selections()));
            System.out.println(canonical(output));
        } else {
            throw new IllegalArgumentException("usage: verify <freeze-directory> | ranks <freeze-directory> <run-id>");
        }
    }
}
