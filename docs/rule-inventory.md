# Regelinventar

`RuleInventoryRepository` speichert wiederverwendbare Regeln (`ReusableRule`). Implementierungen: `InMemoryRuleInventoryRepository`, `Neo4jRuleInventoryRepository`.

## Aktivierung

`InventoryBackedRewriteRuleProvider` aktiviert Regeln nur, wenn alle Bedingungen erfüllt sind:

- Inventar aktiviert (`RuleInventoryConfiguration.enabled()`).
- Beweis-Status ≥ `minProofStatus`.
- Regel-ID erlaubt (allow/deny/disabled-Listen + Repository-Enable-Flag).
- Linkes ≠ rechtes Pattern, positives mittleres Improvement.
- Geschätzter Komplexitätsanstieg ≤ Limit.

Aktivierungs- und Ablehnungsgründe stehen über `lastDecisions()` als `RuleActivationDecision`-Liste zur Verfügung.

## CLI

```
inventory list                    # alle Regeln mit Status, Usage, enabled/disabled, Tags
inventory enable <ruleId>         # aktiviert eine Regel
inventory disable <ruleId>        # deaktiviert eine Regel
inventory tag <ruleId> <tag>      # versieht Regel mit Domain-Tag
inventory import <file.json>      # importiert Export-Bundle in das Inventar
inventory export --format json    # schreibt Inventar nach exports/
```

Beim Import werden Regeln per kanonischem Hash dedupliziert (`InMemoryRuleInventoryRepository#save`).
