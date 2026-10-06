package de.regelsuche.sdk.discovery.signal;

import de.regelsuche.discovery.signal.FourierQuery;
import de.regelsuche.discovery.signal.PeriodicSignal;
import de.regelsuche.discovery.signal.PeriodicSignal.Term;
import de.regelsuche.scalar.ExactRational;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Explicitly separated training and holdout queries for a bounded plan experiment. */
public record PeriodicSignalStudy(List<FourierQuery> training, List<FourierQuery> holdout) {
    public static final int MAX_QUERIES_PER_SPLIT = 8;
    private static final int MAX_PAYLOAD_CHARS = 32_768;
    private static final String SCHEMA = "periodic-signal-study/v1";

    public PeriodicSignalStudy {
        training = checked(training);
        holdout = checked(holdout);
        var seen = new HashSet<>(training);
        for (var query : holdout) {
            if (!seen.add(query)) throw new IllegalArgumentException("training and holdout must be disjoint");
        }
    }

    private static List<FourierQuery> checked(List<FourierQuery> queries) {
        Objects.requireNonNull(queries, "queries");
        if (queries.isEmpty() || queries.size() > MAX_QUERIES_PER_SPLIT) {
            throw new IllegalArgumentException("each split must contain between 1 and 8 queries");
        }
        var copy = List.copyOf(queries);
        if (new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException("duplicate queries within a split");
        }
        return copy;
    }

    public String canonical() {
        return SCHEMA + "\ntrain=" + training.size() + "\n" + encodeQueries(training)
            + "\nholdout=" + holdout.size() + "\n" + encodeQueries(holdout);
    }

    private static String encodeQueries(List<FourierQuery> queries) {
        return queries.stream().map(FourierQuery::canonical).collect(Collectors.joining("\n"));
    }

    public static PeriodicSignalStudy parse(String payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length() > MAX_PAYLOAD_CHARS) throw new IllegalArgumentException("study payload too large");
        String[] lines = payload.split("\n", -1);
        if (lines.length < 5 || !SCHEMA.equals(lines[0])) {
            throw new IllegalArgumentException("invalid study schema or missing splits");
        }
        int trainingSize = splitSize(lines[1], "train=");
        int holdoutLine = 2 + trainingSize;
        if (holdoutLine >= lines.length) throw new IllegalArgumentException("missing holdout split");
        int holdoutSize = splitSize(lines[holdoutLine], "holdout=");
        if (lines.length != holdoutLine + 1 + holdoutSize) {
            throw new IllegalArgumentException("study line count does not match split sizes");
        }
        var study = new PeriodicSignalStudy(
            Arrays.stream(lines, 2, holdoutLine).map(PeriodicSignalStudy::parseQuery).toList(),
            Arrays.stream(lines, holdoutLine + 1, lines.length).map(PeriodicSignalStudy::parseQuery).toList());
        if (!study.canonical().equals(payload)) throw new IllegalArgumentException("noncanonical study payload");
        return study;
    }

    private static int splitSize(String line, String prefix) {
        if (!line.startsWith(prefix) || line.length() != prefix.length() + 1) {
            throw new IllegalArgumentException("invalid split header");
        }
        int count = Integer.parseInt(line.substring(prefix.length()));
        if (count < 1 || count > MAX_QUERIES_PER_SPLIT) throw new IllegalArgumentException("split size out of bounds");
        return count;
    }

    private static FourierQuery parseQuery(String line) {
        String[] fields = line.split("\\|", -1);
        if (fields.length != 3) throw new IllegalArgumentException("expected length|period@weight,...|frequencies");
        List<Term> terms = fields[1].isEmpty() ? List.of()
            : Arrays.stream(fields[1].split(",", -1)).map(text -> {
                String[] pair = text.split("@", -1);
                if (pair.length != 2 || pair[1].length() > 42) {
                    throw new IllegalArgumentException("invalid bounded signal term");
                }
                return new Term(Integer.parseInt(pair[0]), ExactRational.fromCanonicalText(pair[1]));
            }).toList();
        return new FourierQuery(new PeriodicSignal(Integer.parseInt(fields[0]), terms),
            Arrays.stream(fields[2].split(",", -1)).map(Integer::parseInt).toList());
    }
}
