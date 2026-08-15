package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.representation.RepresentationDiscoveryRunWorkspace.ArtifactReference;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunWorkspace.ArtifactRole;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunWorkspace.ArtifactStatus;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunWorkspace.RevisionEvidence;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunWorkspace.RunInput;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunWorkspace.RunOutcome;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunWorkspace.RunPlan;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunWorkspace.RunRelation;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunWorkspace.TerminalState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepresentationDiscoveryRunWorkspaceTest {
    private static final String REPOSITORY_COMMIT =
        "c4a2dbc41430bd72abca0b819119efb00c9bd8dd";

    @Test
    void rootWorkspaceIsOrderIndependentAndCorrelatesArtifactsAndSelection() {
        RunInput input = RunInput.expression(
            " sin(x)^2 + (cos(x)^2 + 0) ",
            List.of("x is real", "x != 0")
        );
        RunPlan plan = plan(
            sha("budget"),
            42,
            List.of("sympy:1.14.0", "internal:java21")
        );
        List<ArtifactReference> artifacts = completeArtifacts();
        RepresentationDiscoveryRunWorkspace first =
            RepresentationDiscoveryRunWorkspace.create(
                input,
                plan,
                completedOutcome(),
                artifacts,
                revisions()
            );

        List<ArtifactReference> reversedArtifacts =
            new ArrayList<>(artifacts);
        Collections.reverse(reversedArtifacts);
        RepresentationDiscoveryRunWorkspace second =
            RepresentationDiscoveryRunWorkspace.create(
                RunInput.expression(
                    "sin(x) ^ 2 + (cos(x) ^ 2 + 0)",
                    List.of("x != 0", "x is real")
                ),
                plan(
                    sha("budget"),
                    42,
                    List.of("internal:java21", "sympy:1.14.0")
                ),
                completedOutcome(),
                reversedArtifacts,
                revisions()
            );

        assertEquals(RunRelation.ROOT, first.relation());
        assertEquals("", first.parentRunId());
        assertEquals(first.contentHash(), first.runId());
        assertEquals(first, second);
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertTrue(first.runId().matches("sha256:[0-9a-f]{64}"));
        assertEquals(
            sha("candidate-dossiers"),
            first.requireArtifact(
                ArtifactRole.CANDIDATE_DOSSIERS,
                "regelsuche.candidate-dossiers/v1"
            ).targetContentHash()
        );

        var selection = first.selection(
            sha("candidate"),
            sha("state"),
            sha("edge"),
            "/0/1",
            sha("proof")
        );
        assertEquals(first.runId(), selection.runId());
        assertTrue(selection.contentHash().matches(
            "sha256:[0-9a-f]{64}"));
        assertEquals(selection,
            first.selection(
                sha("candidate"),
                sha("state"),
                sha("edge"),
                "/0/1",
                sha("proof")
            ));
    }

    @Test
    void duplicateRequiresExactlyOneVisiblePlanChangeAndStartsClean() {
        RepresentationDiscoveryRunWorkspace parent = root();
        RunPlan changedBudget = plan(
            sha("budget-v2"),
            parent.plan().deterministicSeed(),
            parent.plan().backendIdentities()
        );

        RepresentationDiscoveryRunWorkspace duplicate =
            RepresentationDiscoveryRunWorkspace
                .duplicateWithOnePlanChange(
                    parent,
                    changedBudget,
                    revisions()
                );

        assertEquals(
            RunRelation.DUPLICATED_ONE_PARAMETER,
            duplicate.relation()
        );
        assertEquals(parent.runId(), duplicate.parentRunId());
        assertEquals(parent.plan().contentHash(),
            duplicate.parentPlanHash());
        assertEquals("budgetHash", duplicate.changedPlanParameter());
        assertEquals(TerminalState.CREATED, duplicate.outcome().state());
        assertTrue(duplicate.artifacts().stream().allMatch(reference ->
            reference.status() == ArtifactStatus.NOT_PRODUCED));
        assertNotEquals(parent.runId(), duplicate.runId());

        assertThrows(IllegalArgumentException.class, () ->
            RepresentationDiscoveryRunWorkspace
                .duplicateWithOnePlanChange(
                    parent,
                    parent.plan(),
                    revisions()
                ));
        RunPlan twoChanges = plan(
            sha("budget-v2"),
            99,
            parent.plan().backendIdentities()
        );
        assertThrows(IllegalArgumentException.class, () ->
            RepresentationDiscoveryRunWorkspace
                .duplicateWithOnePlanChange(
                    parent,
                    twoChanges,
                    revisions()
                ));
    }

    @Test
    void continuationKeepsInputAndPlanButGetsItsOwnRunIdentity() {
        RepresentationDiscoveryRunWorkspace parent =
            RepresentationDiscoveryRunWorkspace.create(
                expressionInput(),
                basePlan(),
                RunOutcome.created(),
                RepresentationDiscoveryRunWorkspace.notProducedArtifacts(),
                revisions()
            );
        RunOutcome exhausted = RunOutcome.create(
            TerminalState.BUDGET_EXHAUSTED,
            "MAX_GENERATED_TRANSITIONS",
            100,
            100,
            sha("continued-work"),
            sha("continued-runtime")
        );

        RepresentationDiscoveryRunWorkspace continuation =
            RepresentationDiscoveryRunWorkspace.continueFrom(
                parent,
                exhausted,
                completeArtifacts(),
                revisions()
            );

        assertEquals(RunRelation.CONTINUATION, continuation.relation());
        assertEquals(parent.runId(), continuation.parentRunId());
        assertEquals(parent.plan(), continuation.plan());
        assertEquals(parent.input(), continuation.input());
        assertEquals("", continuation.changedPlanParameter());
        assertNotEquals(parent.runId(), continuation.runId());
        assertThrows(IllegalArgumentException.class, () ->
            RepresentationDiscoveryRunWorkspace.continueFrom(
                parent,
                RunOutcome.created(),
                completeArtifacts(),
                revisions()
            ));
    }

    @Test
    void everyArtifactRoleMustBeExplicitAndUnique() {
        List<ArtifactReference> missing = new ArrayList<>(
            RepresentationDiscoveryRunWorkspace.notProducedArtifacts());
        missing.removeFirst();
        assertThrows(IllegalArgumentException.class, () ->
            RepresentationDiscoveryRunWorkspace.create(
                expressionInput(),
                basePlan(),
                RunOutcome.created(),
                missing,
                revisions()
            ));

        List<ArtifactReference> duplicate = new ArrayList<>(
            RepresentationDiscoveryRunWorkspace.notProducedArtifacts());
        duplicate.add(ArtifactReference.notProduced(
            ArtifactRole.SEARCH_GRAPH));
        assertThrows(IllegalArgumentException.class, () ->
            RepresentationDiscoveryRunWorkspace.create(
                expressionInput(),
                basePlan(),
                RunOutcome.created(),
                duplicate,
                revisions()
            ));
    }

    @Test
    void unavailableAndIncompatibleArtifactsFailVisibly() {
        RepresentationDiscoveryRunWorkspace workspace = root();

        IllegalStateException unavailable = assertThrows(
            IllegalStateException.class,
            () -> workspace.requireArtifact(
                ArtifactRole.PROOF_OBLIGATIONS,
                "regelsuche.proof-obligations/v1"
            )
        );
        assertTrue(unavailable.getMessage().contains("NOT_PRODUCED"));
        IllegalStateException incompatible = assertThrows(
            IllegalStateException.class,
            () -> workspace.requireArtifact(
                ArtifactRole.SEARCH_GRAPH,
                "future.search-graph/v2"
            )
        );
        assertTrue(incompatible.getMessage().contains("expected"));
    }

    @Test
    void artifactReferencesRejectForgedAvailabilityAndHashes() {
        ArtifactReference available = ArtifactReference.available(
            ArtifactRole.SEARCH_GRAPH,
            "regelsuche.search-graph/v1",
            sha("graph")
        );
        assertThrows(IllegalArgumentException.class, () ->
            new ArtifactReference(
                available.role(),
                available.status(),
                available.artifactSchema(),
                available.targetContentHash(),
                "not empty",
                available.contentHash()
            ));
        assertThrows(IllegalArgumentException.class, () ->
            new ArtifactReference(
                available.role(),
                available.status(),
                available.artifactSchema(),
                available.targetContentHash(),
                available.detail(),
                sha("forged-reference")
            ));

        ArtifactReference unavailable = ArtifactReference.unavailable(
            ArtifactRole.RULE_RADAR,
            ArtifactStatus.UNSUPPORTED,
            "DOMAIN_UNSUPPORTED"
        );
        assertThrows(IllegalArgumentException.class, () ->
            new ArtifactReference(
                unavailable.role(),
                unavailable.status(),
                unavailable.artifactSchema(),
                sha("forged-unavailable-target"),
                unavailable.detail(),
                unavailable.contentHash()
            ));
        assertThrows(IllegalArgumentException.class, () ->
            ArtifactReference.unavailable(
                ArtifactRole.RULE_RADAR,
                ArtifactStatus.AVAILABLE,
                "wrong factory"
            ));
    }

    @Test
    void workOutcomesBalanceAndKeepRuntimeNonAuthoritative() {
        RunOutcome created = RunOutcome.created();
        assertEquals(0, created.configuredWork());
        assertEquals(0, created.consumedWork());
        assertNotEquals(
            created.canonicalWorkLedgerHash(),
            created.runtimeDiagnosticsHash()
        );

        assertThrows(IllegalArgumentException.class, () ->
            RunOutcome.create(
                TerminalState.COMPLETED,
                "SUCCESS",
                10,
                11,
                sha("work"),
                sha("runtime")
            ));
        assertThrows(IllegalArgumentException.class, () ->
            RunOutcome.create(
                TerminalState.BUDGET_EXHAUSTED,
                "LIMIT",
                10,
                9,
                sha("work"),
                sha("runtime")
            ));
        assertThrows(IllegalArgumentException.class, () ->
            RunOutcome.create(
                TerminalState.CREATED,
                "WRONG",
                0,
                0,
                sha("work"),
                sha("runtime")
            ));

        RunOutcome valid = completedOutcome();
        assertThrows(IllegalArgumentException.class, () ->
            new RunOutcome(
                valid.state(),
                valid.terminalReason(),
                valid.configuredWork(),
                valid.consumedWork(),
                valid.canonicalWorkLedgerHash(),
                valid.runtimeDiagnosticsHash(),
                sha("forged-outcome")
            ));
    }

    @Test
    void expressionInputIsNormalizedAndHashBound() {
        RunInput input = RunInput.expression(
            " (x+0) * 1 ",
            List.of("x is real")
        );

        assertEquals("(x + 0) * 1", input.displayText());
        assertEquals(List.of("x is real"), input.assumptions());
        assertThrows(IllegalArgumentException.class, () ->
            new RunInput(
                input.domainId(),
                input.inputSchema(),
                sha("forged-input"),
                input.displayText(),
                input.assumptions(),
                input.contentHash()
            ));
        assertThrows(IllegalArgumentException.class, () ->
            new RunInput(
                input.domainId(),
                input.inputSchema(),
                input.canonicalInputHash(),
                "x+0",
                input.assumptions(),
                input.contentHash()
            ));

        RunInput generic = RunInput.create(
            "integer-sequence",
            "regelsuche.sequence-input/v1",
            sha("sequence-input"),
            "1, 4, 9, 16",
            List.of()
        );
        assertEquals("integer-sequence", generic.domainId());
    }

    @Test
    void planRevisionSelectionAndWorkspaceTamperingAreRejected() {
        RunPlan plan = basePlan();
        assertThrows(IllegalArgumentException.class, () ->
            new RunPlan(
                plan.informationTrack(),
                plan.informationBoundaryHash(),
                plan.ruleInventoryHash(),
                plan.knowledgePackSelectionHash(),
                plan.knownStructureCatalogHash(),
                plan.searchStrategyId(),
                plan.searchProfileId(),
                plan.objectiveId(),
                plan.budgetHash(),
                plan.deterministicSeed(),
                plan.backendIdentities(),
                sha("forged-plan")
            ));
        assertThrows(IllegalArgumentException.class, () ->
            RunPlan.create(
                plan.informationTrack(),
                plan.informationBoundaryHash(),
                plan.ruleInventoryHash(),
                plan.knowledgePackSelectionHash(),
                plan.knownStructureCatalogHash(),
                plan.searchStrategyId(),
                plan.searchProfileId(),
                plan.objectiveId(),
                plan.budgetHash(),
                plan.deterministicSeed(),
                List.of()
            ));

        RevisionEvidence revision = revisions();
        assertThrows(IllegalArgumentException.class, () ->
            new RevisionEvidence(
                "not-a-commit",
                revision.applicationRevision(),
                revision.contentHash()
            ));
        assertThrows(IllegalArgumentException.class, () ->
            new RevisionEvidence(
                revision.repositoryCommit(),
                revision.applicationRevision(),
                sha("forged-revision")
            ));

        RepresentationDiscoveryRunWorkspace workspace = root();
        assertThrows(IllegalArgumentException.class, () ->
            workspace.selection("", "", "", "", ""));
        var selection = workspace.selection(
            sha("candidate"), "", "", "", "");
        assertThrows(IllegalArgumentException.class, () ->
            new RepresentationDiscoveryRunWorkspace.RunSelection(
                selection.runId(),
                selection.candidateId(),
                selection.stateId(),
                selection.edgeId(),
                selection.occurrencePath(),
                selection.proofObligationId(),
                sha("forged-selection")
            ));

        assertThrows(IllegalArgumentException.class, () ->
            new RepresentationDiscoveryRunWorkspace(
                workspace.schema(),
                sha("forged-run"),
                workspace.relation(),
                workspace.parentRunId(),
                workspace.parentPlanHash(),
                workspace.changedPlanParameter(),
                workspace.input(),
                workspace.plan(),
                workspace.outcome(),
                workspace.artifacts(),
                workspace.revisions(),
                workspace.claimBoundary(),
                workspace.contentHash()
            ));
        assertThrows(IllegalArgumentException.class, () ->
            new RepresentationDiscoveryRunWorkspace(
                workspace.schema(),
                workspace.runId(),
                RunRelation.ROOT,
                sha("parent"),
                sha("parent-plan"),
                "",
                workspace.input(),
                workspace.plan(),
                workspace.outcome(),
                workspace.artifacts(),
                workspace.revisions(),
                workspace.claimBoundary(),
                workspace.contentHash()
            ));
        assertFalse(workspace.toCanonicalJson().contains("elapsedMillis"));
    }

    private static RepresentationDiscoveryRunWorkspace root() {
        return RepresentationDiscoveryRunWorkspace.create(
            expressionInput(),
            basePlan(),
            completedOutcome(),
            completeArtifacts(),
            revisions()
        );
    }

    private static RunInput expressionInput() {
        return RunInput.expression(
            "sin(x)^2 + (cos(x)^2 + 0)",
            List.of()
        );
    }

    private static RunPlan basePlan() {
        return plan(
            sha("budget"),
            42,
            List.of("internal:java21", "sympy:1.14.0")
        );
    }

    private static RunPlan plan(
        String budgetHash,
        long seed,
        List<String> backends
    ) {
        return RunPlan.create(
            RepresentationDiscoveryInformationBoundary.Track
                .R2_CATALOG_BLIND_POST_HOC_BRIDGE,
            sha("boundary"),
            sha("inventory"),
            sha("selection"),
            sha("catalog"),
            "target-free-breadth-first/v1",
            "pareto-archive/v1",
            "representation-discovery/v1",
            budgetHash,
            seed,
            backends
        );
    }

    private static RunOutcome completedOutcome() {
        return RunOutcome.create(
            TerminalState.COMPLETED,
            "CANDIDATES_RETAINED",
            100,
            80,
            sha("work-ledger"),
            sha("runtime-diagnostics")
        );
    }

    private static List<ArtifactReference> completeArtifacts() {
        List<ArtifactReference> references = new ArrayList<>(
            RepresentationDiscoveryRunWorkspace.notProducedArtifacts());
        replace(references, ArtifactReference.available(
            ArtifactRole.SEARCH_GRAPH,
            "regelsuche.search-graph/v1",
            sha("search-graph")
        ));
        replace(references, ArtifactReference.available(
            ArtifactRole.REPRESENTATION_CANDIDATES,
            "regelsuche.representation-candidates/v1",
            sha("candidates")
        ));
        replace(references, ArtifactReference.available(
            ArtifactRole.CANDIDATE_DOSSIERS,
            "regelsuche.candidate-dossiers/v1",
            sha("candidate-dossiers")
        ));
        replace(references, ArtifactReference.unavailable(
            ArtifactRole.RULE_RADAR,
            ArtifactStatus.UNSUPPORTED,
            "NOT_AVAILABLE_FOR_RETAINED_RUN"
        ));
        return references;
    }

    private static void replace(
        List<ArtifactReference> references,
        ArtifactReference replacement
    ) {
        references.removeIf(reference ->
            reference.role() == replacement.role());
        references.add(replacement);
    }

    private static RevisionEvidence revisions() {
        return RevisionEvidence.create(
            REPOSITORY_COMMIT,
            "Regelsuche-workbench/0.3-SNAPSHOT"
        );
    }

    private static String sha(String value) {
        return KnownStructureCatalog.sha256(value);
    }
}
