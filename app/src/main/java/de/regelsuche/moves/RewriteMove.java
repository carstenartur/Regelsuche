package de.regelsuche.moves;

import java.util.List;

/**
 * An explicit, countable rewrite move in the discovery search tree.
 *
 * <p>Besides the legacy {@code ruleId}/{@code operatorId}/{@code assumptions}
 * triple, a move carries an explicit {@link RewriteMoveKind}, the source and
 * target expressions (raw and canonical), a reproducible {@link MoveOrdinal},
 * its parameters and macro-expansion metadata.</p>
 *
 * <p>Macro moves ({@code atomic == false}) are never black boxes: they keep the
 * list of {@code expandedMoves} so they can always be expanded back into atomic
 * steps.</p>
 */
public record RewriteMove(
        String moveId,
        RewriteMoveKind kind,
        String ruleId,
        String operatorId,
        String sourceExpression,
        String targetExpression,
        String canonicalBefore,
        String canonicalAfter,
        MoveOrdinal ordinal,
        List<MoveParameter> parameters,
        List<String> assumptions,
        List<String> tags,
        boolean atomic,
        String macroId,
        int macroVersion,
        List<RewriteMove> expandedMoves,
        String learnedFromPathId,
        String validationStatus) {

    public RewriteMove {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        moveId = moveId == null ? "" : moveId;
        ruleId = ruleId == null ? "" : ruleId;
        operatorId = operatorId == null ? "" : operatorId;
        sourceExpression = sourceExpression == null ? "" : sourceExpression;
        targetExpression = targetExpression == null ? "" : targetExpression;
        canonicalBefore = canonicalBefore == null ? "" : canonicalBefore;
        canonicalAfter = canonicalAfter == null ? "" : canonicalAfter;
        ordinal = ordinal == null ? MoveOrdinal.of(kind, 0, List.of()) : ordinal;
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        tags = tags == null ? List.of() : List.copyOf(tags);
        expandedMoves = expandedMoves == null ? List.of() : List.copyOf(expandedMoves);
        macroId = macroId == null ? "" : macroId;
        learnedFromPathId = learnedFromPathId == null ? "" : learnedFromPathId;
        validationStatus = validationStatus == null ? "" : validationStatus;
    }

    /** @return whether this move carries no resolved parameters. */
    public boolean hasUnresolvedParameters() {
        return tags.contains("parameters-unresolved");
    }

    /** @return whether this move is a (non-atomic) macro move. */
    public boolean isMacro() {
        return kind.isMacro() || !atomic;
    }

    public static Builder builder(RewriteMoveKind kind) {
        return new Builder(kind);
    }

    /** Mutable builder producing immutable {@link RewriteMove}s. */
    public static final class Builder {
        private String moveId = "";
        private final RewriteMoveKind kind;
        private String ruleId = "";
        private String operatorId = "";
        private String sourceExpression = "";
        private String targetExpression = "";
        private String canonicalBefore = "";
        private String canonicalAfter = "";
        private MoveOrdinal ordinal;
        private List<MoveParameter> parameters = List.of();
        private List<String> assumptions = List.of();
        private List<String> tags = List.of();
        private boolean atomic = true;
        private String macroId = "";
        private int macroVersion;
        private List<RewriteMove> expandedMoves = List.of();
        private String learnedFromPathId = "";
        private String validationStatus = "";

        private Builder(RewriteMoveKind kind) {
            if (kind == null) {
                throw new IllegalArgumentException("kind must not be null");
            }
            this.kind = kind;
        }

        public Builder moveId(String value) {
            this.moveId = value;
            return this;
        }

        public Builder ruleId(String value) {
            this.ruleId = value;
            return this;
        }

        public Builder operatorId(String value) {
            this.operatorId = value;
            return this;
        }

        public Builder sourceExpression(String value) {
            this.sourceExpression = value;
            return this;
        }

        public Builder targetExpression(String value) {
            this.targetExpression = value;
            return this;
        }

        public Builder canonicalBefore(String value) {
            this.canonicalBefore = value;
            return this;
        }

        public Builder canonicalAfter(String value) {
            this.canonicalAfter = value;
            return this;
        }

        public Builder ordinal(MoveOrdinal value) {
            this.ordinal = value;
            return this;
        }

        public Builder parameters(List<MoveParameter> value) {
            this.parameters = value == null ? List.of() : List.copyOf(value);
            return this;
        }

        public Builder assumptions(List<String> value) {
            this.assumptions = value == null ? List.of() : List.copyOf(value);
            return this;
        }

        public Builder tags(List<String> value) {
            this.tags = value == null ? List.of() : List.copyOf(value);
            return this;
        }

        public Builder atomic(boolean value) {
            this.atomic = value;
            return this;
        }

        public Builder macroId(String value) {
            this.macroId = value;
            return this;
        }

        public Builder macroVersion(int value) {
            this.macroVersion = value;
            return this;
        }

        public Builder expandedMoves(List<RewriteMove> value) {
            this.expandedMoves = value == null ? List.of() : List.copyOf(value);
            return this;
        }

        public Builder learnedFromPathId(String value) {
            this.learnedFromPathId = value;
            return this;
        }

        public Builder validationStatus(String value) {
            this.validationStatus = value;
            return this;
        }

        public RewriteMove build() {
            MoveOrdinal resolved = ordinal == null ? MoveOrdinal.of(kind, 0, parameters) : ordinal;
            return new RewriteMove(
                    moveId,
                    kind,
                    ruleId,
                    operatorId,
                    sourceExpression,
                    targetExpression,
                    canonicalBefore,
                    canonicalAfter,
                    resolved,
                    parameters,
                    assumptions,
                    tags,
                    atomic,
                    macroId,
                    macroVersion,
                    expandedMoves,
                    learnedFromPathId,
                    validationStatus);
        }
    }
}
