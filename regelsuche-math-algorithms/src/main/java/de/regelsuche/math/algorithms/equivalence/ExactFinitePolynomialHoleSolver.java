package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.scalar.ExactRational;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exhaustively binds finite exact coefficient and sign holes in one polynomial
 * ansatz.
 *
 * <p>The solver receives a source expression, a caller-frozen ansatz template
 * and finite domains. It receives no target expression or historical
 * reference. Every assignment is checked by the source-exact polynomial
 * arithmetic used by residual composition.</p>
 */
public final class ExactFinitePolynomialHoleSolver {
    public static final String SOLVER_ID =
        "regelsuche.exact-finite-polynomial-hole-solver/v1";
    public static final String REVISION_HASH = hash(lengthPrefixed(
        SOLVER_ID,
        "source-exact-polynomial-arithmetic",
        "complete-finite-cartesian-enumeration",
        "coefficient-and-sign-holes",
        "unsupported-instantiation-fails-closed"));

    private static final Pattern HOLE_ID = Pattern.compile(
        "[a-z][a-z0-9_-]{2,63}");
    private static final Pattern PLACEHOLDER = Pattern.compile(
        "\\$\\{([a-z][a-z0-9_-]{2,63})}");
    private static final int MAX_TEMPLATE_CHARS = 16_384;
    private static final int MAX_HOLES = 12;
    private static final int MAX_VALUES_PER_HOLE = 128;
    private static final int MAX_SCALAR_BITS = 512;
    private static final long MAX_ASSIGNMENTS = 100_000L;
    private static final int MAX_RETAINED_SOLUTIONS = 256;

    private final ExactResidualPolynomialArithmetic arithmetic =
        new ExactResidualPolynomialArithmetic();

    public SearchResult solve(
        String sourceExpression,
        String ansatzTemplate,
        List<HoleDomain> holeDomains,
        int maxRetainedSolutions
    ) {
        if (maxRetainedSolutions < 1
                || maxRetainedSolutions > MAX_RETAINED_SOLUTIONS) {
            throw new IllegalArgumentException(
                "maxRetainedSolutions must be in [1,"
                    + MAX_RETAINED_SOLUTIONS + "]");
        }

        String source = normalizePolynomial(
            sourceExpression,
            "sourceExpression");
        Polynomial sourcePolynomial = arithmetic.parse(source);
        String template = normalizeTemplate(ansatzTemplate);
        List<HoleDomain> domains = normalizeDomains(holeDomains);
        validateTemplateHoles(template, domains);
        long totalAssignments = countAssignments(domains);

        SearchAccumulator accumulator = new SearchAccumulator(
            maxRetainedSolutions);
        enumerate(
            sourcePolynomial,
            template,
            domains,
            0,
            new LinkedHashMap<>(),
            accumulator);

        return new SearchResult(
            SOLVER_ID,
            REVISION_HASH,
            source,
            template,
            domains,
            totalAssignments,
            accumulator.evaluatedAssignments,
            accumulator.matchingAssignments,
            maxRetainedSolutions,
            deriveStatus(
                accumulator.matchingAssignments,
                accumulator.solutions.size()),
            accumulator.solutions);
    }

    /**
     * Repeats the complete finite search under this exact solver revision.
     *
     * <p>A successful replay confirms the solver result, not a separate formal
     * proof object or an independently loaded evidence artifact.</p>
     */
    public boolean replay(SearchResult expected) {
        Objects.requireNonNull(expected, "expected");
        try {
            SearchResult actual = solve(
                expected.sourceExpression(),
                expected.ansatzTemplate(),
                expected.holeDomains(),
                expected.retainedSolutionLimit());
            return expected.equals(actual);
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    private void enumerate(
        Polynomial sourcePolynomial,
        String template,
        List<HoleDomain> domains,
        int domainIndex,
        Map<String, Binding> bindings,
        SearchAccumulator accumulator
    ) {
        if (domainIndex == domains.size()) {
            evaluate(
                sourcePolynomial,
                template,
                bindings,
                accumulator);
            return;
        }
        HoleDomain domain = domains.get(domainIndex);
        for (ExactRational value : domain.values()) {
            bindings.put(
                domain.holeId(),
                new Binding(domain.holeId(), domain.kind(), value));
            enumerate(
                sourcePolynomial,
                template,
                domains,
                domainIndex + 1,
                bindings,
                accumulator);
        }
        bindings.remove(domain.holeId());
    }

    private void evaluate(
        Polynomial sourcePolynomial,
        String template,
        Map<String, Binding> bindings,
        SearchAccumulator accumulator
    ) {
        String instantiated = instantiate(template, bindings);
        String normalized;
        Polynomial candidate;
        try {
            normalized = normalizePolynomial(
                instantiated,
                "instantiated ansatz");
            candidate = arithmetic.parse(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "one finite assignment leaves the exact polynomial fragment",
                exception);
        }
        accumulator.evaluatedAssignments++;
        if (!candidate.equals(sourcePolynomial)) {
            return;
        }
        accumulator.matchingAssignments++;
        if (accumulator.solutions.size()
                < accumulator.maxRetainedSolutions) {
            accumulator.solutions.add(new Solution(
                List.copyOf(bindings.values()),
                normalized,
                candidate.toCanonicalString()));
        }
    }

    private String normalizePolynomial(
        String expression,
        String name
    ) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        try {
            String normalized = arithmetic.syntax(expression.trim());
            arithmetic.parse(normalized);
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                name + " is outside the bounded exact polynomial fragment",
                exception);
        }
    }

