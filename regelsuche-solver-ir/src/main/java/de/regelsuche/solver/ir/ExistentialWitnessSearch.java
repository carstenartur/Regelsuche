package de.regelsuche.solver.ir;

import de.regelsuche.solver.ir.ExistentialIntroduction.Hypothesis;
import de.regelsuche.solver.ir.ExistentialIntroduction.NormalForm;
import de.regelsuche.solver.ir.ExistentialIntroduction.Proof;
import de.regelsuche.solver.ir.ExistentialIntroduction.Statement;
import de.regelsuche.solver.ir.SolverIr.Binary;
import de.regelsuche.solver.ir.SolverIr.BinaryOperator;
import de.regelsuche.solver.ir.SolverIr.Expression;
import de.regelsuche.solver.ir.SolverIr.Literal;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Symbol;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Finite goal-directed introduction for the existing single-binder ring fragment. */
public final class ExistentialWitnessSearch {
    private static final int MAX_TRIALS = 4096;
    private static final int MAX_SEEDS = 32;
    private static final List<BinaryOperator> OPERATIONS = List.of(
        BinaryOperator.ADD, BinaryOperator.SUBTRACT, BinaryOperator.MULTIPLY);

    public enum Status { FOUND, BUDGET_EXHAUSTED, GRAMMAR_EXHAUSTED }
    public record Result(Status status, int examined, Optional<Proof> proof) {}

    private final Statement target;
    private final int budget;
    private final Set<Expression> seen = new HashSet<>();
    private int examined;

    private ExistentialWitnessSearch(Statement target, int budget) {
        this.target = Objects.requireNonNull(target);
        if (budget < 0 || budget > MAX_TRIALS) throw new IllegalArgumentException("require 0–4096 candidate trials");
        this.budget = budget;
    }

    /** Search seed terms, then one ordered ring operation on pairs of seeds, stopping on the first replayed proof. */
    public static Result search(Statement target, int maximumTrials) {
        return new ExistentialWitnessSearch(target, maximumTrials).run();
    }

    private Result run() {
        if (budget == 0) return new Result(Status.BUDGET_EXHAUSTED, 0, Optional.empty());
        var seeds = seeds();
        for (var seed : seeds) {
            var stop = trial(seed);
            if (stop != null) return stop;
        }
        for (var left : seeds) {
            for (var right : seeds) {
                for (var operator : OPERATIONS) {
                    var stop = trial(new Binary(operator, left, right));
                    if (stop != null) return stop;
                }
            }
        }
        return new Result(Status.GRAMMAR_EXHAUSTED, examined, Optional.empty());
    }

    private List<Expression> seeds() {
        var terms = new LinkedHashSet<Expression>();
        terms.add(new Literal("0"));
        terms.add(new Literal("1"));
        for (var declaration : target.body().declarations()) {
            if (!declaration.name().equals(target.binder())) terms.add(new Symbol(declaration.name()));
            if (terms.size() == MAX_SEEDS) return List.copyOf(terms);
        }
        collect(target.body().goal().left(), terms);
        collect(target.body().goal().right(), terms);
        for (var premise : target.body().assumptions()) {
            if (terms.size() == MAX_SEEDS) break;
            collect(premise.left(), terms);
            collect(premise.right(), terms);
        }
        return List.copyOf(terms);
    }

    private void collect(Expression term, Set<Expression> terms) {
        if (terms.size() == MAX_SEEDS) return;
        if (!term.referencedSymbols().contains(target.binder())) terms.add(term);
        if (term instanceof Binary binary) {
            collect(binary.left(), terms);
            collect(binary.right(), terms);
        }
    }

    /** A null result continues enumeration. An invalid expanded instance still consumes a trial. */
    private Result trial(Expression witness) {
        if (!seen.add(witness)) return null;
        if (examined == budget) return new Result(Status.BUDGET_EXHAUSTED, examined, Optional.empty());
        examined++;
        Obligation instance;
        try {
            instance = target.instantiate(witness);
        } catch (IllegalArgumentException outsideRingBounds) {
            return null;
        }
        var proof = proofCandidate(witness, instance);
        if (proof != null && ExistentialIntroduction.replay(target, proof)) {
            return new Result(Status.FOUND, examined, Optional.of(proof));
        }
        return null;
    }

    private Proof proofCandidate(Expression witness, Obligation instance) {
        if (instance.assumptions().isEmpty()) return new Proof(target, witness, new NormalForm(instance));
        // The existing normal-form leaf accepts assumption-free instances only.
        for (var premise : instance.assumptions()) {
            if (premise.left().equals(instance.goal().left()) && premise.right().equals(instance.goal().right())) {
                return new Proof(target, witness, new Hypothesis(instance, premise.id()));
            }
        }
        return null;
    }
}
