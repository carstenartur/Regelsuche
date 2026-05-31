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
- `rules conflicts` zeigt konkurrierende Regeln, die dasselbe Quellmuster verwenden

## Konflikterkennung

`RuleConflictDetector` vergleicht die Quellmuster (linke Seite) aller aktivierten Regeln,
Transformationen und Makros. Platzhalternamen werden dabei auf ihre Reihenfolge des
ersten Auftretens normalisiert, sodass `A^2 - B^2` und `X^2 - Y^2` als dasselbe Muster
erkannt werden. Teilen sich zwei oder mehr Einträge dasselbe Quellmuster, konkurrieren
sie um dieselben Treffer; `PluginRuntime` meldet das als `rule-conflict`-Diagnose und
über `runtime.conflicts()`. So lassen sich doppelte oder widersprüchliche Suchkanten früh
erkennen.

## Hot-Reload-Vorbereitung

`PluginRuntime#reload()` lädt Classpath-Plugins, externe JARs aus `plugins/` und Regeldateien aus `rules/` neu. Das ist bewusst einfach gehalten und bereitet spätere Lifecycle-/Hot-Reload-Erweiterungen vor.
