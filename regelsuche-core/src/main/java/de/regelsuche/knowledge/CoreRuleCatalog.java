package de.regelsuche.knowledge;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Catalog of the built-in ("core") rule packs.
 *
 * <p>Every built-in rewrite rule belongs to exactly one pack. Kernel packs are never ablatable;
 * first-party packs can be switched off wholesale, which is how baseline/ablation runs are declared
 * instead of being deployed.</p>
 */
public final class CoreRuleCatalog {
    public static final String IDENTITIES = "core-identities";
    public static final String NORMALIZATION = "core-normalization";
    public static final String POWER_RULES = "core-power-rules";
    public static final String TERM_COLLECTION = "core-term-collection";
    public static final String DISTRIBUTION = "core-distribution";
    public static final String FACTORIZATION = "core-factorization";
    public static final String POLYNOMIAL_DIVISION = "core-polynomial-division";
    public static final String EXACT_POLYNOMIAL_DIVISION = "core-exact-polynomial-division";

    private static final List<CoreRulePack> PACKS = List.of(
            new CoreRulePack(
                    IDENTITIES,
                    "Core Identities",
                    RuleTier.KERNEL,
                    true,
                    "Neutral and absorbing element eliminations required for termination.",
                    List.of(
                            "ast_add_zero_right",
                            "ast_add_zero_left",
                            "ast_multiply_one_right",
                            "ast_multiply_one_left",
                            "ast_multiply_zero_right",
                            "ast_multiply_zero_left",
                            "ast_subtract_zero",
                            "ast_divide_one")),
            new CoreRulePack(
                    NORMALIZATION,
                    "Core Normalization",
                    RuleTier.KERNEL,
                    true,
                    "Canonical normalization and numeric folding used by state hashing.",
                    List.of(
                            "ast_fold_numeric_arithmetic",
                            "ast_canonical_normalize")),
            new CoreRulePack(
                    POWER_RULES,
                    "Core Power Rules",
                    RuleTier.FIRST_PARTY,
                    true,
                    "Power/product conversions and exponent arithmetic shortcuts.",
                    List.of(
                            "ast_product_to_power_two",
                            "ast_power_two_to_product",
                            "ast_combine_powers",
                            "ast_power_of_power",
                            "ast_square_literal_split")),
            new CoreRulePack(
                    TERM_COLLECTION,
                    "Core Term Collection",
                    RuleTier.FIRST_PARTY,
                    true,
                    "Collection of equal or linear terms into compact form.",
                    List.of(
                            "ast_double_term",
                            "ast_linear_offset_simplify")),
            new CoreRulePack(
                    DISTRIBUTION,
                    "Core Distribution",
                    RuleTier.FIRST_PARTY,
                    true,
                    "Distributive expansion over sums and differences.",
                    List.of(
                            "ast_distribute_left_add",
                            "ast_distribute_right_add",
                            "ast_distribute_left_subtract",
                            "ast_distribute_right_subtract",
                            "ast_distribute_division_over_sum")),
            new CoreRulePack(
                    FACTORIZATION,
                    "Core Factorization",
                    RuleTier.FIRST_PARTY,
                    true,
                    "Common-factor extraction and difference-of-squares factoring shortcuts.",
                    List.of(
                            "ast_square_difference_factor",
                            "ast_factor_common_left",
                            "ast_factor_common_right")),
            new CoreRulePack(
                    POLYNOMIAL_DIVISION,
                    "Core Rational Cancellation",
                    RuleTier.FIRST_PARTY,
                    true,
                    "Atomic cancellation of a numerator factor against the divisor.",
                    List.of("ast_cancel_division_factor")),
            new CoreRulePack(
                    EXACT_POLYNOMIAL_DIVISION,
                    "Experimental Exact Polynomial Division",
                    RuleTier.FIRST_PARTY,
                    false,
                    "Opt-in exact univariate polynomial long division. It is excluded from the default benchmark inventory so that a benchmark-discovered gap is not silently closed inside the measured configuration.",
                    List.of("ast_polynomial_exact_division")));

