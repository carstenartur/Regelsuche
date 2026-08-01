package de.regelsuche.evolution;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.CaseCommitment;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Private, content-addressed held-out reveal material.
 *
 * <p>The public surface exposes only hashes and split references. Concrete
 * expressions become available only through {@link #open} after the caller
 * supplies content-addressed prerequisite evidence and the exact public
 * commitment. Real reveal JSON belongs outside the repository and must never
 * be placed on a TRAIN-visible classpath.</p>
 */
public final class EvolutionRewriteProgramHeldOutRevealBundle {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-held-out-reveal-bundle/v1";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{2,127}");
    private static final Pattern IDENTIFIER =
        Pattern.compile("\\b[A-Za-z][A-Za-z0-9_]*\\b");
    private static final Set<String> RESERVED_IDENTIFIERS = Set.of(
        "sin", "cos", "tan", "sqrt", "abs", "log", "ln", "exp", "matrix");

    private final String studyId;
    private final Split split;
    private final List<RevealCase> cases;
    private final String contentHash;

    private EvolutionRewriteProgramHeldOutRevealBundle(
        String studyId,
        Split split,
        List<RevealCase> cases,
        String contentHash
    ) {
        requireId(studyId, "studyId");
        this.studyId = studyId;
        this.split = Objects.requireNonNull(split, "split");
        this.cases = canonicalCases(cases);
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            studyId,
            split,
            this.cases,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "held-out reveal-bundle contentHash mismatch");
        }
        this.contentHash = contentHash;
    }

    public static EvolutionRewriteProgramHeldOutRevealBundle create(
        String studyId,
        Split split,
        List<RevealCase> cases
    ) {
        requireId(studyId, "studyId");
        Objects.requireNonNull(split, "split");
        List<RevealCase> canonical = canonicalCases(cases);
        String hash = EvolutionGenome.hash(render(
            studyId,
            split,
            canonical,
            null));
        return new EvolutionRewriteProgramHeldOutRevealBundle(
            studyId,
            split,
            canonical,
            hash);
    }

    public String studyId() {
        return studyId;
    }

    public Split split() {
        return split;
    }

    /** Safe public identity of the private reveal payload. */
    public String contentHash() {
        return contentHash;
    }

    /** Builds the public commitment without exposing any concrete expression. */
    public EvolutionRewriteProgramHeldOutCommitment commitment() {
        List<CaseCommitment> commitments = cases.stream()
            .map(RevealCase::commitment)
            .toList();
        return EvolutionRewriteProgramHeldOutCommitment.create(
            studyId,
            split,
            commitments,
            contentHash);
    }

    /** Hash-only split references used to build the public split manifest. */
    public List<EvolutionSplitManifest.CaseReference> splitReferences() {
        return cases.stream()
            .map(RevealCase::reference)
            .toList();
    }

    /**
     * Opens the reveal only after verifying content-addressed prerequisite
     * evidence for this split and the exact public commitment.
     */
    public OpenedReveal open(
        EvolutionRewriteProgramHeldOutRevealAuthorization authorization,
        EvolutionRewriteProgramHeldOutCommitment expectedCommitment
    ) {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(expectedCommitment, "expectedCommitment");
        authorization.requireMatches(this, expectedCommitment);
        return new OpenedReveal(studyId, split, cases, contentHash);
    }

    /** Private canonical payload for an external sealed-reveal writer/loader. */
    String privateCanonicalJson() {
        return render(studyId, split, cases, contentHash);
    }

    private static List<RevealCase> canonicalCases(List<RevealCase> values) {
        Objects.requireNonNull(values, "cases");
        List<RevealCase> result = values.stream()
            .map(value -> Objects.requireNonNull(value, "reveal case"))
            .sorted(Comparator.comparing(RevealCase::caseId))
            .toList();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                "held-out reveal bundle must contain cases");
        }
        requireUnique(result, "case ID", RevealCase::caseId);
        requireUnique(result, "input", RevealCase::inputHash);
        requireUnique(result, "target", RevealCase::targetHash);
        requireUnique(result, "exact signature", RevealCase::exactSignatureHash);
        requireUnique(result, "alpha signature", RevealCase::alphaSignatureHash);
        requireUnique(result, "reveal entry", RevealCase::contentHash);
        return List.copyOf(result);
    }

    private static void requireUnique(
        List<RevealCase> cases,
        String label,
        java.util.function.Function<RevealCase, String> identity
    ) {
        List<String> values = cases.stream().map(identity).toList();
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(
                "held-out reveal bundle contains duplicate " + label);
        }
    }

    private static String render(
        String studyId,
        Split split,
        List<RevealCase> cases,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("studyId", studyId)
            .property("split", split.name())
            .array("cases", array -> cases.forEach(item ->
                array.objectValue(object -> object
                    .property("caseId", item.caseId())
                    .property("familyId", item.familyId())
                    .property("inputExpression", item.inputExpression())
                    .property("targetExpression", item.targetExpression())
                    .stringArray("assumptions", item.assumptions())
                    .property("difficultyTier", item.difficultyTier().name())
                    .property("expectedTerminalClass",
                        item.expectedTerminalClass().name())
                    .property("contentHash", item.contentHash()))));
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static void requireId(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has invalid syntax");
        }
    }

    private static String normalizeExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression must not be blank");
        }
        return ExpressionFormatter.format(
            new ExpressionParser().parseTerm(expression));
    }

    private static String assumptionsHash(List<String> assumptions) {
        return EvolutionGenome.hash(
            "regelsuche.evolution-assumptions/v1\n"
                + String.join("\n", assumptions));
    }

    private static String exactSignature(
        String input,
        String target,
        List<String> assumptions
    ) {
        return EvolutionGenome.hash(
            "regelsuche.evolution-held-out-exact-signature/v1\n"
                + input + "\n" + target + "\n"
                + String.join("\n", assumptions));
    }

    private static String alphaSignature(
        String input,
        String target,
        List<String> assumptions
    ) {
        Map<String, String> variables = new LinkedHashMap<>();
        String alphaInput = alphaNormalize(input, variables);
        String alphaTarget = alphaNormalize(target, variables);
        List<String> alphaAssumptions = assumptions.stream()
            .map(value -> alphaNormalize(value, variables))
            .sorted()
            .toList();
        return EvolutionGenome.hash(
            "regelsuche.evolution-held-out-alpha-signature/v1\n"
                + alphaInput + "\n" + alphaTarget + "\n"
                + String.join("\n", alphaAssumptions));
    }

    private static String alphaNormalize(
        String expression,
        Map<String, String> variables
    ) {
        Matcher matcher = IDENTIFIER.matcher(expression);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group();
            String replacement = RESERVED_IDENTIFIERS.contains(
                token.toLowerCase(java.util.Locale.ROOT))
                ? token
                : variables.computeIfAbsent(
                    token,
                    ignored -> "v" + variables.size());
            matcher.appendReplacement(
                output,
                Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    public enum DifficultyTier {
        CONTROL,
        STANDARD,
        HARD,
        BOUNDARY
    }

    public enum ExpectedTerminalClass {
        CONFIRMED,
        MISSING_ASSUMPTION,
        REFUTED,
        UNSUPPORTED
    }

    public record RevealCase(
        String caseId,
        String familyId,
        String inputExpression,
        String targetExpression,
        List<String> assumptions,
        DifficultyTier difficultyTier,
        ExpectedTerminalClass expectedTerminalClass,
        String contentHash
    ) {
        public RevealCase {
            requireId(caseId, "caseId");
            requireId(familyId, "familyId");
            inputExpression = normalizeExpression(inputExpression);
            targetExpression = normalizeExpression(targetExpression);
            assumptions = AssumptionSignature.ofExpressions(assumptions)
                .normalizedAssumptions();
            Objects.requireNonNull(difficultyTier, "difficultyTier");
            Objects.requireNonNull(
                expectedTerminalClass, "expectedTerminalClass");
            EvolutionGenome.requireSha256(contentHash, "contentHash");
            String expected = EvolutionGenome.hash(renderCase(
                caseId,
                familyId,
                inputExpression,
                targetExpression,
                assumptions,
                difficultyTier,
                expectedTerminalClass,
                null));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "held-out reveal-case contentHash mismatch");
            }
        }

        public static RevealCase create(
            String caseId,
            String familyId,
            String inputExpression,
            String targetExpression,
            List<String> assumptions,
            DifficultyTier difficultyTier,
            ExpectedTerminalClass expectedTerminalClass
        ) {
            requireId(caseId, "caseId");
            requireId(familyId, "familyId");
            String input = normalizeExpression(inputExpression);
            String target = normalizeExpression(targetExpression);
            List<String> normalizedAssumptions =
                AssumptionSignature.ofExpressions(assumptions)
                    .normalizedAssumptions();
            Objects.requireNonNull(difficultyTier, "difficultyTier");
            Objects.requireNonNull(
                expectedTerminalClass, "expectedTerminalClass");
            String hash = EvolutionGenome.hash(renderCase(
                caseId,
                familyId,
                input,
                target,
                normalizedAssumptions,
                difficultyTier,
                expectedTerminalClass,
                null));
            return new RevealCase(
                caseId,
                familyId,
                input,
                target,
                normalizedAssumptions,
                difficultyTier,
                expectedTerminalClass,
                hash);
        }

        String inputHash() {
            return EvolutionGenome.hash(inputExpression);
        }

        String targetHash() {
            return EvolutionGenome.hash(targetExpression);
        }

        String assumptionsHash() {
            return EvolutionRewriteProgramHeldOutRevealBundle
                .assumptionsHash(assumptions);
        }

        String exactSignatureHash() {
            return exactSignature(
                inputExpression, targetExpression, assumptions);
        }

        String alphaSignatureHash() {
            return alphaSignature(
                inputExpression, targetExpression, assumptions);
        }

        private CaseCommitment commitment() {
            return new CaseCommitment(
                caseId,
                EvolutionRewriteProgramHeldOutCommitment
                    .familyCommitment(familyId),
                inputHash(),
                targetHash(),
                assumptionsHash(),
                exactSignatureHash(),
                alphaSignatureHash(),
                EvolutionGenome.hash(
                    "difficulty-tier:" + difficultyTier.name()),
                EvolutionGenome.hash(
                    "expected-terminal-class:"
                        + expectedTerminalClass.name()),
                contentHash);
        }

        private EvolutionSplitManifest.CaseReference reference() {
            return new EvolutionSplitManifest.CaseReference(
                caseId,
                familyId,
                exactSignatureHash(),
                alphaSignatureHash(),
                inputHash(),
                targetHash());
        }

        private static String renderCase(
            String caseId,
            String familyId,
            String inputExpression,
            String targetExpression,
            List<String> assumptions,
            DifficultyTier difficultyTier,
            ExpectedTerminalClass expectedTerminalClass,
            String contentHash
        ) {
            JsonWriter json = new JsonWriter().beginObject()
                .property("caseId", caseId)
                .property("familyId", familyId)
                .property("inputExpression", inputExpression)
                .property("targetExpression", targetExpression)
                .stringArray("assumptions", assumptions)
                .property("difficultyTier", difficultyTier.name())
                .property("expectedTerminalClass",
                    expectedTerminalClass.name());
            if (contentHash != null) {
                json.property("contentHash", contentHash);
            }
            return json.endObject().toString();
        }
    }

    /**
     * Concrete held-out material returned only by the verified open boundary.
     * The constructor is intentionally private so callers cannot forge an
     * opened reveal without passing {@link #open}.
     */
    public static final class OpenedReveal {
        private final String studyId;
        private final Split split;
        private final List<RevealCase> cases;
        private final String contentHash;

        private OpenedReveal(
            String studyId,
            Split split,
            List<RevealCase> cases,
            String contentHash
        ) {
            requireId(studyId, "studyId");
            this.studyId = studyId;
            this.split = Objects.requireNonNull(split, "split");
            this.cases = List.copyOf(cases);
            EvolutionGenome.requireSha256(contentHash, "contentHash");
            this.contentHash = contentHash;
        }

        public String studyId() {
            return studyId;
        }

        public Split split() {
            return split;
        }

        public List<RevealCase> cases() {
            return cases;
        }

        public String contentHash() {
            return contentHash;
        }
    }
}
