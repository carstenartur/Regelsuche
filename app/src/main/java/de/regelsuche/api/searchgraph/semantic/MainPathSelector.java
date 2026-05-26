package de.regelsuche.api.searchgraph.semantic;

import de.regelsuche.api.searchgraph.SearchGraphDto;
import de.regelsuche.discovery.DiscoveredTransformation;
import java.util.List;
import java.util.Optional;

public interface MainPathSelector {
    Optional<DiscoveredTransformation> selectMainPath(
        List<DiscoveredTransformation> paths,
        SearchGraphDto rawGraph,
        MainPathCriteria criteria
    );
}
