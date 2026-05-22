package de.regelsuche.api.searchgraph;

import de.regelsuche.json.JsonReader;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JSON-file backed {@link SearchGraphRepository}.
 *
 * <p>All records live in a single file as
 * {@code { "records": [ <SearchGraphRecord>* ] }}. Each {@link #save} call
 * rewrites the file atomically (write-temp + atomic-move). Suitable for
 * single-process workbench scenarios; for concurrent or distributed access
 * use the Neo4j-backed implementation.</p>
 */
public final class JsonFileSearchGraphRepository implements SearchGraphRepository {

    private final Path file;
    private final Map<String, SearchGraphRecord> records = new LinkedHashMap<>();

    public JsonFileSearchGraphRepository(Path file) {
        this.file = file;
        load();
    }

    @Override
    public synchronized void save(SearchGraphRecord record) {
        records.put(record.id(), record);
        flush();
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
        if (records.remove(id) != null) {
            flush();
        }
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return;
            }
            Map<String, Object> root = new JsonReader(content).readObject();
            Object array = root.get("records");
            if (!(array instanceof List<?> list)) {
                return;
            }
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    SearchGraphRecord record = SearchGraphRecordCodec.fromMap((Map<String, Object>) map);
                    records.put(record.id(), record);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load search-graph repository: " + file, ex);
        }
    }

    private void flush() {
        StringBuilder body = new StringBuilder("{\"records\":[");
        boolean first = true;
        for (SearchGraphRecord record : records.values()) {
            if (!first) {
                body.append(',');
            }
            first = false;
            body.append(SearchGraphRecordCodec.toJson(record));
        }
        body.append("]}");
        try {
            AtomicJsonFile.writeUtf8(file, body.toString());
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to persist search-graph repository: " + file, ex);
        }
    }
}