    private static String normalizeTemplate(String template) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException(
                "ansatzTemplate must not be blank");
        }
        if (template.length() > MAX_TEMPLATE_CHARS
                || template.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                "ansatzTemplate exceeds the bounded text grammar");
        }
        return template.trim().replaceAll("\\s+", " ");
    }

    private static void validateTemplateHoles(
        String template,
        List<HoleDomain> domains
    ) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        Set<String> referenced = new LinkedHashSet<>();
        StringBuffer scrubbed = new StringBuffer();
        while (matcher.find()) {
            referenced.add(matcher.group(1));
            matcher.appendReplacement(scrubbed, "0");
        }
        matcher.appendTail(scrubbed);
        if (scrubbed.indexOf("$") >= 0
                || scrubbed.indexOf("{") >= 0
                || scrubbed.indexOf("}") >= 0) {
            throw new IllegalArgumentException(
                "ansatzTemplate contains a malformed placeholder");
        }
        Set<String> declared = Set.copyOf(domains.stream()
            .map(HoleDomain::holeId)
            .toList());
        if (!referenced.equals(declared)) {
            throw new IllegalArgumentException(
                "template and domains must reference exactly the same holes");
        }
    }

    private static String instantiate(
        String template,
        Map<String, Binding> bindings
    ) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            Binding binding = bindings.get(matcher.group(1));
            if (binding == null) {
                throw new IllegalArgumentException(
                    "template references an unbound hole");
            }
            matcher.appendReplacement(
                result,
                Matcher.quoteReplacement(
                    "(" + binding.value().canonicalText() + ")"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static List<HoleDomain> normalizeDomains(
        List<HoleDomain> domains
    ) {
        Objects.requireNonNull(domains, "holeDomains");
        if (domains.isEmpty() || domains.size() > MAX_HOLES) {
            throw new IllegalArgumentException(
                "holeDomains size must be in [1," + MAX_HOLES + "]");
        }
        List<HoleDomain> result = domains.stream()
            .map(domain -> Objects.requireNonNull(domain, "holeDomain"))
            .sorted(Comparator.comparing(HoleDomain::holeId))
            .toList();
        requireUnique(
            result.stream().map(HoleDomain::holeId).toList(),
            "hole domain IDs");
        return List.copyOf(result);
    }

    private static long countAssignments(List<HoleDomain> domains) {
        long result = 1L;
        for (HoleDomain domain : domains) {
            if (result > MAX_ASSIGNMENTS / domain.values().size()) {
                throw new IllegalArgumentException(
                    "finite hole search exceeds " + MAX_ASSIGNMENTS
                        + " assignments");
            }
            result *= domain.values().size();
        }
        return result;
    }

    private static SearchStatus deriveStatus(
        long matchingAssignments,
        int retainedSolutions
    ) {
        if (matchingAssignments == 0) {
            return SearchStatus.COMPLETE_WITHOUT_SOLUTION;
        }
        if (matchingAssignments > retainedSolutions) {
            return SearchStatus.COMPLETE_SOLUTION_SET_TRUNCATED;
        }
        return SearchStatus.COMPLETE_WITH_SOLUTIONS;
    }

    private static List<ExactRational> normalizeValues(
        HoleKind kind,
        List<ExactRational> values
    ) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty() || values.size() > MAX_VALUES_PER_HOLE) {
            throw new IllegalArgumentException(
                "hole values size must be in [1,"
                    + MAX_VALUES_PER_HOLE + "]");
        }
        List<ExactRational> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "hole value"))
            .peek(ExactFinitePolynomialHoleSolver::requireBoundedScalar)
            .sorted()
            .toList();
        if (new HashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException(
                "hole values must be unique");
        }
        if (kind == HoleKind.SIGN
                && result.stream().anyMatch(value ->
                    !value.isOne() && !value.isNegativeOne())) {
            throw new IllegalArgumentException(
                "sign holes admit only -1 and 1");
        }
        return List.copyOf(result);
    }

    private static void requireBoundedScalar(ExactRational value) {
        int bits = Math.max(
            value.numerator().abs().bitLength(),
            value.denominator().bitLength());
        if (bits > MAX_SCALAR_BITS) {
            throw new IllegalArgumentException(
                "hole value exceeds the exact scalar bit limit");
        }
    }

    private static String requireHoleId(String value) {
        if (value == null || !HOLE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "holeId must match " + HOLE_ID.pattern());
        }
        return value;
    }

    private static void requireUnique(
        List<String> values,
        String name
    ) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(name + " must be unique");
        }
    }

    private static String lengthPrefixed(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            appendField(result, value);
        }
        return result.toString();
    }

    private static void appendField(
        StringBuilder target,
        String value
    ) {
        target.append(value.getBytes(StandardCharsets.UTF_8).length)
            .append(':')
            .append(value);
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:"
                + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "SHA-256 unavailable",
                exception);
        }
    }

    public enum HoleKind {
        COEFFICIENT,
        SIGN
    }

    public enum SearchStatus {
        COMPLETE_WITHOUT_SOLUTION,
        COMPLETE_WITH_SOLUTIONS,
        COMPLETE_SOLUTION_SET_TRUNCATED
    }

    public record HoleDomain(
        String holeId,
        HoleKind kind,
        List<ExactRational> values
    ) {
        public HoleDomain {
            holeId = requireHoleId(holeId);
            kind = Objects.requireNonNull(kind, "kind");
            values = normalizeValues(kind, values);
        }

        public static HoleDomain integerRange(
            String holeId,
            int minimum,
            int maximum
        ) {
            long count = (long) maximum - minimum + 1L;
            if (count < 1 || count > MAX_VALUES_PER_HOLE) {
                throw new IllegalArgumentException(
                    "integer range exceeds one finite hole domain");
            }
            List<ExactRational> values = new ArrayList<>((int) count);
            for (long value = minimum; value <= maximum; value++) {
                values.add(ExactRational.integer(value));
            }
            return new HoleDomain(
                holeId,
                HoleKind.COEFFICIENT,
                values);
        }

        public static HoleDomain signs(String holeId) {
            return new HoleDomain(
                holeId,
                HoleKind.SIGN,
                List.of(
                    ExactRational.NEGATIVE_ONE,
                    ExactRational.ONE));
        }
    }

    public record Binding(
        String holeId,
        HoleKind kind,
        ExactRational value
    ) {
        public Binding {
            holeId = requireHoleId(holeId);
            kind = Objects.requireNonNull(kind, "kind");
            value = Objects.requireNonNull(value, "value");
            requireBoundedScalar(value);
            if (kind == HoleKind.SIGN
                    && !value.isOne()
                    && !value.isNegativeOne()) {
                throw new IllegalArgumentException(
                    "sign binding must be -1 or 1");
            }
        }
    }

    public record Solution(
        List<Binding> bindings,
        String instantiatedExpression,
        String exactNormalForm
    ) {
        public Solution {
            Objects.requireNonNull(bindings, "bindings");
            bindings = bindings.stream()
                .map(binding -> Objects.requireNonNull(binding, "binding"))
                .sorted(Comparator.comparing(Binding::holeId))
                .toList();
            requireUnique(
                bindings.stream().map(Binding::holeId).toList(),
                "solution binding IDs");
            if (instantiatedExpression == null
                    || instantiatedExpression.isBlank()
                    || exactNormalForm == null
                    || exactNormalForm.isBlank()) {
                throw new IllegalArgumentException(
                    "solution expressions must not be blank");
            }
        }

        public String bindingKey() {
            return bindings.stream()
                .map(binding -> binding.holeId()
                    + "=" + binding.value().canonicalText())
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        }

        public String contentHash() {
            StringBuilder material = new StringBuilder();
            appendField(material, bindingKey());
            appendField(material, instantiatedExpression);
            appendField(material, exactNormalForm);
            return hash(material.toString());
        }
    }

    public record SearchResult(
        String solverId,
        String solverRevisionHash,
        String sourceExpression,
        String ansatzTemplate,
        List<HoleDomain> holeDomains,
        long totalAssignments,
        long evaluatedAssignments,
        long matchingAssignments,
        int retainedSolutionLimit,
        SearchStatus status,
        List<Solution> solutions
    ) {
        public SearchResult {
            if (!SOLVER_ID.equals(solverId)) {
                throw new IllegalArgumentException(
                    "unexpected finite hole solver ID");
            }
            if (!REVISION_HASH.equals(solverRevisionHash)) {
                throw new IllegalArgumentException(
                    "unexpected finite hole solver revision");
            }
            if (sourceExpression == null || sourceExpression.isBlank()) {
                throw new IllegalArgumentException(
                    "sourceExpression must not be blank");
            }
            ansatzTemplate = normalizeTemplate(ansatzTemplate);
            holeDomains = normalizeDomains(holeDomains);
            validateTemplateHoles(ansatzTemplate, holeDomains);
            if (totalAssignments != countAssignments(holeDomains)
                    || evaluatedAssignments != totalAssignments
                    || matchingAssignments < 0
                    || matchingAssignments > evaluatedAssignments) {
                throw new IllegalArgumentException(
                    "hole-search counts are inconsistent");
            }
            if (retainedSolutionLimit < 1
                    || retainedSolutionLimit > MAX_RETAINED_SOLUTIONS) {
                throw new IllegalArgumentException(
                    "retainedSolutionLimit is outside limits");
            }
            status = Objects.requireNonNull(status, "status");
            Objects.requireNonNull(solutions, "solutions");
            solutions = solutions.stream()
                .map(solution -> Objects.requireNonNull(solution, "solution"))
                .sorted(Comparator.comparing(Solution::bindingKey))
                .toList();
            requireUnique(
                solutions.stream().map(Solution::bindingKey).toList(),
                "solution binding keys");
            int expectedRetained = (int) Math.min(
                matchingAssignments,
                retainedSolutionLimit);
            if (solutions.size() != expectedRetained
                    || status != deriveStatus(
                        matchingAssignments,
                        solutions.size())) {
                throw new IllegalArgumentException(
                    "hole-search status does not match solutions");
            }
            validateSolutionDomains(holeDomains, solutions);
        }

        public String contentHash() {
            StringBuilder material = new StringBuilder();
            appendField(material, solverId);
            appendField(material, solverRevisionHash);
            appendField(material, sourceExpression);
            appendField(material, ansatzTemplate);
            appendField(material, Long.toString(totalAssignments));
            appendField(material, Long.toString(evaluatedAssignments));
            appendField(material, Long.toString(matchingAssignments));
            appendField(material, Integer.toString(retainedSolutionLimit));
            appendField(material, status.name());
            for (HoleDomain domain : holeDomains) {
                appendField(material, domain.holeId());
                appendField(material, domain.kind().name());
                for (ExactRational value : domain.values()) {
                    appendField(material, value.canonicalText());
                }
            }
            for (Solution solution : solutions) {
                appendField(material, solution.contentHash());
            }
            return hash(material.toString());
        }

        private static void validateSolutionDomains(
            List<HoleDomain> domains,
            List<Solution> solutions
        ) {
            Map<String, HoleDomain> domainsById = new LinkedHashMap<>();
            domains.forEach(domain ->
                domainsById.put(domain.holeId(), domain));
            for (Solution solution : solutions) {
                if (!solution.bindings().stream()
                        .map(Binding::holeId)
                        .collect(java.util.stream.Collectors.toSet())
                        .equals(domainsById.keySet())) {
                    throw new IllegalArgumentException(
                        "solution does not bind exactly the declared holes");
                }
                for (Binding binding : solution.bindings()) {
                    HoleDomain domain = domainsById.get(binding.holeId());
                    if (binding.kind() != domain.kind()
                            || !domain.values().contains(binding.value())) {
                        throw new IllegalArgumentException(
                            "solution binding is outside its declared domain");
                    }
                }
            }
        }
    }

    private static final class SearchAccumulator {
        private final int maxRetainedSolutions;
        private final List<Solution> solutions = new ArrayList<>();
        private long evaluatedAssignments;
        private long matchingAssignments;

        private SearchAccumulator(int maxRetainedSolutions) {
            this.maxRetainedSolutions = maxRetainedSolutions;
        }
    }
}
