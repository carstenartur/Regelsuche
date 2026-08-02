package de.regelsuche.benchmarks;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.benchmarks.ComparativeBenchmark.Case;
import de.regelsuche.solver.ir.SolverIr;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Canonical assumption evidence for the target-free simplification track.
 *
 * <p>The contract deliberately separates declarations from execution. Internal
 * Regelsuche search does not receive assumptions as guidance; instead every
 * side condition emitted by the selected path must be present in the case
 * declarations. The external SymPy adapter receives symbol-scoped declarations
 * it can represent and reports composite declarations as an explicit
 * limitation. The pinned reference form is never part of this channel.</p>
 *
 * <p>This is not yet an independent proof that every external output is valid
 * under exactly the declared assumptions. That missing validator remains a
 * published coverage gap. The contract does ensure that internal rewrite paths
 * cannot silently rely on undeclared side conditions.</p>
 */
final class SimplificationAssumptionContract {
    static final String CONTRACT_ID = "target-free-simplification-assumptions/v1";

    private static final SimplificationAssumptionContract EMPTY =
        new SimplificationAssumptionContract(List.of());

    private final List<Declaration> declarations;
    private final Set<String> declaredTexts;
    private final String contractHash;

    private SimplificationAssumptionContract(List<Declaration> declarations) {
        this.declarations = List.copyOf(declarations);
        this.declaredTexts = Set.copyOf(new LinkedHashSet<>(
            this.declarations.stream().map(Declaration::canonicalText).toList()));
        this.contractHash = SolverIr.sha256(
            CONTRACT_ID + "\ndeclarations="
                + this.declarations.stream()
                    .map(Declaration::canonicalMaterial).toList());
    }

    /** @return the contract carrying the declared assumptions of one case. */
    static SimplificationAssumptionContract forCase(Case benchmarkCase) {
        Objects.requireNonNull(benchmarkCase, "benchmarkCase");
        return of(benchmarkCase.assumptions());
    }

    static SimplificationAssumptionContract of(Collection<String> assumptions) {
        List<String> canonical =
            AssumptionSignature.ofExpressions(assumptions == null
                ? List.of() : new ArrayList<>(assumptions))
                .normalizedAssumptions();
        if (canonical.isEmpty()) {
            return EMPTY;
        }
        List<Declaration> parsed = new ArrayList<>();
        for (String text : new TreeSet<>(canonical)) {
            parsed.add(Declaration.parse(text));
        }
        return new SimplificationAssumptionContract(parsed);
    }

    List<Declaration> declarations() {
        return declarations;
    }

    /** @return the canonical declaration texts associated with the case. */
    List<String> declaredAssumptions() {
        return declarations.stream().map(Declaration::canonicalText).toList();
    }

    String contractHash() {
        return contractHash;
    }

    /**
     * Side conditions an internal competitor relied on but the case never declared.
     *
     * @param reliedOnAssumptions side conditions the competitor emitted
     * @return the sorted, canonical undischarged conditions, never {@code null}
     */
    List<String> undischarged(Collection<String> reliedOnAssumptions) {
        List<String> relied =
            AssumptionSignature.ofExpressions(reliedOnAssumptions == null
                ? List.of() : new ArrayList<>(reliedOnAssumptions))
                .normalizedAssumptions();
        return relied.stream()
            .filter(text -> !declaredTexts.contains(text))
            .sorted()
            .toList();
    }

    boolean discharges(Collection<String> reliedOnAssumptions) {
        return undischarged(reliedOnAssumptions).isEmpty();
    }

    /** Classified assumption declaration from the closed contract vocabulary. */
    record Declaration(Kind kind, String subject, String canonicalText) {
        Declaration {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(canonicalText, "canonicalText");
        }

        static Declaration parse(String canonicalText) {
            String text = canonicalText.trim();
            for (Kind kind : Kind.values()) {
                String suffix = kind.suffix();
                if (suffix.isEmpty() || !text.endsWith(suffix)) {
                    continue;
                }
                String subject =
                    text.substring(0, text.length() - suffix.length()).trim();
                if (!subject.isEmpty()) {
                    return new Declaration(kind, subject, text);
                }
            }
            return new Declaration(Kind.OTHER, text, text);
        }

        /**
         * @return {@code true} when the subject is a bare symbol, which is the
         *     only shape the current external adapter can bind as a symbol assumption
         */
        boolean symbolScoped() {
            return kind != Kind.OTHER
                && subject.matches("[A-Za-z_][A-Za-z0-9_]*");
        }

        String canonicalMaterial() {
            return kind.name() + '|' + subject;
        }
    }

    /** Closed assumption vocabulary shared by benchmark evidence. */
    enum Kind {
        NON_ZERO(" != 0", "nonzero"),
        NON_NEGATIVE(" >= 0", "nonnegative"),
        POSITIVE(" > 0", "positive"),
        OTHER("", "");

        private final String suffix;
        private final String externalAssumptionName;

        Kind(String suffix, String externalAssumptionName) {
            this.suffix = suffix;
            this.externalAssumptionName = externalAssumptionName;
        }

        String suffix() {
            return suffix;
        }

        /** @return the external CAS symbol-assumption name, may be empty. */
        String externalAssumptionName() {
            return externalAssumptionName;
        }
    }
}
