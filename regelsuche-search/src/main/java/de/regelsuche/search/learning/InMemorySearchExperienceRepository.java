package de.regelsuche.search.learning;

import de.regelsuche.search.learning.SearchExperienceRepository.ExperienceSummary;
import de.regelsuche.search.learning.SearchExperienceRepository.SearchExperience;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic in-memory repository for successful choices and failed alternatives. */
public final class InMemorySearchExperienceRepository implements SearchExperienceRepository {
    private static final Comparator<SearchExperience> RELEVANCE = Comparator
        .comparingInt((SearchExperience experience) -> experience.successfulChoice() ? 0 : 1)
        .thenComparingInt(experience -> experience.selectedPath() ? 0 : 1)
        .thenComparingInt(SearchExperience::scoreDelta)
        .thenComparingInt(SearchExperience::depth)
        .thenComparing(SearchExperience::ruleId)
        .thenComparing(SearchExperience::experienceId);

    private final Map<String, SearchExperience> byId = new LinkedHashMap<>();
    private final Map<ShapeKey, List<String>> idsByShape = new LinkedHashMap<>();

    @Override
    public synchronized void store(SearchExperience experience) {
        Objects.requireNonNull(experience, "experience");
        SearchExperience previous = byId.put(experience.experienceId(), experience);
        if (previous != null) {
            removeFromIndex(previous);
        }
        ShapeKey key = new ShapeKey(experience.family(), experience.parentAlphaShapeHash());
        idsByShape.computeIfAbsent(key, ignored -> new ArrayList<>())
            .add(experience.experienceId());
    }

    @Override
    public synchronized List<SearchExperience> findByShape(
        String family,
        String parentAlphaShapeHash,
        int limit
    ) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        ShapeKey key = new ShapeKey(safe(family), safe(parentAlphaShapeHash));
        return idsByShape.getOrDefault(key, List.of()).stream()
            .map(byId::get)
            .filter(Objects::nonNull)
            .sorted(RELEVANCE)
            .limit(limit)
            .toList();
    }

    @Override
    public synchronized ExperienceSummary summary() {
        int successful = (int) byId.values().stream()
            .filter(SearchExperience::successfulChoice)
            .count();
        Set<String> families = new LinkedHashSet<>();
        Set<ShapeKey> shapes = new LinkedHashSet<>();
        byId.values().forEach(experience -> {
            families.add(experience.family());
            shapes.add(new ShapeKey(
                experience.family(), experience.parentAlphaShapeHash()));
        });
        return new ExperienceSummary(
            byId.size(),
            successful,
            byId.size() - successful,
            families.size(),
            shapes.size());
    }

    public synchronized int size() {
        return byId.size();
    }

    public synchronized void clear() {
        byId.clear();
        idsByShape.clear();
    }

    private void removeFromIndex(SearchExperience previous) {
        ShapeKey previousKey = new ShapeKey(
            previous.family(), previous.parentAlphaShapeHash());
        List<String> ids = idsByShape.get(previousKey);
        if (ids == null) {
            return;
        }
        ids.remove(previous.experienceId());
        if (ids.isEmpty()) {
            idsByShape.remove(previousKey);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record ShapeKey(String family, String alphaShapeHash) {
    }
}
