package de.regelsuche.benchmarks;

import de.regelsuche.benchmarks.ScopedProofPartitions.Case;
import de.regelsuche.solver.ir.*;
import de.regelsuche.solver.ir.ExistentialIntroduction.Proof;
import de.regelsuche.solver.ir.ExistentialIntroduction.Statement;
import de.regelsuche.solver.ir.SolverIr.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/** Shared receiver-side replay for every arm of the scoped synthetic protocol. */
final class ScopedProofTrials {
    static final List<String> GRAMMAR = List.of("ZERO", "B", "ADD", "SUBTRACT_B_A", "SUBTRACT_A_B", "MULTIPLY");
    enum Arm { LEARNED, FIXED, RANDOM, EXHAUSTIVE, HAND, ORDINARY }
    record Trial(String proposal, String term, String baseId, String stepId, String instanceHash,
                 String proofRoot, boolean accepted, int checkerCalls) { }
    record Row(String arm, String status, boolean selectedPlanApplied, List<Trial> trials, int checkerCalls) { }
    record Shape(String key, Expression a, Expression b) { }
    private static final CoreExpressionIrAdapter TERMS = new CoreExpressionIrAdapter();

    static Shape shape(Statement s) {
        var goal = s.body().goal();
        for (var side : List.of(goal.left(), goal.right())) {
            Expression opposite = side == goal.left() ? goal.right() : goal.left();
            if (opposite.referencedSymbols().contains(s.binder()) || !(side instanceof Binary binary)) continue;
            var binder = new Symbol(s.binder());
            if (binary.left().equals(binder) && !binary.right().referencedSymbols().contains(s.binder()))
                return new Shape(binary.operator().name() + "_LEFT", binary.right(), opposite);
            if (binary.right().equals(binder) && !binary.left().referencedSymbols().contains(s.binder()))
                return new Shape(binary.operator().name() + "_RIGHT", binary.left(), opposite);
        }
        return new Shape("UNMATCHED", null, null);
    }

    static Row run(Case c, int ordinal, Arm arm, List<String> learnedOrder, boolean baseFirst) {
        var trials = new ArrayList<Trial>();
        if (c.witness()) {
            var source = new Statement(c.source(), c.binder());
            var recipient = new Statement(new SolverIrJsonCodec().readObligation(c.source().toCanonicalJson()), c.binder());
            if (!recipient.toScopedObligation().equals(c.receiver())) throw new IllegalArgumentException("recipient differs");
            if (arm == Arm.ORDINARY) {
                ExistentialWitnessSearch.search(source, 6, (term, proof) ->
                    trials.add(witnessTrial(recipient, "ORDINARY", term, proof)));
            } else {
                var shape = shape(source);
                var order = witnessOrder(arm, shape, learnedOrder, ordinal);
                var seen = new HashSet<Expression>();
                for (String proposal : order) {
                    var term = term(proposal, shape);
                    if (!seen.add(term)) continue;
                    var proof = ExistentialWitnessSearch.checkWitness(source, term);
                    var trial = witnessTrial(recipient, proposal, term, proof);
                    trials.add(trial);
                    if (trial.accepted() && arm != Arm.EXHAUSTIVE) break;
                }
            }
        } else {
            inductionTrials(c, ordinal, arm, baseFirst, trials);
        }
        boolean found = trials.stream().anyMatch(Trial::accepted);
        String status = found ? "FOUND" : trials.size() == 6 ? "BUDGET_EXHAUSTED" : "GRAMMAR_EXHAUSTED";
        return new Row(arm.name(), status, arm == Arm.LEARNED && found, List.copyOf(trials),
            trials.stream().mapToInt(Trial::checkerCalls).sum());
    }

    private static List<String> witnessOrder(Arm arm, Shape shape, List<String> learned, int ordinal) {
        if (shape.a() == null) return List.of("ZERO");
        var order = new ArrayList<>(arm == Arm.LEARNED ? learned : GRAMMAR);
        if (arm == Arm.RANDOM) Collections.shuffle(order, new Random(8741066L + ordinal));
        if (arm == Arm.HAND) return switch (shape.key()) {
            case "ADD_LEFT", "ADD_RIGHT" -> List.of("SUBTRACT_B_A");
            case "SUBTRACT_LEFT" -> List.of("ADD");
            case "SUBTRACT_RIGHT" -> List.of("SUBTRACT_A_B");
            default -> List.of("ZERO");
        };
        return order;
    }

