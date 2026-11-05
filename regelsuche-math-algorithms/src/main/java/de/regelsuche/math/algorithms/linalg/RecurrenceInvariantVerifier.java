package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Work;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantFamily.VerifiedFamily;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Evolution;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Proposal;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.representation.RepresentationBridge.WorkLedger;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Independent symbolic step/base checker, not a replay of observed numerical samples. */
public final class RecurrenceInvariantVerifier {
    public enum Outcome { VERIFIED, REJECTED, UNSUPPORTED, BUDGET_INCONCLUSIVE }
    public record Verification(Outcome outcome, Optional<VerifiedLaw> law, WorkLedger work) {
        public Verification {
            Objects.requireNonNull(outcome, "outcome"); Objects.requireNonNull(law, "law"); Objects.requireNonNull(work, "work");
            if ((outcome == Outcome.VERIFIED) != law.isPresent()) throw new IllegalArgumentException("only verified results carry a law");
        }
    }

    /** Issuer-owned proof of P(T^n(initial)) = multiplier^n P(initial), for every n >= 0. */
    public static final class VerifiedLaw {
        private final Transition transition;
        private final Proposal proposal;
        private final List<Rational> initialState;
        private final Rational initialValue;
        private VerifiedLaw(Transition transition, Proposal proposal, List<Rational> initialState, Rational initialValue) {
            this.transition = transition; this.proposal = proposal;
            this.initialState = List.copyOf(initialState); this.initialValue = initialValue;
        }
        public Transition transition() { return transition; }
        public Proposal proposal() { return proposal; }
        public List<Rational> initialState() { return initialState; }
        public Rational initialValue() { return initialValue; }
        /** Value of the proved invariant, NOT a closed form for each coordinate of the recurrence. */
        public Rational valueAt(BigInteger index) {
            Objects.requireNonNull(index, "index");
            if (index.signum() < 0) throw new IllegalArgumentException("index must be nonnegative");
            return proposal.evolution() == Evolution.ALTERNATING && index.testBit(0) ? initialValue.negate() : initialValue;
        }
    }

    public Verification verify(Transition source, Proposal proposal, List<Rational> initialState, Budget budget) {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(proposal, "proposal"); Objects.requireNonNull(budget, "budget");
        var initial = RecurrenceInvariantSynthesis.checkedInitial(source, initialState);
        var work = new Work(budget.maxWorkUnits());
        try {
            return check(source, proposal, initial, work);
        } catch (RecurrenceInvariantSupport.Exhausted exhausted) {
            return withoutLaw(Outcome.BUDGET_INCONCLUSIVE, work);
        } catch (IllegalArgumentException unsupported) {
            return withoutLaw(Outcome.UNSUPPORTED, work);
        }
    }

    /**
     * Checks an instance using a previously issued universal family proof. Fixed
     * source entries and the concrete initial value are checked on every call.
     * The already proved symbolic step is not expanded again. This accepts only
     * an immutable VerifiedFamily, never an untrusted persisted proposal.
     * Use verify(source, proposal, initial, budget) for an independent full replay.
     */
    public Verification verifyFamily(VerifiedFamily family, Transition source,
            List<Rational> initialState, Budget budget) {
        Objects.requireNonNull(family, "family"); Objects.requireNonNull(source, "source");
        Objects.requireNonNull(budget, "budget");
        var initial=RecurrenceInvariantSynthesis.checkedInitial(source,initialState);
        var work=new Work(budget.maxWorkUnits());
        try {
            var polynomial=family.checkedSpecialization(source,work);
            if (polynomial==null) return withoutLaw(Outcome.REJECTED,work);
            String text=polynomial.toCanonicalString();
            work.add(text.length());
            if (text.length()>RecurrenceInvariantSynthesis.MAX_PROPOSAL_LENGTH)
                return withoutLaw(Outcome.UNSUPPORTED,work);
            var proposal=new Proposal(text,family.first().proposal().evolution());
            return bindInitial(source,proposal,polynomial,initial,work);
        } catch (RecurrenceInvariantSupport.Exhausted exhausted) {
            return withoutLaw(Outcome.BUDGET_INCONCLUSIVE,work);
        } catch (IllegalArgumentException unsupported) {
            return withoutLaw(Outcome.UNSUPPORTED,work);
        }
    }

    private static Verification check(Transition source, Proposal proposal, List<Rational> initial, Work work) {
        var polynomial = RecurrenceInvariantSupport.project(proposal.polynomial(), work);
        var variables = RecurrenceInvariantSupport.variables(source.linear().columns());
        work.add(polynomial.termCount());
        if (polynomial.totalDegree() == 0 || !variables.containsAll(polynomial.variables()))
            return withoutLaw(Outcome.REJECTED, work);
        if (polynomial.totalDegree() > 2) return withoutLaw(Outcome.UNSUPPORTED, work);
        var images = RecurrenceInvariantSupport.images(source, work);
        Polynomial transformed = RecurrenceInvariantSupport.substitute(polynomial, images, work);
        work.add(polynomial.termCount() + transformed.termCount());
        if (!transformed.equals(polynomial.multiply(Rational.of(proposal.evolution().multiplier()))))
            return withoutLaw(Outcome.REJECTED, work);
        return bindInitial(source,proposal,polynomial,initial,work);
    }

    /** The same independently calculated base case for full replay and proof reuse. */
    private static Verification bindInitial(Transition source, Proposal proposal, Polynomial polynomial,
            List<Rational> initial, Work work) {
        var initialImages = initial.stream().map(Rational::toString).toList();
        Polynomial value = RecurrenceInvariantSupport.substitute(polynomial, initialImages, work);
        work.add(value.termCount() + 1L);
        if (!value.variables().isEmpty()) throw new IllegalStateException("initial substitution did not eliminate all coordinates");
        Rational initialValue = value.isZero() ? Rational.ZERO : value.terms().values().iterator().next();
        var canonicalProposal = new Proposal(polynomial.toCanonicalString(), proposal.evolution());
        var law = new VerifiedLaw(source, canonicalProposal, initial, initialValue);
        return new Verification(Outcome.VERIFIED, Optional.of(law), work.ledger());
    }

    private static Verification withoutLaw(Outcome outcome, Work work) {
        return new Verification(outcome, Optional.empty(), work.ledger());
    }
}
