package de.regelsuche.plugin;

public interface RegelsuchePlugin {
    String id();

    String name();

    String version();

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
