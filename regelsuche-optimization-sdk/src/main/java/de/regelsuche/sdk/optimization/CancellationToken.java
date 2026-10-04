package de.regelsuche.sdk.optimization;
@FunctionalInterface
public interface CancellationToken {
    CancellationToken NONE = () -> false;
    boolean isCancelled();
}
