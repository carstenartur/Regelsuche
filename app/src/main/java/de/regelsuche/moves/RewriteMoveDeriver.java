package de.regelsuche.moves;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/**
 * Best-effort derivation of a {@link RewriteMove} from existing, legacy search
 * data (before/after expression, rule id, operator id, assumptions, tags).
 *
 * <p>The deriver never fails: when the move kind or the parameters cannot be
 * reconstructed safely it still returns a structurally complete move and tags
 * it with {@code "parameters-unresolved"}.</p>
 */
public final class RewriteMoveDeriver {

    private static final String UNRESOLVED_TAG = "parameters-unresolved";

    private final ExpressionCanonicalizer canonicalizer;

    public RewriteMoveDeriver() {
        this(new ExpressionCanonicalizer());
    }

    public RewriteMoveDeriver(ExpressionCanonicalizer canonicalizer) {
        this.canonicalizer = canonicalizer == null ? new ExpressionCanonicalizer() : canonicalizer;
    }

    /** Derives a best-effort move from the supplied request. */
    public RewriteMove derive(MoveDerivationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        RewriteMoveKind kind = detectKind(request);
        List<MoveParameter> parameters = substitutionParameters(request.assumptions());
        boolean hasSubstitutionEvidence = hasSubstitutionEvidence(request.assumptions());

        LinkedHashSet<String> tags = new LinkedHashSet<>(request.tags());
        if (kind == RewriteMoveKind.UNKNOWN || (hasSubstitutionEvidence && parameters.isEmpty())) {
            tags.add(UNRESOLVED_TAG);
        }

        String canonicalBefore = canonical(request.beforeExpression());
        String canonicalAfter = canonical(request.afterExpression());
        int occurrence = occurrenceOrdinal(canonicalBefore, canonicalAfter);
        MoveOrdinal ordinal = MoveOrdinal.of(kind, occurrence, parameters);

        boolean macro = kind.isMacro();
        String macroId = macro ? firstNonBlank(request.macroId(), request.ruleId()) : "";

        return RewriteMove.builder(kind)
                .moveId(moveId(kind, request, canonicalBefore, canonicalAfter))
                .ruleId(request.ruleId())
                .operatorId(request.operatorId())
                .sourceExpression(request.beforeExpression())
                .targetExpression(request.afterExpression())
                .canonicalBefore(canonicalBefore)
                .canonicalAfter(canonicalAfter)
                .ordinal(ordinal)
                .parameters(parameters)
                .assumptions(request.assumptions())
                .tags(List.copyOf(tags))
                .atomic(!macro)
                .macroId(macroId)
                .macroVersion(macro ? Math.max(1, request.macroVersion()) : 0)
                .learnedFromPathId(kind == RewriteMoveKind.LEARNED_MACRO ? request.learnedFromPathId() : "")
                .validationStatus(request.validationStatus())
                .build();
    }

    RewriteMoveKind detectKind(MoveDerivationRequest request) {
        String ruleId = request.ruleId().toLowerCase(Locale.ROOT);
        String operatorId = request.operatorId().toLowerCase(Locale.ROOT);
        String source = request.source().toLowerCase(Locale.ROOT);
        String combined = ruleId + " " + operatorId;

        if (!request.macroId().isBlank()
                || combined.contains("learned_macro")
                || source.contains("macro") && (combined.contains("learned") || source.contains("learned"))) {
            return RewriteMoveKind.LEARNED_MACRO;
        }
        if (combined.contains("curated") || combined.contains("static_macro")
                || (source.contains("curated"))) {
            return RewriteMoveKind.CURATED_MACRO;
        }
        if (contains(combined, "substitution_introduction") || contains(combined, "substitute_introduce")) {
            return RewriteMoveKind.SUBSTITUTE_INTRODUCE;
        }
        if (contains(combined, "substitution_expansion") || contains(combined, "substitute_expand")) {
            return RewriteMoveKind.SUBSTITUTE_EXPAND;
        }
        if (contains(combined, "complete_square")) {
            return RewriteMoveKind.COMPLETE_SQUARE;
        }
        if (contains(combined, "common_subexpression")) {
            return RewriteMoveKind.COMMON_SUBEXPRESSION;
        }
        if (contains(combined, "sophie_germain")) {
            return RewriteMoveKind.SOPHIE_GERMAIN;
        }
        if (contains(combined, "difference_of_squares")) {
            return RewriteMoveKind.DIFFERENCE_OF_SQUARES;
        }
        if (contains(combined, "ast_linear_offset_simplify") || contains(combined, "normalize")) {
            return RewriteMoveKind.NORMALIZE;
        }
        if (contains(combined, "expand")) {
            return RewriteMoveKind.EXPAND;
        }
        if (contains(combined, "factor")) {
            return RewriteMoveKind.FACTOR;
        }
        if (source.contains("macro") || contains(combined, "macro")) {
            return RewriteMoveKind.LEARNED_MACRO;
        }
        if (source.contains("import")) {
            return RewriteMoveKind.IMPORTED_RULE;
        }
        return RewriteMoveKind.UNKNOWN;
    }

