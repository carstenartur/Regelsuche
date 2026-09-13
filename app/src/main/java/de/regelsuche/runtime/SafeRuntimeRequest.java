package de.regelsuche.runtime;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.math.algorithms.linalg.MatrixPreparation;
import de.regelsuche.math.algorithms.linalg.MatrixPreparationJson;
import de.regelsuche.search.reachability.PatternTargetedLocalBridgeSearch;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static de.regelsuche.runtime.RuntimeJson.*;

/** Source-side policy input. Targets, answers and executable rules have no fields here. */
public record SafeRuntimeRequest(Profile profile, String source, AssumptionSignature assumptions,
        List<String> ruleIds, List<String> preparationRuleIds, boolean includeSymPy, int maxWorkUnits,
        int maxPrimitiveRewrites, int maxTheoryWorkUnits,
        PatternTargetedLocalBridgeSearch.Budget preparationBudget, MatrixPreparation.Request representation) {
    public static final String SCHEMA = "regelsuche.safe-runtime-request/v1";
    public static final int DEFAULT_WORK = 200_000;
    public enum Profile { DIRECT_V1, SAFE_PREPARATION_V3, SAFE_PREPARATION_V4 }

    public SafeRuntimeRequest {
        Objects.requireNonNull(profile, "profile");
        source = source == null ? "" : source.trim();
        if (source.isBlank() == (representation == null)) throw new IllegalArgumentException("supply source or representation");
        if (source.length() > 65_536) throw new IllegalArgumentException("source is too large");
        assumptions = AssumptionSignature.ofExpressions(Objects.requireNonNull(assumptions, "assumptions").normalizedAssumptions());
        if (assumptions.normalizedAssumptions().size() > 64) throw new IllegalArgumentException("too many assumptions");
        ruleIds = List.copyOf(ruleIds);
        preparationRuleIds = List.copyOf(preparationRuleIds);
        if (ruleIds.size() > 1024 || ruleIds.stream().anyMatch(String::isBlank)
                || ruleIds.stream().distinct().count() != ruleIds.size()) throw new IllegalArgumentException("invalid rule selection");
        if (preparationRuleIds.stream().anyMatch(String::isBlank)
                || preparationRuleIds.stream().distinct().count() != preparationRuleIds.size()) throw new IllegalArgumentException("invalid preparation selection");
        if (maxWorkUnits < 0 || maxWorkUnits > 1_000_000) throw new IllegalArgumentException("runtime work budget outside 0..1000000");
        if (maxPrimitiveRewrites < 0 || maxPrimitiveRewrites > 1024 || maxTheoryWorkUnits < 0 || maxTheoryWorkUnits > 1_000_000) {
            throw new IllegalArgumentException("invalid primitive/theory path budget");
        }
        Objects.requireNonNull(preparationBudget, "preparationBudget");
        if (representation != null && representation.profile() != representationProfile(profile)) {
            throw new IllegalArgumentException("typed representation profile differs from runtime profile");
        }
    }

    public static SafeRuntimeRequest read(Map<String, ?> values) {
        only(values, "schema", "profile", "source", "assumptions", "ruleIds", "preparationRuleIds", "includeSymPy", "maxWorkUnits", "maxPrimitiveRewrites", "maxTheoryWorkUnits", "preparationBudget", "representation");
        if (!SCHEMA.equals(values.get("schema"))) throw new IllegalArgumentException("unsupported safe runtime request schema");
        Profile profile = Profile.valueOf(text(values, "profile", "")); // opt-in is mandatory
        int maximum = integer(values, "maxWorkUnits", DEFAULT_WORK, 0, 1_000_000);
        MatrixPreparation.Request representation = null;
        if (values.containsKey("representation")) {
            Map<String, Object> typed = new java.util.LinkedHashMap<>(object(values.get("representation")));
            if (typed.containsKey("profile") && !representationProfile(profile).name().equals(typed.get("profile"))) {
                throw new IllegalArgumentException("typed representation profile differs from runtime profile");
            }
            if (typed.containsKey("maxWorkUnits")) throw new IllegalArgumentException("the runtime owns typed work budgets");
            typed.put("profile", representationProfile(profile).name());
            typed.put("maxWorkUnits", maximum);
            representation = MatrixPreparationJson.readRequest(typed);
        }
        return new SafeRuntimeRequest(profile, text(values, "source", ""),
            AssumptionSignature.ofExpressions(strings(values, "assumptions")), strings(values, "ruleIds"),
            strings(values, "preparationRuleIds"), bool(values, "includeSymPy", true), maximum,
            integer(values, "maxPrimitiveRewrites", 64, 0, 1024), integer(values, "maxTheoryWorkUnits", DEFAULT_WORK, 0, 1_000_000),
            readBudget(values.containsKey("preparationBudget") ? object(values.get("preparationBudget")) : Map.of()), representation);
    }

    public Map<String, Object> observation() {
        var result = fields("schema", SCHEMA, "profile", profile.name(), "assumptions", assumptions.normalizedAssumptions(),
            "ruleIds", ruleIds, "preparationRuleIds", preparationRuleIds, "includeSymPy", includeSymPy, "maxWorkUnits", maxWorkUnits,
            "maxPrimitiveRewrites", maxPrimitiveRewrites, "maxTheoryWorkUnits", maxTheoryWorkUnits,
            "preparationBudget", RuntimeJson.observation(preparationBudget));
        if (representation == null) result.put("source", source);
        else {
            var typed = new java.util.LinkedHashMap<>(new de.regelsuche.json.JsonReader(
                MatrixPreparationJson.requestJson(representation)).readObject());
            typed.remove("maxWorkUnits");
            result.put("representation", typed);
        }
        return result;
    }

    static MatrixPreparation.Profile representationProfile(Profile profile) {
        return profile == Profile.DIRECT_V1 ? MatrixPreparation.Profile.RECOGNITION_ONLY_V1
            : MatrixPreparation.Profile.SAFE_PREPARED_REPRESENTATION_V1;
    }

    private static PatternTargetedLocalBridgeSearch.Budget readBudget(Map<String, ?> values) {
        only(values, "maxDepth", "maxVisitedStates", "maxGeneratedTransitions", "maxPrimitiveSteps", "maxExpressionNodes", "maxSuccessorsPerState", "maxMatchResults", "maxMatchSteps", "maxPatternBranches");
        return new PatternTargetedLocalBridgeSearch.Budget(
            integer(values, "maxDepth", 2, 0, 8), integer(values, "maxVisitedStates", 128, 1, 4096),
            integer(values, "maxGeneratedTransitions", 512, 0, 16384), integer(values, "maxPrimitiveSteps", 4, 0, 32),
            integer(values, "maxExpressionNodes", 256, 1, 4096), integer(values, "maxSuccessorsPerState", 32, 1, 512),
            integer(values, "maxMatchResults", 32, 1, 512), integer(values, "maxMatchSteps", 4096, 1, 65536),
            integer(values, "maxPatternBranches", 1024, 1, 16384));
    }
}
