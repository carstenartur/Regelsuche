package de.regelsuche.evolution;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.math.algorithms.linalg.LinearRepresentationPlanner;
import de.regelsuche.math.algorithms.linalg.LinearRepresentationJson;
import de.regelsuche.math.algorithms.linalg.LinearRepresentationPlanner.Audit;
import de.regelsuche.math.algorithms.linalg.LinearRepresentationPlanner.Result;
import de.regelsuche.math.algorithms.linalg.LinearRepresentationPlanner.Route;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Learns one finite branch condition from verified training executions.
 * The split/solve/compose skeleton and mathematical solvers are fixed.
 * Evaluation cases are never supplied to fit, and Policy has no mutation API.
 */
public final class RepresentationStrategyLearner {
    public static final List<Integer> THRESHOLDS = List.of(2, 4, 6, 8, 12, 17);
    public enum Profile { DIRECT, MATRIX, BLOCKS, FIXED_AUTO, RANDOM, LEARNED }
    public record Observation(int trainingIndex, Result result, Audit audit) {
        public int totalWork() { return result.totalWork() + audit.work(); }
    }
    public record Application(Result result, Audit audit, int selectionWork) {
        public int totalWork() { return result.totalWork() + audit.work() + selectionWork; }
    }
    public static final class Policy {
        private final int minimumVariables;
        private final int fitWork;
        private final List<Observation> observations;
        private final Set<String> trainingIdentities;
        private Policy(int minimumVariables, int fitWork, List<Observation> observations, Set<String> identities) {
            this.minimumVariables = minimumVariables;
            this.fitWork = fitWork;
            this.observations = List.copyOf(observations);
            this.trainingIdentities = Set.copyOf(identities);
        }
        public int minimumVariables() { return minimumVariables; }
        public int fitWork() { return fitWork; }
        public List<Observation> observations() { return observations; }
        public Set<String> trainingIdentities() { return trainingIdentities; }
        public int learningWork() {
            return fitWork + observations.stream().mapToInt(Observation::totalWork).sum();
        }
    }

    public Policy fit(List<List<String>> training, int budget) {
        if (training.isEmpty() || training.size() > 32) throw new IllegalArgumentException("Expected 1 to 32 training cases");
        var planner = new LinearRepresentationPlanner();
        List<Observation> observations = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (int i = 0; i < training.size(); i++) {
            String identity = identity(training.get(i));
            if (!identities.add(identity)) throw new IllegalArgumentException("Repeated alpha-polynomial training case");
            for (Route route : List.of(Route.DIRECT, Route.MATRIX, Route.BLOCKS)) {
                Result result = planner.solve(training.get(i), route, budget);
                observations.add(new Observation(i, result, planner.audit(result, LinearRepresentationJson.AUDIT_BUDGET)));
            }
        }
        int best = THRESHOLDS.getFirst();
        int bestSolved = -1;
        long bestWork = Long.MAX_VALUE;
        int fitWork = 0;
        for (int threshold : THRESHOLDS) {
            int solved = 0;
            long work = 0;
            for (int i = 0; i < training.size(); i++) {
                Observation direct = observations.get(i * 3);
                Result source = direct.result;
                boolean blocks = source.blocks().size() > 1 && source.variables().size() >= threshold;
                Observation chosen = observations.get(i * 3 + (blocks ? 2 : 0));
                fitWork += 2;
                if (chosen.audit.verified()) solved++;
                work += chosen.totalWork();
            }
            if (solved > bestSolved || solved == bestSolved && work < bestWork) {
                best = threshold; bestSolved = solved; bestWork = work;
            }
        }
        return new Policy(best, fitWork, observations, identities);
    }

    public Application apply(Policy policy, List<String> source, Profile profile, int budget) {
        var planner = new LinearRepresentationPlanner();
        var plan = planner.prepare(source, budget);
        int selectionWork = profile == Profile.LEARNED || profile == Profile.RANDOM ? 2 : 0;
        Route route = switch (profile) {
            case DIRECT -> Route.DIRECT;
            case MATRIX -> Route.MATRIX;
            case BLOCKS -> Route.BLOCKS;
            case FIXED_AUTO -> Route.AUTO;
            case LEARNED -> plan.independent() && plan.variables().size() >= policy.minimumVariables
                ? Route.BLOCKS : Route.DIRECT;
            // Fixed public source-shape seed; no evaluation outcome is consulted.
            case RANDOM -> List.of(Route.DIRECT, Route.MATRIX, Route.BLOCKS).get(
                Math.floorMod(730 + plan.variables().size() * 31 + source.size() * 17 + plan.blocks().size(), 3));
        };
        // Selection work is reported separately, like the independent audit.
        Result result = planner.execute(plan, route, budget);
        return new Application(result, planner.audit(result, LinearRepresentationJson.AUDIT_BUDGET), selectionWork);
    }

    /** Conservative row-wise alpha-polynomial exclusion, not system semantic equivalence.
     * Unsupported identities fail closed instead of establishing disjointness.
     * Parsing/canonicalization are protocol preprocessing outside solver work units.
     */
    public static String identity(List<String> source) {
        var analysis = new ExactPolynomialAnalysis();
        List<String> rows = new ArrayList<>();
        for (String text : source) {
            String[] equation = text.split("=", -1);
            if (equation.length != 2) throw new IllegalArgumentException("Expected equation");
            rows.add(analysis.alphaIdentity("(" + equation[0] + ")-(" + equation[1] + ")"));
        }
        return String.join("\n", rows.stream().sorted().toList());
    }
}
