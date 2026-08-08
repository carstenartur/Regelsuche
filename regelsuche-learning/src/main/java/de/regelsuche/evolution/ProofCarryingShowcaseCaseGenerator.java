package de.regelsuche.evolution;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Deterministic, network-free generator for the frozen 24-case showcase suite. */
public final class ProofCarryingShowcaseCaseGenerator {
    public static final String DOMAIN =
        "regelsuche.proof-carrying-showcase-case-generator/v1";

    public ProofCarryingShowcaseGeneratedFinalTest generate(
        ProofCarryingShowcasePlan plan,
        ProofCarryingShowcaseSeedReceipt seed
    ) {
        seed.requireCompatible(plan);
        List<ProofCarryingShowcaseGeneratedCase> cases =
            new ArrayList<>();
        int ordinal = 0;
        for (ProofCarryingShowcasePlan.Family family
                : plan.challengeGenerator().families()) {
            for (int difficulty : family.difficultyLevels()) {
                for (int variant : List.of(0, 1)) {
                    byte[] data = digest(
                        seed.derivedSeed(),
                        family.familyId(),
                        difficulty,
                        variant);
                    GeneratedMaterial material = generateMaterial(
                        family.familyId(),
                        ordinal,
                        difficulty,
                        variant,
                        data);
                    cases.add(ProofCarryingShowcaseGeneratedCase.create(
                        plan,
                        seed,
                        family.familyId(),
                        difficulty,
                        variant,
                        material.inputExpression(),
                        material.targetExpression(),
                        material.assumptions(),
                        material.coefficientVector(),
                        material.blockKinds()));
                    ordinal++;
                }
            }
        }
        return ProofCarryingShowcaseGeneratedFinalTest.create(
            plan,
            seed,
            cases);
    }

    private static GeneratedMaterial generateMaterial(
        String familyId,
        int caseOrdinal,
        int difficulty,
        int variant,
        byte[] data
    ) {
        return switch (familyId) {
            case "nested-rational-cancellation" ->
                nestedRationalCase(
                    caseOrdinal, difficulty, variant, data);
            case "factor-cancel-collect" ->
                factorCancelCollectCase(
                    caseOrdinal, difficulty, variant, data);
            case "multi-stage-rational-polynomial" ->
                multiStageCase(
                    caseOrdinal, difficulty, variant, data);
            default -> throw new IllegalArgumentException(
                "unsupported frozen showcase family " + familyId);
        };
    }

    private static GeneratedMaterial nestedRationalCase(
        int caseOrdinal,
        int difficulty,
        int variant,
        byte[] data
    ) {
        List<Integer> coefficients = coefficientVector(
            data, difficulty, caseOrdinal);
        List<String> blocks = new ArrayList<>();
        List<String> numerators = new ArrayList<>();
        List<String> denominators = new ArrayList<>();
        List<String> assumptions = new ArrayList<>();
        for (int index = 0; index < coefficients.size(); index++) {
            int coefficient = coefficients.get(index);
            String suffix = caseOrdinal + "_" + index;
            String p = "p" + suffix;
            String q = "q" + suffix;
            String factor = "f" + suffix;
            String quotient =
                "((" + p + "/" + factor + ")/("
                    + q + "/" + factor + "))";
            String block = variant == 0
                ? "(" + coefficient + "*" + quotient + ")"
                : "(((" + coefficient + "*" + p + ")/"
                    + factor + ")/(" + q + "/" + factor + "))";
            blocks.add(block);
            numerators.add("(" + coefficient + "*" + p + ")");
            denominators.add(q);
            assumptions.add(factor + " != 0");
            assumptions.add(q + " != 0");
            if (index > 0) {
                assumptions.add(p + " != 0");
            }
        }
        String targetNumerator = parenthesizedProduct(
            combineFirstAndTail(numerators, denominators));
        String targetDenominator = parenthesizedProduct(
            combineFirstAndTail(denominators, numerators));
        return new GeneratedMaterial(
            leftDivision(blocks),
            "(" + targetNumerator + ")/("
                + targetDenominator + ")",
            normalizedAssumptions(assumptions),
            coefficients,
            repeat(
                "SHARED_DENOMINATOR_QUOTIENT",
                difficulty));
    }

    private static GeneratedMaterial factorCancelCollectCase(
        int caseOrdinal,
        int difficulty,
        int variant,
        byte[] data
    ) {
        List<Integer> coefficients = coefficientVector(
            data, difficulty, caseOrdinal);
        String denominator = "d" + caseOrdinal;
        List<String> terms = new ArrayList<>();
        List<String> targetTerms = new ArrayList<>();
        List<String> assumptions = new ArrayList<>();
        assumptions.add(denominator + " != 0");
        for (int index = 0; index < coefficients.size(); index++) {
            int coefficient = coefficients.get(index);
            String suffix = caseOrdinal + "_" + index;
            String left = "a" + suffix;
            String right = "b" + suffix;
            String difference =
                "(" + left + "^2-" + right + "^2)";
            String divisor = "(" + left + "-" + right + ")";
            String term = variant == 0
                ? "(" + coefficient + "*(("
                    + difference + "/" + divisor + ")/"
                    + denominator + "))"
                : "(((" + coefficient + "*" + difference
                    + ")/" + divisor + ")/" + denominator + ")";
            terms.add(term);
            targetTerms.add(
                "(" + coefficient + "*("
                    + left + "+" + right + "))");
            assumptions.add(divisor + " != 0");
        }
        return new GeneratedMaterial(
            parenthesizedSum(terms),
            "(" + parenthesizedSum(targetTerms)
                + ")/" + denominator,
            normalizedAssumptions(assumptions),
            coefficients,
            repeat(
                "FACTOR_CANCEL_SHARED_DENOMINATOR",
                difficulty));
    }

