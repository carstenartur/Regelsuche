# Plugin-API

## Einstieg

```java
public final class MyPlugin implements RegelsuchePlugin {
    public String id() { return "my-plugin"; }
    public String name() { return "My Plugin"; }
    public String version() { return "1.0.0"; }
    public String apiVersion() { return "1"; }
    public String minimumCoreVersion() { return "1.0.0"; }
    public Set<String> capabilities() { return Set.of("rules"); }
    public List<PluginDependency> dependencies() {
        return List.of(new PluginDependency("algebra-core", ">=1.0.0", false));
    }
    public String provenance() { return "https://github.com/org/repo/releases/tag/v1.0.0"; }
    public String signature() { return "sigstore:sha256:..."; }
}
```

Registrierung über `META-INF/services/de.regelsuche.plugin.RegelsuchePlugin`.

## Erweiterungspunkte

### Regeln

`RuleRegistry` nimmt direkte `RewriteRule`-Beiträge auf.

### Transformationen

`TransformationRegistry` nimmt `PatternTransformation`-Beiträge auf. Diese erscheinen als normale Kanten im Suchgraphen.

### AST-Visitor

`AstVisitorRegistry` unterstützt Hook-Phasen:

- `AFTER_PARSE`
- `BEFORE_NORMALIZATION`
- `AFTER_NORMALIZATION`
- `BEFORE_SEARCH`
- `DURING_SEARCH`
- `AFTER_TRANSFORMATION`
- `BEFORE_OUTPUT`
- `EXPLAIN_PATH`

`PluginAwareAstRewriteTransformationEngine` ruft alle Phasen während eines
Transformationsdurchlaufs auf. Visitor können Diagnosen, Marker und knotengebundene
Metadaten über `AstVisitorContext` ablegen.

### Makros

`MacroRegistry` nimmt `RuleMacro`-Beiträge auf.

### Laufzeit-Erweiterungspunkte

Plugins können weitere steuerbare Erweiterungen registrieren:

- `SearchStrategyRegistry` für benannte Suchstrategien
- `HeuristicRegistry` für heuristische Bewertungen
- `CostFunctionRegistry` für Kostenfunktionen auf Transformationen
- `RendererRegistry` für Ausgabe-Renderer
- `ExplanationRegistry` für regelbezogene Erklärungen
- `ParserExtensionRegistry` für benannte Parser-Erweiterungen (Registry-only; der
  Aufrufer muss `supports()`/`normalize()` aus den aktivierten Erweiterungen selbst
  aufrufen – die Erweiterungen werden nicht automatisch in den Parsing-Pfad eingehängt)
- `ExampleRegistry` für Beispielpakete

Alle diese Registries unterstützen `register`, `disable`, `registrations` und
`enabledExtensions`, sodass Laufzeitkonfigurationen Beiträge gezielt abschalten
und UI/CLI-Komponenten nur aktive Erweiterungen verwenden können.

## Beispielplugin

`BinomialFormulaPlugin` demonstriert:

- Registry-Erweiterung
- AST-Visitor für Binomialmuster
- Transformationen für binomische Formeln
- Makro-Registrierung
- Laufzeit-Erweiterungen für Heuristik, Kosten, Renderer, Erklärungen, Parser-Normalisierung und Beispiele

## Kompatibilität

`RegelsuchePlugin` bietet standardmäßig `apiVersion()`, `minimumCoreVersion()` und `capabilities()`. `PluginCompatibilityChecker` prüft diese Angaben beim Laden eines Plugins und meldet Inkompatibilitäten als Laufzeitdiagnosen, statt das Plugin stillschweigend zu registrieren.

## Metadaten, Abhängigkeiten und Vertrauen

