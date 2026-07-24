# Plugin-Index

Diese Seite listet die mit Regelsuche mitgelieferten Plugins und Regelpakete und
ordnet die beiden unterschiedlichen Katalogrollen ein.

## Lokaler Laufzeitkatalog

Der lokale Katalog zeigt, was in einer konkreten Installation bereits geladen
oder als Regeldatei vorhanden ist.

### Eingebaute Plugins

| ID | Name | Version | Fähigkeiten |
|----|------|---------|-------------|
| `algebra-core` | Algebra Core | 1.0.0 | rules, transformations |
| `factorization-pack` | Factorization Pack | 1.0.0 | transformations |
| `trigonometry-pack` | Trigonometry Pack | 1.0.0 | rules |
| `rational-functions-pack` | Rational Functions Pack | 1.0.0 | rules |
| `discovery-operators-pack` | Discovery Operators Pack | 1.0.0 | search-strategies, heuristics, examples |
| `binomial-formulas` | Binomial Formulas | 1.0.0 | rules, transformations, visitors, macros, search-strategies, heuristics, cost-functions, renderers, explanations, parser-extensions, examples |

Den aktuellen Laufzeitkatalog liefern:

```text
app plugins list
GET /api/plugins
```

## Eingebaute Regelpakete

Die folgenden `.regelsuche`-Dateien liegen im Verzeichnis `examples/`:

| Datei | Inhalt |
|-------|--------|
| `binomial-formulas.regelsuche` | Binomische Formeln |
| `factorization.regelsuche` | Faktorisierungsregeln |
| `power-laws.regelsuche` | Potenzgesetze |
| `trig-identities.regelsuche` | Trigonometrische Identitäten |

Pakete lassen sich lokal mit `rules import` übernehmen:

```text
app rules import examples/binomial-formulas.regelsuche --into rules/
```

## Unveränderlicher Veröffentlichungsindex

Der neue Vertrag `regelsuche.plugin-artifact-index/v1` beschreibt dagegen
**veröffentlichte**, content-addressed Artefakte. Er unterscheidet Java-Plugins,
Regelpakete und Knowledge Packs und bindet pro Version:

- API-/Core-Kompatibilität und Capabilities;
- erforderliche und optionale Abhängigkeiten;
- Dateiname, SHA-256 und Distribution;
- Publisher, Signaturmanifest und Source-Provenance;
- eine unveränderliche Entry- und Indexidentität.

`PluginArtifactResolver` kann daraus eine exakte oder latest-compatible
Abhängigkeitsauflösung erzeugen. Diese Auflösung ist noch kein Download und
keine Installation. Details stehen unter
[Unveränderlicher Plugin- und Paketindex](plugin-artifact-index.md).

## Eigene Plugins hinzufügen

1. Implementiere `de.regelsuche.plugin.RegelsuchePlugin` (siehe [Plugin-API](plugin-api.md)).
2. Registriere deine Klasse in `META-INF/services/de.regelsuche.plugin.RegelsuchePlugin`.
3. Paketiere das Plugin als JAR.
4. Erzeuge einen unveränderlichen SHA-256 und eine Source-/Release-Provenance.
5. Veröffentliche `<plugin>.jar.sig.json` und lasse den Publisher-Key im lokalen Trust Store zu.
6. Beschreibe die Version in einer unveränderlichen Indexrevision.

Details zu Metadaten, Abhängigkeiten, Vertrauensmodell und Regelpaketen stehen in
[plugin-api.md](plugin-api.md), [plugins.md](plugins.md),
[Unveränderlicher Plugin- und Paketindex](plugin-artifact-index.md) und
[Kryptografische Plugin-Artefaktprüfung](plugin-artifact-trust.md).

## Community-Erweiterungen

Externe Plugins sollten folgende Laufzeitmetadaten bereitstellen, damit sie im
lokalen Katalog nachvollziehbar erscheinen:

- `id()` — eindeutiger Bezeichner;
- `version()` — semantische Versionsnummer;
- `provenance()` — beschreibende Release- oder Repository-Referenz;
- `signature()` — optionaler beschreibender Hinweis; **keine** Ladeautorisierung;
- `dependencies()` — Laufzeitansicht der Plugin-Abhängigkeiten.

Für kryptografisches Vertrauen sind stattdessen erforderlich:

- unveränderlicher `sha256:`-Hash des veröffentlichten JARs;
- Detached Ed25519-Manifest `regelsuche.plugin-signature/v1`;
- Publisher- und Key-ID;
- ein zugelassener, nicht widerrufener öffentlicher Schlüssel im Trust Store;
- eine explizite lokale Policy (`WARN` oder `REQUIRE_VERIFIED`).

Ein minimales Starter-Template findet sich am Ende von [plugin-api.md](plugin-api.md).
