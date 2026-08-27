package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Deterministic proper-subset search used by Zassenhaus recombination. */
final class ZassenhausSearch {
    private ZassenhausSearch() {
    }

    static SearchResult findFactor(
        UnivariatePolynomialView<BigInteger> source,
        List<UnivariatePolynomialView<BigInteger>> modularFactors,
        List<List<Integer>> groups,
        BigInteger coefficientBound,
        BigInteger modulus,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work,
        long candidatesBefore,
        long candidateLimit,
        CandidateAudit audit
    ) {
        List<BigInteger> leadingDivisors =
            IntegerPolynomialArithmetic.positiveDivisors(
                source.leadingCoefficient(),
                policy,
                work);
        long candidates = candidatesBefore;
        int factorCount = modularFactors.size();

        for (int subsetSize = 1;
                subsetSize <= factorCount / 2;
                subsetSize++) {
            int[] subset = initialCombination(subsetSize);
            while (subset != null) {
                boolean duplicateComplement =
                    subsetSize * 2 == factorCount
                        && subset[0] != 0;
                if (!duplicateComplement) {
                    for (BigInteger leadingDivisor : leadingDivisors) {
                        if (candidates >= candidateLimit) {
                            return SearchResult.inconclusive(
                                candidates,
                                "ZASSENHAUS_CANDIDATE_BUDGET_EXHAUSTED");
                        }
                        candidates++;
                        work.consume(
                            "zassenhaus.candidate.attempts",
                            1);
                        UnivariatePolynomialView<BigInteger> candidate =
                            IntegerPolynomialArithmetic.subsetCandidate(
                                modularFactors,
                                subset,
                                leadingDivisor,
                                modulus,
                                policy,
                                work);
                        String rejection = classifyShape(
                            candidate,
                            source,
                            coefficientBound,
                            work);
                        if (rejection != null) {
                            audit.record(
                                subset,
                                leadingDivisor,
                                candidate,
                                rejection);
                            continue;
                        }
                        IntegerPolynomialArithmetic.ExactDivision division =
                            IntegerPolynomialArithmetic.exactDivide(
                                source,
                                candidate,
                                policy,
                                work,
                                "zassenhaus.candidate.exact-division");
                        if (!division.exact()) {
                            audit.record(
                                subset,
                                leadingDivisor,
                                candidate,
                                "NOT_AN_EXACT_INTEGER_DIVISOR");
                            continue;
                        }
                        audit.record(
                            subset,
                            leadingDivisor,
                            candidate,
                            "ACCEPTED");
                        return SearchResult.found(
                            candidate,
                            division.quotient(),
                            subset,
                            union(groups, subset),
                            candidates);
                    }
                }
                subset = nextCombination(subset, factorCount);
            }
        }
        return SearchResult.exhaustive(candidates);
    }

    private static String classifyShape(
        UnivariatePolynomialView<BigInteger> candidate,
        UnivariatePolynomialView<BigInteger> source,
        BigInteger coefficientBound,
        PolynomialWorkBudget work
    ) {
        if (candidate.degree() <= 0
                || candidate.degree() >= source.degree()) {
            return "NOT_A_PROPER_NONCONSTANT_FACTOR";
        }
        for (BigInteger coefficient : candidate.coefficients()) {
            work.consume("zassenhaus.candidate.bound-tests", 1);
            if (coefficient.abs().compareTo(coefficientBound) > 0) {
                return "COEFFICIENT_BOUND_EXCEEDED";
            }
        }
        return IntegerPolynomialArithmetic.isPrimitivePositive(
            candidate,
            work)
                ? null
                : "NOT_PRIMITIVE_POSITIVE";
    }

    private static int[] initialCombination(int size) {
        int[] result = new int[size];
        for (int index = 0; index < size; index++) {
            result[index] = index;
        }
        return result;
    }

    private static int[] nextCombination(
        int[] current,
        int universeSize
    ) {
        int[] next = current.clone();
        int index = next.length - 1;
        while (index >= 0
                && next[index]
                    == universeSize - next.length + index) {
            index--;
        }
        if (index < 0) {
            return null;
        }
        next[index]++;
        for (int following = index + 1;
                following < next.length;
                following++) {
            next[following] = next[following - 1] + 1;
        }
        return next;
    }

    private static List<Integer> union(
        List<List<Integer>> groups,
        int[] selected
    ) {
        return java.util.Arrays.stream(selected)
            .boxed()
            .flatMap(index -> groups.get(index).stream())
            .sorted()
            .toList();
    }

    static final class CandidateAudit {
        private String state;
        private long count;

        CandidateAudit(String sourceMaterial) {
            state = AlgorithmEvidence.sha256(
                ZassenhausRecombination.METHOD_ID
                    + ':' + sourceMaterial);
        }

        void record(
            int[] subset,
            BigInteger leadingDivisor,
            UnivariatePolynomialView<BigInteger> candidate,
            String disposition
        ) {
            count = Math.addExact(count, 1L);
            StringBuilder material = new StringBuilder(state);
            AlgorithmEvidence.append(
                material,
                java.util.Arrays.toString(subset));
            AlgorithmEvidence.append(
                material,
                leadingDivisor.toString());
            AlgorithmEvidence.append(
                material,
                candidate.canonicalMaterial());
            AlgorithmEvidence.append(material, disposition);
            state = AlgorithmEvidence.sha256(material.toString());
        }

        String hash() {
            return state;
        }

        long count() {
            return count;
        }
    }

    record SearchResult(
        boolean found,
        boolean exhaustive,
        String detailCode,
        UnivariatePolynomialView<BigInteger> factor,
        UnivariatePolynomialView<BigInteger> quotient,
        int[] selectedPositions,
        List<Integer> partition,
        long candidatesConsidered
    ) {
        SearchResult {
            selectedPositions = selectedPositions == null
                ? null
                : selectedPositions.clone();
            partition = List.copyOf(partition);
        }

        static SearchResult found(
            UnivariatePolynomialView<BigInteger> factor,
            UnivariatePolynomialView<BigInteger> quotient,
            int[] selectedPositions,
            List<Integer> partition,
            long candidates
        ) {
            return new SearchResult(
                true,
                false,
                "ZASSENHAUS_FACTOR_ACCEPTED",
                factor,
                quotient,
                selectedPositions,
                partition,
                candidates);
        }

        static SearchResult exhaustive(long candidates) {
            return new SearchResult(
                false,
                true,
                "ZASSENHAUS_SEARCH_EXHAUSTED",
                null,
                null,
                null,
                List.of(),
                candidates);
        }

        static SearchResult inconclusive(
            long candidates,
            String detailCode
        ) {
            return new SearchResult(
                false,
                false,
                detailCode,
                null,
                null,
                null,
                List.of(),
                candidates);
        }

        @Override
        public int[] selectedPositions() {
            return selectedPositions == null
                ? null
                : selectedPositions.clone();
        }
    }

    static <T> List<T> removeSelected(
        List<T> values,
        int[] selected
    ) {
        boolean[] removed = new boolean[values.size()];
        for (int index : selected) {
            removed[index] = true;
        }
        ArrayList<T> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            if (!removed[index]) {
                result.add(values.get(index));
            }
        }
        return List.copyOf(result);
    }

    static List<List<Integer>> singletonGroups(int count) {
        ArrayList<List<Integer>> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(List.of(index));
        }
        return List.copyOf(result);
    }
}
