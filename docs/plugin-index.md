# Plugin-Index

Dieser Index listet die mit Regelsuche mitgelieferten Plugins und
Regelpakete. Er dient als Ausgangspunkt für Community-Erweiterungen.

## Eingebaute Plugins

| ID | Name | Version | Fähigkeiten |
|----|------|---------|-------------|
| `algebra-core` | Algebra Core | 1.0.0 | rules, transformations |
| `factorization-pack` | Factorization | 1.0.0 | rules, transformations |
| `trigonometry-pack` | Trigonometry | 1.0.0 | rules |
| `rational-functions-pack` | Rational Functions | 1.0.0 | rules |
| `discovery-operators-pack` | Discovery Operators | 1.0.0 | rules, transformations, search-strategies, heuristics, examples |
| `binomial-formulas` | Binomial Formulas | 1.0.0 | rules, transformations, ast-visitors, macros, search-strategies, heuristics, cost-functions, renderers, explanations, parser-extensions, examples |

Den aktuellen Katalog liefern:

```
app plugins list
GET /api/plugins
```

## Eingebaute Regelpakete

Die folgenden `.regelsuche`-Dateien liegen im Verzeichnis `examples/`:

| Datei | Inhalt |
|-------|--------|
| `algebra/basic-algebra.regelsuche` | Grundlegende Algebra-Regeln |
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

Details zu Metadaten, Abhängigkeiten, Vertrauensmodell und Regelpaketen stehen in
[plugin-api.md](plugin-api.md) und [plugins.md](plugins.md).

## Community-Erweiterungen

Externe Plugins tragen sich am besten mit folgenden Metadaten aus, damit sie
im Katalog sichtbar und vertrauenswürdig erscheinen:

- `id()` — eindeutiger Bezeichner (z. B. `mein-plugin`)
- `version()` — semantische Versionsnummer (z. B. `1.0.0`)
- `provenance()` — Release-URL oder Repository-Link
- `signature()` — optionaler Sigstore-/Commit-Fingerabdruck
- `dependencies()` — Liste der Plugin-Abhängigkeiten

Ein minimales Starter-Template findet sich am Ende von [plugin-api.md](plugin-api.md).
