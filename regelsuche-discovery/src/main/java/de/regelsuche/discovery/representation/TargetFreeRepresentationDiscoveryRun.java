package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.CANDIDATE_DOSSIERS;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.EXPORT_BUNDLE;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.PATH_REPLAY;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.PROGRESS_LEDGER;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.PROOF_OBLIGATIONS;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.REPRESENTATION_CANDIDATES;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.RULE_RADAR;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.SEARCH_GRAPH;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunOutcome.TerminalState.COMPLETED;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Binds the existing target-free R2 search and post-freeze bridge evidence to
 * one immutable representation-discovery run workspace.
 *
 * <p>The workspace reuses the canonical target-free search artifact as its
 * mathematical-work ledger. Runtime diagnostics remain a separate explicitly
 * unmeasured identity. The resulting run is not proof, novelty, global
 * optimality or a general search-superiority claim.</p>
 */
public final class TargetFreeRepresentationDiscoveryRun {
    public static final String SCHEMA =
        "regelsuche.target-free-representation-discovery-run/v1";
    public static final String SCENARIO_FILE_NAME =
        "target-free-sympy-bridge.json";
    public static final String WORKSPACE_FILE_NAME = "run-workspace.json";

    private static final String SEARCH_PROFILE_ID =
        "SMALL_BOUNDED_ENUMERATION_V1";
    private static final String OBJECTIVE_ID =
        "RAW_SEMANTIC_PARETO_ARCHIVE";
    private static final long DETERMINISTIC_SEED = 0L;
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    private TargetFreeRepresentationDiscoveryRun() {
    }

    public static RunBundle run(String repositoryRevision) {
        String revision = requireRepositoryRevision(repositoryRevision);
        TargetFreeSymPyBridgeDiscoveryScenario.ScenarioArtifact scenario =
            TargetFreeSymPyBridgeDiscoveryScenario.run();
        RepresentationDiscoveryInformationBoundary boundary =
            enabledBoundary();
        validateScenarioBinding(scenario, boundary);

        TargetFreeRepresentationSearch.SearchContent search =
            scenario.content().search();
        String searchHash = scenario.content().searchContentHash();
        RepresentationDiscoveryRunWorkspace workspace =
            RepresentationDiscoveryRunWorkspace.create(
                RepresentationDiscoveryRunInput.expression(
                    search.sourceExpression(),
                    List.of()
                ),
                RepresentationDiscoveryRunPlan.create(
                    boundary.track(),
                    boundary.contentHash(),
                    search.ruleInventoryHash(),
                    boundary.candidateFormationSelectionCommitment(),
                    boundary.postFreezeCatalogCommitment(),
                    TargetFreeRepresentationSearch.SCHEMA,
                    SEARCH_PROFILE_ID,
                    OBJECTIVE_ID,
                    budgetHash(search.budget()),
                    DETERMINISTIC_SEED,
                    List.of("sympy-oracle-validator/v1")
                ),
                RepresentationDiscoveryRunOutcome.create(
                    COMPLETED,
                    terminalReason(search),
                    search.budget().maxGeneratedTransitions(),
                    search.generatedTransitionCount(),
                    searchHash,
                    runtimeDiagnosticsHash()
                ),
                artifactReferences(scenario),
                RepresentationDiscoveryRevisionEvidence.create(
                    revision,
                    SCHEMA
                )
            );
        return new RunBundle(scenario, workspace);
    }

