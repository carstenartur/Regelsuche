# Regeldateien

Regeldateien verwenden eine kleine mathematische DSL statt JSON/YAML.

## Beispiel

```text
rule difference_of_squares:
  pattern: A^2 - B^2
  replace: (A - B) * (A + B)
  direction: forward
  tags:
    - factorization
  explanation: "Erkennt die Differenz zweier Quadrate."
```

## Unterstützte Einträge

- `rule <id>:`
- `macro <id>:`

## Unterstützte Regel-Felder

- `pattern`
- `replace`
- `direction` (`forward`, `backward`, `both`)
- `tags`
- `conditions`
- `difficulty`
- `explanation`

## Unterstützte Makro-Felder

- `input`
- `output`
- `tags`
- `explanation`

Beim Laden werden die DSL-Einträge in ein typisiertes internes Modell (`RuleFileParser`) überführt und anschließend als `PatternRewriteRule`/`RuleMacro` registriert.
