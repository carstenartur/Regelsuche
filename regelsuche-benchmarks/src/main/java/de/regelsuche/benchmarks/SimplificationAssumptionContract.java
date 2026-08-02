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
 * Symmetric assumption channel of the target-free simplification track.
 *
 * <p>Before this contract existed, a case such as {@code (x^2 - 1) / (x - 1)}
 * recorded the side condition {@code x - 1 != 0} as evidence while no
 * competitor ever received it, and no competitor's own side conditions were
 * ever checked. That asymmetry was tracked as the configuration limitation
 * {@code RECORDED_CASE_ASSUMPTIONS_ARE_NOT_INJECTED}.</p>
 *
 * <p>The contract closes it in both directions and identically for every
 * competitor:</p>
 * <ul>
 *   <li><b>Injection.</b> The declared assumptions of a case are handed to
 *       every configured competitor before it runs. No competitor receives
 *       more or fewer declarations than another, and no declaration reveals
 *       anything about the reference simplest form.</li>
 *   <li><b>Discharge.</b> A competitor that relies on a side condition only
 *       reaches the reference form when that condition is entailed by the
 *       declared assumptions. A cancellation performed on an undeclared
 *       side condition is recorded as {@code ASSUMPTION_NOT_DISCHARGED}
 *       instead of being silently scored as a success.</li>
 * </ul>
 *
 * <p>Declarations use a small closed vocabulary so that both an internal
 * rewrite engine and an external CAS can consume the same text. Anything
 * outside the vocabulary stays a declaration and is still injected, but it is
 * classified as {@link Kind#OTHER} and cannot be mapped onto a symbol-scoped
 * assumption of an external system.</p>
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

    /** @return the canonical declaration texts handed to every competitor. */
    List<String> declaredAssumptions() {
        return declarations.stream().map(Declaration::canonicalText).toList();
    }

    String contractHash() {
        return contractHash;
    }

    /**
     * Side conditions a competitor relied on but that the case never declared.
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
         *     only shape an external CAS can bind as a symbol assumption
         */
        boolean symbolScoped() {
            return kind != Kind.OTHER
                && subject.matches("[A-Za-z_][A-Za-z0-9_]*");
        }

        String canonicalMaterial() {
            return kind.name() + '|' + subject;
        }
    }

    /** Closed assumption vocabulary shared by all configured competitors. */
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
