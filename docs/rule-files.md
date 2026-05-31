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

## Unterstützte Makro-Felder

- `input`
- `output`
- `priority` (Ganzzahl; wird für die erzeugte Makro-Transformation übernommen)
- `tags`
- `difficulty`
- `explanation`

## Aktivierungsprofile

Profile aktivieren oder deaktivieren Regeln, Transformationen und Makros anhand ihrer `tags`.

```text
profile school_algebra:
  enable_tags:
    - binomial
    - factorization
  disable_tags:
    - complex_analysis
```

- `enable_tags`: Whitelist. Ist sie nicht leer, bleibt ein Eintrag nur aktiv, wenn er
  mindestens einen dieser Tags trägt.
- `disable_tags`: Blacklist. Ein Eintrag mit einem dieser Tags wird immer deaktiviert.

Ein Profil wird über `PluginRuntimeConfig#activeProfile` bzw. `rules list --profile <id>`
aktiviert. Geladene Profile lassen sich mit `rules profiles` anzeigen.

Beim Laden werden die DSL-Einträge in ein typisiertes internes Modell (`RuleFileParser`) überführt und anschließend als `PatternRewriteRule`/`RuleMacro`/`RuleProfile` registriert.

## Beispiel-Regelpakete

Im Verzeichnis `examples/` liegen ladbare Regelpakete für typische Schulmathematik-Domänen:

- `binomial-formulas.regelsuche` – binomische Formeln
- `factorization.regelsuche` – Ausklammern und Differenz zweier Quadrate
- `power-laws.regelsuche` – Potenzgesetze
- `trig-identities.regelsuche` – einfache trigonometrische Identitäten

Jedes Paket bringt ein passendes Aktivierungsprofil mit und wird durch Tests in
`RuleFileLoaderTest` auf fehlerfreies Laden geprüft.
