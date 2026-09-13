package de.regelsuche.symbol;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded, thread-safe name resolution at the input boundary. One allocation
 * namespace is shared by all symbols declared in this scope. Snapshot restoration
 * is single-writer resumption; independent writers must allocate fresh namespaces.
 */
public final class SymbolScope {
    public static final int MAXIMUM_NAME_LENGTH = 128;
    private final UUID namespace;
    private final SymbolScope parent;
    private final Limits limits;
    private final Registry registry;
    private final int depth;
    private final Map<String, SymbolId> bindings = new LinkedHashMap<>();
    private long nextOrdinal = 1;

    public SymbolScope() {
        this(UUID.randomUUID());
    }

    public SymbolScope(UUID namespace) {
        this(namespace, Limits.defaults());
    }

    public SymbolScope(UUID namespace, Limits limits) {
        this(namespace, null, Objects.requireNonNull(limits, "limits"), new Registry(limits.maximumScopes()));
    }

    private SymbolScope(UUID namespace, SymbolScope parent, Limits limits, Registry registry) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.parent = parent;
        this.limits = limits;
        this.registry = registry;
        this.depth = parent == null ? 0 : parent.depth + 1;
        registry.register(namespace, depth, limits.maximumDepth());
    }

    public UUID namespace() {
        return namespace;
    }

    public SymbolScope child() {
        return child(UUID.randomUUID());
    }

    public SymbolScope child(UUID childNamespace) {
        synchronized (registry) {
            return new SymbolScope(childNamespace, this, limits, registry);
        }
    }

    public SymbolId resolve(String name) {
        return resolveAll(java.util.List.of(requireName(name))).get(name);
    }

    /** Resolves a bounded batch atomically, including validation and allocation. */
    public Map<String, SymbolId> resolveAll(Collection<String> names) {
        var checked = checkedNames(names);
        synchronized (registry) {
            long unknown = checked.stream().filter(name -> find(name) == null).count();
            requireCapacity(unknown);
            Map<String, SymbolId> result = new LinkedHashMap<>();
            for (String name : checked) {
                SymbolId symbol = find(name);
                if (symbol == null) {
                    symbol = new SymbolId(namespace, nextOrdinal++);
                    bindings.put(name, symbol);
                }
                result.put(name, symbol);
            }
            return Map.copyOf(result);
        }
    }

    /** Explicit declaration may shadow a parent, but never replaces a local binding. */
    public SymbolId declare(String name) {
        requireName(name);
        synchronized (registry) {
            if (bindings.containsKey(name)) throw new IllegalArgumentException("name is already declared locally");
            requireCapacity(1);
            var symbol = new SymbolId(namespace, nextOrdinal++);
            bindings.put(name, symbol);
            return symbol;
        }
    }

    /** An alias must refer to a symbol already known in this lexical ancestry. */
    public SymbolId alias(String name, SymbolId symbol) {
        requireName(name);
        Objects.requireNonNull(symbol, "symbol");
        synchronized (registry) {
            SymbolId canonical = knownSymbol(symbol);
            if (canonical == null) throw new IllegalArgumentException("alias refers to an unknown symbol");
            SymbolId existing = bindings.get(name);
            if (existing != null && !existing.equals(symbol)) throw new IllegalArgumentException("alias replaces a local binding");
            if (existing == null && bindings.size() >= limits.maximumBindings()) {
                throw new IllegalArgumentException("symbol binding capacity exceeded");
            }
            bindings.put(name, canonical);
            return canonical;
        }
    }

    public Snapshot snapshot() {
        synchronized (registry) {
            return new Snapshot(namespace, parent == null ? null : parent.namespace, nextOrdinal, bindings, limits);
        }
    }

    /** Restores a root; restore each child into its restored parent before continuing allocation. */
    public static SymbolScope restore(Snapshot snapshot) {
        Map<String, SymbolId> restored = validateSnapshot(snapshot, null);
        var scope = new SymbolScope(snapshot.namespace(), snapshot.limits());
        scope.bindings.putAll(restored);
        scope.nextOrdinal = snapshot.nextOrdinal();
        return scope;
    }

    public SymbolScope restoreChild(Snapshot snapshot) {
        synchronized (registry) {
            Map<String, SymbolId> restored = validateSnapshot(snapshot, this);
            var child = new SymbolScope(snapshot.namespace(), this, limits, registry);
            child.bindings.putAll(restored);
            child.nextOrdinal = snapshot.nextOrdinal();
            return child;
        }
    }

    /** Names are syntax only. This check does not assign or compare symbol identities. */
    public static String requireName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAXIMUM_NAME_LENGTH
                || !Character.isLetter(name.charAt(0)) || name.startsWith(SymbolId.IDENTIFIER_PREFIX)) {
            throw new IllegalArgumentException("invalid input symbol name");
        }
        for (int i = 1; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (!Character.isLetterOrDigit(ch) && ch != '_') {
                throw new IllegalArgumentException("invalid input symbol name");
            }
        }
        return name;
    }

    private Set<String> checkedNames(Collection<String> names) {
        Objects.requireNonNull(names, "names");
        if (names.size() > limits.maximumBindings()) throw new IllegalArgumentException("symbol batch capacity exceeded");
        var checked = new LinkedHashSet<String>();
        int inspected = 0;
        for (String name : names) {
            if (++inspected > limits.maximumBindings()) throw new IllegalArgumentException("symbol batch capacity exceeded");
            checked.add(requireName(name));
        }
        return checked;
    }

    private void requireCapacity(long count) {
        if (count > limits.maximumBindings() - bindings.size() || count > Long.MAX_VALUE - nextOrdinal) {
            throw new IllegalArgumentException("symbol allocation capacity exceeded");
        }
    }

    private SymbolId find(String name) {
        for (var scope = this; scope != null; scope = scope.parent) {
            SymbolId symbol = scope.bindings.get(name);
            if (symbol != null) return symbol;
        }
        return null;
    }

    private SymbolId knownSymbol(SymbolId id) {
        for (var scope = this; scope != null; scope = scope.parent) {
            for (var symbol : scope.bindings.values()) {
                if (symbol.equals(id)) return symbol;
            }
        }
        return null;
    }

    private static Map<String, SymbolId> validateSnapshot(Snapshot snapshot, SymbolScope parent) {
        Objects.requireNonNull(snapshot, "snapshot");
        UUID expectedParent = parent == null ? null : parent.namespace;
        if (!Objects.equals(expectedParent, snapshot.parentNamespace())
                || parent != null && !parent.limits.equals(snapshot.limits())
                || snapshot.bindings().size() > snapshot.limits().maximumBindings()
                || snapshot.nextOrdinal() < 1 || snapshot.nextOrdinal() > snapshot.limits().maximumBindings() + 1L) {
            throw new IllegalArgumentException("inconsistent symbol scope snapshot");
        }
        Map<String, SymbolId> restored = new LinkedHashMap<>();
        Map<SymbolId, SymbolId> localSymbols = new HashMap<>();
        for (var entry : snapshot.bindings().entrySet()) {
            String name = requireName(entry.getKey());
            SymbolId id = entry.getValue();
            SymbolId canonical;
            if (id.namespace().equals(snapshot.namespace())) {
                if (id.ordinal() >= snapshot.nextOrdinal()) throw new IllegalArgumentException("snapshot reuses an ordinal");
                canonical = localSymbols.computeIfAbsent(id, value -> value);
            } else {
                canonical = parent == null ? null : parent.knownSymbol(id);
                if (canonical == null) throw new IllegalArgumentException("snapshot refers to an unknown inherited symbol");
            }
            restored.put(name, canonical);
        }
        if (localSymbols.size() != snapshot.nextOrdinal() - 1) {
            throw new IllegalArgumentException("snapshot omits an allocated symbol");
        }
        return restored;
    }

    public record Limits(int maximumBindings, int maximumScopes, int maximumDepth) {
        public Limits {
            if (maximumBindings < 1 || maximumBindings > 4096 || maximumScopes < 1 || maximumScopes > 1024
                    || maximumDepth < 0 || maximumDepth > 64) {
                throw new IllegalArgumentException("invalid symbol scope limits");
            }
        }
        public static Limits defaults() { return new Limits(1024, 256, 32); }
    }

    /** A data snapshot, not an authorization to fork multiple writers into one namespace. */
    public record Snapshot(UUID namespace, UUID parentNamespace, long nextOrdinal,
            Map<String, SymbolId> bindings, Limits limits) {
        public Snapshot {
            Objects.requireNonNull(namespace, "namespace");
            bindings = Map.copyOf(Objects.requireNonNull(bindings, "bindings"));
            Objects.requireNonNull(limits, "limits");
        }
    }

    private static final class Registry {
        private final int maximumScopes;
        private final Set<UUID> namespaces = new HashSet<>();
        private Registry(int maximumScopes) { this.maximumScopes = maximumScopes; }
        private void register(UUID namespace, int depth, int maximumDepth) {
            if (depth > maximumDepth || namespaces.size() >= maximumScopes || namespaces.contains(namespace)) {
                throw new IllegalArgumentException("duplicate namespace or symbol scope capacity exceeded");
            }
            namespaces.add(namespace);
        }
    }
}
