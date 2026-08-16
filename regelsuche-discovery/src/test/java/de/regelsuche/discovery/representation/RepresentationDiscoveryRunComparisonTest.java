package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunComparison.Category;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunComparison.Entry;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunComparison.Relationship;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunOutcome.TerminalState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepresentationDiscoveryRunComparisonTest {
    private static final String COMMIT =
        "0123456789abcdef0123456789abcdef01234567";

    @Test
    void comparesSameRun() {
        RepresentationDiscoveryRunWorkspace workspace = root(
            basePlan(),
            completedOutcome(sha("runtime")),
            artifacts(false)
        );

        RepresentationDiscoveryRunComparison comparison =
            RepresentationDiscoveryRunComparison.compare(
                workspace,
                workspace
            );

        assertEquals(Relationship.SAME_RUN, comparison.relationship());
        assertTrue(comparison.changedEntries().isEmpty());
        assertTrue(comparison.sameCanonicalEvidence());
        assertTrue(comparison.contentHash().matches(
            "sha256:[0-9a-f]{64}"
        ));
        assertEquals(comparison,
            RepresentationDiscoveryRunComparison.compare(
                workspace,
                workspace
            ));
    }

    @Test
    void separatesRuntimeDiagnostics() {
        RepresentationDiscoveryRunWorkspace left = root(
            basePlan(),
            completedOutcome(sha("runtime-left")),
            artifacts(false)
        );
        RepresentationDiscoveryRunWorkspace right = root(
            basePlan(),
            completedOutcome(sha("runtime-right")),
            artifacts(false)
        );

        RepresentationDiscoveryRunComparison comparison =
            RepresentationDiscoveryRunComparison.compare(left, right);

        assertEquals(Relationship.UNRELATED, comparison.relationship());
        assertEquals(1, comparison.changedEntries().size());
        assertEquals(
            Category.RUNTIME_DIAGNOSTICS,
            comparison.changedEntries().getFirst().category()
        );
        assertEquals(
            "runtimeDiagnosticsHash",
            comparison.changedEntries().getFirst().field()
        );
        assertTrue(comparison.sameCanonicalEvidence());
        assertTrue(comparison.canonicalChangedEntries().isEmpty());
    }

    @Test
    void comparesDuplicateLineage() {
        RepresentationDiscoveryRunWorkspace parent = root(
            basePlan(),
            completedOutcome(sha("runtime")),
            artifacts(true)
        );
        RepresentationDiscoveryRunPlan revisedPlan = plan(
            sha("budget-v2"),
            parent.plan().deterministicSeed()
        );
        RepresentationDiscoveryRunWorkspace duplicate =
            RepresentationDiscoveryRunWorkspace
                .duplicateWithOnePlanChange(
                    parent,
                    revisedPlan,
                    revisions()
                );

        RepresentationDiscoveryRunComparison comparison =
            RepresentationDiscoveryRunComparison.compare(
                parent,
                duplicate
            );

        assertEquals(
            Relationship.DIRECT_LINEAGE,
            comparison.relationship()
        );
        assertEquals(
            List.of("budgetHash"),
            comparison.changedEntries(Category.PLAN).stream()
                .map(Entry::field)
                .toList()
        );
        assertTrue(comparison.changedEntries(Category.LINEAGE).stream()
            .anyMatch(entry -> entry.field().equals("parentRunId")));
        assertFalse(comparison.sameCanonicalEvidence());
    }

    @Test
    void comparesSiblingLineage() {
        RepresentationDiscoveryRunWorkspace parent = root(
            basePlan(),
            completedOutcome(sha("runtime")),
            artifacts(false)
        );
        RepresentationDiscoveryRunWorkspace changedBudget =
            RepresentationDiscoveryRunWorkspace
                .duplicateWithOnePlanChange(
                    parent,
                    plan(sha("budget-v2"), 42),
                    revisions()
                );
        RepresentationDiscoveryRunWorkspace changedSeed =
            RepresentationDiscoveryRunWorkspace
                .duplicateWithOnePlanChange(
                    parent,
                    plan(sha("budget"), 99),
                    revisions()
                );

        RepresentationDiscoveryRunComparison comparison =
            RepresentationDiscoveryRunComparison.compare(
                changedBudget,
                changedSeed
            );

        assertEquals(
            Relationship.SIBLING_LINEAGE,
            comparison.relationship()
        );
        assertEquals(
            List.of("budgetHash", "deterministicSeed"),
            comparison.changedEntries(Category.PLAN).stream()
                .map(Entry::field)
                .toList()
        );
        assertFalse(comparison.claimBoundary().toLowerCase()
            .contains("better"));
    }

    @Test
    void comparesArtifactAvailability() {
        RepresentationDiscoveryRunWorkspace left = root(
            basePlan(),
            completedOutcome(sha("runtime")),
            artifacts(false)
        );
        RepresentationDiscoveryRunWorkspace right = root(
            basePlan(),
            completedOutcome(sha("runtime")),
            artifacts(true)
        );

        RepresentationDiscoveryRunComparison comparison =
            RepresentationDiscoveryRunComparison.compare(left, right);
        List<Entry> artifactChanges = comparison.changedEntries(
            Category.ARTIFACT
        );

        assertEquals(4, artifactChanges.size());
        assertTrue(artifactChanges.stream().allMatch(entry ->
            entry.field().startsWith("SEARCH_GRAPH.")));
        assertTrue(artifactChanges.stream().anyMatch(entry ->
            entry.field().equals("SEARCH_GRAPH.status")
                && entry.leftValue().equals("NOT_PRODUCED")
                && entry.rightValue().equals("AVAILABLE")));
    }

    @Test
    void keepsDirectionalIdentity() {
        RepresentationDiscoveryRunWorkspace left = root(
            basePlan(),
            completedOutcome(sha("runtime-left")),
            artifacts(false)
        );
        RepresentationDiscoveryRunWorkspace right = root(
            plan(sha("budget-v2"), 42),
            completedOutcome(sha("runtime-right")),
            artifacts(true)
        );

        RepresentationDiscoveryRunComparison forward =
            RepresentationDiscoveryRunComparison.compare(left, right);
        RepresentationDiscoveryRunComparison reverse =
            RepresentationDiscoveryRunComparison.compare(right, left);

        assertNotEquals(forward.contentHash(), reverse.contentHash());
        Entry forwardBudget = forward.changedEntries(Category.PLAN)
            .stream()
            .filter(entry -> entry.field().equals("budgetHash"))
            .findFirst()
            .orElseThrow();
        Entry reverseBudget = reverse.changedEntries(Category.PLAN)
            .stream()
            .filter(entry -> entry.field().equals("budgetHash"))
            .findFirst()
            .orElseThrow();
        assertEquals(forwardBudget.leftValue(), reverseBudget.rightValue());
        assertEquals(forwardBudget.rightValue(), reverseBudget.leftValue());
    }

    @Test
    void rejectsTampering() {
        RepresentationDiscoveryRunWorkspace workspace = root(
            basePlan(),
            completedOutcome(sha("runtime")),
            artifacts(false)
        );
        RepresentationDiscoveryRunComparison valid =
            RepresentationDiscoveryRunComparison.compare(
                workspace,
                workspace
            );

        assertThrows(IllegalArgumentException.class, () ->
            new RepresentationDiscoveryRunComparison(
                valid.schema(),
                valid.leftRunId(),
                valid.rightRunId(),
                valid.relationship(),
                valid.entries(),
                valid.claimBoundary(),
                sha("forged-comparison")
            ));

        List<Entry> incomplete = new ArrayList<>(valid.entries());
        incomplete.removeLast();
        assertThrows(IllegalArgumentException.class, () ->
            new RepresentationDiscoveryRunComparison(
                valid.schema(),
                valid.leftRunId(),
                valid.rightRunId(),
                valid.relationship(),
                incomplete,
                valid.claimBoundary(),
                valid.contentHash()
            ));

        Entry entry = valid.entries().getFirst();
        assertThrows(IllegalArgumentException.class, () ->
            new Entry(
                entry.category(),
                entry.field(),
                entry.leftValue(),
                entry.rightValue(),
                !entry.equal()
            ));
    }

    private static RepresentationDiscoveryRunWorkspace root(
        RepresentationDiscoveryRunPlan plan,
        RepresentationDiscoveryRunOutcome outcome,
        List<RepresentationDiscoveryArtifactReference> artifacts
    ) {
        return RepresentationDiscoveryRunWorkspace.create(
            RepresentationDiscoveryRunInput.expression(
                "sin(x)^2 + (cos(x)^2 + 0)",
                List.of("x is real")
            ),
            plan,
            outcome,
            artifacts,
            revisions()
        );
    }

    private static RepresentationDiscoveryRunPlan basePlan() {
        return plan(sha("budget"), 42);
    }

    private static RepresentationDiscoveryRunPlan plan(
        String budgetHash,
        long seed
    ) {
        return RepresentationDiscoveryRunPlan.create(
            RepresentationDiscoveryInformationBoundary.Track
                .R2_CATALOG_BLIND_POST_HOC_BRIDGE,
            sha("boundary"),
            sha("inventory"),
            sha("knowledge-pack-selection"),
            sha("known-structure-catalog"),
            "target-free-breadth-first/v1",
            "pareto-archive/v1",
            "representation-discovery/v1",
            budgetHash,
            seed,
            List.of("internal:java25", "sympy:1.14.0")
        );
    }

    private static RepresentationDiscoveryRunOutcome completedOutcome(
        String runtimeDiagnosticsHash
    ) {
        return RepresentationDiscoveryRunOutcome.create(
            TerminalState.COMPLETED,
            "CANDIDATES_RETAINED",
            100,
            80,
            sha("canonical-work-ledger"),
            runtimeDiagnosticsHash
        );
    }

    private static List<RepresentationDiscoveryArtifactReference> artifacts(
        boolean graphAvailable
    ) {
        List<RepresentationDiscoveryArtifactReference> references =
            new ArrayList<>(
                RepresentationDiscoveryRunWorkspace.notProducedArtifacts()
            );
        if (graphAvailable) {
            replace(
                references,
                RepresentationDiscoveryArtifactReference.available(
                    ArtifactRole.SEARCH_GRAPH,
                    "regelsuche.search-graph/v1",
                    sha("search-graph")
                )
            );
        }
        return references;
    }

    private static void replace(
        List<RepresentationDiscoveryArtifactReference> references,
        RepresentationDiscoveryArtifactReference replacement
    ) {
        references.removeIf(reference ->
            reference.role() == replacement.role());
        references.add(replacement);
    }

    private static RepresentationDiscoveryRevisionEvidence revisions() {
        return RepresentationDiscoveryRevisionEvidence.create(
            COMMIT,
            "Regelsuche-workbench/0.3-SNAPSHOT"
        );
    }

    private static String sha(String value) {
        return KnownStructureCatalog.sha256(value);
    }
}
