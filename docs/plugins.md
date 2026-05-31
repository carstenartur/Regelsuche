# Plugins

Regelsuche unterstützt ein leichtgewichtiges Java-Plugin-Modell auf Basis von `ServiceLoader`.

## Standard-Verzeichnisse

- `plugins/` für externe Plugin-JARs
- `rules/` für textbasierte Regelpakete

## Plugin-Vertrag

Ein Plugin implementiert `de.regelsuche.plugin.RegelsuchePlugin` und kann vier Registries erweitern:

- `RuleRegistry`
- `TransformationRegistry`
- `AstVisitorRegistry`
- `MacroRegistry`

Die Beispielimplementierung liegt in `app/src/main/java/de/regelsuche/plugin/example/BinomialFormulaPlugin.java`.

## Sichtbarkeit und Debugging

- `plugins list` zeigt geladene Plugins
- `rules list` zeigt geladene Regeln, Transformationen und Makros
- `rules validate <datei>` prüft DSL-Dateien mit verständlichen Diagnosen

## Hot-Reload-Vorbereitung

`PluginRuntime#reload()` lädt Classpath-Plugins, externe JARs aus `plugins/` und Regeldateien aus `rules/` neu. Das ist bewusst einfach gehalten und bereitet spätere Lifecycle-/Hot-Reload-Erweiterungen vor.
