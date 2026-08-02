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

Die verbindliche Richtlinie für Plugin-, API- und Core-Versionen, unveränderliche
Artefaktversionen, Dependency-Constraints und Capability-Änderungen steht unter
[Plugin-Veröffentlichung, Kompatibilität und Governance](plugin-publishing-governance.md).

## Metadaten, Abhängigkeiten und Vertrauen

- `dependencies()` beschreibt Plugin-Abhängigkeiten einschließlich
  Version-Constraint und Kennzeichnung als optional oder erforderlich.
- `provenance()` beschreibt die vom Plugin gemeldete Herkunft, beispielsweise
  Release-URL, Repository oder Commit.
- `signature()` ist ein historischer Metadatenhinweis, den Plugin-Code selbst
  zurückliefert. Er ist keine kryptografische Sicherheitsgrenze, weil der
  Plugin-Code bereits geladen werden müsste, um ihn abzufragen.
- `signaturePresent`, `signatureVerified` und `trustedSource` im historischen
  `PluginRuntime`-Katalog bleiben aus Kompatibilitätsgründen erhalten. Sie sind
  nicht die maßgebliche Vorladeentscheidung für externe JARs.
- `TrustedPluginRuntime` und `PluginArtifactGate` prüfen externe JAR-Bytes vor
  der Codeausführung gegen Detached-Ed25519-Manifeste, Publisher-Trust,
  Key-/Artefaktwiderruf und eine explizite Trust Policy.
- `TrustedPluginRuntime.gateResult()` liefert die autoritative,
  artefaktbezogene Verification- und Admission-Evidence.
- `plugins list` sowie `GET /api/plugins` zeigen Katalog- und
  Kompatibilitätsinformationen. Eine erfolgreiche Kataloganzeige ist kein
  Ersatz für das Artefakt-Trust-Gate.

Details stehen in
[Kryptografische Plugin-Artefaktprüfung](plugin-artifact-trust.md),
[Plugin Artifact Index](plugin-artifact-index.md) und
[Authentisierte Plugin-Trust-State-Revisionen](plugin-trust-store-revisions.md).

Der gehostete Katalogtransport und ein atomarer Client-Lebenszyklus für
Download, Installation, Update, Entfernung und Rollback sind noch nicht
vollständig implementiert. Lokale kryptografische Prüfung darf deshalb nicht
als bereits verfügbare öffentliche End-to-End-Distribution beschrieben werden.

## Paketierung und Veröffentlichung

### Plugin als JAR paketieren

1. Implementiere `RegelsuchePlugin` in einem eigenen Maven- oder Gradle-Projekt.
2. Trage die Implementierung unter
   `src/main/resources/META-INF/services/de.regelsuche.plugin.RegelsuchePlugin`
   ein.
3. Baue und teste das Projekt aus einem frischen Checkout.
4. Erzeuge für externe Verteilung ein unveränderliches Release-Bundle mit
   Artefakthash, Detached Signature Manifest, Provenienz, Lizenz und SBOM.
5. Veröffentliche neue Bytes immer unter einer neuen Version; veröffentlichte
   Koordinate/Version/Hash-Zuordnungen werden nicht überschrieben.

Der vollständige Publishing-, Review-, Incident- und Revocation-Vertrag steht
unter [Plugin-Veröffentlichung, Kompatibilität und Governance](plugin-publishing-governance.md).

### Plugin lokal installieren

Solange der öffentliche Download- und Installationslebenszyklus nicht
implementiert ist, erfolgt die lokale Bereitstellung durch bewusstes Platzieren
des JARs und seiner Trust-Artefakte:

```text
plugins/
  mein-plugin-1.0.0.jar
  mein-plugin-1.0.0.jar.sig.json
  trust-store.json
```

Für externe JARs soll eine Installation mit `TrustedPluginRuntime` und einer
expliziten Policy wie `REQUIRE_VERIFIED` erfolgen. Ein bloßes Kopieren in das
Plugin-Verzeichnis und Laden über den historischen permissiven Runtime-Pfad ist
keine vertrauenswürdige Distribution.

Regelsuche erkennt lokale JARs beim Start beziehungsweise nach `plugins reload`.
Der aktuelle lokale Ablauf ist nicht mit einem implementierten atomaren
Download-, Update- oder Rollback-Client gleichzusetzen.

### Regelpaket veröffentlichen

Regelpakete sind Textdateien mit der Endung `.regelsuche` oder `.rules`. Sie
können als unveränderliches Release-Artefakt mit Provenienz, Lizenz,
Content-Hash und Indexeintrag veröffentlicht werden.

```text
app rules export --dir rules/ --out exports/
```

Die erzeugte Datei `exported-rules.regelsuche` enthält die aktiven Regeln und
lässt sich mit `rules import` in eine andere Installation übernehmen:

```text
app rules import exported-rules.regelsuche --into rules/
```

Import und lokale Auflösung sind nicht automatisch eine öffentliche,
authentisierte Verteilung. Publisher- und Curator-Identität sowie die exakten
Artefaktbytes müssen weiterhin über die vorgesehenen Trust- und Indexverträge
gebunden werden.

### Versionierung

- Plugins geben ihre Version über `version()` zurück; semantische Versionierung
  wird empfohlen.
- `apiVersion()` und `minimumCoreVersion()` steuern die
  Kompatibilitätsprüfung.
- Regelpakete werden über Artefaktkoordinate, Version und Content-Hash
  identifiziert; Dateiname allein genügt nicht.
- Korrigierte Artefaktbytes erhalten stets eine neue Version und Signatur.

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

```text
com.example.MyPlugin
```

Vollständiges integriertes Beispiel mit allen Erweiterungspunkten:

```text
app/src/main/java/de/regelsuche/plugin/example/BinomialFormulaPlugin.java
```

Für Issue #104 sind zusätzlich separat klon- und baubare Community-Beispiele
erforderlich, die gegen veröffentlichte API-Artefakte statt interne App-Pakete
bauen. Das integrierte Beispiel ersetzt diese noch ausstehenden externen
Referenzprojekte nicht.
