package de.regelsuche.evolution;

import de.regelsuche.evolution.StructuralConstructionPrograms.Limit;
import de.regelsuche.evolution.StructuralConstructionPrograms.PreparedResidual;
import de.regelsuche.evolution.StructuralConstructionPrograms.Work;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialResidualComposer.Composition;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialResidualComposer.Effect;
import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.search.program.RewriteProgram;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MovePriorityPolicy;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.MoveState;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Bounded pair selector. Every admitted pair still goes through the native exact composition checker. */
public final class ResidualPlanController {
    public enum Mode { COMPLETE_REFERENCE, DISCOVERY }
    public record State(String key, String status, boolean done, long work, long remainingWork,
                        int preparedGrowthCharacters, int unresolvedObligations) { }
    public record Step(String action, List<String> effects, List<String> proofRoots) {
        public Step { effects = List.copyOf(effects); proofRoots = List.copyOf(proofRoots); }
    }
    public static Session open(String source, Mode mode, int mask, long budget) {
        return new Session(Objects.requireNonNull(source).strip().replaceAll("\\s+", " "), mode, mask, budget);
    }

    /** Search the source's residual alternatives through the staged frontier and its independent move verifier. */
    public static MoveSearch.Result search(String source, MoveContext context, Mode mode, int mask,
                                           long formationBudget, long replayBudget, MoveSearch.Budget searchBudget) {
        var root = new MoveState(source, 0, 0, "", context.initialAssumptions(), java.util.Set.of(), 0);
        var plans = new ResidualPlanProvider(root, context, mode, mask, formationBudget, replayBudget);
        var problem = new MoveSearch.Problem(source, context, List.of(plans.provider()), MovePriorityPolicy.INVENTORY_ORDER,
            plans, state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED_INCREMENTAL, searchBudget);
        return new MoveSearch().search(problem);
    }

    /** A suspended run of this controller, sharing its native checker and selectors. */
    public static final class Session {
        private record Issued(List<String> effects, long acquisitionWork, int pairCount) { }
        private final Map<String,Issued> issued = new TreeMap<>();
        private final String source;
        private final String sourceHash;
        private final int mask;
        private final long budget;
        private final Work work;
        private final ResidualPlanController controller;
        private StructuralConstructionPrograms.ResidualPreparation preparation;
        private ResidualPlanController.PairCursor cursor;
        private ResidualPlanController.Pair pending;
        private String status = "ACTIVE";

        private Session(String source, Mode mode, int mask, long budget) {
            this(source, mode, mask, budget, new Work(budget));
            while (status.equals("PREPARING")) prepareNext();
        }

        Session(String source, Mode mode, int mask, long budget, Work work) {
            Objects.requireNonNull(mode);
            if (mask < 0 || mask > 7) throw new IllegalArgumentException("selector mask must be in [0,7]");
            this.mask = mode == Mode.COMPLETE_REFERENCE ? 0 : mask;
            this.budget = budget;
            this.source = source;
            this.work = work;
            this.controller = new ResidualPlanController(source, work);
            this.sourceHash = SolverIr.sha256("residual-search/v1\n" + source);
            status = "PREPARING";
        }

        void prepareNext() {
            try {
                if (preparation == null) {
                    work.add(source.length() + 1L);
                    preparation = new StructuralConstructionPrograms.ResidualPreparation(source, work, true);
                }
                preparation.advance();
                if (!preparation.done) return;
                controller.material = preparation.result;
                preparation = null;
                if (controller.material == null || controller.material.parts().size() != 4) status = "UNSUPPORTED";
                else { cursor = controller.cursor(); status = "ACTIVE"; }
            } catch (Limit exhausted) {
                status = "BUDGET_EXHAUSTED";
            } catch (IllegalArgumentException unsupported) {
                status = "UNSUPPORTED";
            }
        }

        String status() { return status; }

        /** Source-bound typed holes and obligations of the selected, still unchecked pair. */
        public java.util.Optional<SchematicProofPlan> openPlan() {
            return pending == null ? java.util.Optional.empty()
                : java.util.Optional.of(ResidualPlanCompiler.plan(source, controller.material.expression()));
        }

        public State state() {
            boolean done = !status.equals("ACTIVE");
            long remaining = budget - work.units();
            // Cache contents are deterministic from the source, mask and enumeration prefix.
            // Completed prefixes also determine which proof roots can be compiled and their required work.
            String continuation = done ? status : cursor.offset() + "|" + (pending == null ? "select" : pending.key());
            String future = sourceHash + "|" + mask + "|" + remaining + "|" + continuation + "|" + issued;
            return new State(SolverIr.sha256(future), status, done, work.units(), remaining,
                controller.preparedGrowth(), pending == null ? 0 : 4);
        }

