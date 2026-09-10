package de.regelsuche.search.moves;

import java.util.List;

/** A wrapper exposes mathematical candidate sources rather than hiding a second scheduling policy. */
public interface MoveProviderInventory {
    List<MoveProvider> moveProviders();
}
