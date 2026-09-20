package de.regelsuche.search.moves;

import de.regelsuche.transform.TransformationCursor;
import java.util.Objects;

/** Search-owned allowlisted wrapper; candidate descriptions confer no proof authority. */
public final class RegisteredIncrementalMoveProvider implements IncrementalMoveProvider {
    private final Descriptor descriptor;
    private final IncrementalProviderContract.Definition definition;
    private final IncrementalProviderContract.Factory factory;
    public RegisteredIncrementalMoveProvider(Descriptor descriptor, IncrementalProviderContract.Definition definition,
            IncrementalProviderContract.Registry registry) {
        this.descriptor = Objects.requireNonNull(descriptor);
        this.definition = Objects.requireNonNull(definition);
        if (!descriptor.id().equals(definition.providerId()) || definition.kind() != IncrementalProviderContract.Kind.REGISTERED_SCHEMA)
            throw new IllegalArgumentException("registration differs from provider descriptor");
        factory = Objects.requireNonNull(registry).require(definition);
    }
    @Override public Descriptor descriptor() { return descriptor; }
    /** Registered schemas have no invented native rule inventory. */
    @Override public TransformationCursor.Definition definition() {
        throw new UnsupportedOperationException("registered schemas use contractDefinition");
    }
    @Override public TransformationCursor openCursor(MoveState state, MoveContext context) {
        throw new UnsupportedOperationException("registered schemas require staged v2 sessions");
    }
    @Override public IncrementalProviderContract.Definition contractDefinition() { return definition; }
    @Override public IncrementalProviderContract.Cursor openSession(MoveState state, MoveContext context) {
        return new ManagedIncrementalCursor(descriptor, definition, factory, state, context);
    }
}
