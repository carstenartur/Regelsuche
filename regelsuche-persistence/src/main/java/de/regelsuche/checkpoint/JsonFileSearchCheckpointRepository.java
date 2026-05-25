package de.regelsuche.checkpoint;

import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * JSON-file backed implementation of {@link SearchCheckpointRepository}.
 *
 * <p>Stores all checkpoints in a single JSON document so the file can be
 * inspected/edited and is easy to back up. The repository keeps an
 * in-memory copy for fast reads and rewrites the file on every save.</p>
 *
 * <p>Format:</p>
 * <pre>
 * {
 *   "checkpoints": [
 *     {
 *       "jobId": "...",
 *       "originalExpression": "...",
 *       "profile": "...",
 *       "heuristicName": "...",
 *       "randomSeed": 42,
 *       "createdAt": "...",
 *       "updatedAt": "...",
 *       "frontier": ["...", "..."],
 *       "visitedHashes": ["...", "..."],
 *       "bestPaths": [
 *         {"expression": "...", "improvement": 4, "lastRuleId": "..."}
 *       ]
 *     }
 *   ]
 * }
 * </pre>
 */
public class JsonFileSearchCheckpointRepository implements SearchCheckpointRepository {
    private final Path file;
    private final ConcurrentMap<String, SearchCheckpoint> cache = new ConcurrentHashMap<>();

    public JsonFileSearchCheckpointRepository(Path file) {
        this.file = file;
        loadFromDisk();
    }

    @Override
    public synchronized void save(SearchCheckpoint checkpoint) {
        cache.put(checkpoint.jobId(), checkpoint);
        flush();
    }

    @Override
    public Optional<SearchCheckpoint> findByJobId(String jobId) {
        return Optional.ofNullable(cache.get(jobId));
    }

    @Override
    public List<SearchCheckpoint> findAll() {
        return new ArrayList<>(cache.values());
    }

    @Override
    public synchronized boolean delete(String jobId) {
        boolean removed = cache.remove(jobId) != null;
        if (removed) {
            flush();
        }
        return removed;
    }

