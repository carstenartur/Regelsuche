package de.regelsuche.sdk.optimization;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves the core's canonical premise syntax against this concrete source view only.
 * Every subject must identify an actual encoded subtree and its original typed Java value.
 * There is no ambient name lookup, predicate evaluation or assumption added by search.
 */
final class JavaRulePremises {
    record SourceTerm(Expr mathematical, Expr javaValue) { }
    private final EstablishedNumericFacts facts;
    private final VerificationWork work;
    private final List<SourceTerm> terms;
    private Map<String, Expr> subjects;
    private Set<String> ambiguous;

    JavaRulePremises(EstablishedNumericFacts facts, VerificationWork work, List<SourceTerm> terms) {
        this.facts = facts;
        this.work = work;
        this.terms = terms;
    }
    boolean discharge(List<Assumption> assumptions) {
        work.charge(1);
        if (assumptions.size() > 64) return false;
        for (var assumption : assumptions) {
            work.charge(1);
            if (assumption == null || assumption.symbols().size() != 1) return false;
            String subject = assumption.symbols().getFirst();
            work.charge(subject.length() + (long) assumption.expression().length());
            if (subject.length() > 16_384) return false;
            String suffix = switch (assumption.kind()) {
                case NON_ZERO -> " != 0";
                case POSITIVE -> " > 0";
                case NON_NEGATIVE -> " >= 0";
                default -> null;
            };
            if (suffix == null || !assumption.expression().equals(subject + suffix)) return false;
            indexSubjects();
            Expr value = subjects.get(subject);
            if (value == null || ambiguous.contains(subject)) return false;
            boolean established = switch (assumption.kind()) {
                case NON_ZERO -> facts.nonzero(value);
                case POSITIVE -> facts.positive(value);
                case NON_NEGATIVE -> facts.nonnegative(value);
                default -> false;
            };
            if (!established) return false;
        }
        return true;
    }
    private void indexSubjects() {
        if (subjects != null) return;
        var indexed = new HashMap<String, Expr>();
        var collisions = new HashSet<String>();
        var structures = new HashMap<String, Expr>();
        for (var term : terms) {
            work.charge(1);
            String subject = ExpressionFormatter.format(term.mathematical());
            Expr previous = indexed.putIfAbsent(subject, term.javaValue());
            Expr previousStructure = structures.putIfAbsent(subject, term.mathematical());
            // Formatting may omit associative parentheses. Reject ambiguous text
            // instead of transferring a fact between different source structures.
            if (previous != null && (!previous.equals(term.javaValue()) || !previousStructure.equals(term.mathematical())))
                collisions.add(subject);
        }
        // Formatting and work limits can interrupt indexing. Publish only a
        // complete index; a later rule must never consume a successful prefix.
        subjects = indexed;
        ambiguous = collisions;
    }
}
