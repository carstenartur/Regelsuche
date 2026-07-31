package de.regelsuche.search.program;

import de.regelsuche.transform.TransformationEngine;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Canonical, typed intermediate representation for the Java-internal rewrite
 * strategy DSL.
 *
 * <p>The records deliberately describe execution instead of executing it.
 * That makes plans inspectable, traceable and suitable as the semantic target
 * of a later textual DSL.</p>
 */
public sealed interface RewriteProgram permits
        RewriteProgram.Source,
        RewriteProgram.Choice,
        RewriteProgram.FirstApplicable,
        RewriteProgram.Sequence,
        RewriteProgram.Repeat,
        RewriteProgram.Require,
        RewriteProgram.Prioritize,
        RewriteProgram.Prune {

    NodeMetadata metadata();

    default String id() {
        return metadata().id();
    }

    record Source(NodeMetadata metadata, TransformationEngine engine)
            implements RewriteProgram {
        public Source {
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(engine, "engine");
        }
    }

    record Choice(NodeMetadata metadata, List<RewriteProgram> alternatives)
            implements RewriteProgram {
        public Choice {
            Objects.requireNonNull(metadata, "metadata");
            alternatives = nonEmptyCopy(alternatives, "alternatives");
        }
    }

    /** Selects the first child that produces at least one candidate. */
    record FirstApplicable(NodeMetadata metadata, List<RewriteProgram> alternatives)
            implements RewriteProgram {
        public FirstApplicable {
            Objects.requireNonNull(metadata, "metadata");
            alternatives = nonEmptyCopy(alternatives, "alternatives");
        }
    }

    /** Applies every step to every candidate produced by its predecessor. */
    record Sequence(NodeMetadata metadata, List<RewriteProgram> steps)
            implements RewriteProgram {
        public Sequence {
            Objects.requireNonNull(metadata, "metadata");
            steps = nonEmptyCopy(steps, "steps");
        }
    }

    /** Bounded repetition; all endpoints between min and max are retained. */
    record Repeat(
        NodeMetadata metadata,
        RewriteProgram body,
        int minIterations,
        int maxIterations
    ) implements RewriteProgram {
        public Repeat {
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(body, "body");
            if (minIterations < 1 || maxIterations < minIterations) {
                throw new IllegalArgumentException(
                    "repeat requires 1 <= minIterations <= maxIterations");
            }
        }
    }

    /** Hard semantic filter. Rejected candidates are not executed by search. */
    record Require(
        NodeMetadata metadata,
        RewriteProgram body,
        String conditionDescription,
        Predicate<RewriteCandidate> condition
    ) implements RewriteProgram {
        public Require {
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(body, "body");
            conditionDescription = requireText(conditionDescription, "conditionDescription");
            Objects.requireNonNull(condition, "condition");
        }
    }

    /** Soft ordering only; it must not add or remove candidates. */
    record Prioritize(
        NodeMetadata metadata,
        RewriteProgram body,
        String orderingDescription,
        Comparator<RewriteCandidate> comparator
    ) implements RewriteProgram {
        public Prioritize {
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(body, "body");
            orderingDescription = requireText(orderingDescription, "orderingDescription");
            Objects.requireNonNull(comparator, "comparator");
        }
    }

    /** Explicitly incomplete candidate truncation. */
    record Prune(
        NodeMetadata metadata,
        RewriteProgram body,
        int maxCandidates,
        String reason
    ) implements RewriteProgram {
        public Prune {
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(body, "body");
            if (maxCandidates < 1) {
                throw new IllegalArgumentException("maxCandidates must be positive");
            }
            reason = requireText(reason, "reason");
        }
    }

    record NodeMetadata(String id, String label, SourceLocation sourceLocation) {
        public NodeMetadata {
            id = requireText(id, "id");
            label = label == null || label.isBlank() ? id : label;
            sourceLocation = sourceLocation == null
                ? SourceLocation.unknown()
                : sourceLocation;
        }

        public static NodeMetadata named(String id) {
            return new NodeMetadata(id, id, SourceLocation.unknown());
        }
    }

    record SourceLocation(String sourceName, int line, int column) {
        public SourceLocation {
            sourceName = sourceName == null ? "" : sourceName;
            if (line < 0 || column < 0) {
                throw new IllegalArgumentException("line and column must not be negative");
            }
            if (sourceName.isBlank() && (line != 0 || column != 0)) {
                throw new IllegalArgumentException(
                    "unknown source locations must use line=0 and column=0");
            }
        }

        public static SourceLocation unknown() {
            return new SourceLocation("", 0, 0);
        }

        public static SourceLocation at(String sourceName, int line, int column) {
            return new SourceLocation(requireText(sourceName, "sourceName"), line, column);
        }

        public boolean known() {
            return !sourceName.isBlank();
        }
    }

    private static List<RewriteProgram> nonEmptyCopy(
        List<RewriteProgram> values,
        String name
    ) {
        Objects.requireNonNull(values, name);
        List<RewriteProgram> copy = List.copyOf(values);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return copy;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
