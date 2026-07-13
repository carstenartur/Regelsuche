package de.regelsuche.search.policy;

import de.regelsuche.search.learning.TransformationDescriptor;
import de.regelsuche.search.learning.TransformationDescriptor.OccurrenceRole;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Deterministic predictive feature expansion shared by training and live scoring. */
final class DescriptorFeatureVector {
    private DescriptorFeatureVector() {
    }

    static Map<String, Integer> of(TransformationDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        Map<String, Integer> features = new TreeMap<>(descriptor.featureVector());
        TransformationDescriptor.LocalChange local = descriptor.localChange();
        if (local.available() && local.role() != OccurrenceRole.ROOT) {
            features.put("local.contextRole." + local.contextRoot().kind().name()
                + "_" + local.role().name(), 1);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(features));
    }

    static boolean pairwiseContextFeature(String featureName) {
        return featureName != null && (featureName.startsWith("local.context.")
            || featureName.startsWith("local.contextRole."));
    }
}
