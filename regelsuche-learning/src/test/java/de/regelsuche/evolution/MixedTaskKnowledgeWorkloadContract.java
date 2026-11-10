package de.regelsuche.evolution;

import java.util.List;
import java.util.Map;

/** Dependency-light supplemental executable; the same cases remain in the Maven/JUnit suite. */
final class MixedTaskKnowledgeWorkloadContract {
    public static void main(String[] args) {
        require(MixedTaskKnowledgeStudy.checkSeparation() > 0, "all seed/learning/promotion/evaluation splits");
        var promotion = MixedTaskKnowledgeWorkload.promotion();
        require(promotion.stream().map(MixedTaskKnowledgeWorkload.Case::role).toList().equals(
            List.of("shallow", "medium", "deep", "compound-sum", "compound-product", "offset", "negative", "already-solved")), "all roles");
        require(promotion.stream().map(MixedTaskKnowledgeWorkload.Case::role).toList().equals(
            MixedTaskKnowledgeWorkload.evaluation().stream().map(MixedTaskKnowledgeWorkload.Case::role).toList()), "matching role weights");
        boolean rejected = false;
        try { CumulativeTaskKnowledge.disjoint(MixedTaskKnowledgeWorkload.promotionTasks(),
            List.of(MixedTaskKnowledgeWorkload.task("((renamed+other)*(renamed-other)+other*other)+29", 7))); }
        catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "renaming cannot evade split checks");
        var good = new KnowledgeCostGate.Trial(sample(100), sample(10));
        var bad = new KnowledgeCostGate.Trial(sample(10), sample(30));
        var paid = new KnowledgeCostGate.Cost(1, 1);
        require(KnowledgeCostGate.evaluate(paid, List.of(good), 128) == KnowledgeCostGate.Decision.ADOPT, "specialist-only misleading win");
        require(KnowledgeCostGate.evaluate(paid, List.of(bad, bad, good, bad, bad, bad, bad, bad), 128)
            == KnowledgeCostGate.Decision.NO_GAIN, "mixed workload rejects net regression");
        var quality = new KnowledgeCostGate.Trial(sample(100),
            new KnowledgeCostGate.Sample(new KnowledgeCostGate.Cost(1, 1), false, 4, true));
        require(KnowledgeCostGate.evaluate(paid, List.of(good, quality), 128)
            == KnowledgeCostGate.Decision.QUALITY_REGRESSION, "quality is a per-case veto");
        try (var worker = new TaskKnowledgeLifecycleWorker()) {
            var result = worker.handle(LearnedSchedulingArtifacts.json(Map.of("op", "restore", "profile", "MEASURED_IMPROVEMENT",
                "snapshot", "unused", "directory", "unused", "improvementBatches", List.of(), "futureQueries", 128,
                "promotionWorkload", MixedTaskKnowledgeWorkload.REVISION)));
            require(result.get("status").equals("CONFIGURED"), "named worker workload accepted");
            require(result.get("promotionCases").equals(promotion), "worker reports fixed cases");
        }
        System.out.println("PASS: mixed workload, role weights, exact disjointness, alpha leak rejection, adversarial costs, quality veto, worker selection");
    }
    private static KnowledgeCostGate.Sample sample(long cost) {
        return new KnowledgeCostGate.Sample(new KnowledgeCostGate.Cost(cost, cost), true, 3, true);
    }
    private static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
