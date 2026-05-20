package de.regelsuche.api.searchgraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory {@link SearchGraphRepository}: stores records in a
 * {@link LinkedHashMap} keyed by record id. The default backend used in unit
 * tests and one-shot CLI runs.
 */
public final class InMemorySearchGraphRepository implements SearchGraphRepository {

    private final Map<String, SearchGraphRecord> records = new LinkedHashMap<>();

    @Override
    public synchronized void save(SearchGraphRecord record) {
        Objects.requireNonNull(record, "record");
        records.put(record.id(), record);
    }

    @Override
    public synchronized Optional<SearchGraphRecord> findById(String id) {
        return Optional.ofNullable(records.get(id));
    }

    @Override
    public synchronized List<SearchGraphRecord> findAll() {
        return new ArrayList<>(records.values());
    }

    @Override
    public synchronized void delete(String id) {
        records.remove(id);
    }
}
