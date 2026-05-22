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
        String expectedHighlight,
        String expectedResultExpression,
        String domain
    ) {
        /** Backwards-compatible constructor: domain defaults to {@code "algebra"}. */
        public Demo(
            String id,
            String title,
            String description,
            String expression,
            InputType inputType,
            SearchProfile profile,
            String expectedHighlight,
            String expectedResultExpression
        ) {
            this(id, title, description, expression, inputType, profile,
                expectedHighlight, expectedResultExpression, "algebra");
        }
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
            "(a+b)^2 = a^2 + 2*a*b + b^2",
            "9 + 6 * x + x ^ 2"
        ));
        map.put("rational", new Demo(
            "rational",
            "Bruchkürzung",
            "Bei (x*y)/(x*z) wird der gemeinsame Faktor x aus Zähler und Nenner erkannt; "
                + "Voraussetzung: x ≠ 0.",
            "(x*y)/(x*z)",
            InputType.TERM,
            SearchProfile.DISCOVERY,
            "(x*y)/(x*z) = y/z, sofern x ≠ 0",
            "y / z"
        ));
        map.put("trigonometry", new Demo(
            "trigonometry",
            "Trigonometrische Identität",
            "sin(x)^2 + cos(x)^2 wird über die Pythagoräische Identität auf 1 reduziert.",
            "sin(x)^2 + cos(x)^2",
            InputType.TERM,
            SearchProfile.DISCOVERY,
            "sin(x)^2 + cos(x)^2 = 1",
            "1"
        ));
        map.put("polynomial-expansion", new Demo(
            "polynomial-expansion",
            "Polynom-Expansion",
            "(x+1)*(x+2) wird durch Distributivität und Zusammenfassen gleicher Terme zu "
                + "x^2 + 3*x + 2 expandiert.",
            "(x+1)*(x+2)",
            InputType.TERM,
            SearchProfile.DISCOVERY,
            "(x+1)*(x+2) = x^2 + 3*x + 2",
            "2 + 3 * x + x ^ 2"
        ));
        map.put("macro-learning", new Demo(
            "macro-learning",
            "System lernt eine Makroregel",
            "Vier aufeinanderfolgende Suchläufe über (x+1)^2, (x+2)^2, (x+3)^2 und (x+7)^2: "
                + "nach drei Beispielen aktiviert MacroRuleLearningService die binomische "
                + "Formel als Makroregel im Inventar, der vierte Lauf nutzt sie und ist messbar "
                + "kürzer.",
            "(x+7)^2",
            InputType.TERM,
            SearchProfile.DISCOVERY_PLUS,
            "(a+b)^2 wird als gelernte Regel angewandt",
            "49 + 14 * x + x ^ 2"
        ));
        // ---------------- math-domain demos (PR #13/follow-up) ----------------
        // These four entries flow through `UnifiedMathDomainWorkbench` instead
        // of the generic atomic-rule engine. They demonstrate that linear
        // equations, inequalities, derivatives and matrix distributivity
        // appear as first-class citizens in the same workbench UI, replay,
        // proof bridge and macro-rule learning pipeline as the algebraic
        // demos — no special UI path.
        map.put("math-equation", new Demo(
            "math-equation",
            "Lineare Gleichung",
            "x + 3 = 7 wird durch Isolieren der Variablen schrittweise zu x = 4 umgeformt. "
                + "Die Schritte erscheinen als reguläre Transformationsschritte im SearchGraph "
                + "und Replay.",
            "x + 3 = 7",
            InputType.EQUATION,
            SearchProfile.DISCOVERY,
            "x + 3 = 7 ⇒ x = 4",
            "x = 4",
            "equations"
        ));
        map.put("math-inequality", new Demo(
            "math-inequality",
            "Ungleichung mit Vorzeichen-Flip",
            "-2*x < 4 wird zu x > -2 umgeformt. Replay markiert die Division durch einen "
                + "negativen Faktor, die das Vergleichszeichen dreht.",
            "-2*x < 4",
            InputType.TERM,
            SearchProfile.DISCOVERY,
            "Division durch negativen Faktor dreht das Vergleichszeichen",
            "x > -2",
            "inequalities"
        ));
        map.put("math-derivative", new Demo(
            "math-derivative",
            "Ableitung – Potenzregel",
            "d/dx x^3 wird durch die Anwendung der Potenzregel als gewöhnliche RewriteRule "
                + "zu 3*x^2 umgeformt. Replay zeigt 'Potenzregel angewendet'.",
            "diff(x ^ 3, x)",
            InputType.TERM,
            SearchProfile.DISCOVERY,
            "d/dx x^n = n*x^(n-1)",
            "3 * x ^ 2",
            "calculus"
        ));
        map.put("math-matrix", new Demo(
            "math-matrix",
            "Matrix-Distributivität",
            "A*(B+C) wird durch Distributivität zu A*B + A*C umgeformt. Die LaTeX-Ausgabe "
                + "rendert konkrete Matrizen als \\begin{bmatrix}.",
            "A * (B + C)",
            InputType.TERM,
            SearchProfile.DISCOVERY,
            "A*(B+C) = A*B + A*C",
            "A * B + A * C",
            "linear-algebra"
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
        String key = id.toLowerCase(java.util.Locale.ROOT);
        Demo found = DEMOS.get(key);
        if (found != null) {
            return found;
        }
        // Backwards-compatible alias: the demo formerly known as "equation"
        // has been honestly renamed to "polynomial-expansion" because the
        // current atomic rule set does not solve linear equations.
        if ("equation".equals(key)) {
            return DEMOS.get("polynomial-expansion");
        }
        return null;
    }
}