    /**
     * Parses substitution evidence assumptions of the forms
     * {@code substitution.placeholder.X=...}, {@code substitution.occurrences.X=...}
     * and {@code substitution.substituted=...} into concrete {@link MoveParameter}s.
     */
    List<MoveParameter> substitutionParameters(List<String> assumptions) {
        TreeMap<String, String> placeholders = new TreeMap<>();
        TreeMap<String, String> occurrences = new TreeMap<>();
        String substituted = null;
        for (String assumption : assumptions) {
            if (assumption == null) {
                continue;
            }
            int separator = assumption.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String key = assumption.substring(0, separator).trim();
            String value = assumption.substring(separator + 1).trim();
            if (key.startsWith("substitution.placeholder.")) {
                placeholders.put(key.substring("substitution.placeholder.".length()), value);
            } else if (key.startsWith("substitution.occurrences.")) {
                occurrences.put(key.substring("substitution.occurrences.".length()), value);
            } else if (key.equals("substitution.substituted")) {
                substituted = value;
            }
        }
        if (placeholders.isEmpty() && occurrences.isEmpty() && substituted == null) {
            return List.of();
        }
        List<MoveParameter> parameters = new ArrayList<>();
        int index = 0;
        for (var entry : placeholders.entrySet()) {
            parameters.add(new MoveParameter(
                    entry.getKey(),
                    MoveParameterKind.PLACEHOLDER,
                    entry.getValue(),
                    canonical(entry.getValue()),
                    index++,
                    "substitution-evidence"));
        }
        for (var entry : occurrences.entrySet()) {
            parameters.add(new MoveParameter(
                    entry.getKey(),
                    MoveParameterKind.OCCURRENCE,
                    entry.getValue(),
                    entry.getValue(),
                    index++,
                    "substitution-evidence"));
        }
        if (substituted != null) {
            parameters.add(new MoveParameter(
                    "substituted",
                    MoveParameterKind.REPLACEMENT,
                    substituted,
                    canonical(substituted),
                    index,
                    "substitution-evidence"));
        }
        return List.copyOf(parameters);
    }

    private int occurrenceOrdinal(String canonicalBefore, String canonicalAfter) {
        // Deterministic, content-derived occurrence position. Stable for a given
        // (before, after) pair so repeated runs yield identical ordinals.
        int hash = (canonicalBefore + "=>" + canonicalAfter).hashCode();
        return Math.floorMod(hash, 1_000_000);
    }

    private String moveId(RewriteMoveKind kind, MoveDerivationRequest request, String before, String after) {
        String rule = request.ruleId().isBlank() ? request.operatorId() : request.ruleId();
        return kind.name() + "|" + rule + "|" + before + "=>" + after;
    }

    private String canonical(String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        try {
            return canonicalizer.canonicalize(expression);
        } catch (RuntimeException exception) {
            return expression.trim().replaceAll("\\s+", " ");
        }
    }

    private static boolean contains(String haystack, String needle) {
        return haystack.contains(needle);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private boolean hasSubstitutionEvidence(List<String> assumptions) {
        for (String assumption : assumptions) {
            if (assumption != null && assumption.trim().startsWith("substitution.")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Input to {@link RewriteMoveDeriver#derive(MoveDerivationRequest)}.
     *
     * @param beforeExpression  source expression
     * @param afterExpression   target expression
     * @param ruleId            applied rule id
     * @param operatorId        applied operator id
     * @param assumptions       assumptions / evidence carried by the step
     * @param source            provenance of the step (e.g. {@code "macro"})
     * @param tags              pre-existing tags
     * @param macroId           macro id when known
     * @param macroVersion      macro version when known
     * @param learnedFromPathId originating path id for learned macros
     * @param validationStatus  validation status of the move
     */
    public record MoveDerivationRequest(
            String beforeExpression,
            String afterExpression,
            String ruleId,
            String operatorId,
            List<String> assumptions,
            String source,
            List<String> tags,
            String macroId,
            int macroVersion,
            String learnedFromPathId,
            String validationStatus) {

        public MoveDerivationRequest {
            beforeExpression = beforeExpression == null ? "" : beforeExpression;
            afterExpression = afterExpression == null ? "" : afterExpression;
            ruleId = ruleId == null ? "" : ruleId;
            operatorId = operatorId == null ? "" : operatorId;
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            source = source == null ? "" : source;
            tags = tags == null ? List.of() : List.copyOf(tags);
            macroId = macroId == null ? "" : macroId;
            learnedFromPathId = learnedFromPathId == null ? "" : learnedFromPathId;
            validationStatus = validationStatus == null ? "" : validationStatus;
        }

        public MoveDerivationRequest(
                String beforeExpression,
                String afterExpression,
                String ruleId,
                String operatorId,
                List<String> assumptions) {
            this(beforeExpression, afterExpression, ruleId, operatorId, assumptions, "", List.of(), "", 0, "", "");
        }

        public MoveDerivationRequest(
                String beforeExpression,
                String afterExpression,
                String ruleId,
                String operatorId,
                List<String> assumptions,
                String source,
                List<String> tags) {
            this(beforeExpression, afterExpression, ruleId, operatorId, assumptions, source, tags, "", 0, "", "");
        }

        Set<String> tagSet() {
            return new LinkedHashSet<>(tags);
        }
    }
}
