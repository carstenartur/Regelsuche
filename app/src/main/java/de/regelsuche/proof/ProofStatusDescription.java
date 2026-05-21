package de.regelsuche.proof;

import de.regelsuche.mining.CandidateProofStatus;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Human-readable explanations for each {@link CandidateProofStatus}.
 *
 * <p>Used by the {@code /api/proof-status} endpoint and the UI badge tooltips
 * so users understand what level of trust each rule has earned (only observed,
 * validated by examples, symbolically verified by SymPy/SMT, formally
 * provable, or formally proved).</p>
 */
public final class ProofStatusDescription {

    public record Description(
        CandidateProofStatus status,
        String label,
        String summaryDe,
        String summaryEn
    ) {
    }

    private static final Map<CandidateProofStatus, Description> DESCRIPTIONS = build();

    private static Map<CandidateProofStatus, Description> build() {
        Map<CandidateProofStatus, Description> map = new LinkedHashMap<>();
        map.put(CandidateProofStatus.REJECTED, new Description(
            CandidateProofStatus.REJECTED,
            "Verworfen",
            "Regel wurde durch Gegenbeispiele oder Widerspruch verworfen und ist nicht im Inventar aktiv.",
            "Rule was rejected by counter-examples or contradiction and is not active in the inventory."
        ));
        map.put(CandidateProofStatus.OBSERVED, new Description(
            CandidateProofStatus.OBSERVED,
            "Beobachtet",
            "Regel ist nur als Muster beobachtet – noch keine Validierung durch Beispiele oder Solver.",
            "Rule is only observed as a pattern – no example validation or solver check yet."
        ));
        map.put(CandidateProofStatus.VALIDATED_BY_EXAMPLES, new Description(
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            "Durch Beispiele bestätigt",
            "Regel ist auf einer Menge konkreter Beispiele gleichwertigkeitserhaltend.",
            "Rule preserves equivalence on a set of concrete examples."
        ));
        map.put(CandidateProofStatus.SYMBOLICALLY_VERIFIED, new Description(
            CandidateProofStatus.SYMBOLICALLY_VERIFIED,
            "Symbolisch verifiziert",
            "Regel wurde durch ein symbolisches Verfahren (SymPy/SMT) als allgemein gültig geprüft.",
            "Rule has been verified as universally valid via a symbolic procedure (SymPy/SMT)."
        ));
        map.put(CandidateProofStatus.FORMALLY_PROVABLE, new Description(
            CandidateProofStatus.FORMALLY_PROVABLE,
            "Formal beweisbar",
            "Es existiert ein formaler Beweis-Pfad (z.B. Lean-Skizze), der noch nicht durchgeführt wurde.",
            "A formal proof outline exists (e.g. a Lean sketch) but has not been completed yet."
        ));
        map.put(CandidateProofStatus.FORMALLY_PROVED, new Description(
            CandidateProofStatus.FORMALLY_PROVED,
            "Formal bewiesen",
            "Regel wurde formal in einem Beweissystem bewiesen (Lean/Isabelle/Coq).",
            "Rule has been formally proved in a proof assistant (Lean/Isabelle/Coq)."
        ));
        return java.util.Collections.unmodifiableMap(map);
    }

    private ProofStatusDescription() {
    }

    public static Map<CandidateProofStatus, Description> all() {
        return DESCRIPTIONS;
    }

    public static Description of(CandidateProofStatus status) {
        return DESCRIPTIONS.get(status);
    }

    public static Description ofName(String name) {
        if (name == null) {
            return null;
        }
        try {
            return DESCRIPTIONS.get(CandidateProofStatus.valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