- `dependencies()` beschreibt Plugin-Abhängigkeiten inkl. Version-Constraint und optional/required.
- `provenance()` beschreibt Herkunft (z. B. Release-URL, Registry-Referenz, Commit).
- `signature()` erlaubt das Hinterlegen von Signatur-Metadaten (z. B. Referenz/Identifier).
- `signaturePresent` bedeutet nur, dass `signature()` einen nicht-leeren Wert liefert.
- `signatureVerified` bleibt aktuell `false`; eine kryptografische Verifikation externer Plugin-Artefakte ist derzeit nicht implementiert.
- `trustedSource` ist aktuell nur für Classpath-/Built-in-Plugins `true`; externe Quellen bleiben ohne Verifizierer/Allowlist untrusted.
- `plugins list` sowie `GET /api/plugins` zeigen diese Felder als Plugin-Katalog inklusive Vertrauenswarnungen.

Damit sind Import/Export-Workflows (`rules import`/`rules export`) und Drittanbieter-Pakete transparent dokumentiert und prüfbar.

## Paketierung und Veröffentlichung

### Plugin als JAR veröffentlichen

1. Implementiere `RegelsuchePlugin` in einem eigenen Maven/Gradle-Projekt.
2. Lege die Klasse in `src/main/resources/META-INF/services/de.regelsuche.plugin.RegelsuchePlugin` an.
3. Baue das Projekt (`./gradlew jar` oder `mvn package`).
4. Veröffentliche das JAR, z. B. auf Maven Central, GitHub Packages oder einem privaten Repository.

### Plugin installieren

Lege das fertige JAR in das Verzeichnis `plugins/` neben der Regelsuche-Installation:

```
plugins/
  mein-plugin-1.0.0.jar
```

Regelsuche lädt alle JARs beim Start oder nach `plugins reload` über `URLClassLoader` und `ServiceLoader`.

### Regelpaket veröffentlichen

Regelpakete sind einfache Textdateien mit der Endung `.regelsuche` oder `.rules`.
Sie können als Artefakt zu einem GitHub-Release oder einer Registry hochgeladen werden.

```
app rules export --dir rules/ --out exports/
```

Die erzeugte Datei `exported-rules.regelsuche` enthält alle aktiven Regeln und
lässt sich direkt mit `rules import` in eine andere Installation übernehmen:

```
app rules import exported-rules.regelsuche --into rules/
```

### Versionierung

- Plugins geben ihre Version über `version()` zurück (semantische Versionierung empfohlen).
- `apiVersion()` und `minimumCoreVersion()` steuern die Kompatibilitätsprüfung.
- Regelpakete werden über Dateiname und Hash in der Reload-Diff sichtbar gemacht.

## Starter-Template

Minimales Plugin als Ausgangspunkt für eigene Erweiterungen:

```java
package com.example;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.plugin.RegelsuchePlugin;
import de.regelsuche.plugin.RuleRegistry;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import java.util.Set;

public final class MyPlugin implements RegelsuchePlugin {
    private static final PatternExpr A = PatternExpr.var("A");

    @Override public String id() { return "my-plugin"; }
    @Override public String name() { return "My Plugin"; }
    @Override public String version() { return "1.0.0"; }
    @Override public String apiVersion() { return "1"; }
    @Override public String minimumCoreVersion() { return "1.0.0"; }
    @Override public Set<String> capabilities() { return Set.of("rules"); }
    @Override public String provenance() { return "https://github.com/your-org/my-plugin/releases/tag/v1.0.0"; }

    @Override
    public void registerRules(RuleRegistry registry) {
        registry.register(new PatternRewriteRule(
            "my_rule_id",
            PatternExpr.op(BinaryOperator.ADD, A, PatternExpr.num(0)),
            A,
            RewriteKind.SIMPLIFY,
            false,
            -1,
            true
        ), id(), "Removes neutral addition.", List.of("my-domain", "simplify"));
    }
}
```

`META-INF/services/de.regelsuche.plugin.RegelsuchePlugin`:
```
com.example.MyPlugin
```

Vollständige Beispiele mit allen Erweiterungspunkten:
`app/src/main/java/de/regelsuche/plugin/example/BinomialFormulaPlugin.java`