    private static GeneratedMaterial multiStageCase(
        int caseOrdinal,
        int difficulty,
        int variant,
        byte[] data
    ) {
        List<Integer> coefficients = coefficientVector(
            data, difficulty, caseOrdinal);
        List<String> blocks = new ArrayList<>();
        List<String> numerators = new ArrayList<>();
        List<String> denominators = new ArrayList<>();
        List<String> assumptions = new ArrayList<>();
        List<String> blockKinds = new ArrayList<>();

        for (int index = 0; index < coefficients.size(); index++) {
            int coefficient = coefficients.get(index);
            String suffix = caseOrdinal + "_" + index;
            if (index % 2 == 0) {
                String a = "r" + suffix;
                String b = "s" + suffix;
                String x = "x" + suffix;
                String y = "y" + suffix;
                String numerator = variant == 0
                    ? "(" + coefficient + "*(("
                        + a + "/" + x + ")-("
                        + a + "/" + y + ")))"
                    : "(((" + coefficient + "*" + a + ")/"
                        + x + ")-((" + coefficient + "*" + a
                        + ")/" + y + "))";
                String denominator =
                    "((" + b + "/" + x + ")-("
                        + b + "/" + y + "))";
                blocks.add("(" + numerator + ")/("
                    + denominator + ")");
                numerators.add("(" + coefficient + "*" + a + ")");
                denominators.add(b);
                String delta = "(" + y + "-" + x + ")";
                assumptions.add(x + " != 0");
                assumptions.add(y + " != 0");
                assumptions.add(b + " != 0");
                assumptions.add(delta + " != 0");
                assumptions.add("(" + b + "*" + delta + ") != 0");
                if (index > 0) {
                    assumptions.add(a + " != 0");
                    assumptions.add(
                        "(" + a + "*" + delta + ") != 0");
                }
                blockKinds.add("MIXED_DENOMINATOR_RATIO");
            } else {
                String p = "u" + suffix;
                String q = "v" + suffix;
                String difference =
                    "(" + p + "^2-" + q + "^2)";
                String divisor = "(" + p + "-" + q + ")";
                String block = variant == 0
                    ? "(" + coefficient + "*(("
                        + difference + ")/" + divisor + "))"
                    : "((" + coefficient + "*" + difference
                        + ")/" + divisor + ")";
                blocks.add(block);
                numerators.add(
                    "(" + coefficient + "*("
                        + p + "+" + q + "))");
                denominators.add("1");
                assumptions.add(divisor + " != 0");
                assumptions.add("(" + p + "+" + q + ") != 0");
                assumptions.add(difference + " != 0");
                blockKinds.add(
                    "DIFFERENCE_OF_SQUARES_QUOTIENT");
            }
        }

        List<String> numeratorFactors = new ArrayList<>();
        numeratorFactors.add(numerators.getFirst());
        denominators.stream()
            .skip(1)
            .filter(value -> !"1".equals(value))
            .forEach(numeratorFactors::add);
        List<String> denominatorFactors = new ArrayList<>();
        denominatorFactors.add(denominators.getFirst());
        denominatorFactors.addAll(numerators.subList(
            1, numerators.size()));
        return new GeneratedMaterial(
            leftDivision(blocks),
            "(" + parenthesizedProduct(numeratorFactors)
                + ")/("
                + parenthesizedProduct(denominatorFactors)
                + ")",
            normalizedAssumptions(assumptions),
            coefficients,
            List.copyOf(blockKinds));
    }

    private static byte[] digest(
        String seed,
        String familyId,
        int difficulty,
        int variant
    ) {
        String material = String.join(
            "\n",
            DOMAIN,
            "seed=" + seed,
            "family=" + familyId,
            "difficulty=" + difficulty,
            "variant=" + variant);
        return ProofCarryingShowcaseJsonSupport.sha256Bytes(material);
    }

    private static List<Integer> coefficientVector(
        byte[] data,
        int count,
        int caseOrdinal
    ) {
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int value = 2 + (
                (Byte.toUnsignedInt(data[index % data.length])
                    + 17 * caseOrdinal
                    + 11 * index)
                % 23);
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static List<String> combineFirstAndTail(
        List<String> first,
        List<String> tail
    ) {
        List<String> result = new ArrayList<>();
        result.add(first.getFirst());
        result.addAll(tail.subList(1, tail.size()));
        return result;
    }

    private static String parenthesizedProduct(
        List<String> factors
    ) {
        if (factors.isEmpty()) {
            return "1";
        }
        String result = factors.getFirst();
        for (String factor : factors.subList(1, factors.size())) {
            result = "(" + result + "*" + factor + ")";
        }
        return result;
    }

    private static String parenthesizedSum(List<String> terms) {
        if (terms.isEmpty()) {
            return "0";
        }
        String result = terms.getFirst();
        for (String term : terms.subList(1, terms.size())) {
            result = "(" + result + "+" + term + ")";
        }
        return result;
    }

    private static String leftDivision(List<String> blocks) {
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException(
                "left division requires at least one block");
        }
        String result = blocks.getFirst();
        for (String block : blocks.subList(1, blocks.size())) {
            result = "(" + result + ")/(" + block + ")";
        }
        return result;
    }

    private static List<String> normalizedAssumptions(
        List<String> assumptions
    ) {
        return List.copyOf(new TreeSet<>(assumptions));
    }

    private static List<String> repeat(String value, int count) {
        List<String> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(value);
        }
        return List.copyOf(result);
    }

    private record GeneratedMaterial(
        String inputExpression,
        String targetExpression,
        List<String> assumptions,
        List<Integer> coefficientVector,
        List<String> blockKinds
    ) {
    }
}
