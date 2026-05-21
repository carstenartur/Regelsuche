package de.regelsuche.demo;

import de.regelsuche.input.InputType;
import de.regelsuche.search.SearchProfile;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Curated catalogue of "killer-demo" scenarios that demonstrate the end-to-end
 * Regelsuche workflow on representative mathematical examples.
 *
 * <p>Each entry is deterministic: a fixed expression, input type and search
 * profile so the resulting search graph, replay and identity report are stable
 * across runs. This is what users see when they click one of the big demo
 * buttons on the landing page.</p>
 */
public final class DemoCatalog {

    public record Demo(
        String id,
        String title,
        String description,
        String expression,
        InputType inputType,
        SearchProfile profile,
        String expectedHighlight
    ) {
    }

    private static final Map<String, Demo> DEMOS = buildDemos();

    private static Map<String, Demo> buildDemos() {
        Map<String, Demo> map = new LinkedHashMap<>();
        map.put("binomial", new Demo(
            "binomial",
            "Binomische Formel",
            "Aus (x+3)^2 emergiert die binomische Formel als Makroregel aus rein atomaren "
                + "Umformungsregeln (Potenz als Produkt, Distributivität, gleiche Terme).",
            "(x+3)^2",
            InputType.TERM,
            SearchProfile.DISCOVERY,
            "(a+b)^2 = a^2 + 2*a*b + b^2"
        ));
        map.put("rational", new Demo(
            "rational",
            "Bruchkürzung",
            "Bei (x*y)/(x*z) wird der gemeinsame Faktor x aus Zähler und Nenner als "
                + "kürzbare Struktur erkannt.",
            "(x*y)/(x*z)",
            InputType.TERM,
            SearchProfile.TEACHING,
            "(x*y)/(x*z) = y/z, sofern x ≠ 0"
        ));
        map.put("trigonometry", new Demo(
            "trigonometry",
            "Trigonometrische Identität",
            "sin(x)^2 + cos(x)^2 zeigt das Zusammenspiel von Pythagoräischer Identität "
                + "und Suchraum: die atomaren Regeln allein liefern (noch) keinen Beweis, "
                + "aber Suchgraph und Replay machen den Lösungsraum sichtbar.",
            "sin(x)^2 + cos(x)^2",
            InputType.TERM,
            SearchProfile.DISCOVERY,
            "sin(x)^2 + cos(x)^2 = 1"
        ));
        map.put("equation", new Demo(
            "equation",
            "Polynom-Gleichung",
            "Bei (x+1)*(x+2) zeigt der Suchgraph, wie aus Distributivität und gleichen "
                + "Termen eine ausmultiplizierte Form x^2 + 3x + 2 entsteht.",
            "(x+1)*(x+2)",
            InputType.TERM,
            SearchProfile.DISCOVERY,
            "(x+1)*(x+2) = x^2 + 3*x + 2"
        ));
        return java.util.Collections.unmodifiableMap(map);
    }

    private DemoCatalog() {
    }

    public static Map<String, Demo> all() {
        return DEMOS;
    }

    public static Demo byId(String id) {
        if (id == null) {
            return null;
        }
        return DEMOS.get(id.toLowerCase(java.util.Locale.ROOT));
    }
}
