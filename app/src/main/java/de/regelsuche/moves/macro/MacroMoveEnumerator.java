package de.regelsuche.moves.macro;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.inventory.RuleInventoryRepository;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.moves.MoveOrdinal;
import de.regelsuche.moves.MoveParameter;
import de.regelsuche.moves.MoveParameterKind;
import de.regelsuche.moves.RewriteMove;
import de.regelsuche.moves.RewriteMoveKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Enumerates the active macros from the existing rule-inventory structure as
 * normal — but expandable — {@link RewriteMove}s.
 *
 * <p>Each macro move carries {@code atomic == false} together with the macro id,
 * version, originating path id and validation status. It expands into atomic
 * {@link RewriteMove}s (the left/right pattern rewrite) so a macro is never a
 * black box. When the inventory does not contain enough structure to expand the
 * macro, a best-effort move is produced and tagged {@code "macro-expansion-partial"}.</p>
 */
public final class MacroMoveEnumerator {

    private static final String PARTIAL_TAG = "macro-expansion-partial";

    private final ExpressionCanonicalizer canonicalizer;

    public MacroMoveEnumerator() {
        this(new ExpressionCanonicalizer());
    }

    public MacroMoveEnumerator(ExpressionCanonicalizer canonicalizer) {
        this.canonicalizer = canonicalizer == null ? new ExpressionCanonicalizer() : canonicalizer;
    }

    /**
     * Enumerates macro moves applicable to {@code expression} from the inventory.
     *
     * @param inventory  the existing macro/inventory repository
     * @param expression the current expression
     * @return deterministic, ordinal-sorted macro moves
     */
    public List<RewriteMove> enumerate(RuleInventoryRepository inventory, String expression) {
        if (inventory == null) {
            return List.of();
        }
        return enumerate(inventory.findEnabled(), expression);
    }

    /** Enumerates macro moves from an explicit list of macros. */
    public List<RewriteMove> enumerate(List<ReusableRule> macros, String expression) {
        if (macros == null || macros.isEmpty()) {
            return List.of();
        }
        String canonicalBefore = canonical(expression);
        List<RewriteMove> moves = new ArrayList<>();
        for (ReusableRule macro : macros) {
            moves.add(toMove(macro, expression, canonicalBefore));
        }
        moves.sort(macroOrder());
        return List.copyOf(moves);
    }

    private RewriteMove toMove(ReusableRule macro, String expression, String canonicalBefore) {
        RewriteMoveKind kind = kindFor(macro);
        boolean applicable = isApplicable(macro, canonicalBefore);
        String predictedAfter = applicable ? rewrite(expression, macro) : expression;

        List<MoveParameter> parameters = parameters(macro);
        MoveOrdinal ordinal = MoveOrdinal.of(kind, occurrenceOrdinal(macro), parameters);

        List<String> tags = new ArrayList<>();
        List<RewriteMove> expanded = expand(macro, expression, predictedAfter);
        if (expanded.isEmpty()) {
            tags.add(PARTIAL_TAG);
        }
        if (!applicable) {
            tags.add("macro-applicability-uncertain");
        }

        return RewriteMove.builder(kind)
                .moveId("macro:" + macro.id())
                .ruleId(macro.id())
                .operatorId(macro.id())
                .sourceExpression(expression)
                .targetExpression(predictedAfter)
                .canonicalBefore(canonicalBefore)
                .canonicalAfter(canonical(predictedAfter))
                .ordinal(ordinal)
                .parameters(parameters)
                .assumptions(macro.assumptions())
                .tags(List.copyOf(tags))
                .atomic(false)
                .macroId(macro.id())
                .macroVersion(macroVersion(macro))
                .expandedMoves(expanded)
                .learnedFromPathId(macro.supportingPathIds().isEmpty() ? "" : macro.supportingPathIds().getFirst())
                .validationStatus(macro.proofStatus().name())
                .build();
    }

    private RewriteMoveKind kindFor(ReusableRule macro) {
        if (macro.knownRuleStatus() == RuleStatus.MATCHES_KNOWN_RULE
                || macro.id().toLowerCase(Locale.ROOT).contains("curated")
                || macro.id().toLowerCase(Locale.ROOT).contains("static")) {
            return RewriteMoveKind.CURATED_MACRO;
        }
        return RewriteMoveKind.LEARNED_MACRO;
    }

    private boolean isApplicable(ReusableRule macro, String canonicalBefore) {
        String left = canonical(macro.leftPattern());
        if (left.isBlank() || canonicalBefore.isBlank()) {
            return false;
        }
        return canonicalBefore.equals(left) || canonicalBefore.contains(stripWhitespace(macro.leftPattern()));
    }

    private String rewrite(String expression, ReusableRule macro) {
        String left = stripWhitespace(macro.leftPattern());
        String right = macro.rightPattern();
        if (left.isBlank()) {
            return expression;
        }
        String compact = stripWhitespace(expression);
        if (compact.equals(left)) {
            return right;
        }
        return compact.replace(left, stripWhitespace(right));
    }

    private List<RewriteMove> expand(ReusableRule macro, String before, String after) {
        if (macro.leftPattern().isBlank() || macro.rightPattern().isBlank()) {
            return List.of();
        }
        RewriteMove atomic = RewriteMove.builder(RewriteMoveKind.IMPORTED_RULE)
                .moveId("macro:" + macro.id() + ":atomic")
                .ruleId(macro.id() + ":pattern")
                .sourceExpression(before)
                .targetExpression(after)
                .canonicalBefore(canonical(before))
                .canonicalAfter(canonical(after))
                .ordinal(MoveOrdinal.of(RewriteMoveKind.IMPORTED_RULE, 0, List.of()))
                .parameters(parameters(macro))
                .assumptions(macro.assumptions())
                .atomic(true)
                .build();
        return List.of(atomic);
    }

    private List<MoveParameter> parameters(ReusableRule macro) {
        List<MoveParameter> parameters = new ArrayList<>();
        List<String> relations = macro.parameterRelations();
        for (int index = 0; index < relations.size(); index++) {
            String relation = relations.get(index);
            parameters.add(new MoveParameter(
                    "p" + index,
                    MoveParameterKind.MACRO_ARGUMENT,
                    relation,
                    relation,
                    index,
                    "macro-inventory"));
        }
        parameters.sort(MoveParameter.CANONICAL_ORDER);
        return List.copyOf(parameters);
    }

    private int macroVersion(ReusableRule macro) {
        return Math.max(1, macro.usageCount() + 1);
    }

    private int occurrenceOrdinal(ReusableRule macro) {
        return Math.floorMod(macro.id().hashCode(), 1_000_000);
    }

    private Comparator<RewriteMove> macroOrder() {
        return Comparator.comparing(RewriteMove::macroId)
                .thenComparingInt(RewriteMove::macroVersion)
                .thenComparing(RewriteMove::ordinal, MoveOrdinal.CANONICAL_ORDER);
    }

    private String canonical(String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        try {
            return canonicalizer.canonicalize(expression);
        } catch (RuntimeException exception) {
            return stripWhitespace(expression);
        }
    }

    private String stripWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }
}
