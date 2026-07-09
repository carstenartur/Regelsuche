package de.regelsuche.search.telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lightweight observer that records one {@link TimelinePoint} per {@link SearchEventType#STATE_VISITED}
 * event for SVG timeline generation.
 *
 * <p>Only the fields needed for visualization (sequence, frontierSize, visitedCount) are captured,
 * keeping memory overhead small even for moderately large searches.</p>
 */
public final class SearchTimelineDataCollector implements SearchObserver {

    private final List<TimelinePoint> points = new ArrayList<>();

    @Override
    public synchronized void onEvent(SearchEvent event) {
        if (event.type() == SearchEventType.STATE_VISITED) {
            points.add(new TimelinePoint(event.sequence(), event.frontierSize(), event.visitedCount()));
        }
    }

    /** Returns an unmodifiable snapshot of the collected timeline points in emission order. */
    public synchronized List<TimelinePoint> points() {
        return Collections.unmodifiableList(new ArrayList<>(points));
    }

    /**
     * A single data point in the search timeline.
     *
     * @param sequence     sequence number of the triggering {@code STATE_VISITED} event.
     * @param frontierSize frontier (open list) size at that moment.
     * @param visitedCount number of canonical states visited so far.
     */
    public record TimelinePoint(long sequence, int frontierSize, int visitedCount) {
    }
}
