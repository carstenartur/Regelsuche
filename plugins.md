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

- Classpath-Plugins gelten als bekannte, gemeinsam mit der Anwendung ausgelieferte Quelle.
- `RegelsuchePlugin.signature()` und `provenance()` sind beschreibende Metadaten, die erst nach der Plugin-Instanziierung verfügbar sind. Sie dürfen deshalb keine Code-Ladeentscheidung autorisieren.
- Für eine kryptografische Vorladeprüfung wird `TrustedPluginRuntime` verwendet. Es prüft ein Detached Ed25519-Manifest und einen lokalen Publisher-Trust-Store, bevor ein externes JAR einen ClassLoader erreicht.
- `PluginTrustPolicy.WARN` erhält das historische permissive Verhalten und protokolliert jede Trust-Entscheidung. `PluginTrustPolicy.REQUIRE_VERIFIED` blockiert unsigned, manipulierte, unbekannte oder widerrufene Artefakte.
- Das Gate materialisiert genau die zuvor gelesenen und verifizierten Bytes in ein privates Staging-Verzeichnis. So kann die Quelldatei nicht zwischen Prüfung und Classloading ausgetauscht werden.
- Die maßgebliche Artefakt-Evidence steht über `TrustedPluginRuntime.gateResult()` bereit. Die bisherigen Katalogfelder bleiben für Kompatibilität und nachgelagerte Plugin-Metadaten erhalten.
- Publisher-Key-Rotation, aktive/retired/revoked Keys sowie explizite Artefaktwiderrufe werden im versionierten Trust Store abgebildet.

Das Manifestformat, die Trust-Store-Struktur, Policies und Fail-closed-Status sind in [Kryptografische Plugin-Artefaktprüfung](plugin-artifact-trust.md) dokumentiert.

## Community und Autoren-Onboarding

- Der Katalog in `plugins list` und `GET /api/plugins` dient als lokaler Plugin-Index.
- `docs/plugin-api.md` enthält den Autorenleitfaden inklusive Metadaten, Abhängigkeiten und Service-Registrierung.
- Die Beispielplugins unter `de.regelsuche.plugin.example` dienen als Templates/Referenzprojekte für Community-Erweiterungen.

## Abgrenzung zu Core-Regelpaketen

Erstanbieter-Transformationen werden nicht als weiteres Plugin ausgeliefert, sondern als
Core-Regelpaket mit Tier. Damit wird eine Ablation als Profil-ID plus Manifest-Hash deklariert
statt über die Anwesenheit einer JAR-Datei in `plugins/`. Der Plugin-Pfad bleibt für fremden Code
mit eigenem ClassLoader und Artefaktprüfung reserviert. Siehe
[Regel-Tiers und Ablation](rule-tiers.md).

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