        public Step advance() {
            if (!status.equals("ACTIVE")) throw new IllegalStateException("residual controller is terminal");
            try {
                Step step = pending == null ? select() : discharge();
                if (pending == null && !cursor.hasNext()) status = "COMPLETE";
                return step;
            } catch (Limit exhausted) {
                var effects = pending == null ? List.<String>of() : effectIds(pending);
                pending = null;
                status = "BUDGET_EXHAUSTED";
                return new Step(status, effects, List.of());
            }
        }

        private Step select() {
            var pair = cursor.next();
            if (!controller.admit(pair, mask)) return new Step("SELECTOR_REJECTED", effectIds(pair), List.of());
            pending = pair;
            return new Step("OBLIGATIONS_OPEN", effectIds(pair), List.of());
        }

        private Step discharge() {
            var results = controller.check(pending);
            var ids = effectIds(pending);
            pending = null;
            for (var candidate : results) {
                issued.putIfAbsent(candidate.root(), new Issued(ids, work.units(), cursor.offset()));
            }
            var roots = results.stream().map(ResidualPlanController.Candidate::root).toList();
            return new Step(roots.isEmpty() ? "EXACT_REJECTED" : "EXACT_ACCEPTED", ids, roots);
        }

        /** The first accepted prefix is charged alongside the independent compile-time proof replay. */
        public RewriteProgram.BudgetedSource compileAccepted(String proofRoot, long verifierBudget) {
            var prefix = issued.get(proofRoot);
            if (prefix == null) throw new IllegalArgumentException("proof root has not been issued by this search");
            String acquisition = SolverIr.sha256(sourceHash + "|" + mask + "|" + prefix.pairCount() + "|" + prefix.acquisitionWork());
            return ResidualPlanCompiler.compileSearch(source, prefix.effects(), proofRoot, verifierBudget, prefix.acquisitionWork(), acquisition);
        }

        private static List<String> effectIds(ResidualPlanController.Pair pair) {
            return List.of(pair.left().id(), pair.right().id());
        }
    }

    enum Arm { LEARNED, FIXED, RANDOM, EXHAUSTIVE, HAND, ALL_AT_ONCE }
    record Pair(Effect left, Effect right) { String key() { return left.id() + "|" + right.id(); } }
    record Signature(Monomial powers, BigInteger absNumerator, BigInteger denominator, int sign) { }
    record Decision(String pair, String status) { }
    record Candidate(String root, String expression, List<String> effects, List<String> primitiveRules,
                     List<String> applications, List<String> assumptions, String residual) { }
    record Report(String arm, String status, String preparedSource, long work, long prepareWork, List<Decision> decisions,
                  List<Effect> localEffects, List<Candidate> candidates, String candidateFreeze) { }

    private final String source;
    private final Work work;
    private final ExactPolynomialAnalysis exact;
    private final Map<String,Signature> features = new HashMap<>();
    private final List<Decision> decisions = new ArrayList<>();
    private final Map<String,Candidate> retained = new TreeMap<>();
    private PreparedResidual material;
    private long prepareWork;

    ResidualPlanController(String source) { this(source, new Work()); }

    ResidualPlanController(String source, Work work) {
        this.source = source;
        this.work = work;
        this.exact = ExactPolynomialAnalysis.withProductWork(work::add);
    }

    boolean prepare() {
        work.add(source.length() + 1L);
        material = StructuralConstructionPrograms.prepareResidual(source, work, true);
        prepareWork = work.units();
        return material != null && material.parts().size() == 4;
    }

    int preparedGrowth() { return material == null ? 0 : material.expression().length() - source.length(); }


    Report run(Arm arm, int learnedMask, int ordinal) {
        String status = "COMPLETE";
        try {
            if (!prepare()) status = "UNSUPPORTED";
            else if (arm == Arm.ALL_AT_ONCE) batch();
            else runPairs(arm, learnedMask, ordinal);
        } catch (Limit stopped) {
            status = "BUDGET_EXHAUSTED";
        }
        var candidates = List.copyOf(retained.values());
        String freeze = SolverIr.sha256("residual-candidates/v1\n" + candidates.stream().map(Candidate::root).toList());
        return new Report(arm.name(), status, material == null ? "" : material.expression(), work.units(), prepareWork, List.copyOf(decisions),
            material == null ? List.of() : material.effects(), candidates, freeze);
    }

