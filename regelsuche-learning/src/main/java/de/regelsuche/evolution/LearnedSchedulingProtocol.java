package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.search.moves.MoveSearch;
import java.util.List;

/** Frozen public held-out protocol: changes require a new revision, never post-result task/budget selection. */
public final class LearnedSchedulingProtocol {
    public static final String REVISION = "regelsuche.learned-knowledge-search/v4";
    public static final List<Long> WORK_BUDGETS = List.of(256L, 512L, 1024L, 2048L, 4096L, 16384L);
    public record Case(String id, String source, String target, boolean polynomial) {}
    public record Configuration(String id, LearnedSearchProfile profile, MoveSearch.Scheduling scheduling,
            boolean utility, boolean history, boolean continuation, boolean activity, boolean landmarks) {}
    public static List<Configuration> configurations() {
        return List.of(config("BASE", LearnedSearchProfile.BASE, false, false, false, false, false, true),
            config("LEARNED_NAIVE", LearnedSearchProfile.LEARNED_NAIVE, false, false, false, false, false, true),
            config("LEARNED_RANKED", LearnedSearchProfile.LEARNED_RANKED, true, true, true, true, true, true),
            config("EXPERT", LearnedSearchProfile.EXPERT, true, false, false, false, false, true),
            config("BASE_STAGED", LearnedSearchProfile.BASE, true, false, false, false, false, true),
            config("NAIVE_STAGED", LearnedSearchProfile.LEARNED_NAIVE, true, false, false, false, false, true),
            config("NO_UTILITY", LearnedSearchProfile.LEARNED_RANKED, true, false, true, true, true, true),
            config("NO_HISTORY", LearnedSearchProfile.LEARNED_RANKED, true, true, false, false, true, true),
            config("NO_CONTINUATION", LearnedSearchProfile.LEARNED_RANKED, true, true, true, false, true, true),
            config("NO_ACTIVITY", LearnedSearchProfile.LEARNED_RANKED, true, true, true, true, false, true),
            config("NO_LANDMARKS", LearnedSearchProfile.LEARNED_RANKED, true, true, true, true, true, false));
    }
    private static Configuration config(String id, LearnedSearchProfile profile, boolean staged, boolean utility,
            boolean history, boolean continuation, boolean activity, boolean landmarks) {
        return new Configuration(id, profile, staged ? MoveSearch.Scheduling.STAGED : MoveSearch.Scheduling.EAGER_CONTROL,
            utility, history, continuation, activity, landmarks);
    }
    public static List<Case> cases() {
        return List.of(
            new Case("three-squares", "((a+b)*(a-b)+b*b)+((a+c)*(a-c)+c*c)+((a+d)*(a-d)+d*d)", "3*a^2", true),
            new Case("nested-product", "((u+v)*(u-v)+v*v)*(u+3)*(u+4)", "u^2*(u+3)*(u+4)", true),
            new Case("compound-shared", "((p*q+r)*(p*q-r)+r*r)+((p*q+s)*(p*q-s)+s*s)", "2*(p*q)^2", true),
            new Case("weighted-two-sites", "2*((x+y)*(x-y)+y*y)+4*((x+z)*(x-z)+z*z)", "2*x^2+4*x^2", true),
            new Case("two-factor-sites", "(a*b+a*c)*(d*b+d*c)", "(a*(b+c))*(d*(b+c))", true),
            new Case("residual-control", "((u+v)*(u-v)+v*(v+1))*(u+3)", "u^2*(u+3)", true),
            new Case("already-target", "m^3+11", "m^3+11", true),
            new Case("unsupported-control", "sin(u)+cos(v)", "1", false));
    }
    public static String canonicalJson() {
        return new JsonWriter().beginObject().property("schema", REVISION)
            .property("split", "PUBLIC_HELD_OUT_FROM_TRAIN_AND_PR1_DEVELOPMENT;NOT_A_SEALED_FINAL_TEST")
            .property("parity", "SAME_TARGETS_PRIMITIVES_TOTAL_WORK_AND_EXACT_PRIMITIVE_REPLAY")
            .property("freeze", "TASKS_BUDGETS_WEIGHTS_AND_ABLATIONS_FIXED_BEFORE_FIRST_RUN;TEST_NEVER_UPDATES")
            .property("work", "SOURCE_EVENTS_PLUS_MEASURED_PRIMITIVE_APPLICATIONS_PLUS_FRONTIER_FEATURE_PROBE_AND_EXACT_REPLAY_WORK;ATOMIC_OVERRUN_RETAINED")
            .property("primitiveDepth", 9).property("searchDepth", 9).property("states", 256).property("complexityDebt", 24)
            .property("referenceWork", 1000000).property("referenceStates", 4096).property("trainingEpochs", 5)
            .property("amortizationPopulation", "UNIFORM_COMMON_SOLVED_CASES_AT_MAXIMUM_WORK_BUDGET;CANONICAL_HOT_PATH_UNITS")
            .property("trainingFeedbackWork", "ACTIVITY_ENTRY_UPDATES_HISTORY_CONTEXT_NODES_AND_TABLE_UPDATES_UTILITY_OBSERVATION_INSPECTIONS")
            .stringArray("budgets", WORK_BUDGETS.stream().map(String::valueOf).toList())
            .array("configurations", array -> configurations().forEach(c -> array.objectValue(value -> value.property("id", c.id())
                .property("profile", c.profile().name()).property("scheduling", c.scheduling().name()).property("utility", c.utility())
                .property("history", c.history()).property("continuation", c.continuation()).property("activity", c.activity()).property("landmarks", c.landmarks()))))
            .array("cases", array -> cases().forEach(c -> array.objectValue(value -> value.property("id", c.id())
                .property("source", c.source()).property("target", c.target()).property("polynomial", c.polynomial())))).endObject().toString();
    }
}
