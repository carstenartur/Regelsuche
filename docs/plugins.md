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
