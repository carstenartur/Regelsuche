# Regeldateien

Regeldateien verwenden eine kleine mathematische DSL statt JSON/YAML.

## Beispiel

```text
rule difference_of_squares:
  pattern: A^2 - B^2
  replace: (A - B) * (A + B)
  direction: forward
  priority: 5
  tags:
    - factorization
  explanation: "Erkennt die Differenz zweier Quadrate."
```

## Unterstützte Einträge

- `rule <id>:`
- `macro <id>:`
- `profile <id>:`

## Unterstützte Regel-Felder

- `pattern`
- `replace`
- `direction` (`forward`, `backward`, `both`)
- `priority` (Ganzzahl; höhere Werte werden als geringere Suchkosten registriert)
- `tags`
- `conditions`
- `difficulty`
- `explanation`

## Bedingungen

`conditions` ist eine Liste typisierter Schlüssel-Wert-Bedingungen. Jede Bedingung
verwendet die Form `<name>: <value>` und wird beim Laden validiert und als Metadatum
an der registrierten Regel gespeichert. Aktuell dienen diese Bedingungen der
Validierung, Dokumentation, Debug-Ausgabe und späteren spezialisierten Matchern; die
Standard-Pattern-Auswertung ignoriert unbekannte Bedingungen bewusst.

Beispiele:

```text
conditions:
  - A: expression
  - B: expression
  - commutative_addition: true
  - commutative_multiplication: true
```

## Unterstützte Makro-Felder

- `input`
- `output`
- `priority` (Ganzzahl; wird für die erzeugte Makro-Transformation übernommen)
- `tags`
- `difficulty`
- `explanation`

## Aktivierungsprofile

Profile aktivieren oder deaktivieren Regeln, Transformationen und Makros anhand ihrer `tags`
und können einzelne Regel-/Makro-IDs explizit erlauben oder sperren.

```text
profile school_algebra:
  enable_tags:
    - binomial
    - factorization
  disable_tags:
    - complex_analysis
  whitelist:
    - difference_of_squares
  blacklist:
    - unsafe_expand
```

- `enable_tags`: Whitelist. Ist sie nicht leer, bleibt ein Eintrag nur aktiv, wenn er
  mindestens einen dieser Tags trägt.
- `disable_tags`: Blacklist. Ein Eintrag mit einem dieser Tags wird immer deaktiviert.
- `whitelist`/`enable_rules`: explizite IDs, die trotz Tag-Filter aktiv bleiben.
- `blacklist`/`disable_rules`: explizite IDs, die immer deaktiviert werden. Bei
  Konflikten mit `whitelist` gewinnt die Blacklist.

Ein Profil wird über `PluginRuntimeConfig#activeProfile` bzw. `rules list --profile <id>`
aktiviert. Geladene Profile lassen sich mit `rules profiles` anzeigen; `rules profiles
--profile <id>` markiert zusätzlich das aktive Profil.

Beim Laden werden die DSL-Einträge in ein typisiertes internes Modell (`RuleFileParser`) überführt und anschließend als `PatternRewriteRule`/`RuleMacro`/`RuleProfile` registriert.

## Beispiel-Regelpakete

Im Verzeichnis `examples/` liegen ladbare Regelpakete für typische Schulmathematik-Domänen:

- `binomial-formulas.regelsuche` – binomische Formeln
- `factorization.regelsuche` – Ausklammern und Differenz zweier Quadrate
- `power-laws.regelsuche` – Potenzgesetze
- `trig-identities.regelsuche` – einfache trigonometrische Identitäten

Jedes Paket bringt ein passendes Aktivierungsprofil mit und wird durch Tests in
`RuleFileLoaderTest` auf fehlerfreies Laden geprüft.

## Import, Export und Debugging

- `rules import <datei-oder-verzeichnis>` kopiert `.regelsuche`- und `.rules`-Dateien in das Zielverzeichnis (standardmäßig `rules/`).
- `rules export --profile <id> [--dir <rules>] [--out <ziel>]` schreibt die aktuell aktiven Regeln als `.regelsuche`-Datei heraus.
- `rules debug <ausdruck> [--dir <rules>]` führt die pluginbewusste Regel-Engine im Debug-Modus aus und zeigt Regelversuche, erfolgreiche Anwendungen sowie Rejektionen durch Wachstums- oder Kandidatenlimits.
  Zusätzlich werden Laufzeitdiagnosen als Rejektionsgründe sichtbar: `DISABLED_BY_CONFIG`, `DISABLED_BY_PROFILE`, `CONDITION_FAILED` und `CYCLE_RISK` erscheinen mit Kontext in der Diagnose- und Attempt-Ausgabe.
