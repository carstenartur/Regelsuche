package de.regelsuche.proof;

import de.regelsuche.validation.CandidateProofStatus;
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
            "Regel ist auf konkreten Beispielen gleichwertigkeitserhaltend. Das beweist keine allgemeine Gültigkeit.",
            "Rule preserves equivalence on concrete examples; this does not prove general validity."
        ));
        map.put(CandidateProofStatus.SYMBOLICALLY_VERIFIED, new Description(
            CandidateProofStatus.SYMBOLICALLY_VERIFIED,
            "Symbolisch verifiziert",
            "Regel wurde im angegebenen Modell unter seinen Voraussetzungen symbolisch geprüft. Verfahren und Beleg bestimmen die Beweiskraft.",
            "Rule was checked symbolically in the stated model under its assumptions. The method and evidence determine the strength of the result."
        ));
        map.put(CandidateProofStatus.FORMALLY_PROVABLE, new Description(
            CandidateProofStatus.FORMALLY_PROVABLE,
            "Beweis vorbereitet",
            "Ein Beweis-Pfad oder eine Skizze ist vorbereitet, aber noch nicht erfolgreich geprüft. Der Status garantiert keinen Beweis.",
            "A proof path or sketch is prepared but has not passed checking. This status does not guarantee a proof."
        ));
        map.put(CandidateProofStatus.FORMALLY_PROVED, new Description(
            CandidateProofStatus.FORMALLY_PROVED,
            "Formal bewiesen",
            "Ein formaler Nachweis wurde akzeptiert. Prüfer, gebundene Aussage, Axiome und Voraussetzungen müssen dem Beleg entnommen werden; ein importiertes Statuslabel allein ist keine Autorisierung.",
            "A formal proof was accepted. Consult the evidence for its checker, bound statement, axioms and assumptions; an imported status label alone is not authorization."
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
