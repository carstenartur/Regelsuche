package de.regelsuche.search.strategy;

import java.util.List;

public interface SearchStrategy {
    List<SearchState> search(SearchProblem problem);
}
