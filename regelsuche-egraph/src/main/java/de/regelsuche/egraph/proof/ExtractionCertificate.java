package de.regelsuche.egraph.proof;

import de.regelsuche.transform.PatternExpr;

import java.util.List;
import java.util.Map;

/** Detached data only; construction does not imply validity or acceptance. */
public record ExtractionCertificate(
        int schemaVersion,
        List<Term> terms,
        List<RuleIdentity> rules,
        List<Step> steps,
        int inputTerm,
        int outputTerm,
        int conclusion) {
    public static final int SCHEMA_VERSION = 1;

    public ExtractionCertificate {
        terms = List.copyOf(terms);
        rules = List.copyOf(rules);
        steps = List.copyOf(steps);
    }

    public enum Kind {
        NUMBER,
        VARIABLE,
        BINARY,
        FUNCTION
    }

    public record Term(Kind kind, String payload, List<Integer> children) {
        public Term {
            children = List.copyOf(children);
        }
    }

    public record RuleIdentity(String id, PatternExpr source, PatternExpr target) {}

    public sealed interface Step
            permits Reflexivity, Symmetry, Transitivity, Congruence, PatternInstance {}

    public record Reflexivity(int term) implements Step {}

    public record Symmetry(int premise) implements Step {}

    public record Transitivity(int first, int second) implements Step {}

    public record Congruence(int left, int right, List<Integer> childProofs) implements Step {
        public Congruence {
            childProofs = List.copyOf(childProofs);
        }
    }

    public record PatternInstance(int rule, Map<String, Integer> bindings, int before, int after)
            implements Step {
        public PatternInstance {
            bindings = Map.copyOf(bindings);
        }
    }
}
