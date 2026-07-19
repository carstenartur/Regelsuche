package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvolutionStudyPlanExampleTest {
    @Test
    void writerCreatesReplayableNotStartedArtifacts(@TempDir Path output)
            throws Exception {
        EvolutionStudyPlanExample.WrittenContracts written =
            EvolutionStudyPlanExample.write(output);
        EvolutionStudyContractCodec codec = new EvolutionStudyContractCodec();

        assertTrue(Files.size(written.splitManifestPath()) > 0);
        assertTrue(Files.size(written.studyPlanPath()) > 0);
        assertEquals(written.splitManifest(),
            codec.readSplitManifest(written.splitManifestPath()));
        assertEquals(written.studyPlan(),
            codec.readStudyPlan(written.studyPlanPath()));
        assertEquals(EvolutionStudyPlan.StudyStatus.NOT_STARTED,
            written.studyPlan().status());
    }
}
