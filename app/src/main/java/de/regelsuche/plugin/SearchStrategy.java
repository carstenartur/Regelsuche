package de.regelsuche.plugin;

import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import java.util.List;

/**
 * Plugin extension that contributes a named search strategy.
 *
 * <p>Implementations must override {@link #search(SearchProblem)} to steer
 * runtime search behaviour.  The default implementation returns an empty list,
 * which effectively disables candidate exploration for this strategy.
 */
public interface SearchStrategy extends PluginExtension, de.regelsuche.search.strategy.SearchStrategy {
    default String description() {
        return "";
    }

    @Override
    default List<SearchState> search(SearchProblem problem) {
        return List.of();
    }
}
