package de.regelsuche.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Checks whether a plugin is compatible with the current Regelsuche core version.
 */
public final class PluginCompatibilityChecker {
    /** The API version supported by this core installation. */
    public static final String CORE_API_VERSION = "1";
    /** The core version string. */
    public static final String CORE_VERSION = "1.0.0";
    private static final Set<String> SUPPORTED_CAPABILITIES = Set.of(
        "rules",
        "transformations",
        "visitors",
        "macros",
        "search-strategies",
        "heuristics",
        "cost-functions",
        "renderers",
        "explanations",
        "parser-extensions",
        "examples"
    );

    private PluginCompatibilityChecker() {}

    /**
     * Returns a list of compatibility diagnostics for the given plugin.
     * An empty list means the plugin is compatible.
     */
    public static List<PluginRuntime.RuntimeDiagnostic> check(RegelsuchePlugin plugin) {
        List<PluginRuntime.RuntimeDiagnostic> issues = new ArrayList<>();
        if (!CORE_API_VERSION.equals(plugin.apiVersion())) {
            issues.add(new PluginRuntime.RuntimeDiagnostic(plugin.id(),
                "PLUGIN_INCOMPATIBLE_API_VERSION: plugin requires API version "
                    + plugin.apiVersion() + " but core provides " + CORE_API_VERSION));
        }
        if (isNewerVersion(plugin.minimumCoreVersion(), CORE_VERSION)) {
            issues.add(new PluginRuntime.RuntimeDiagnostic(plugin.id(),
                "PLUGIN_REQUIRES_NEWER_CORE: plugin requires core >= "
                    + plugin.minimumCoreVersion() + " but running " + CORE_VERSION));
        }
        for (String capability : plugin.capabilities()) {
            if (!isSupportedCapability(capability)) {
                issues.add(new PluginRuntime.RuntimeDiagnostic(plugin.id(),
                    "PLUGIN_CAPABILITY_UNKNOWN: required capability '" + capability + "' is not provided by core"));
            }
        }
        return List.copyOf(issues);
    }

    public static boolean isCompatible(RegelsuchePlugin plugin) {
        return check(plugin).isEmpty();
    }

    static boolean isSupportedCapability(String capability) {
        return capability != null && SUPPORTED_CAPABILITIES.contains(capability.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns true if {@code required} is strictly newer than {@code available}.
     * Simple dot-separated numeric comparison.
     */
    static boolean isNewerVersion(String required, String available) {
        if (required == null || required.isBlank() || required.equals("0.0.0")) {
            return false;
        }
        String[] req = required.split("\\.", 3);
        String[] avail = available.split("\\.", 3);
        for (int i = 0; i < Math.min(req.length, avail.length); i++) {
            try {
                int r = Integer.parseInt(req[i].replaceAll("[^0-9]", ""));
                int a = Integer.parseInt(avail[i].replaceAll("[^0-9]", ""));
                if (r > a) {
                    return true;
                }
                if (r < a) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }
}
