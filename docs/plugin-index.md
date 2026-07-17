# Plugin-Index

Dieser Index listet die mit Regelsuche mitgelieferten Plugins und
Regelpakete. Er dient als Ausgangspunkt für Community-Erweiterungen.

## Eingebaute Plugins

| ID | Name | Version | Fähigkeiten |
|----|------|---------|-------------|
| `algebra-core` | Algebra Core | 1.0.0 | rules, transformations |
| `factorization-pack` | Factorization Pack | 1.0.0 | transformations |
| `trigonometry-pack` | Trigonometry Pack | 1.0.0 | rules |
| `rational-functions-pack` | Rational Functions Pack | 1.0.0 | rules |
| `discovery-operators-pack` | Discovery Operators Pack | 1.0.0 | search-strategies, heuristics, examples |
| `binomial-formulas` | Binomial Formulas | 1.0.0 | rules, transformations, visitors, macros, search-strategies, heuristics, cost-functions, renderers, explanations, parser-extensions, examples |

Den aktuellen Katalog liefern:

```
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

Pakete lassen sich mit `rules import` übernehmen:

```
app rules import examples/binomial-formulas.regelsuche --into rules/
```

## Eigene Plugins hinzufügen

1. Implementiere `de.regelsuche.plugin.RegelsuchePlugin` (siehe [Plugin-API](plugin-api.md)).
2. Registriere deine Klasse in `META-INF/services/de.regelsuche.plugin.RegelsuchePlugin`.
3. Paketiere das Plugin als JAR und lege es in das Verzeichnis `plugins/`.
4. Für verifizierte Installation: veröffentliche `<plugin>.jar.sig.json` und lasse den Publisher-Key im lokalen Trust Store zu.

Details zu Metadaten, Abhängigkeiten, Vertrauensmodell und Regelpaketen stehen in
[plugin-api.md](plugin-api.md), [plugins.md](plugins.md) und
[Kryptografische Plugin-Artefaktprüfung](plugin-artifact-trust.md).

## Community-Erweiterungen

Externe Plugins sollten folgende Laufzeitmetadaten bereitstellen, damit sie im
lokalen Katalog nachvollziehbar erscheinen:

- `id()` — eindeutiger Bezeichner (z. B. `mein-plugin`)
- `version()` — semantische Versionsnummer (z. B. `1.0.0`)
- `provenance()` — Release-URL oder Repository-Link
- `signature()` — optionaler beschreibender Hinweis; **keine** Ladeautorisierung
- `dependencies()` — Liste der Plugin-Abhängigkeiten

Für kryptografisches Vertrauen sind stattdessen erforderlich:

- unveränderlicher `sha256:`-Hash des veröffentlichten JARs;
- Detached Ed25519-Manifest `regelsuche.plugin-signature/v1`;
- Publisher- und Key-ID;
- ein zugelassener, nicht widerrufener öffentlicher Schlüssel im Trust Store;
- eine explizite lokale Policy (`WARN` oder `REQUIRE_VERIFIED`).

Ein minimales Starter-Template findet sich am Ende von [plugin-api.md](plugin-api.md).
