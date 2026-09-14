package de.regelsuche.scoring;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.search.strategy.SearchReplayArtifact;
import de.regelsuche.search.strategy.SearchStateReplay;
import de.regelsuche.search.strategy.WorkSearchReplay;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScoreRevisionAdmissionTest {
    @TempDir Path directory;

    @Test void legacyStateArtifactsAreRejectedBeforeSourceReconstruction() throws Exception {
        assertRejectedBeforeSupplier("{\"schema\":\"regelsuche.search-state-replay/v1\"}", false);
    }
    @Test void legacyWorkArtifactsAreRejectedBeforeSourceReconstruction() throws Exception {
        assertRejectedBeforeSupplier("{\"schema\":\"regelsuche.work-search-replay/v1\"}", true);
    }
    @Test void missingAndForeignRevisionsAreRejectedBeforeSourceReconstruction() throws Exception {
        for (boolean work : new boolean[]{false,true}) {
            String schema = work ? WorkSearchReplay.SCHEMA : SearchStateReplay.SCHEMA;
            assertRejectedBeforeSupplier("{\"schema\":\"" + schema + "\"}", work);
            assertRejectedBeforeSupplier("{\"schema\":\"" + schema + "\",\"scoringRevision\":\"custom/v1\"}", work);
        }
    }
    @Test void nestedScoresCannotClaimAnotherRevision() {
        String json = "{\"schema\":\"" + SearchStateReplay.SCHEMA + "\",\"scoringRevision\":\""
            + ScoreRevision.CURRENT + "\",\"score\":{\"scoringRevision\":\"old/v1\"}}";
        assertThrows(IllegalArgumentException.class, () -> ScoreRevision.requireReplay(json, SearchStateReplay.SCHEMA, ScoreRevision.CURRENT));
    }
    @Test void explicitCustomContractsAreAllowedButUnknownContractsAreNot() {
        String json = "{\"schema\":\"example/v2\",\"scoringRevision\":\"custom/v1\"}";
        assertDoesNotThrow(() -> ScoreRevision.requireReplay(json, "example/v2", "custom/v1"));
        assertThrows(IllegalArgumentException.class, () -> ScoreRevision.requireReplay(json, "example/v2", null));
        assertThrows(IllegalArgumentException.class, () -> ScoreRevision.requireReplay(json, "example/v2", ScoreRevision.UNSPECIFIED));
    }
    private void assertRejectedBeforeSupplier(String json, boolean work) throws Exception {
        Path file = directory.resolve("artifact.json");
        Files.writeString(file, json);
        var reference = SearchReplayArtifact.describe(json);
        var called = new AtomicBoolean();
        if (work) assertThrows(IllegalArgumentException.class, () -> WorkSearchReplay.verifyArtifact(file, reference, () -> {
            called.set(true); throw new AssertionError("must not reconstruct sources");
        }));
        else assertThrows(IllegalArgumentException.class, () -> SearchStateReplay.verifyArtifact(file, reference, () -> {
            called.set(true); throw new AssertionError("must not reconstruct sources");
        }));
        assertFalse(called.get());
    }
}
