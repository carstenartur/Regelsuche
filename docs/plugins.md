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

Beispielimplementierungen liegen unter `app/src/main/java/de/regelsuche/plugin/example/`:

- `AlgebraPlugin`
- `BinomialFormulaPlugin`
- `FactorizationPlugin`
- `TrigonometryPlugin`
- `RationalFunctionsPlugin`
- `DiscoveryOperatorsPlugin`

## Umsetzungsstand zu Issue 74

- [x] Stabile Plugin-Schnittstelle (`RegelsuchePlugin`)
- [x] Plugin-Discovery über `ServiceLoader`
- [x] Vorbereitung für Reload über `PluginRuntime#reload()`
- [x] Hot-Reload-Ergebnisobjekt (`PluginReloadResult`) und Verzeichnis-Watcher für `plugins/` und `rules/`
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
- Der Plugin-Katalog (`plugins list` und `GET /api/plugins`) zeigt pro Plugin:
  - Metadaten (`id`, `name`, `version`, `apiVersion`, `minimumCoreVersion`, `capabilities`)
  - Kompatibilitätsstatus (`compatible`, `incompatible`, `not-checked`) inkl. `compatibilityIssues`
  - Abhängigkeiten (`dependencies` mit Version-Constraints, optional/required und Status wie `present`, `missing-required`, `missing-optional`, `version-not-checked`)
  - Vertrauensinformationen (`provenance`, `signaturePresent`, `signatureVerified`, `trustedSource`, `trustWarnings`)
- `plugins reload` liefert Diff, Diagnosen und Konflikte eines Reloads
- `plugins status` zeigt Verzeichnis-, Plugin-, Regel- und Konfliktstatus
- `plugins watch` überwacht `plugins/` und `rules/` mit debounce-basiertem Hot-Reload
- `rules list` zeigt geladene Regeln, Transformationen und Makros (`--profile <id>` wendet ein Aktivierungsprofil an)
- `rules validate <datei>` prüft DSL-Dateien mit verständlichen Diagnosen
- `rules conflicts` zeigt konkurrierende Regeln, die dasselbe Quellmuster verwenden, sowie zyklische (zueinander inverse) Regelpaare
- `rules profiles` zeigt geladene Aktivierungsprofile
- `rules debug <ausdruck>` zeigt Regelversuche, Rejektionsgründe und Diagnosen eines Transformationslaufs, inklusive deaktivierter Regeln, Bedingungen und Zyklusrisiken
- `rules import` kopiert `.regelsuche`/`.rules`-Pakete in das Zielverzeichnis
- `rules export` schreibt aktive Regeln als `.regelsuche`-Paket heraus

## Paketierung und Distribution

- Externe Plugins werden als JAR-Artefakte über `META-INF/services/de.regelsuche.plugin.RegelsuchePlugin` veröffentlicht.
- Regelpakete werden als `.regelsuche`/`.rules`-Artefakte veröffentlicht und können mit `rules import`/`rules export` ausgetauscht werden.
- Versionierung erfolgt pro Plugin über `version()`, `apiVersion()` und `minimumCoreVersion()`.

## Vertrauensmodell

- Classpath-Plugins gelten als bekannte Quelle.
- Für externe Quellen werden Signatur- und Provenance-Metadaten ausgewertet (`signature()`, `provenance()`), aber nicht kryptografisch verifiziert.
- `signaturePresent` signalisiert nur vorhandene Signatur-Metadaten; `signatureVerified` bleibt ohne Verifizierer `false`.
- Externe Plugins bleiben ohne Verifizierer/Allowlist untrusted (`trustedSource=false`).
- Bei unbekannter oder untrusted externer Herkunft und fehlenden Metadaten erscheinen Warnungen (`MISSING_PROVENANCE`, `MISSING_SIGNATURE_METADATA`, `SIGNATURE_NOT_VERIFIED`, `UNKNOWN_SOURCE`, `UNTRUSTED_EXTERNAL_SOURCE`) im Katalog.

## Community und Autoren-Onboarding

- Der Katalog in `plugins list` und `GET /api/plugins` dient als lokaler Plugin-Index.
- `docs/plugin-api.md` enthält den Autorenleitfaden inklusive Metadaten, Abhängigkeiten und Service-Registrierung.
- Die Beispielplugins unter `de.regelsuche.plugin.example` dienen als Templates/Referenzprojekte für Community-Erweiterungen.

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

## Hot-Reload

`PluginRuntime#reload()` lädt Classpath-Plugins, externe JARs aus `plugins/` und Regeldateien aus `rules/` neu. `PluginRuntime#reloadWithResult()` liefert zusätzlich Plugin-/Regeldatei-Diffs, Diagnosen und Konflikte als `PluginReloadResult`.

Die Reload-Diffs vergleichen Snapshots statt nur IDs bzw. Pfade: unveränderte Plugins oder Regeldateien erscheinen nicht im Ergebnis, geänderte Snapshots erscheinen als `CHANGED`, nur vorher vorhandene Einträge als `REMOVED` und nur nachher vorhandene Einträge als `ADDED`. Regeldatei-Snapshots enthalten den Inhalts-Hash, sodass auch Änderungen bei gleichem Pfad und gleicher Eintragszahl sichtbar werden.

`PluginDirectoryWatcher` beobachtet `plugins/` und `rules/` per `WatchService`, entprellt Dateisystem-Events und löst anschließend `reloadWithResult()` aus. Die CLI bindet das über `plugins watch` an, damit Plugin-JARs und Regelpakete ohne Neustart getestet werden können.
