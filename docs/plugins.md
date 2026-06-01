# Plugins

Regelsuche unterstützt ein leichtgewichtiges Java-Plugin-Modell auf Basis von `ServiceLoader`.

## Standard-Verzeichnisse

- `plugins/` für externe Plugin-JARs
- `rules/` für textbasierte Regelpakete

## Plugin-Vertrag

Ein Plugin implementiert `de.regelsuche.plugin.RegelsuchePlugin` und kann mehrere Registries erweitern:

- `RuleRegistry`
- `TransformationRegistry`
- `AstVisitorRegistry`
- `MacroRegistry`
- `SearchStrategyRegistry`
- `HeuristicRegistry`
- `CostFunctionRegistry`
- `RendererRegistry`
- `ExplanationRegistry`
- `ParserExtensionRegistry`
- `ExampleRegistry`

Die Beispielimplementierung liegt in `app/src/main/java/de/regelsuche/plugin/example/BinomialFormulaPlugin.java`.

## Umsetzungsstand zu Issue 74

- [x] Stabile Plugin-Schnittstelle (`RegelsuchePlugin`)
- [x] Plugin-Discovery über `ServiceLoader`
- [x] Vorbereitung für Reload über `PluginRuntime#reload()`
- [x] Erweiterbare zentrale Registries: `RuleRegistry`, `TransformationRegistry`, `AstVisitorRegistry`, `MacroRegistry`, `SearchStrategyRegistry`, `HeuristicRegistry`, `CostFunctionRegistry`, `RendererRegistry`, `ExplanationRegistry`, `ParserExtensionRegistry`, `ExampleRegistry`
- [x] Eigene AST-Visitor mit allen dokumentierten Hook-Phasen
- [x] Plugin-Transformationen erscheinen als Suchgraph-Kanten
- [x] Textbasierte Regeldateien werden geladen
- [x] DSL-Regeln werden in ein typisiertes Java-Modell überführt
- [x] Beispiel-Regelpakete unter `examples/`
- [x] Java-Beispielplugin für binomische Formeln
- [x] Geladene Plugins, Regeln, Profile und Konflikte sind per CLI sichtbar
- [x] Fehlerhafte Regeldateien liefern verständliche Diagnosen
- [x] Regel-Deaktivierung über Laufzeitkonfiguration und Aktivierungsprofile
- [x] Prioritäten werden als Suchkosten berücksichtigt
- [x] Konflikte und zyklische Regelpaare werden erkannt
- [x] Dokumentation für Plugins, Plugin-API, Regeldateien und Makros
- [x] Tests für Discovery, Registry-Erweiterung, Regeldateien, Visitor, Suchgraph-Integration, deaktivierte Regeln, Konflikte und Beispielpakete

## Sichtbarkeit und Debugging

- `plugins list` zeigt geladene Plugins
- `rules list` zeigt geladene Regeln, Transformationen und Makros (`--profile <id>` wendet ein Aktivierungsprofil an)
- `rules validate <datei>` prüft DSL-Dateien mit verständlichen Diagnosen
- `rules conflicts` zeigt konkurrierende Regeln, die dasselbe Quellmuster verwenden, sowie zyklische (zueinander inverse) Regelpaare
- `rules profiles` zeigt geladene Aktivierungsprofile

## Aktivierungsprofile

Aktivierungsprofile bündeln Regeln, Transformationen und Makros über ihre `tags` zu
Profilen (z. B. `school_algebra`). Ein aktives Profil deaktiviert alle Einträge, deren Tags
nicht zur `enable_tags`-Whitelist passen oder in der `disable_tags`-Blacklist stehen. So
lässt sich der Suchraum gezielt auf eine Domäne einschränken. Details zur Syntax stehen in
`docs/rule-files.md`.

## Konflikterkennung

Regeldateien können `conditions` in der Form `<name>: <value>` deklarieren. Die
Einträge werden beim Laden validiert, typisiert und in den Registry-Metadaten
sichtbar gemacht, sodass Plugins und Debug-Werkzeuge sie auswerten können.

`RuleConflictDetector` vergleicht die Quellmuster (linke Seite) aller aktivierten Regeln,
Transformationen und Makros. Platzhalternamen werden dabei auf ihre Reihenfolge des
ersten Auftretens normalisiert, sodass `A^2 - B^2` und `X^2 - Y^2` als dasselbe Muster
erkannt werden. Teilen sich zwei oder mehr Einträge dasselbe Quellmuster, konkurrieren
sie um dieselben Treffer; `PluginRuntime` meldet das als `rule-conflict`-Diagnose und
über `runtime.conflicts()`. So lassen sich doppelte oder widersprüchliche Suchkanten früh
erkennen.

Zusätzlich erkennt `RuleConflictDetector` zyklische (zueinander inverse) Regelpaare:
Schreibt eine Regel `S -> T` und eine andere `T -> S`, bilden sie einen Zwei-Schritt-Zyklus
im Suchgraphen und können die Suche endlos zwischen beiden Formen pendeln lassen. Die
Platzhalternamen werden über Quell- und Zielmuster hinweg konsistent normalisiert, sodass
`(A + B)^2 -> A^2 + 2*A*B + B^2` und `X^2 + 2*X*Y + Y^2 -> (X + Y)^2` als invers erkannt
werden. `PluginRuntime` meldet solche Paare als `rule-cycle`-Diagnose und über
`runtime.cyclicConflicts()`. So lassen sich potenzielle Endlosschleifen früh erkennen und
durch Richtung, Priorität oder Aktivierungsprofile auflösen.

## Hot-Reload-Vorbereitung

`PluginRuntime#reload()` lädt Classpath-Plugins, externe JARs aus `plugins/` und Regeldateien aus `rules/` neu. Das ist bewusst einfach gehalten und bereitet spätere Lifecycle-/Hot-Reload-Erweiterungen vor.