    private void batch() {
        var result = material.composer().compose(material.expression(), material.parts(), material.effects(), 2, 8);
        decisions.add(new Decision("native-composition-enumeration", "NATIVE_BATCH"));
        result.forEach(this::retain);
    }

    private void runPairs(Arm arm, int learnedMask, int ordinal) {
        int mask = switch (arm) {
            case LEARNED -> learnedMask;
            case FIXED, RANDOM -> 1;
            case HAND -> 7;
            default -> 0;
        };
        if (arm == Arm.RANDOM) {
            var available = new ArrayList<Pair>();
            pairs(pair -> {
                if (admit(pair, mask)) available.add(pair);
                else decisions.add(new Decision(pair.key(), "SELECTOR_REJECTED"));
            });
            work.add(available.size());
            Collections.shuffle(available, new Random(874235L + ordinal));
            available.forEach(this::check);
        } else {
            pairs(pair -> {
                if (admit(pair, mask)) check(pair);
                else decisions.add(new Decision(pair.key(), "SELECTOR_REJECTED"));
            });
        }
    }

    private void pairs(Consumer<Pair> receiver) {
        var cursor = cursor();
        while (cursor.hasNext()) receiver.accept(cursor.next());
    }

    PairCursor cursor() { return new PairCursor(material.effects(), work); }

    static final class PairCursor {
        private final List<Effect> effects;
        private final Work work;
        private int first;
        private int second = 1;
        private int offset;

        PairCursor(List<Effect> effects, Work work) {
            this.effects = effects.stream().sorted(Comparator.comparing(Effect::id)).toList();
            this.work = work;
        }
        int offset() { return offset; }
        boolean hasNext() { return second < effects.size(); }
        Pair next() {
            if (!hasNext()) throw new java.util.NoSuchElementException("source pairs exhausted");
            work.add(1);
            var pair = new Pair(effects.get(first), effects.get(second));
            offset++;
            if (++second == effects.size()) second = ++first + 1;
            return pair;
        }
    }

    boolean admit(Pair p, int mask) {
        if ((mask & 1) != 0) {
            work.add(1);
            if (!Collections.disjoint(p.left().componentIds(), p.right().componentIds())) return false;
        }
        if ((mask & 6) == 0) return true;
        var a = signature(p.left());
        var b = signature(p.right());
        if (a == null || b == null) return false;
        if ((mask & 2) != 0) {
            work.add(1);
            if (a.sign() != -b.sign()) return false;
        }
        if ((mask & 4) != 0) {
            work.add(1);
            return a.powers().equals(b.powers()) && a.absNumerator().equals(b.absNumerator())
                && a.denominator().equals(b.denominator());
        }
        return true;
    }

    private Signature signature(Effect e) {
        work.add(1);
        return features.computeIfAbsent(e.id(), ignored -> {
            var terms = exact.normalForm(e.residualNormalForm()).terms();
            if (terms.size() != 1) return null;
            var entry = terms.entrySet().iterator().next();
            var value = entry.getValue();
            return new Signature(entry.getKey(), value.numerator().abs(), value.denominator(), value.numerator().signum());
        });
    }

    List<Candidate> check(Pair p) {
        try {
            var result = material.composer().compose(material.expression(), material.parts(), List.of(p.left(),p.right()), 2, 1);
            var candidates = result.stream().map(this::retain).toList();
            decisions.add(new Decision(p.key(), result.isEmpty() ? "EXACT_REJECTED" : "EXACT_ACCEPTED"));
            return candidates;
        } catch (Limit stopped) {
            decisions.add(new Decision(p.key(), "BUDGET_EXHAUSTED"));
            throw stopped;
        }
    }

    private Candidate retain(Composition c) {
        exact.requireEquivalent(source, c.candidateExpression());
        var candidate = checkedCandidate(source, material.expression(), c);
        retained.putIfAbsent(candidate.root(), candidate);
        return candidate;
    }

    static Candidate checkedCandidate(String source, String prepared, Composition c) {
        List<String> ids = c.effects().stream().map(Effect::id).sorted().toList();
        List<String> premises = c.effects().stream().flatMap(e -> e.assumptions().stream()).distinct().sorted().toList();
        String root = SolverIr.sha256("residual-composition/v1\n" + source + "\n" + prepared + "\n" + c.candidateExpression()
            + "\n" + ids + "\n" + c.primitiveRuleIds() + "\n" + c.applicationKeys() + "\n" + premises + "\n" + c.combinedResidualNormalForm());
        return new Candidate(root, c.candidateExpression(), ids, c.primitiveRuleIds(), c.applicationKeys(), premises,
            c.combinedResidualNormalForm());
    }
}
