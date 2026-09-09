package de.regelsuche.evolution;

import de.regelsuche.evolution.RepresentationStrategyLearner.Profile;
import de.regelsuche.evolution.RepresentationTransferExperiment.Row;
import de.regelsuche.evolution.RepresentationTransferExperiment.Study;
import de.regelsuche.json.JsonWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Post-evaluation diagnosis, never a training feature or executable selection policy.
 * The hindsight bound covers only the three actually measured routes at the same
 * construction budget, and only compares costs when both solutions are verified.
 */
public final class RepresentationLearningDiagnosis {
    private RepresentationLearningDiagnosis() { }
    private static final List<Profile> ROUTES = List.of(Profile.DIRECT, Profile.MATRIX, Profile.BLOCKS);

    public record Work(Profile profile, long construction, long audit, long selection) {
        public long application() { return construction + audit + selection; }
    }
    public record CaseBound(String id, boolean fixedVerified, boolean anyRouteVerified,
                            long fixedWork, long bestVerifiedRouteWork, String bestVerifiedRoute) { }
    public record Diagnosis(List<Work> work, List<CaseBound> cases, int comparableCases,
                            int unverifiedCases, int additionalSolvableCases,
                            int cheaperVerifiedCases, long avoidableFixedWork,
                            int learnedChoiceDifferences) {
        public Diagnosis { work = List.copyOf(work); cases = List.copyOf(cases); }
    }

    public static Diagnosis analyze(Study study) {
        Map<String, Map<Profile, Row>> cases = new TreeMap<>();
        for (Row row : study.rows()) {
            Row previous = cases.computeIfAbsent(row.task().id(), ignored -> new EnumMap<>(Profile.class))
                .putIfAbsent(row.profile(), row);
            if (previous != null) throw new IllegalArgumentException("Duplicate case/profile observation");
        }
        if (cases.isEmpty()) throw new IllegalArgumentException("No application observations");
        List<CaseBound> bounds = new ArrayList<>();
        int comparable = 0, unverified = 0, additional = 0, cheaper = 0, differences = 0;
        long avoidable = 0;
        for (var entry : cases.entrySet()) {
            Map<Profile, Row> profiles = entry.getValue();
            if (profiles.size() != Profile.values().length)
                throw new IllegalArgumentException("Incomplete profile comparison");
            Row fixed = profiles.get(Profile.FIXED_AUTO);
            for (Row row : profiles.values()) {
                if (!row.task().equals(fixed.task())
                        || !row.application().result().source().equals(fixed.task().equations())
                        || row.application().result().budget() != fixed.application().result().budget())
                    throw new IllegalArgumentException("Unmatched task or construction budget");
            }
            if (profiles.get(Profile.LEARNED).application().result().selected()
                    != fixed.application().result().selected()) differences++;
            var best = ROUTES.stream().map(profiles::get).filter(RepresentationLearningDiagnosis::verified)
                .min(Comparator.comparingInt(row -> row.application().totalWork()));
            boolean fixedVerified = verified(fixed);
            if (best.isEmpty()) unverified++;
            else if (!fixedVerified) additional++;
            else {
                comparable++;
                long saved = (long) fixed.application().totalWork() - best.orElseThrow().application().totalWork();
                if (saved > 0) { cheaper++; avoidable += saved; }
            }
            bounds.add(new CaseBound(entry.getKey(), fixedVerified, best.isPresent(), fixed.application().totalWork(),
                best.map(row -> (long) row.application().totalWork()).orElse(-1L),
                best.map(row -> row.profile().name()).orElse("NONE")));
        }
        List<Work> work = new ArrayList<>();
        for (Profile profile : Profile.values()) {
            var rows = study.rows().stream().filter(row -> row.profile() == profile).toList();
            work.add(new Work(profile,
                rows.stream().mapToLong(row -> row.application().result().totalWork()).sum(),
                rows.stream().mapToLong(row -> row.application().audit().work()).sum(),
                rows.stream().mapToLong(row -> row.application().selectionWork()).sum()));
        }
        return new Diagnosis(work, bounds, comparable, unverified, additional, cheaper, avoidable, differences);
    }

    private static boolean verified(Row row) {
        return row.application().result().status().equals("SOLVED") && row.application().audit().verified();
    }

    public static String toJson(Study study) {
        JsonWriter writer = new JsonWriter().beginObject();
        write(writer, analyze(study));
        return writer.endObject().toString() + "\n";
    }

    static void write(JsonWriter writer, Diagnosis diagnosis) {
        writer.property("schema", "regelsuche.representation-learning-diagnosis/v1")
            .property("purpose", "POST_EVALUATION_DIAGNOSIS_NOT_A_POLICY")
            .property("learnedQuantity", "One minimum-variable threshold from six candidates")
            .property("learnsMathematicalProcedures", false)
            .property("knowledgeAblation", false)
            .property("referenceKnowledge", "Handwritten exact solvers, decomposition, composition and development-tuned selection")
            .property("boundScope", "Verified DIRECT/MATRIX/BLOCKS executions at the recorded budgets; no global algorithmic optimum")
            .property("comparableVerifiedCases", diagnosis.comparableCases)
            .property("casesWithoutVerifiedRoute", diagnosis.unverifiedCases)
            .property("additionalSolvableCases", diagnosis.additionalSolvableCases)
            .property("cheaperVerifiedCases", diagnosis.cheaperVerifiedCases)
            .property("avoidableFixedWork", diagnosis.avoidableFixedWork)
            .property("learnedChoiceDifferences", diagnosis.learnedChoiceDifferences)
            .array("work", w -> diagnosis.work.forEach(work -> w.objectValue(p -> p
                .property("profile", work.profile.name()).property("constructionWork", work.construction)
                .property("auditWork", work.audit).property("selectionWork", work.selection)
                .property("applicationWork", work.application()))))
            .array("cases", w -> diagnosis.cases.forEach(bound -> w.objectValue(c -> c
                .property("id", bound.id).property("fixedVerified", bound.fixedVerified)
                .property("anyRouteVerified", bound.anyRouteVerified).property("fixedWork", bound.fixedWork)
                .property("bestVerifiedRouteWork", bound.bestVerifiedRouteWork)
                .property("bestVerifiedRoute", bound.bestVerifiedRoute))));
    }
}
