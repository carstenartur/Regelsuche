package de.regelsuche.search.memory;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.search.SearchTraceStore;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Compact in-memory {@link SearchTraceStore} optimized for large discovery runs.
 *
 * <p>Expressions/rules/assumptions are hash-consed and addressed by numeric ids.
 * Paths are stored as edge-id sequences. The first {@code topKFullPaths} paths
 * are kept fully for hot replay access; remaining paths are delta+varint encoded.
 */
public final class CompactInMemorySearchTraceStore implements SearchTraceStore {
    private static final int DEFAULT_TOP_K_FULL_PATHS = 16;

    private final int topKFullPaths;

    private final Map<ExpressionKey, Long> expressionIntern = new HashMap<>();
    private final List<ExpressionRecord> expressionsById = new ArrayList<>();

    private final Map<String, Long> ruleIntern = new HashMap<>();
    private final List<String> rulesById = new ArrayList<>();

    private final Map<AssumptionKey, Long> assumptionsIntern = new HashMap<>();
    private final List<AssumptionSignature> assumptionsById = new ArrayList<>();

    private final List<EdgeRecord> edgesById = new ArrayList<>();

    private final Map<Long, long[]> fullPaths = new HashMap<>();
    private final Map<Long, byte[]> compactPaths = new HashMap<>();
    private long pathCount;

    public CompactInMemorySearchTraceStore() {
        this(DEFAULT_TOP_K_FULL_PATHS);
    }

    public CompactInMemorySearchTraceStore(int topKFullPaths) {
        if (topKFullPaths < 0) {
            throw new IllegalArgumentException("topKFullPaths must be >= 0");
        }
        this.topKFullPaths = topKFullPaths;
    }

    @Override
    public synchronized long internExpression(String canonicalHash, String canonicalForm) {
        String hash = requireNonBlank(canonicalHash, "canonicalHash");
        String form = requireNonBlank(canonicalForm, "canonicalForm");
        ExpressionKey key = new ExpressionKey(hash, form);
        Long existing = expressionIntern.get(key);
        if (existing != null) {
            return existing;
        }
        long id = expressionsById.size() + 1L;
        expressionIntern.put(key, id);
        expressionsById.add(new ExpressionRecord(id, hash, form));
        return id;
    }

    @Override
    public synchronized long internRule(String ruleId) {
        String rule = requireNonBlank(ruleId, "ruleId");
        Long existing = ruleIntern.get(rule);
        if (existing != null) {
            return existing;
        }
        long id = rulesById.size() + 1L;
        ruleIntern.put(rule, id);
        rulesById.add(rule);
        return id;
    }

    @Override
    public synchronized long internAssumptions(AssumptionSignature assumptions) {
        Objects.requireNonNull(assumptions, "assumptions");
        AssumptionSignature normalized = AssumptionSignature.ofExpressions(assumptions.normalizedAssumptions());
        AssumptionKey key = new AssumptionKey(
            normalized.fingerprint(),
            normalized.normalizedAssumptions()
        );
        Long existing = assumptionsIntern.get(key);
        if (existing != null) {
            return existing;
        }
        long id = assumptionsById.size() + 1L;
        assumptionsIntern.put(key, id);
        assumptionsById.add(normalized);
        return id;
    }

    @Override
    public synchronized long addEdge(long fromExprId, long toExprId, int ruleId, long assumptionsId) {
        requireExistingExpression(fromExprId, "fromExprId");
        requireExistingExpression(toExprId, "toExprId");
        long normalizedRuleId = normalizeRuleId(ruleId);
        requireExistingAssumptions(assumptionsId);
        long id = edgesById.size() + 1L;
        edgesById.add(new EdgeRecord(id, fromExprId, toExprId, (int) normalizedRuleId, assumptionsId));
        return id;
    }

    @Override
    public synchronized long addPath(long[] edgeIds) {
        Objects.requireNonNull(edgeIds, "edgeIds");
        long[] copy = Arrays.copyOf(edgeIds, edgeIds.length);
        for (long edgeId : copy) {
            requireExistingEdge(edgeId);
        }

        long id = ++pathCount;
        if (id <= topKFullPaths) {
            fullPaths.put(id, copy);
        } else {
            compactPaths.put(id, encodePath(copy));
        }
        return id;
    }

    public synchronized long[] expandPath(long pathId) {
        long[] full = fullPaths.get(pathId);
        if (full != null) {
            return Arrays.copyOf(full, full.length);
        }
        byte[] compact = compactPaths.get(pathId);
        if (compact == null) {
            throw new IllegalArgumentException("Unknown path id: " + pathId);
        }
        return decodePath(compact);
    }

    public synchronized List<ReplayStep> replay(long pathId) {
        long[] edgeIds = expandPath(pathId);
        List<ReplayStep> steps = new ArrayList<>(edgeIds.length);
        for (long edgeId : edgeIds) {
            EdgeRecord edge = edgeById(edgeId);
            ExpressionRecord from = expressionById(edge.fromExprId());
            ExpressionRecord to = expressionById(edge.toExprId());
            String rule = ruleById(Integer.toUnsignedLong(edge.ruleId()));
            AssumptionSignature assumptions = assumptionsById(edge.assumptionsId());
            steps.add(new ReplayStep(
                edge.id(),
                from.canonicalForm(),
                to.canonicalForm(),
                rule,
                assumptions
            ));
        }
        return List.copyOf(steps);
    }

