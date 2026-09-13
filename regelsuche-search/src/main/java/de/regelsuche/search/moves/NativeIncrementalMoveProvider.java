package de.regelsuche.search.moves;

import de.regelsuche.transform.PreparedAstRewriteTransformationEngine;
import de.regelsuche.transform.TransformationCursor;
import java.util.Objects;

/** One immutable native rule inventory, traversed by occurrence before advancing to the next provider. */
public final class NativeIncrementalMoveProvider implements IncrementalMoveProvider {
    private final Descriptor descriptor;
    private final PreparedAstRewriteTransformationEngine engine;
    private final TransformationCursor.Definition definition;

    public NativeIncrementalMoveProvider(Descriptor descriptor, PreparedAstRewriteTransformationEngine engine) {
        this(descriptor, engine, TransformationCursor.DEFAULT_MATCHER_BRANCH_LIMIT);
    }
    public NativeIncrementalMoveProvider(Descriptor descriptor, PreparedAstRewriteTransformationEngine engine, int matcherBranchLimit) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.engine = Objects.requireNonNull(engine, "engine");
        if (descriptor.sourceKind() != SearchMove.SourceKind.PRIMITIVE)
            throw new IllegalArgumentException("native cursor provider must be primitive");
        definition = engine.cursorDefinition(matcherBranchLimit);
    }
    @Override public Descriptor descriptor() { return descriptor; }
    @Override public TransformationCursor.Definition definition() { return definition; }
    @Override public TransformationCursor openCursor(MoveState state, MoveContext context) {
        if (!context.carries(descriptor.requiredAssumptions(), state))
            throw new AssumptionsNotCarried();
        return engine.openCursor(state.expression(), definition.matcherBranchLimit());
    }
}
