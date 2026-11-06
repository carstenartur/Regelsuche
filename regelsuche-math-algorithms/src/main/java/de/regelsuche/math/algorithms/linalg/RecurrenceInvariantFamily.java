package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Work;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Proposal;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantVerifier.Outcome;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.representation.RepresentationBridge.WorkLedger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Infers affine coefficient dependence from two laws, then proves it with the parameter symbolic. */
public final class RecurrenceInvariantFamily {
    private static final String PARAMETER = "parameter";
    public record Anchor(Transition transition, Proposal proposal) {
        public Anchor { Objects.requireNonNull(transition); Objects.requireNonNull(proposal); }
    }
    public record Formation(Outcome outcome, Optional<VerifiedFamily> family, WorkLedger work) {
        public Formation {
            Objects.requireNonNull(outcome); Objects.requireNonNull(family); Objects.requireNonNull(work);
            if ((outcome == Outcome.VERIFIED) != family.isPresent()) throw new IllegalArgumentException("family outcome mismatch");
        }
    }
    /** This is an untrusted concrete proposal, not an initial-state task result. */
    public record Specialization(Outcome outcome, Optional<Proposal> proposal, WorkLedger work) {
        public Specialization {
            Objects.requireNonNull(outcome); Objects.requireNonNull(proposal); Objects.requireNonNull(work);
            if ((outcome == Outcome.VERIFIED) != proposal.isPresent()) throw new IllegalArgumentException("proposal outcome mismatch");
        }
    }

    /** Issued only after the identity holds for every rational value of the varying entry. */
    public static final class VerifiedFamily {
        private final Anchor first, second;
        private final int degree, coordinate;
        private final Polynomial intercept, slope;
        private VerifiedFamily(Anchor first, Anchor second, int degree, int coordinate, Polynomial intercept, Polynomial slope) {
            this.first=first; this.second=second; this.degree=degree; this.coordinate=coordinate;
            this.intercept=intercept; this.slope=slope;
        }
        public Anchor first() { return first; }
        public Anchor second() { return second; }
        public int degree() { return degree; }
        public boolean matches(Transition source) {
            if (source.linear().columns()!=first.transition().linear().columns()) return false;
            int count=source.linear().columns()*(source.linear().columns()+1);
            for (int i=0;i<count;i++)
                if (i!=coordinate && !cell(source,i).equals(cell(first.transition(),i))) return false;
            return true;
        }
        /** The caller must independently verify the returned proposal with its own initial state. */
        public Specialization specialize(Transition source, Budget budget) {
            Objects.requireNonNull(source); Objects.requireNonNull(budget);
            var work=new Work(budget.maxWorkUnits());
            try {
                var polynomial=checkedSpecialization(source,work);
                if (polynomial==null) return specialized(Outcome.REJECTED,Optional.empty(),work);
                var text=polynomial.toCanonicalString(); work.add(text.length());
                if (text.length()>RecurrenceInvariantSynthesis.MAX_PROPOSAL_LENGTH)
                    return specialized(Outcome.UNSUPPORTED,Optional.empty(),work);
                return specialized(Outcome.VERIFIED,Optional.of(new Proposal(text,first.proposal().evolution())),work);
            } catch (RecurrenceInvariantSupport.Exhausted exhausted) {
                return specialized(Outcome.BUDGET_INCONCLUSIVE,Optional.empty(),work);
            }
        }

        /**
         * Instantiation of this issuer-owned universal proof, not a supplied proposal.
         * The verifier consumes the exact polynomial directly, so a serialized or
         * caller-constructed Specialization cannot become proof authority.
         */
        Polynomial checkedSpecialization(Transition source, Work work) {
            work.add(2L+source.linear().columns()*(source.linear().columns()+1));
            if (!matches(source)) return null;
            work.add(1L+intercept.termCount()+slope.termCount());
            var polynomial=intercept.add(slope.multiply(cell(source,coordinate)));
            return polynomial.totalDegree()==0 ? null : polynomial;
        }
    }

