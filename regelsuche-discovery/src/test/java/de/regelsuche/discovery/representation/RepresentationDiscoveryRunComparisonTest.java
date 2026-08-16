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
        var workspace = workspace(basePlan(), "runtime", false);
        var comparison = compare(workspace, workspace);

        assertEquals(Relationship.SAME_RUN, comparison.relationship());
        assertTrue(comparison.changedEntries().isEmpty());
        assertTrue(comparison.sameCanonicalEvidence());
        assertTrue(comparison.contentHash().matches("sha256:[0-9a-f]{64}"));
        assertEquals(comparison, compare(workspace, workspace));
    }

    @Test
    void separatesRuntimeDiagnostics() {
        var comparison = compare(
            workspace(basePlan(), "runtime-left", false),
            workspace(basePlan(), "runtime-right", false)
        );

        assertEquals(Relationship.UNRELATED, comparison.relationship());
        assertEquals(1, comparison.changedEntries().size());
        Entry change = comparison.changedEntries().getFirst();
        assertEquals(Category.RUNTIME_DIAGNOSTICS, change.category());
        assertEquals("runtimeDiagnosticsHash", change.field());
        assertTrue(comparison.sameCanonicalEvidence());
        assertTrue(comparison.canonicalChangedEntries().isEmpty());
    }

    @Test
    void comparesDuplicateLineage() {
        var parent = workspace(basePlan(), "runtime", true);
        var duplicate = RepresentationDiscoveryRunWorkspace
            .duplicateWithOnePlanChange(
                parent,
                plan("budget-v2", parent.plan().deterministicSeed()),
                revisions()
            );
        var comparison = compare(parent, duplicate);

        assertEquals(Relationship.DIRECT_LINEAGE, comparison.relationship());
        assertEquals(
            List.of("budgetHash"),
            fields(comparison, Category.PLAN)
        );
        assertTrue(comparison.changedEntries(Category.LINEAGE).stream()
            .anyMatch(entry -> entry.field().equals("parentRunId")));
        assertFalse(comparison.sameCanonicalEvidence());
    }

    @Test
    void comparesSiblingLineage() {
        var parent = workspace(basePlan(), "runtime", false);
        var changedBudget = RepresentationDiscoveryRunWorkspace
            .duplicateWithOnePlanChange(
                parent,
                plan("budget-v2", 42),
                revisions()
            );
        var changedSeed = RepresentationDiscoveryRunWorkspace
            .duplicateWithOnePlanChange(
                parent,
                plan("budget", 99),
                revisions()
            );
        var comparison = compare(changedBudget, changedSeed);

        assertEquals(Relationship.SIBLING_LINEAGE, comparison.relationship());
        assertEquals(
            List.of("budgetHash", "deterministicSeed"),
            fields(comparison, Category.PLAN)
        );
        assertFalse(comparison.claimBoundary().toLowerCase().contains("better"));
    }

    @Test
    void comparesArtifactAvailability() {
        var comparison = compare(
            workspace(basePlan(), "runtime", false),
            workspace(basePlan(), "runtime", true)
        );
        List<Entry> changes = comparison.changedEntries(Category.ARTIFACT);

        assertEquals(4, changes.size());
        assertTrue(changes.stream().allMatch(entry ->
            entry.field().startsWith("SEARCH_GRAPH.")));
        assertTrue(changes.stream().anyMatch(entry ->
            entry.field().equals("SEARCH_GRAPH.status")
                && entry.leftValue().equals("NOT_PRODUCED")
                && entry.rightValue().equals("AVAILABLE")));
    }

    @Test
    void keepsDirectionalIdentity() {
        var left = workspace(basePlan(), "runtime-left", false);
        var right = workspace(
            plan("budget-v2", 42),
            "runtime-right",
            true
        );
        var forward = compare(left, right);
        var reverse = compare(right, left);

        assertNotEquals(forward.contentHash(), reverse.contentHash());
        Entry forwardBudget = entry(forward, Category.PLAN, "budgetHash");
        Entry reverseBudget = entry(reverse, Category.PLAN, "budgetHash");
        assertEquals(forwardBudget.leftValue(), reverseBudget.rightValue());
        assertEquals(forwardBudget.rightValue(), reverseBudget.leftValue());
    }

    @Test
    void rejectsTampering() {
        var workspace = workspace(basePlan(), "runtime", false);
        var valid = compare(workspace, workspace);

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

        Entry first = valid.entries().getFirst();
        assertThrows(IllegalArgumentException.class, () ->
            new Entry(
                first.category(),
                first.field(),
                first.leftValue(),
                first.rightValue(),
                !first.equal()
            ));
    }

    private static RepresentationDiscoveryRunComparison compare(
        RepresentationDiscoveryRunWorkspace left,
        RepresentationDiscoveryRunWorkspace right
    ) {
        return RepresentationDiscoveryRunComparison.compare(left, right);
    }

    private static List<String> fields(
        RepresentationDiscoveryRunComparison comparison,
        Category category
    ) {
        return comparison.changedEntries(category).stream()
            .map(Entry::field)
            .toList();
    }

    private static Entry entry(
        RepresentationDiscoveryRunComparison comparison,
        Category category,
        String field
    ) {
        return comparison.changedEntries(category).stream()
            .filter(candidate -> candidate.field().equals(field))
            .findFirst()
            .orElseThrow();
    }

    private static RepresentationDiscoveryRunWorkspace workspace(
        RepresentationDiscoveryRunPlan plan,
        String runtimeTag,
        boolean graphAvailable
    ) {
        return RepresentationDiscoveryRunWorkspace.create(
            RepresentationDiscoveryRunInput.expression(
                "sin(x)^2 + (cos(x)^2 + 0)",
                List.of("x is real")
            ),
            plan,
            RepresentationDiscoveryRunOutcome.create(
                TerminalState.COMPLETED,
                "CANDIDATES_RETAINED",
                100,
                80,
                sha("canonical-work-ledger"),
                sha(runtimeTag)
            ),
            artifacts(graphAvailable),
            revisions()
        );
    }

    private static RepresentationDiscoveryRunPlan basePlan() {
        return plan("budget", 42);
    }

    private static RepresentationDiscoveryRunPlan plan(
        String budgetTag,
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
            sha(budgetTag),
            seed,
            List.of("internal:java25", "sympy:1.14.0")
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
            references.removeIf(reference ->
                reference.role() == ArtifactRole.SEARCH_GRAPH);
            references.add(
                RepresentationDiscoveryArtifactReference.available(
                    ArtifactRole.SEARCH_GRAPH,
                    "regelsuche.search-graph/v1",
                    sha("search-graph")
                )
            );
        }
        return references;
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
