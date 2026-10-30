package de.regelsuche.evolution;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CumulativeTaskKnowledgeTest {
    private static CumulativeTaskKnowledge knowledge;
    @BeforeAll static void learnThreeGenerations() { knowledge = CumulativeTaskKnowledgeContract.generations(); }
    @Test void restoresVerifiedKnowledgeAndTypedRouting(@TempDir Path directory) throws Exception {
        CumulativeTaskKnowledgeContract.persistenceAndStrategies(knowledge, directory);
    }
    @Test void failedLessonsAndSplitIsolationStayVisible() {
        CumulativeTaskKnowledgeContract.failedLearningAndSplitIsolation(knowledge);
    }
    @Test void technicalFailuresRetainCompletedWorkWithoutPromotion() {
        CumulativeTaskKnowledgeContract.failuresRetainPaidPrefixes(knowledge);
    }
    @Test void maliciousAndMalformedModelsAreRechecked() throws Exception {
        CumulativeTaskKnowledgeContract.corruptedModelsNeverGainAuthority(knowledge);
    }
    @Test void acquisitionBudgetAndNoBenefitKeepOldMathematics() {
        CumulativeTaskKnowledgeContract.acquisitionBudgetAndUnhelpfulCandidatesRemainPaid();
    }
    @Test void immutableSnapshotsCannotOverwriteConflicts() throws Exception {
        CumulativeTaskKnowledgeContract.snapshotFilesAreNeverSilentlyReplaced(knowledge);
    }
    @Test void actualMixedStreamRepaysDeclaredAcquisitionAndReloadCosts(@TempDir Path directory) throws Exception {
        CumulativeTaskKnowledgeContract.measuredLifecycle(knowledge, directory);
    }
}