    private static final Map<String, CoreRulePack> BY_ID = indexById(PACKS);
    private static final Map<String, String> PACK_ID_BY_RULE_ID = indexByRuleId(PACKS);

    private CoreRuleCatalog() {
    }

    public static List<CoreRulePack> packs() {
        return PACKS;
    }

    public static CoreRulePack pack(String packId) {
        CoreRulePack pack = BY_ID.get(packId);
        if (pack == null) {
            throw new IllegalArgumentException("Unknown core rule pack: " + packId);
        }
        return pack;
    }

    public static Set<String> packIds() {
        return BY_ID.keySet();
    }

    public static Set<String> kernelPackIds() {
        Set<String> kernel = new LinkedHashSet<>();
        for (CoreRulePack pack : PACKS) {
            if (pack.tier() == RuleTier.KERNEL) {
                kernel.add(pack.packId());
            }
        }
        return Set.copyOf(kernel);
    }

    /** Returns the pack owning {@code ruleId}, or {@code null} when the rule is not a core rule. */
    public static String packIdForRule(String ruleId) {
        return PACK_ID_BY_RULE_ID.get(ruleId);
    }

    public static RuleTier tierForRule(String ruleId) {
        String packId = PACK_ID_BY_RULE_ID.get(ruleId);
        return packId == null ? RuleTier.PLUGIN : pack(packId).tier();
    }

    /**
     * Resolves the enabled core packs for a selection.
     *
     * <p>Kernel packs are always enabled; attempting to disable one is rejected so that ablation
     * runs cannot silently remove soundness-critical rules. Selection ids not owned by this catalog
     * are deliberately ignored here because scenario and plugin runtimes may contribute additional
     * packs. User-facing validation belongs at the boundary that knows the complete catalog.</p>
     */
    public static Set<String> enabledPackIds(KnowledgePackSelection selection) {
        KnowledgePackSelection effective = selection == null ? KnowledgePackSelection.CORE : selection;
        rejectKernelDisable(effective.disabledPacks());
        Set<String> defaults = new LinkedHashSet<>();
        for (CoreRulePack pack : PACKS) {
            if (!pack.enabledByDefault()) {
                continue;
            }
            if (pack.tier() == RuleTier.KERNEL || effective.profile().includeFirstPartyDefaults()) {
                defaults.add(pack.packId());
            }
        }
        Set<String> enabled = new LinkedHashSet<>(effective.effectiveEnabledPacks(packIds(), defaults));
        enabled.addAll(kernelPackIds());
        Set<String> ordered = new LinkedHashSet<>();
        for (CoreRulePack pack : PACKS) {
            if (enabled.contains(pack.packId())) {
                ordered.add(pack.packId());
            }
        }
        return Set.copyOf(ordered);
    }

    private static void rejectKernelDisable(Set<String> disabledPacks) {
        for (String packId : disabledPacks) {
            CoreRulePack pack = BY_ID.get(packId);
            if (pack != null && pack.tier() == RuleTier.KERNEL) {
                throw new IllegalArgumentException("Kernel rule pack cannot be disabled: " + packId);
            }
        }
    }

    private static Map<String, CoreRulePack> indexById(List<CoreRulePack> packs) {
        Map<String, CoreRulePack> index = new LinkedHashMap<>();
        for (CoreRulePack pack : packs) {
            if (index.put(pack.packId(), pack) != null) {
                throw new IllegalStateException("Duplicate core rule pack id: " + pack.packId());
            }
        }
        return Map.copyOf(index);
    }

    private static Map<String, String> indexByRuleId(List<CoreRulePack> packs) {
        Map<String, String> index = new LinkedHashMap<>();
        for (CoreRulePack pack : packs) {
            for (String ruleId : pack.ruleIds()) {
                if (index.put(ruleId, pack.packId()) != null) {
                    throw new IllegalStateException("Rule assigned to more than one core pack: " + ruleId);
                }
            }
        }
        return Map.copyOf(index);
    }
}
