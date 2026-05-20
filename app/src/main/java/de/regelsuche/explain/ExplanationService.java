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
 */
public class ExplanationService {

    /** Rendering form requested by the caller. */
    public enum Form {
        /** One short line per step (e.g. {@code a*(b+c) = a*b + a*c}). */
        SHORT,
        /** Numbered step-by-step rendering. Alias of {@link #SHORT} kept for UI clarity. */
        STEPS,
        /** Verbose, classroom-style German explanation. */
        SCHOOL,
        /** Technical: lists rule id, kind and cost delta. */
        EXPERT,
        /** LaTeX-ready string (no surrounding environment). */
        LATEX,
        /** Strict JSON representation of all metadata. */
        JSON
    }

    public String renderStep(TransformationStep step, Form form) {
        return switch (form) {
            case SHORT, STEPS -> step.beforeExpression() + " = " + step.afterExpression();
            case SCHOOL -> renderSchool(step);
            case EXPERT -> renderExpert(step);
            case LATEX -> toLatex(step.beforeExpression()) + " \\;\\rightarrow\\; " + toLatex(step.afterExpression());
            case JSON -> renderStepJson(step);
        };
    }

    public String renderPath(DiscoveredTransformation path, Form form) {
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
        StringBuilder builder = new StringBuilder();
        List<TransformationStep> steps = path.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (form == Form.SCHOOL) {
                builder.append("Schritt ").append(i + 1).append(": ");
            } else if (form == Form.EXPERT) {
                builder.append("[").append(i + 1).append("] ");
            } else if (form == Form.LATEX) {
                builder.append("% Schritt ").append(i + 1).append('\n');
            } else {
                builder.append(i + 1).append(". ");
            }
            builder.append(renderStep(steps.get(i), form));
            if (i < steps.size() - 1) {
                builder.append('\n');
            }
        }
        return builder.toString();
    }

    private String renderSchool(TransformationStep step) {
        String ruleName = humanRuleName(step.ruleId());
        String preserving = step.equivalencePreserving()
            ? "Äquivalenz erhaltend."
            : "Achtung: nicht garantiert äquivalent.";
        return "Regel: " + ruleName + "\n"
            + "Vorher: " + step.beforeExpression() + "\n"
            + "Nachher: " + step.afterExpression() + "\n"
            + "Begründung: " + ruleExplanation(step.ruleId()) + "\n"
            + "Status: " + preserving;
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

    private String toLatex(String expression) {
        return expression.replace("*", " \\cdot ");
    }

    private String humanRuleName(String ruleId) {
        String mapped = RULE_NAMES.get(ruleId);
        if (mapped != null) {
            return mapped;
        }
        return ruleId
            .replace("ast_", "")
            .replace("polynomial_", "Polynom: ")
            .replace("rational_", "Bruch: ")
            .replace("_", " ");
    }

    private String ruleExplanation(String ruleId) {
        String mapped = RULE_EXPLANATIONS.get(ruleId);
        if (mapped != null) {
            return mapped;
        }
        return "Atomare Umformungsregel.";
    }

    private static final Map<String, String> RULE_NAMES = new LinkedHashMap<>();
    private static final Map<String, String> RULE_EXPLANATIONS = new LinkedHashMap<>();

    static {
        RULE_NAMES.put("ast_add_zero_right", "Neutrales Element der Addition (rechts)");
        RULE_NAMES.put("ast_add_zero_left", "Neutrales Element der Addition (links)");
        RULE_NAMES.put("ast_multiply_one_right", "Neutrales Element der Multiplikation (rechts)");
        RULE_NAMES.put("ast_multiply_one_left", "Neutrales Element der Multiplikation (links)");
        RULE_NAMES.put("ast_multiply_zero_right", "Absorbierendes Element 0 (rechts)");
        RULE_NAMES.put("ast_multiply_zero_left", "Absorbierendes Element 0 (links)");
        RULE_NAMES.put("ast_distribute_left_add", "Distributivgesetz (Addition, links)");
        RULE_NAMES.put("ast_distribute_right_add", "Distributivgesetz (Addition, rechts)");
        RULE_NAMES.put("ast_distribute_left_subtract", "Distributivgesetz (Subtraktion, links)");
        RULE_NAMES.put("ast_distribute_right_subtract", "Distributivgesetz (Subtraktion, rechts)");
        RULE_NAMES.put("ast_double_term", "Gleiche Summanden zusammenfassen");
        RULE_NAMES.put("ast_product_to_power_two", "Produkt zweier gleicher Faktoren als Quadrat");
        RULE_NAMES.put("ast_power_two_to_product", "Quadrat als Produkt zweier Faktoren");
        RULE_NAMES.put("ast_combine_powers", "Potenzgesetz: gleiche Basis, Exponenten addieren");
        RULE_NAMES.put("ast_power_of_power", "Potenzgesetz: Potenz einer Potenz");
        RULE_NAMES.put("ast_factor_common_left", "Gemeinsamen Faktor links ausklammern");
        RULE_NAMES.put("ast_factor_common_right", "Gemeinsamen Faktor rechts ausklammern");
        RULE_NAMES.put("ast_canonical_normalize", "Kanonische Normalform");
        RULE_NAMES.put("polynomial_combine_like_terms", "Gleichartige Terme zusammenfassen");
        RULE_NAMES.put("rational_cancel_common_factor", "Bruch kürzen (gemeinsamer Faktor)");
        RULE_NAMES.put("rational_multiply_fractions", "Brüche multiplizieren");
        RULE_NAMES.put("rational_divide_by_fraction", "Durch einen Bruch dividieren");

        RULE_EXPLANATIONS.put("ast_add_zero_right", "Addition mit 0 verändert den Wert nicht.");
        RULE_EXPLANATIONS.put("ast_add_zero_left", "Addition mit 0 verändert den Wert nicht.");
        RULE_EXPLANATIONS.put("ast_multiply_one_right", "Multiplikation mit 1 verändert den Wert nicht.");
        RULE_EXPLANATIONS.put("ast_multiply_one_left", "Multiplikation mit 1 verändert den Wert nicht.");
        RULE_EXPLANATIONS.put("ast_multiply_zero_right", "Multiplikation mit 0 ergibt 0.");
        RULE_EXPLANATIONS.put("ast_multiply_zero_left", "Multiplikation mit 0 ergibt 0.");
        RULE_EXPLANATIONS.put("ast_distribute_left_add",
            "Multiplikation wird über die Addition verteilt.");
        RULE_EXPLANATIONS.put("ast_distribute_right_add",
            "Multiplikation wird über die Addition verteilt.");
        RULE_EXPLANATIONS.put("ast_distribute_left_subtract",
            "Multiplikation wird über die Subtraktion verteilt.");
        RULE_EXPLANATIONS.put("ast_distribute_right_subtract",
            "Multiplikation wird über die Subtraktion verteilt.");
        RULE_EXPLANATIONS.put("ast_double_term", "Zwei gleiche Summanden ergeben das Doppelte.");
        RULE_EXPLANATIONS.put("ast_product_to_power_two", "a·a entspricht a².");
        RULE_EXPLANATIONS.put("ast_power_two_to_product", "a² ist das Produkt aus a und a.");
        RULE_EXPLANATIONS.put("ast_combine_powers",
            "Gleiche Basis: Potenzen werden multipliziert, indem die Exponenten addiert werden.");
        RULE_EXPLANATIONS.put("ast_power_of_power",
            "Potenz einer Potenz: Exponenten werden multipliziert.");
        RULE_EXPLANATIONS.put("ast_factor_common_left",
            "Gemeinsamer Faktor steht in beiden Summanden links und wird ausgeklammert.");
        RULE_EXPLANATIONS.put("ast_factor_common_right",
            "Gemeinsamer Faktor steht in beiden Summanden rechts und wird ausgeklammert.");
        RULE_EXPLANATIONS.put("ast_canonical_normalize",
            "Strukturelle Normalisierung (Reihenfolge, neutrale Elemente).");
        RULE_EXPLANATIONS.put("polynomial_combine_like_terms",
            "Zwei Vielfache desselben Terms werden zu einem Term mit summierter Koeffizientensumme.");
        RULE_EXPLANATIONS.put("rational_cancel_common_factor",
            "Gemeinsamer Faktor von Zähler und Nenner kann gekürzt werden.");
        RULE_EXPLANATIONS.put("rational_multiply_fractions",
            "Brüche werden multipliziert, indem Zähler mal Zähler und Nenner mal Nenner gerechnet wird.");
        RULE_EXPLANATIONS.put("rational_divide_by_fraction",
            "Durch einen Bruch dividieren entspricht der Multiplikation mit dem Kehrwert.");
    }
}