    private void loadFromDisk() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            for (SearchCheckpoint checkpoint : parse(json)) {
                cache.put(checkpoint.jobId(), checkpoint);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load checkpoints from " + file, ex);
        }
    }

    private void flush() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            AtomicJsonFile.writeUtf8(file, render());
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to write checkpoint file " + file, ex);
        }
    }

    private String render() {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n  \"checkpoints\": [\n");
        List<SearchCheckpoint> all = new ArrayList<>(cache.values());
        for (int i = 0; i < all.size(); i++) {
            SearchCheckpoint checkpoint = all.get(i);
            builder.append("    {\n");
            builder.append("      \"jobId\": ").append(quote(checkpoint.jobId())).append(",\n");
            builder.append("      \"originalExpression\": ").append(quote(checkpoint.originalExpression())).append(",\n");
            builder.append("      \"profile\": ").append(quote(checkpoint.profile())).append(",\n");
            builder.append("      \"heuristicName\": ").append(quote(checkpoint.heuristicName())).append(",\n");
            builder.append("      \"randomSeed\": ").append(checkpoint.randomSeed()).append(",\n");
            builder.append("      \"createdAt\": ").append(quote(checkpoint.createdAt().toString())).append(",\n");
            builder.append("      \"updatedAt\": ").append(quote(checkpoint.updatedAt().toString())).append(",\n");
            builder.append("      \"frontier\": ").append(renderStringList(checkpoint.frontier())).append(",\n");
            builder.append("      \"visitedHashes\": ").append(renderStringList(checkpoint.visitedHashes())).append(",\n");
            builder.append("      \"bestPaths\": ").append(renderBestPaths(checkpoint.bestPaths())).append('\n');
            builder.append("    }");
            if (i < all.size() - 1) {
                builder.append(',');
            }
            builder.append('\n');
        }
        builder.append("  ]\n}\n");
        return builder.toString();
    }

    private String renderStringList(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(quote(values.get(i)));
        }
        builder.append(']');
        return builder.toString();
    }

    private String renderBestPaths(List<SearchCheckpoint.BestPath> paths) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            SearchCheckpoint.BestPath path = paths.get(i);
            builder.append("{\"expression\": ").append(quote(path.expression()))
                .append(", \"improvement\": ").append(path.improvement())
                .append(", \"lastRuleId\": ").append(quote(path.lastRuleId()))
                .append('}');
        }
        builder.append(']');
        return builder.toString();
    }

    private List<SearchCheckpoint> parse(String json) {
        List<SearchCheckpoint> result = new ArrayList<>();
        int start = json.indexOf("\"checkpoints\"");
        if (start < 0) {
            return result;
        }
        int arrayStart = json.indexOf('[', start);
        int arrayEnd = json.lastIndexOf(']');
        if (arrayStart < 0 || arrayEnd <= arrayStart) {
            return result;
        }
        String body = json.substring(arrayStart + 1, arrayEnd);
        int position = 0;
        while (position < body.length()) {
            int objectStart = body.indexOf('{', position);
            if (objectStart < 0) {
                break;
            }
            int objectEnd = matchBrace(body, objectStart);
            if (objectEnd < 0) {
                break;
            }
            String object = body.substring(objectStart, objectEnd + 1);
            Map<String, String> fields = parseFlatFields(object);
            SearchCheckpoint checkpoint = new SearchCheckpoint(
                fields.getOrDefault("jobId", ""),
                fields.getOrDefault("originalExpression", ""),
                fields.getOrDefault("profile", "FAST_SIMPLIFY"),
                fields.getOrDefault("heuristicName", "default"),
                parseStringArray(fields.getOrDefault("frontier", "[]")),
                parseStringArray(fields.getOrDefault("visitedHashes", "[]")),
                parseBestPaths(fields.getOrDefault("bestPaths", "[]")),
                parseLong(fields.getOrDefault("randomSeed", "0")),
                parseInstant(fields.get("createdAt")),
                parseInstant(fields.get("updatedAt"))
            );
            if (!checkpoint.jobId().isEmpty()) {
                result.add(checkpoint);
            }
            position = objectEnd + 1;
        }
        return result;
    }

    private Map<String, String> parseFlatFields(String object) {
        Map<String, String> map = new LinkedHashMap<>();
        int position = 1;
        while (position < object.length() - 1) {
            position = skipWhitespaceAndCommas(object, position);
            if (position >= object.length() - 1 || object.charAt(position) != '"') {
                break;
            }
            int keyEnd = findStringEnd(object, position + 1);
            String key = object.substring(position + 1, keyEnd);
            position = keyEnd + 1;
            while (position < object.length() && object.charAt(position) != ':') {
                position++;
            }
            position++;
            position = skipWhitespace(object, position);
            if (position >= object.length()) {
                break;
            }
            char c = object.charAt(position);
            String value;
            if (c == '"') {
                int valueEnd = findStringEnd(object, position + 1);
                value = unescape(object.substring(position + 1, valueEnd));
                position = valueEnd + 1;
            } else if (c == '[') {
                int arrayEnd = matchBracket(object, position);
                value = object.substring(position, arrayEnd + 1);
                position = arrayEnd + 1;
            } else if (c == '{') {
                int braceEnd = matchBrace(object, position);
                value = object.substring(position, braceEnd + 1);
                position = braceEnd + 1;
            } else {
                int valueStart = position;
                while (position < object.length() && ",}\n\r".indexOf(object.charAt(position)) < 0) {
                    position++;
                }
                value = object.substring(valueStart, position).trim();
            }
            map.put(key, value);
        }
        return map;
    }

    private List<String> parseStringArray(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        String body = raw.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        int position = 0;
        while (position < body.length()) {
            position = skipWhitespaceAndCommas(body, position);
            if (position >= body.length() || body.charAt(position) != '"') {
                break;
            }
            int end = findStringEnd(body, position + 1);
            result.add(unescape(body.substring(position + 1, end)));
            position = end + 1;
        }
        return result;
    }

    private List<SearchCheckpoint.BestPath> parseBestPaths(String raw) {
        List<SearchCheckpoint.BestPath> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        String body = raw.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        int position = 0;
        while (position < body.length()) {
            int objectStart = body.indexOf('{', position);
            if (objectStart < 0) {
                break;
            }
            int objectEnd = matchBrace(body, objectStart);
            if (objectEnd < 0) {
                break;
            }
            Map<String, String> fields = parseFlatFields(body.substring(objectStart, objectEnd + 1));
            result.add(new SearchCheckpoint.BestPath(
                fields.getOrDefault("expression", ""),
                (int) parseLong(fields.getOrDefault("improvement", "0")),
                fields.getOrDefault("lastRuleId", "")
            ));
            position = objectEnd + 1;
        }
        return result;
    }

    private static int matchBrace(String text, int openIndex) {
        return matchBracketing(text, openIndex, '{', '}');
    }

    private static int matchBracket(String text, int openIndex) {
        return matchBracketing(text, openIndex, '[', ']');
    }

    private static int matchBracketing(String text, int openIndex, char open, char close) {
        int depth = 0;
        boolean inString = false;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int skipWhitespace(String text, int position) {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
        return position;
    }

    private static int skipWhitespaceAndCommas(String text, int position) {
        while (position < text.length() && (Character.isWhitespace(text.charAt(position)) || text.charAt(position) == ',')) {
            position++;
        }
        return position;
    }

    private static int findStringEnd(String text, int start) {
        int position = start;
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c == '\\') {
                position += 2;
                continue;
            }
            if (c == '"') {
                return position;
            }
            position++;
        }
        return text.length();
    }

    private static String unescape(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    default -> builder.append(next);
                }
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
        return builder.toString();
    }
}
