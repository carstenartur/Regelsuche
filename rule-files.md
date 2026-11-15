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

Mit `RuleFileParser` aus der Plugin-API lassen sich die DSL-Einträge als
typisierte Daten lesen. Die Laufzeit registriert sie als `PatternRewriteRule`,
`RuleMacro` oder `RuleProfile`.

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

### Portables Faktorisierungspaket mit 0.5.2

Kopiere [factorization.regelsuche](../examples/factorization.regelsuche) und
[factorization-export-profile.rules](../examples/factorization-export-profile.rules)
in ein leeres Arbeitsverzeichnis. Mit Java 25 und der entpackten
[Anwendungsdistribution 0.5.2](https://github.com/carstenartur/Regelsuche/releases/download/v0.5.2/regelsuche-0.5.2.zip)
lässt sich das Paket über die veröffentlichte CLI verwenden.

```bash
CLI=/absoluter/pfad/regelsuche-0.5.2/bin/regelsuche
sh "$CLI" rules validate factorization.regelsuche
sh "$CLI" rules import factorization.regelsuche --into rules
cmp factorization.regelsuche rules/factorization.regelsuche
sh "$CLI" rules profiles --dir rules --profile factorization_basics

sh "$CLI" rules import factorization-export-profile.rules --into rules
sh "$CLI" rules export --dir rules --profile factorization_export --out exports
EXPORT=exports/exported-rules-factorization_export.regelsuche
sh "$CLI" rules validate "$EXPORT"
sh "$CLI" rules import "$EXPORT" --into roundtrip
cmp "$EXPORT" roundtrip/exported-rules-factorization_export.regelsuche
sh "$CLI" rules list --dir roundtrip
sh "$CLI" rules debug '2*x+3*x' --dir roundtrip
```

Das Exportprofil hält die zwei ausgewählten IDs samt ihren Vorwärts- und
Rückwärtsrichtungen aktiv. Das Tag `community-factorization-template` begrenzt
den Tag-Filter, und die Whitelist nimmt genau die beiden IDs auf. Das breitere
Profil `factorization_basics` lässt auch eingebaute Regeln mit passenden Tags zu.
Ein solcher Export kann beim Laden mit gleichnamigen eingebauten Regeln
kollidieren.

Im geprüften Linux-Lauf mit Java 25.0.5 und dem Anwendungs-ZIP mit SHA-256
`fbe150fe751177d026d1a08d79ce5a81abbfb00e639e41bbe9a60a573d303cfc`
meldete `rules list --dir roundtrip` alle vier Richtungen als aktiv aus der
importierten Exportdatei. `rules debug` wendete `extract_common_factor.forward`
auf `2*x+3*x` an. Der Export hatte SHA-256
`25cbdf54786ca822a24820b9936ee6465b7c0b98307f05017e13e327ae16daa4`.
Die [SDK-Qualifikation](released-sdk-maven-qualification.md#data-only-rule-package)
prüft die Formatvorlagen mit der veröffentlichten Plugin-API.
