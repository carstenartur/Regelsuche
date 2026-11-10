package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MixedTaskKnowledgeWorkloadTest {
    @Test void fixedPromotionMatchesEveryEvaluationRoleAndWeight() {
        var promotion = MixedTaskKnowledgeWorkload.promotion();
        var evaluation = MixedTaskKnowledgeWorkload.evaluation();
        assertEquals(List.of("shallow", "medium", "deep", "compound-sum", "compound-product",
            "offset", "negative", "already-solved"), promotion.stream().map(MixedTaskKnowledgeWorkload.Case::role).toList());
        assertEquals(promotion.stream().map(MixedTaskKnowledgeWorkload.Case::role).toList(),
            evaluation.stream().map(MixedTaskKnowledgeWorkload.Case::role).toList());
        assertEquals(promotion, MixedTaskKnowledgeWorkload.promotion());
    }

    @Test void allThreeSplitsArePolynomialAlphaDisjointIncludingSeedAndNoOpAcquisition() {
        var training = Stream.concat(MixedTaskKnowledgeWorkload.seedBatches().stream(),
            MixedTaskKnowledgeWorkload.improvementBatches().stream())
            .flatMap(b -> Stream.concat(b.lessons().stream(), b.validation().stream())).toList();
        var promotion = MixedTaskKnowledgeWorkload.promotion().stream().map(MixedTaskKnowledgeWorkload.Case::task).toList();
        var evaluation = MixedTaskKnowledgeWorkload.evaluation().stream().map(MixedTaskKnowledgeWorkload.Case::task).toList();
        assertTrue(CumulativeTaskKnowledge.disjoint(training, promotion) > 0);
        assertTrue(CumulativeTaskKnowledge.disjoint(training, evaluation) > 0);
        assertTrue(CumulativeTaskKnowledge.disjoint(promotion, evaluation) > 0);
        assertThrows(IllegalArgumentException.class, () -> CumulativeTaskKnowledge.disjoint(promotion,
            List.of(MixedTaskKnowledgeWorkload.task("((renamed+other)*(renamed-other)+other*other)+29", 7))));
        for (var batch : MixedTaskKnowledgeWorkload.improvementBatches()) assertEquals(promotion, batch.promotion());
    }
}