    public Formation form(Anchor first, Anchor second, int degree, Budget budget) {
        Objects.requireNonNull(first); Objects.requireNonNull(second); Objects.requireNonNull(budget);
        if (degree<1 || degree>2) throw new IllegalArgumentException("degree must be one or two");
        var work=new Work(budget.maxWorkUnits());
        try {
            var family=infer(first,second,degree,work);
            return new Formation(family==null ? Outcome.REJECTED : Outcome.VERIFIED,Optional.ofNullable(family),work.ledger());
        } catch (RecurrenceInvariantSupport.Exhausted exhausted) {
            return new Formation(Outcome.BUDGET_INCONCLUSIVE,Optional.empty(),work.ledger());
        } catch (IllegalArgumentException unsupported) {
            return new Formation(Outcome.UNSUPPORTED,Optional.empty(),work.ledger());
        }
    }
    private static VerifiedFamily infer(Anchor first, Anchor second, int degree, Work work) {
        int coordinate=difference(first.transition(),second.transition(),work);
        if (coordinate<0 || first.proposal().evolution()!=second.proposal().evolution()) return null;
        if (!verifyAnchor(first,work) || !verifyAnchor(second,work)) return null;
        var a=RecurrenceInvariantSupport.project(first.proposal().polynomial(),work);
        var b=RecurrenceInvariantSupport.project(second.proposal().polynomial(),work);
        if (a.totalDegree()>degree || b.totalDegree()>degree) return null;
        // Scalar multiples represent the same law; align their normalization before interpolation.
        work.add(1L+a.termCount()+b.termCount());
        var order=new de.regelsuche.math.algorithms.equivalence.GradedReverseLexOrder();
        a=a.monic(order); b=b.monic(order);
        Rational left=cell(first.transition(),coordinate), right=cell(second.transition(),coordinate);
        work.add(3L+a.termCount()+b.termCount());
        Polynomial slope=b.subtract(a).multiply(Rational.ONE.divide(right.subtract(left)));
        Polynomial intercept=a.subtract(slope.multiply(left));
        if (!universalStep(first,coordinate,intercept,slope,work)) return null;
        return new VerifiedFamily(first,second,degree,coordinate,intercept,slope);
    }
    private static boolean verifyAnchor(Anchor anchor, Work work) {
        var initial=Collections.nCopies(anchor.transition().linear().columns(),Rational.ZERO);
        var checked=new RecurrenceInvariantVerifier().verify(anchor.transition(),anchor.proposal(),initial,new Budget(work.remaining()));
        work.add(checked.work().consumedWorkUnits());
        if (checked.outcome()==Outcome.BUDGET_INCONCLUSIVE) throw new RecurrenceInvariantSupport.Exhausted();
        if (checked.outcome()==Outcome.UNSUPPORTED) throw new IllegalArgumentException("unsupported anchor");
        return checked.outcome()==Outcome.VERIFIED;
    }
    private static boolean universalStep(Anchor anchor, int coordinate, Polynomial intercept, Polynomial slope, Work work) {
        var images=parameterImages(anchor.transition(),coordinate,work);
        var nextIntercept=RecurrenceInvariantSupport.substitute(intercept,images,work);
        var nextSlope=RecurrenceInvariantSupport.substitute(slope,images,work);
        var parameter=Polynomial.variable(PARAMETER);
        work.add(2L+intercept.termCount()+slope.termCount()+nextIntercept.termCount()+nextSlope.termCount());
        var before=intercept.add(parameter.multiply(slope));
        var after=nextIntercept.add(parameter.multiply(nextSlope));
        // Matching both training points alone is never accepted as a universal law.
        return after.equals(before.multiply(Rational.of(anchor.proposal().evolution().multiplier())));
    }
    private static List<String> parameterImages(Transition source, int coordinate, Work work) {
        int dimension=source.linear().columns(), width=dimension+1;
        var images=new ArrayList<String>();
        for (int row=0;row<dimension;row++) {
            StringBuilder image=new StringBuilder("("+symbol(source,row*width+dimension,coordinate)+")");
            for (int col=0;col<dimension;col++) {
                work.add(1);
                image.append("+(").append(symbol(source,row*width+col,coordinate)).append(")*x").append(col);
            }
            images.add(image.toString());
        }
        return List.copyOf(images);
    }
    private static String symbol(Transition source, int index, int coordinate) {
        return index==coordinate ? PARAMETER : cell(source,index).toString();
    }
    private static int difference(Transition a, Transition b, Work work) {
        work.add(1);
        if (a.linear().columns()!=b.linear().columns()) return -1;
        int found=-1, count=a.linear().columns()*(a.linear().columns()+1);
        for (int i=0;i<count;i++) {
            work.add(1);
            if (cell(a,i).equals(cell(b,i))) continue;
            if (found>=0) return -1;
            found=i;
        }
        return found;
    }
    private static Rational cell(Transition source, int index) {
        int dimension=source.linear().columns(), row=index/(dimension+1), column=index%(dimension+1);
        return column==dimension ? source.offset().get(row) : source.linear().get(row,column);
    }
    private static Specialization specialized(Outcome outcome, Optional<Proposal> proposal, Work work) {
        return new Specialization(outcome,proposal,work.ledger());
    }
}
