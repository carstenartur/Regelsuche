package de.regelsuche.math.algorithms.combinatorics;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Defensive snapshots with ordered iteration and logarithmic lookup on sparse integer keys. */
final class ImmutableIntegerMap<V> extends AbstractMap<Integer, V> {
    private final Map<Integer, V> entries;

    private ImmutableIntegerMap(TreeMap<Integer, V> entries) {
        this.entries = Collections.unmodifiableMap(entries);
    }

    static <V> Map<Integer, V> copyOf(Map<Integer, V> source) {
        return copyOf(source, null);
    }

    /** Copy a budgeted producer's map after it has reserved the key and value cells. */
    static <V> Map<Integer, V> copyOf(Map<Integer, V> source, CertificateWork work) {
        Objects.requireNonNull(source);
        if (source instanceof ImmutableIntegerMap<?>) return source;
        var copy = new TreeMap<Integer, V>();
        for (var entry : source.entrySet()) {
            if (work != null) work.charge(1);
            if (copy.put(Objects.requireNonNull(entry.getKey()), Objects.requireNonNull(entry.getValue())) != null) {
                throw new IllegalArgumentException("DUPLICATE_INTEGER_KEY");
            }
        }
        if (work != null) work.charge(0);
        return new ImmutableIntegerMap<>(copy);
    }

    @Override public Set<Entry<Integer, V>> entrySet() {
        return entries.entrySet();
    }

    @Override public int size() {
        return entries.size();
    }

    @Override public V get(Object key) {
        return Objects.requireNonNull(key) instanceof Integer ? entries.get(key) : null;
    }

    @Override public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override public boolean containsValue(Object value) {
        return entries.containsValue(Objects.requireNonNull(value));
    }

    @Override public Set<Integer> keySet() {
        return Collections.unmodifiableSet(super.keySet());
    }

    @Override public Collection<V> values() {
        return Collections.unmodifiableCollection(super.values());
    }

    @Override public V put(Integer key, V value) {
        return entries.put(key, value);
    }

    @Override public void putAll(Map<? extends Integer, ? extends V> source) {
        entries.putAll(source);
    }

    @Override public V putIfAbsent(Integer key, V value) {
        return entries.putIfAbsent(key, value);
    }

    @Override public V remove(Object key) {
        return entries.remove(key);
    }

    @Override public boolean remove(Object key, Object value) {
        return entries.remove(key, value);
    }

    @Override public void clear() {
        entries.clear();
    }

    @Override public V replace(Integer key, V value) {
        return entries.replace(key, value);
    }

    @Override public boolean replace(Integer key, V previous, V replacement) {
        return entries.replace(key, previous, replacement);
    }

    @Override public void replaceAll(BiFunction<? super Integer, ? super V, ? extends V> function) {
        entries.replaceAll(function);
    }

    @Override public V compute(Integer key, BiFunction<? super Integer, ? super V, ? extends V> function) {
        return entries.compute(key, function);
    }

    @Override public V computeIfPresent(Integer key, BiFunction<? super Integer, ? super V, ? extends V> function) {
        return entries.computeIfPresent(key, function);
    }

    @Override public V computeIfAbsent(Integer key, Function<? super Integer, ? extends V> function) {
        return entries.computeIfAbsent(key, function);
    }

    @Override public V merge(Integer key, V value, BiFunction<? super V, ? super V, ? extends V> function) {
        return entries.merge(key, value, function);
    }
}
