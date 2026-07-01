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
