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
}