    private static Expression term(String rule, Shape s) {
        return switch (rule) {
            case "ZERO" -> new Literal("0");
            case "B" -> s.b();
            case "ADD" -> new Binary(BinaryOperator.ADD, s.b(), s.a());
            case "SUBTRACT_B_A" -> new Binary(BinaryOperator.SUBTRACT, s.b(), s.a());
            case "SUBTRACT_A_B" -> new Binary(BinaryOperator.SUBTRACT, s.a(), s.b());
            case "MULTIPLY" -> new Binary(BinaryOperator.MULTIPLY, s.b(), s.a());
            default -> throw new IllegalArgumentException("unknown proposal");
        };
    }

    private static Trial witnessTrial(Statement recipient, String rule, Expression witness, Optional<Proof> proof) {
        String term = TERMS.render(witness);
        if (proof.isEmpty()) return new Trial(rule, term, "", "", "", "", false, 1);
        var candidate = proof.get();
        String instance = candidate.premise().instance().contentHash();
        String root = SolverIr.sha256("witness/v1\n" + candidate.conclusion().toScopedObligation().contentHash()
            + "\n" + witness.canonicalMaterial() + "\nnormal-form=" + instance);
        boolean accepted = ExistentialIntroduction.replay(recipient, candidate);
        return new Trial(rule, term, "", "", instance, root, accepted, 2);
    }

    private static void inductionTrials(Case c, int ordinal, Arm arm, boolean baseFirst, List<Trial> trials) {
        var declarations = new ArrayList<>(c.source().declarations());
        if (arm == Arm.RANDOM) Collections.shuffle(declarations, new Random(8741066L + ordinal));
        if (arm == Arm.HAND || arm == Arm.LEARNED && baseFirst) {
            String first = firstBase(c.source());
            declarations.sort(Comparator.comparing(d -> !d.name().equals(first)));
        }
        var recipient = new SolverIrJsonCodec().readObligation(c.receiver().toCanonicalJson());
        // The ordinary native search enumerates all candidates, even if an early one will match.
        var ordinary = arm == Arm.ORDINARY ? NaturalInduction.discover(c.source()) : List.<NaturalInduction.Plan>of();
        for (var d : declarations) {
            var proposal = arm == Arm.ORDINARY ? ordinary.stream().filter(p -> p.predicate().equals(d.name())).findFirst()
                : NaturalInduction.propose(c.source(), d);
            var trial = inductionTrial(recipient, d, proposal);
            trials.add(trial);
            if (trial.accepted() && arm != Arm.EXHAUSTIVE && arm != Arm.ORDINARY) break;
        }
    }

    static String firstBase(Obligation o) {
        for (var premise : o.assumptions()) {
            if (premise.relation() == Relation.HOLDS && premise.left() instanceof Call c && c.function().equals("at")
                    && c.arguments().size() == 2 && c.arguments().get(0) instanceof Symbol p
                    && c.arguments().get(1).equals(ScopedProofPartitions.app("nat", new Literal("0")))) return p.name();
        }
        return "";
    }

    private static Trial inductionTrial(Obligation receiver, SymbolDeclaration d, Optional<NaturalInduction.Plan> plan) {
        if (plan.isEmpty()) return new Trial(d.name(), "", "", "", "", "", false, 1);
        var candidate = plan.get();
        String conclusion = candidate.conclusion().contentHash();
        String root = SolverIr.sha256("induction/v1\n" + conclusion + "\n" + d.name() + "\n"
            + candidate.base().id() + "\n" + candidate.step().id());
        return new Trial(d.name(), "", candidate.base().id(), candidate.step().id(), conclusion, root,
            NaturalInduction.matches(receiver, candidate), 2);
    }
    private ScopedProofTrials() { }
}