    public static RunBundle write(
        Path directory,
        String repositoryRevision
    ) throws IOException {
        Path root = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath().normalize();
        Files.createDirectories(root);
        RunBundle bundle = run(repositoryRevision);

        String scenarioJson = bundle.scenario().toCanonicalJson() + "\n";
        String workspaceJson = bundle.workspace().toCanonicalJson();
        Path scenarioPath = root.resolve(SCENARIO_FILE_NAME);
        Path workspacePath = root.resolve(WORKSPACE_FILE_NAME);
        AtomicJsonFile.writeUtf8(scenarioPath, scenarioJson);
        AtomicJsonFile.writeUtf8(workspacePath, workspaceJson);
        requireWritten(scenarioPath, scenarioJson);
        requireWritten(workspacePath, workspaceJson);

        RepresentationDiscoveryRunWorkspace decoded =
            RepresentationDiscoveryRunWorkspace.fromCanonicalJson(
                workspaceJson
            );
        if (!bundle.workspace().equals(decoded)) {
            throw new IllegalStateException(
                "retained run workspace changed during canonical round-trip"
            );
        }
        return bundle;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                "usage: <repository-commit> <output-directory>"
            );
        }
        RunBundle bundle = write(Path.of(args[1]), args[0]);
        System.out.println(
            "targetFreeRepresentationRunId=" + bundle.workspace().runId()
        );
        System.out.println(
            "targetFreeRepresentationSearchHash="
                + bundle.scenario().content().searchContentHash()
        );
    }

    private static RepresentationDiscoveryInformationBoundary
            enabledBoundary() {
        KnowledgePackSelection selection =
            KnowledgePackSelection.CORE.enablePack(
                TargetFreeSymPyBridgeDiscoveryScenario.PACK_ID
            );
        return RepresentationDiscoveryInformationBoundary
            .fromKnowledgePacks(
                new KnowledgePackRegistry(),
                RepresentationDiscoveryInformationBoundary.Track
                    .R2_CATALOG_BLIND_POST_HOC_BRIDGE,
                selection,
                Set.of()
            );
    }

    private static void validateScenarioBinding(
        TargetFreeSymPyBridgeDiscoveryScenario.ScenarioArtifact scenario,
        RepresentationDiscoveryInformationBoundary boundary
    ) {
        TargetFreeSymPyBridgeDiscoveryScenario.ScenarioContent content =
            Objects.requireNonNull(scenario, "scenario").content();
        requireEqual(
            boundary.track().id(),
            content.informationTrack(),
            "scenario information track"
        );
        requireEqual(
            boundary.contentHash(),
            content.freeze().enabledBoundaryHash(),
            "scenario information boundary"
        );
        requireEqual(
            boundary.candidateFormationRuleInventoryHash(),
            content.search().ruleInventoryHash(),
            "scenario formation inventory"
        );
        requireEqual(
            boundary.postFreezeCatalogCommitment(),
            content.freeze().enabledCatalogHash(),
            "scenario post-freeze catalog"
        );
        requireEqual(
            content.searchContentHash(),
            KnownStructureCatalog.sha256(json(content.search())),
            "scenario search content"
        );
    }

    private static List<RepresentationDiscoveryArtifactReference>
            artifactReferences(
        TargetFreeSymPyBridgeDiscoveryScenario.ScenarioArtifact scenario
    ) {
        String searchHash = scenario.content().searchContentHash();
        return List.of(
            RepresentationDiscoveryArtifactReference.available(
                SEARCH_GRAPH,
                TargetFreeRepresentationSearch.SCHEMA,
                searchHash
            ),
            RepresentationDiscoveryArtifactReference.available(
                REPRESENTATION_CANDIDATES,
                TargetFreeRepresentationSearch.SCHEMA,
                searchHash
            ),
            RepresentationDiscoveryArtifactReference.available(
                CANDIDATE_DOSSIERS,
                TargetFreeSymPyBridgeDiscoveryScenario.SCHEMA,
                scenario.contentHash()
            ),
            RepresentationDiscoveryArtifactReference.notProduced(PATH_REPLAY),
            RepresentationDiscoveryArtifactReference.notProduced(RULE_RADAR),
            RepresentationDiscoveryArtifactReference.notProduced(
                PROOF_OBLIGATIONS
            ),
            RepresentationDiscoveryArtifactReference.notProduced(
                EXPORT_BUNDLE
            ),
            RepresentationDiscoveryArtifactReference.available(
                PROGRESS_LEDGER,
                TargetFreeRepresentationSearch.SCHEMA,
                searchHash
            )
        );
    }

    private static String terminalReason(
        TargetFreeRepresentationSearch.SearchContent search
    ) {
        return search.truncated()
            ? "BOUNDED_SEARCH_COMPLETED_WITH_TRUNCATION"
            : "BOUNDED_SEARCH_FRONTIER_EXHAUSTED";
    }

    private static String budgetHash(
        TargetFreeRepresentationSearch.Budget budget
    ) {
        return KnownStructureCatalog.sha256(
            SCHEMA + "/budget/" + json(budget)
        );
    }

    private static String runtimeDiagnosticsHash() {
        return KnownStructureCatalog.sha256(
            SCHEMA + "/runtime-diagnostics/NOT_MEASURED"
        );
    }

    private static String requireRepositoryRevision(String value) {
        String revision = requireText(value, "repositoryRevision");
        if (!revision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                "repositoryRevision must be a lowercase 40-character "
                    + "Git commit SHA"
            );
        }
        return revision;
    }

    private static void requireWritten(
        Path path,
        String expected
    ) throws IOException {
        if (!Files.isRegularFile(path)
                || !expected.equals(Files.readString(
                    path,
                    StandardCharsets.UTF_8))) {
            throw new IllegalStateException(
                "retained target-free run artifact changed: " + path
            );
        }
    }

    private static void requireEqual(
        Object expected,
        Object actual,
        String label
    ) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException(
                label + " differs: expected=" + expected
                    + ", actual=" + actual
            );
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Unable to render target-free run evidence",
                exception
            );
        }
    }

    public record RunBundle(
        TargetFreeSymPyBridgeDiscoveryScenario.ScenarioArtifact scenario,
        RepresentationDiscoveryRunWorkspace workspace
    ) {
        public RunBundle {
            scenario = Objects.requireNonNull(scenario, "scenario");
            workspace = Objects.requireNonNull(workspace, "workspace");
            String searchHash = scenario.content().searchContentHash();
            requireEqual(
                scenario.content().freeze().enabledBoundaryHash(),
                workspace.plan().informationBoundaryHash(),
                "workspace information boundary"
            );
            requireEqual(
                scenario.content().search().ruleInventoryHash(),
                workspace.plan().ruleInventoryHash(),
                "workspace formation inventory"
            );
            requireEqual(
                scenario.content().freeze().enabledCatalogHash(),
                workspace.plan().knownStructureCatalogHash(),
                "workspace post-freeze catalog"
            );
            requireEqual(
                scenario.content().search().sourceExpression(),
                workspace.input().displayText(),
                "workspace source input"
            );
            requireEqual(
                searchHash,
                workspace.requireArtifact(
                    SEARCH_GRAPH,
                    TargetFreeRepresentationSearch.SCHEMA
                ).targetContentHash(),
                "workspace search graph"
            );
            requireEqual(
                searchHash,
                workspace.requireArtifact(
                    REPRESENTATION_CANDIDATES,
                    TargetFreeRepresentationSearch.SCHEMA
                ).targetContentHash(),
                "workspace representation candidates"
            );
            requireEqual(
                scenario.contentHash(),
                workspace.requireArtifact(
                    CANDIDATE_DOSSIERS,
                    TargetFreeSymPyBridgeDiscoveryScenario.SCHEMA
                ).targetContentHash(),
                "workspace candidate dossier"
            );
            requireEqual(
                searchHash,
                workspace.requireArtifact(
                    PROGRESS_LEDGER,
                    TargetFreeRepresentationSearch.SCHEMA
                ).targetContentHash(),
                "workspace progress ledger"
            );
            requireEqual(
                searchHash,
                workspace.outcome().canonicalWorkLedgerHash(),
                "workspace canonical work ledger"
            );
            requireEqual(
                scenario.content().search().generatedTransitionCount(),
                Math.toIntExact(workspace.outcome().consumedWork()),
                "workspace consumed work"
            );
        }
    }
}
