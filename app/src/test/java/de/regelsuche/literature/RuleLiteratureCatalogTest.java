package de.regelsuche.literature;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.export.DefaultTransformationImportService;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.web.WebWorkbenchServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleLiteratureCatalogTest {
    private static final String REVISION = "sha256:" + "a".repeat(64);
    private static final String OTHER_REVISION = "sha256:" + "b".repeat(64);
    @TempDir Path directory;

    @Test void exportsAReviewedSourceOnceAndKeepsLaterLiteratureDistinct() throws Exception {
        var path = path("p", REVISION);
        var first = path.steps().getFirst();
        var second = path.steps().getLast();
        String later = link("FOUND_AFTER_FREEZE", RuleLiteratureCatalog.occurrence(path, second), true);
        var catalog = read(link("RULE_SOURCE", null, true) + "," + later);
        var numbering = catalog.numbering();
        assertEquals(List.of(RuleLiteratureCatalog.Role.RULE_SOURCE),
            numbering.forStep(path, first).stream().map(RuleLiteratureCatalog.Citation::role).toList());
        assertEquals(List.of(RuleLiteratureCatalog.Role.RULE_SOURCE, RuleLiteratureCatalog.Role.FOUND_AFTER_FREEZE),
            numbering.forStep(path, second).stream().map(RuleLiteratureCatalog.Citation::role).toList());
        assertEquals(1, numbering.bibliography().size());
        var exporter = new DefaultTransformationExportService(Clock.systemUTC(), catalog);
        String markdown = exporter.exportMarkdown(List.of(path));
        assertTrue(markdown.contains("Literaturabgleich nach der Herleitung [1]"), markdown);
        assertTrue(markdown.contains("n ≥ 0"), markdown);
        assertTrue(markdown.contains("Index shifted by one"), markdown);
        assertEquals(1, markdown.split("https://example.org/source", -1).length - 1, markdown);
        String latex = exporter.exportLatex(List.of(path));
        assertTrue(latex.contains("Literaturabgleich nach der Herleitung [1]"), latex);
        assertTrue(latex.contains("A \\& B"), latex);
        assertTrue(latex.contains("n \\ensuremath{\\geq} 0"), latex);
        assertTrue(latex.contains("Index shifted by one"), latex);
        assertEquals(1, latex.split("https://example.org/source", -1).length - 1, latex);
        var imported = new DefaultTransformationImportService().importJson(exporter.exportJson(List.of(path), List.of()));
        assertEquals(REVISION, imported.transformations().getFirst().steps().getFirst().ruleRevision());
    }

    @Test void workbenchReplayAndPathExportExposeTheSameReviewedCitation() throws Exception {
        var catalog = read(link("RULE_SOURCE", null, true));
        var store = new InMemoryExpressionGraphStore();
        store.saveDiscoveredTransformation(path("p", REVISION));
        var exporter = new DefaultTransformationExportService(Clock.systemUTC(), catalog);
        var server = new WebWorkbenchServer("127.0.0.1", 0, store, new InMemoryRuleInventoryRepository(), exporter);
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            String replay = get(client, server, "/api/paths/p/replay");
            assertTrue(replay.contains("\"role\":\"RULE_SOURCE\""), replay);
            assertTrue(replay.contains("\"locator\":\"Lemma 2\""), replay);
            assertTrue(replay.contains("\"assumptions\":[\"n ≥ 0\"]"), replay);
            assertTrue(replay.contains("\"rationale\":\"Index shifted by one\""), replay);
            assertTrue(replay.contains(REVISION), replay);
            String latex = get(client, server, "/api/exports/path/p.tex");
            assertTrue(latex.contains("Regelquelle [1]"), latex);
            assertTrue(latex.contains("Lemma 2"), latex);
        } finally {
            server.stop();
        }
    }

    @Test void retainsDistinctQualificationsForTheSameSource() throws Exception {
        var path = path("p", REVISION);
        var original = link("RULE_SOURCE", null, true);
        var alternative = original.replace("n ≥ 0", "n ≥ 1").replace("Index shifted by one", "Other index shift");
        var numbering = read(original + "," + alternative).numbering();
        assertEquals(2, numbering.forStep(path, path.steps().getFirst()).size());
        assertEquals(1, numbering.bibliography().size());
    }

    private static String get(HttpClient client, WebWorkbenchServer server, String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.boundPort() + path))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    @Test void onlyExactRevisionsOrExplicitOccurrencesReceiveReleasedReferences() throws Exception {
        var oldPath = path("old", null);
        String legacy = link("USED_AS_LEMMA", RuleLiteratureCatalog.occurrence(oldPath, oldPath.steps().getFirst()), true)
            .replace("\"" + REVISION + "\"", "null");
        var catalog = read(link("RULE_SOURCE", null, true) + "," + legacy + ","
            + link("FOUND_AFTER_FREEZE", RuleLiteratureCatalog.occurrence(oldPath, oldPath.steps().getLast()), false)
                .replace("\"" + REVISION + "\"", "null"));
        var numbering = catalog.numbering();
        var newPath = path("new", OTHER_REVISION);
        assertTrue(numbering.forStep(newPath, newPath.steps().getFirst()).isEmpty());
        assertEquals(RuleLiteratureCatalog.Role.USED_AS_LEMMA,
            numbering.forStep(oldPath, oldPath.steps().getFirst()).getFirst().role());
        assertTrue(numbering.forStep(oldPath, oldPath.steps().getLast()).isEmpty());
        assertTrue(numbering.forStep(oldPath, new TransformationStep(0, "x+0", "y",
            "identity_add_zero", RewriteKind.NORMALIZE, 5, 4, true, "")).isEmpty());
    }

    @Test void rejectsUnlocatedSourcesAndUnboundProofUse() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> read(link("USED_AS_LEMMA", null, true)));
        Path file = directory.resolve("invalid.json");
        Files.writeString(file, document(link("RULE_SOURCE", null, true)).replace("Lemma 2", " "));
        assertThrows(IllegalArgumentException.class, () -> RuleLiteratureCatalog.read(file));
    }

    private RuleLiteratureCatalog read(String links) throws Exception {
        Path file = directory.resolve("catalog.json");
        Files.writeString(file, document(links));
        return RuleLiteratureCatalog.read(file);
    }

    private static String document(String links) {
        return """
            {"schema":"regelsuche.rule-literature/v1","sources":[{
              "id":"lemma","authors":["A & B"],"title":"Source","year":2025,
              "identifier":"doi:10.0000/example","version":"published",
              "locator":"Lemma 2","url":"https://example.org/source"
            }],"links":[%s]}
            """.formatted(links);
    }

    private static String link(String role, String occurrence, boolean released) {
        return """
            {"ruleId":"identity_add_zero","ruleRevision":"%s","occurrence":%s,
             "sourceId":"lemma","relation":"EQUIVALENT","role":"%s","assumptions":["n ≥ 0"],
             "reviewedBy":"reviewer","reviewedOn":"2026-11-08","rationale":"Index shifted by one",
             "released":%s}
            """.formatted(REVISION, occurrence == null ? "null" : "\"" + occurrence + "\"", role, released);
    }

    private static DiscoveredTransformation path(String id, String revision) {
        var first = new TransformationStep(0, "(x+0)+0", "x+0", "identity_add_zero",
            RewriteKind.NORMALIZE, 6, 5, true, "", List.of(), null, revision);
        var second = new TransformationStep(1, "x+0", "x", "identity_add_zero",
            RewriteKind.NORMALIZE, 5, 4, true, "", List.of(), null, revision);
        var score = new ExpressionScore(1, 1, 1, 1, 1);
        return new DiscoveredTransformation(id, "(x+0)+0", "x", List.of(first, second), score, score, 2,
            CandidateProofStatus.OBSERVED, Instant.EPOCH, "");
    }
}
