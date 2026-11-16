package de.regelsuche.discovery;

import de.regelsuche.learning.MacroLearningResult;
import de.regelsuche.mining.SuccessfulTransformationPath;
import java.util.List;

/** Optional orchestration service for macro learning and promotion outside engine composition. */
public interface MacroLearningService {
    MacroLearningResult learnAndPromote(List<SuccessfulTransformationPath> paths, DiscoveryLearningOptions options);
}