    public synchronized long expressionCount() {
        return expressionsById.size();
    }

    public synchronized long ruleCount() {
        return rulesById.size();
    }

    public synchronized long assumptionsCount() {
        return assumptionsById.size();
    }

    public synchronized long edgeCount() {
        return edgesById.size();
    }

    public synchronized long fullPathCount() {
        return fullPaths.size();
    }

    public synchronized long compactPathCount() {
        return compactPaths.size();
    }

    public synchronized int pathStorageBytes(long pathId) {
        long[] full = fullPaths.get(pathId);
        if (full != null) {
            return Math.multiplyExact(full.length, Long.BYTES);
        }
        byte[] compact = compactPaths.get(pathId);
        if (compact == null) {
            throw new IllegalArgumentException("Unknown path id: " + pathId);
        }
        return compact.length;
    }

    public synchronized Optional<EdgeRecord> findEdge(long edgeId) {
        if (edgeId <= 0 || edgeId > edgesById.size()) {
            return Optional.empty();
        }
        return Optional.of(edgesById.get((int) edgeId - 1));
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private void requireExistingExpression(long expressionId, String fieldName) {
        if (expressionId <= 0 || expressionId > expressionsById.size()) {
            throw new IllegalArgumentException(fieldName + " does not exist: " + expressionId);
        }
    }

    private long normalizeRuleId(int ruleId) {
        if (ruleId <= 0) {
            throw new IllegalArgumentException("ruleId must be positive");
        }
        long normalized = Integer.toUnsignedLong(ruleId);
        if (normalized > rulesById.size()) {
            throw new IllegalArgumentException("ruleId does not exist: " + ruleId);
        }
        return normalized;
    }

    private void requireExistingAssumptions(long assumptionsId) {
        if (assumptionsId <= 0 || assumptionsId > assumptionsById.size()) {
            throw new IllegalArgumentException("assumptionsId does not exist: " + assumptionsId);
        }
    }

    private void requireExistingEdge(long edgeId) {
        if (edgeId <= 0 || edgeId > edgesById.size()) {
            throw new IllegalArgumentException("edgeId does not exist: " + edgeId);
        }
    }

    private ExpressionRecord expressionById(long expressionId) {
        return expressionsById.get((int) expressionId - 1);
    }

    private String ruleById(long ruleId) {
        return rulesById.get((int) ruleId - 1);
    }

    private AssumptionSignature assumptionsById(long assumptionsId) {
        return assumptionsById.get((int) assumptionsId - 1);
    }

    private EdgeRecord edgeById(long edgeId) {
        return edgesById.get((int) edgeId - 1);
    }

    private static byte[] encodePath(long[] edgeIds) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8, edgeIds.length));
        long previous = 0L;
        for (long edgeId : edgeIds) {
            long delta = edgeId - previous;
            writeVarLong(zigZagEncode(delta), out);
            previous = edgeId;
        }
        return out.toByteArray();
    }

    private static long[] decodePath(byte[] encoded) {
        long[] tmp = new long[Math.max(8, encoded.length)];
        int size = 0;
        long previous = 0L;
        int index = 0;
        while (index < encoded.length) {
            VarLong value = readVarLong(encoded, index);
            long delta = zigZagDecode(value.value());
            long edgeId = previous + delta;
            if (size == tmp.length) {
                tmp = Arrays.copyOf(tmp, tmp.length * 2);
            }
            tmp[size++] = edgeId;
            previous = edgeId;
            index = value.nextIndex();
        }
        return Arrays.copyOf(tmp, size);
    }

    private static long zigZagEncode(long value) {
        return (value << 1) ^ (value >> 63);
    }

    private static long zigZagDecode(long value) {
        return (value >>> 1) ^ -(value & 1L);
    }

    private static void writeVarLong(long value, ByteArrayOutputStream out) {
        long remaining = value;
        while ((remaining & ~0x7FL) != 0) {
            out.write((int) ((remaining & 0x7F) | 0x80));
            remaining >>>= 7;
        }
        out.write((int) remaining);
    }

    private static VarLong readVarLong(byte[] bytes, int index) {
        long value = 0L;
        int shift = 0;
        int cursor = index;
        while (cursor < bytes.length) {
            int b = bytes[cursor++] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new VarLong(value, cursor);
            }
            shift += 7;
            if (shift > 63) {
                throw new IllegalArgumentException("Malformed varint path payload");
            }
        }
        throw new IllegalArgumentException("Malformed varint path payload");
    }

    private record ExpressionKey(String canonicalHash, String canonicalForm) {
    }

    private record AssumptionKey(String fingerprint, List<String> normalizedAssumptions) {
    }

    private record VarLong(long value, int nextIndex) {
    }

    public record ExpressionRecord(long id, String canonicalHash, String canonicalForm) {
    }

    public record EdgeRecord(long id, long fromExprId, long toExprId, int ruleId, long assumptionsId) {
    }

    public record ReplayStep(
        long edgeId,
        String fromCanonicalForm,
        String toCanonicalForm,
        String ruleId,
        AssumptionSignature assumptions
    ) {
    }
}
