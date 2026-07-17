package de.regelsuche.plugin;

public interface RegelsuchePlugin {
    String id();

    String name();

    String version();

    default String apiVersion() {
        return "1";
    }

    default String minimumCoreVersion() {
        return "0.0.0";
    }

    default java.util.Set<String> capabilities() {
        return java.util.Set.of();
    }

    default java.util.List<PluginDependency> dependencies() {
        return java.util.List.of();
    }

    default String provenance() {
        return "";
    }

    /**
     * Optional descriptive signature hint exposed after plugin instantiation.
     *
     * <p>This value is not an artifact-verification boundary: plugin code must
     * already be loaded to invoke it, and the plugin controls the returned
     * string. Secure external loading uses a detached
     * {@link PluginSignatureManifest}, {@link PluginTrustStore} and
     * {@link TrustedPluginRuntime} before any plugin bytecode enters a class
     * loader.</p>
     */
    default String signature() {
        return "";
    }

    default void registerRules(RuleRegistry registry) {
    }

    default void registerTransformations(TransformationRegistry registry) {
    }

    default void registerVisitors(AstVisitorRegistry registry) {
    }

    default void registerMacros(MacroRegistry registry) {
    }

    default void registerSearchStrategies(SearchStrategyRegistry registry) {
    }

    default void registerHeuristics(HeuristicRegistry registry) {
    }

    default void registerCostFunctions(CostFunctionRegistry registry) {
    }

    default void registerRenderers(RendererRegistry registry) {
    }

    default void registerExplanations(ExplanationRegistry registry) {
    }

    default void registerParserExtensions(ParserExtensionRegistry registry) {
    }

    default void registerExamples(ExampleRegistry registry) {
    }
}
