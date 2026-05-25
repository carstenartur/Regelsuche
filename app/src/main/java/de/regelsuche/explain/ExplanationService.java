package de.regelsuche.explain;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.json.JsonWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Produces multi-form, didactically usable explanations for individual
 * {@link TransformationStep transformation steps} and whole
 * {@link DiscoveredTransformation transformation paths}.
 *
 * <p>Supported {@link Form forms}: {@code SHORT}, {@code SCHOOL},
 * {@code EXPERT}, {@code LATEX}, {@code JSON}.</p>
 *
 * <p>{@code SCHOOL} output and path headings are locale-aware. The overloads
 * without a {@link Locale} parameter default to {@link Locale#GERMAN} for
 * backward compatibility. Pass {@link Locale#ENGLISH} (or any other locale,
 * which falls back to English) to obtain English output.</p>
 */
public class ExplanationService {

    /** Rendering form requested by the caller. */
    public enum Form {
        /** One short line per step (e.g. {@code a*(b+c) = a*b + a*c}). */
        SHORT,
        /** Numbered step-by-step rendering. Alias of {@link #SHORT} kept for UI clarity. */
        STEPS,
        /** Verbose, classroom-style explanation (localised). */
        SCHOOL,
        /** Technical: lists rule id, kind and cost delta. */
        EXPERT,
        /** LaTeX-ready string (no surrounding environment). */
        LATEX,
        /** Strict JSON representation of all metadata. */
        JSON
    }

    // ── Locale-unaware entry points (kept for backward compatibility) ───────

    public String renderStep(TransformationStep step, Form form) {
        return renderStep(step, form, Locale.GERMAN);
    }

    public String renderPath(DiscoveredTransformation path, Form form) {
        return renderPath(path, form, Locale.GERMAN);
    }

    // ── Locale-aware entry points ────────────────────────────────────────────

    /**
     * Renders a single transformation step in the requested form and
     * language.
     *
     * @param step   the transformation step to render
     * @param form   output format
     * @param locale target locale; German and English are fully
     *               supported, all other locales fall back to English
     */
    public String renderStep(TransformationStep step, Form form, Locale locale) {
        return switch (form) {
            case SHORT, STEPS -> step.beforeExpression() + " = " + step.afterExpression();
            case SCHOOL -> renderSchool(step, locale);
            case EXPERT -> renderExpert(step);
            case LATEX -> toLatex(step.beforeExpression()) + " \\;\\rightarrow\\; " + toLatex(step.afterExpression());
            case JSON -> renderStepJson(step);
        };
    }

    /**
     * Renders a whole transformation path in the requested form and
     * language.
     *
     * @param path   the transformation path to render
     * @param form   output format
     * @param locale target locale; German and English are fully
     *               supported, all other locales fall back to English
     */
    public String renderPath(DiscoveredTransformation path, Form form, Locale locale) {
        if (form == Form.JSON) {
            JsonWriter writer = new JsonWriter();
            writer.beginObject();
            writer.property("id", path.id());
            writer.property("originalExpression", path.originalExpression());
            writer.property("improvedExpression", path.improvedExpression());
            writer.property("validationStatus", path.validationStatus().name());
            writer.array("steps", w -> path.steps().forEach(step ->
                w.objectValue(inner -> writeStepFields(inner, step))));
            writer.endObject();
            return writer.toString();
        }
        boolean german = isGerman(locale);
        StringBuilder builder = new StringBuilder();
        List<TransformationStep> steps = path.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (form == Form.SCHOOL) {
                builder.append(german ? "Schritt " : "Step ").append(i + 1).append(": ");
            } else if (form == Form.EXPERT) {
                builder.append("[").append(i + 1).append("] ");
            } else if (form == Form.LATEX) {
                builder.append(german ? "% Schritt " : "% Step ").append(i + 1).append('\n');
            } else {
                builder.append(i + 1).append(". ");
            }
            builder.append(renderStep(steps.get(i), form, locale));
            if (i < steps.size() - 1) {
                builder.append('\n');
            }
        }
        return builder.toString();
    }

    /**
     * Returns the localized, human-readable title for a rewrite rule without
     * any surrounding {@code Regel:}/{@code Rule:} label.
     *
     * @param ruleId the transformation rule id
     * @param locale target locale; German is used for {@code de}, all other
     *               locales fall back to English
     * @return localized rule title
     */
    public String ruleTitle(String ruleId, Locale locale) {
        return humanRuleName(ruleId, locale);
    }

    // ── Private rendering helpers ────────────────────────────────────────────

    private String renderSchool(TransformationStep step, Locale locale) {
        boolean german = isGerman(locale);
        String ruleName = humanRuleName(step.ruleId(), locale);
        String preserving;
        if (step.equivalencePreserving()) {
            preserving = german ? "Äquivalenz erhaltend." : "Equivalence-preserving.";
        } else {
            preserving = german
                ? "Achtung: nicht garantiert äquivalent."
                : "Warning: not guaranteed to be equivalent.";
        }
        if (german) {
            return "Regel: " + ruleName + "\n"
                + "Vorher: " + step.beforeExpression() + "\n"
                + "Nachher: " + step.afterExpression() + "\n"
                + "Begründung: " + ruleExplanation(step.ruleId(), locale) + "\n"
                + "Status: " + preserving;
        } else {
            return "Rule: " + ruleName + "\n"
                + "Before: " + step.beforeExpression() + "\n"
                + "After: " + step.afterExpression() + "\n"
                + "Reason: " + ruleExplanation(step.ruleId(), locale) + "\n"
                + "Status: " + preserving;
        }
    }

    private String renderExpert(TransformationStep step) {
        return String.format(Locale.ROOT,
            "rule=%s kind=%s scoreΔ=%d equiv=%s : %s -> %s",
            step.ruleId(),
            step.ruleKind().name(),
            step.scoreAfter() - step.scoreBefore(),
            step.equivalencePreserving(),
            step.beforeExpression(),
            step.afterExpression()
        );
    }

    private String renderStepJson(TransformationStep step) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writeStepFields(writer, step);
        writer.endObject();
        return writer.toString();
    }

    private void writeStepFields(JsonWriter writer, TransformationStep step) {
        writer.property("index", step.index());
        writer.property("beforeExpression", step.beforeExpression());
        writer.property("afterExpression", step.afterExpression());
        writer.property("ruleId", step.ruleId());
        writer.property("ruleKind", step.ruleKind().name());
        writer.property("scoreBefore", step.scoreBefore());
        writer.property("scoreAfter", step.scoreAfter());
        writer.property("equivalencePreserving", step.equivalencePreserving());
        writer.property("explanation", step.explanation());
    }

    private static final de.regelsuche.export.MathPresentation MATH =
        de.regelsuche.export.MathPresentation.DEFAULT;

    private String toLatex(String expression) {
        return MATH.latex(expression);
    }

    private static boolean isGerman(Locale locale) {
        if (locale == null) {
            return false;
        }
        return "de".equalsIgnoreCase(locale.getLanguage());
    }

    private String humanRuleName(String ruleId, Locale locale) {
        if (isGerman(locale)) {
            String mapped = RULE_NAMES_DE.get(ruleId);
            if (mapped != null) {
                return mapped;
            }
            return ruleId
                .replace("ast_", "")
                .replace("polynomial_", "Polynom: ")
                .replace("rational_", "Bruch: ")
                .replace("_", " ");
        } else {
            String mapped = RULE_NAMES_EN.get(ruleId);
            if (mapped != null) {
                return mapped;
            }
            return ruleId
                .replace("ast_", "")
                .replace("polynomial_", "Polynomial: ")
                .replace("rational_", "Fraction: ")
                .replace("_", " ");
        }
    }

    private String ruleExplanation(String ruleId, Locale locale) {
        if (isGerman(locale)) {
            String mapped = RULE_EXPLANATIONS_DE.get(ruleId);
            return mapped != null ? mapped : "Atomare Umformungsregel.";
        } else {
            String mapped = RULE_EXPLANATIONS_EN.get(ruleId);
            return mapped != null ? mapped : "Atomic rewrite rule.";
        }
    }

    // ── German rule catalogue ────────────────────────────────────────────────

    private static final Map<String, String> RULE_NAMES_DE = new LinkedHashMap<>();
    private static final Map<String, String> RULE_EXPLANATIONS_DE = new LinkedHashMap<>();

    // ── English rule catalogue ───────────────────────────────────────────────

    private static final Map<String, String> RULE_NAMES_EN = new LinkedHashMap<>();
    private static final Map<String, String> RULE_EXPLANATIONS_EN = new LinkedHashMap<>();

    static {
        // ── German names ────────────────────────────────────────────────────
        RULE_NAMES_DE.put("ast_add_zero_right", "Neutrales Element der Addition (rechts)");
        RULE_NAMES_DE.put("ast_add_zero_left", "Neutrales Element der Addition (links)");
        RULE_NAMES_DE.put("ast_multiply_one_right", "Neutrales Element der Multiplikation (rechts)");
        RULE_NAMES_DE.put("ast_multiply_one_left", "Neutrales Element der Multiplikation (links)");
        RULE_NAMES_DE.put("ast_multiply_zero_right", "Absorbierendes Element 0 (rechts)");
        RULE_NAMES_DE.put("ast_multiply_zero_left", "Absorbierendes Element 0 (links)");
        RULE_NAMES_DE.put("ast_distribute_left_add", "Distributivgesetz (Addition, links)");
        RULE_NAMES_DE.put("ast_distribute_right_add", "Distributivgesetz (Addition, rechts)");
        RULE_NAMES_DE.put("ast_distribute_left_subtract", "Distributivgesetz (Subtraktion, links)");
        RULE_NAMES_DE.put("ast_distribute_right_subtract", "Distributivgesetz (Subtraktion, rechts)");
        RULE_NAMES_DE.put("ast_double_term", "Gleiche Summanden zusammenfassen");
        RULE_NAMES_DE.put("ast_product_to_power_two", "Produkt zweier gleicher Faktoren als Quadrat");
        RULE_NAMES_DE.put("ast_power_two_to_product", "Quadrat als Produkt zweier Faktoren");
        RULE_NAMES_DE.put("ast_combine_powers", "Potenzgesetz: gleiche Basis, Exponenten addieren");
        RULE_NAMES_DE.put("ast_power_of_power", "Potenzgesetz: Potenz einer Potenz");
        RULE_NAMES_DE.put("ast_factor_common_left", "Gemeinsamen Faktor links ausklammern");
        RULE_NAMES_DE.put("ast_factor_common_right", "Gemeinsamen Faktor rechts ausklammern");
        RULE_NAMES_DE.put("ast_canonical_normalize", "Kanonische Normalform");
        RULE_NAMES_DE.put("polynomial_combine_like_terms", "Gleichartige Terme zusammenfassen");
        RULE_NAMES_DE.put("rational_cancel_common_factor", "Bruch kürzen (gemeinsamer Faktor)");
        RULE_NAMES_DE.put("rational_multiply_fractions", "Brüche multiplizieren");
        RULE_NAMES_DE.put("rational_divide_by_fraction", "Durch einen Bruch dividieren");

        // ── German explanations ──────────────────────────────────────────────
        RULE_EXPLANATIONS_DE.put("ast_add_zero_right", "Addition mit 0 verändert den Wert nicht.");
        RULE_EXPLANATIONS_DE.put("ast_add_zero_left", "Addition mit 0 verändert den Wert nicht.");
        RULE_EXPLANATIONS_DE.put("ast_multiply_one_right", "Multiplikation mit 1 verändert den Wert nicht.");
        RULE_EXPLANATIONS_DE.put("ast_multiply_one_left", "Multiplikation mit 1 verändert den Wert nicht.");
        RULE_EXPLANATIONS_DE.put("ast_multiply_zero_right", "Multiplikation mit 0 ergibt 0.");
        RULE_EXPLANATIONS_DE.put("ast_multiply_zero_left", "Multiplikation mit 0 ergibt 0.");
        RULE_EXPLANATIONS_DE.put("ast_distribute_left_add",
            "Multiplikation wird über die Addition verteilt.");
        RULE_EXPLANATIONS_DE.put("ast_distribute_right_add",
            "Multiplikation wird über die Addition verteilt.");
        RULE_EXPLANATIONS_DE.put("ast_distribute_left_subtract",
            "Multiplikation wird über die Subtraktion verteilt.");
        RULE_EXPLANATIONS_DE.put("ast_distribute_right_subtract",
            "Multiplikation wird über die Subtraktion verteilt.");
        RULE_EXPLANATIONS_DE.put("ast_double_term", "Zwei gleiche Summanden ergeben das Doppelte.");
        RULE_EXPLANATIONS_DE.put("ast_product_to_power_two", "a·a entspricht a².");
        RULE_EXPLANATIONS_DE.put("ast_power_two_to_product", "a² ist das Produkt aus a und a.");
        RULE_EXPLANATIONS_DE.put("ast_combine_powers",
            "Gleiche Basis: Potenzen werden multipliziert, indem die Exponenten addiert werden.");
        RULE_EXPLANATIONS_DE.put("ast_power_of_power",
            "Potenz einer Potenz: Exponenten werden multipliziert.");
        RULE_EXPLANATIONS_DE.put("ast_factor_common_left",
            "Gemeinsamer Faktor steht in beiden Summanden links und wird ausgeklammert.");
        RULE_EXPLANATIONS_DE.put("ast_factor_common_right",
            "Gemeinsamer Faktor steht in beiden Summanden rechts und wird ausgeklammert.");
        RULE_EXPLANATIONS_DE.put("ast_canonical_normalize",
            "Strukturelle Normalisierung (Reihenfolge, neutrale Elemente).");
        RULE_EXPLANATIONS_DE.put("polynomial_combine_like_terms",
            "Zwei Vielfache desselben Terms werden zu einem Term mit summierter Koeffizientensumme.");
        RULE_EXPLANATIONS_DE.put("rational_cancel_common_factor",
            "Gemeinsamer Faktor von Zähler und Nenner kann gekürzt werden.");
        RULE_EXPLANATIONS_DE.put("rational_multiply_fractions",
            "Brüche werden multipliziert, indem Zähler mal Zähler und Nenner mal Nenner gerechnet wird.");
        RULE_EXPLANATIONS_DE.put("rational_divide_by_fraction",
            "Durch einen Bruch dividieren entspricht der Multiplikation mit dem Kehrwert.");

        // ── English names ────────────────────────────────────────────────────
        RULE_NAMES_EN.put("ast_add_zero_right", "Identity element of addition (right)");
        RULE_NAMES_EN.put("ast_add_zero_left", "Identity element of addition (left)");
        RULE_NAMES_EN.put("ast_multiply_one_right", "Identity element of multiplication (right)");
        RULE_NAMES_EN.put("ast_multiply_one_left", "Identity element of multiplication (left)");
        RULE_NAMES_EN.put("ast_multiply_zero_right", "Absorbing element 0 (right)");
        RULE_NAMES_EN.put("ast_multiply_zero_left", "Absorbing element 0 (left)");
        RULE_NAMES_EN.put("ast_distribute_left_add", "Distributive law (addition, left)");
        RULE_NAMES_EN.put("ast_distribute_right_add", "Distributive law (addition, right)");
        RULE_NAMES_EN.put("ast_distribute_left_subtract", "Distributive law (subtraction, left)");
        RULE_NAMES_EN.put("ast_distribute_right_subtract", "Distributive law (subtraction, right)");
        RULE_NAMES_EN.put("ast_double_term", "Combine equal summands");
        RULE_NAMES_EN.put("ast_product_to_power_two", "Product of two equal factors as a square");
        RULE_NAMES_EN.put("ast_power_two_to_product", "Square as product of two factors");
        RULE_NAMES_EN.put("ast_combine_powers", "Power law: same base, add exponents");
        RULE_NAMES_EN.put("ast_power_of_power", "Power law: power of a power");
        RULE_NAMES_EN.put("ast_factor_common_left", "Factor out common factor on the left");
        RULE_NAMES_EN.put("ast_factor_common_right", "Factor out common factor on the right");
        RULE_NAMES_EN.put("ast_canonical_normalize", "Canonical normal form");
        RULE_NAMES_EN.put("polynomial_combine_like_terms", "Combine like terms");
        RULE_NAMES_EN.put("rational_cancel_common_factor", "Cancel common factor");
        RULE_NAMES_EN.put("rational_multiply_fractions", "Multiply fractions");
        RULE_NAMES_EN.put("rational_divide_by_fraction", "Divide by a fraction");

        // ── English explanations ─────────────────────────────────────────────
        RULE_EXPLANATIONS_EN.put("ast_add_zero_right", "Adding 0 does not change the value.");
        RULE_EXPLANATIONS_EN.put("ast_add_zero_left", "Adding 0 does not change the value.");
        RULE_EXPLANATIONS_EN.put("ast_multiply_one_right", "Multiplying by 1 does not change the value.");
        RULE_EXPLANATIONS_EN.put("ast_multiply_one_left", "Multiplying by 1 does not change the value.");
        RULE_EXPLANATIONS_EN.put("ast_multiply_zero_right", "Multiplying by 0 yields 0.");
        RULE_EXPLANATIONS_EN.put("ast_multiply_zero_left", "Multiplying by 0 yields 0.");
        RULE_EXPLANATIONS_EN.put("ast_distribute_left_add",
            "Multiplication is distributed over addition.");
        RULE_EXPLANATIONS_EN.put("ast_distribute_right_add",
            "Multiplication is distributed over addition.");
        RULE_EXPLANATIONS_EN.put("ast_distribute_left_subtract",
            "Multiplication is distributed over subtraction.");
        RULE_EXPLANATIONS_EN.put("ast_distribute_right_subtract",
            "Multiplication is distributed over subtraction.");
        RULE_EXPLANATIONS_EN.put("ast_double_term", "Two equal summands yield twice the value.");
        RULE_EXPLANATIONS_EN.put("ast_product_to_power_two", "a·a equals a².");
        RULE_EXPLANATIONS_EN.put("ast_power_two_to_product", "a² is the product of a and a.");
        RULE_EXPLANATIONS_EN.put("ast_combine_powers",
            "Same base: powers are multiplied by adding the exponents.");
        RULE_EXPLANATIONS_EN.put("ast_power_of_power",
            "Power of a power: exponents are multiplied.");
        RULE_EXPLANATIONS_EN.put("ast_factor_common_left",
            "A common factor appears on the left in both summands and is factored out.");
        RULE_EXPLANATIONS_EN.put("ast_factor_common_right",
            "A common factor appears on the right in both summands and is factored out.");
        RULE_EXPLANATIONS_EN.put("ast_canonical_normalize",
            "Structural normalisation (ordering, neutral elements).");
        RULE_EXPLANATIONS_EN.put("polynomial_combine_like_terms",
            "Two multiples of the same term are combined into one term with the summed coefficient.");
        RULE_EXPLANATIONS_EN.put("rational_cancel_common_factor",
            "A common factor of numerator and denominator can be cancelled.");
        RULE_EXPLANATIONS_EN.put("rational_multiply_fractions",
            "Fractions are multiplied by multiplying numerators together and denominators together.");
        RULE_EXPLANATIONS_EN.put("rational_divide_by_fraction",
            "Dividing by a fraction equals multiplying by its reciprocal.");
    }
}
