# Makros

Makros in Regeldateien sind **vom Autor deklarierte** Kurzformen für häufige
Umformungen. Sie sind von den aus realen Suchpfaden gelernten und erst nach
Evidenz-/Promotion-Gates aktivierten Makroregeln zu unterscheiden.

## Modell

Deklarative Makros werden als `de.regelsuche.plugin.RuleMacro` registriert und
über `MacroRegistry` sichtbar gemacht.

## Beispiel

```text
macro expand_square:
  input: (A + B)^2
  output: A^2 + 2*A*B + B^2
  tags:
    - macro
```

Aktivierte Makros werden als einstufige Transformationskanten (`macro.<id>`) in
den Suchgraphen eingebunden und können wie reguläre Umformungen angewendet
werden. Ein über `--disable-rule <id>` oder eine Laufzeitkonfiguration
deaktiviertes Makro erzeugt keine Kante.

Makros erscheinen in `rules debug` als normale Regelversuche. Beim `rules export`
werden sie nur berücksichtigt, wenn ihre erzeugten Transformationskanten aktiv
sind. Profilfilter und deaktivierte Makros bleiben dadurch in Debug- und
Paket-Workflows konsistent sichtbar.

## Abgrenzung zu gelernten Makros

Ein deklaratives Regeldatei-Makro wird unmittelbar vom Autor vorgegeben. Ein
gelerntes Makro entsteht dagegen aus reproduzierbaren Suchpfaden und darf erst
nach Generalisierung, Holdouts, Gegenbeispielsuche, Novelty, Proof- und
Promotion-Gates in das wiederverwendbare Inventar gelangen.

Siehe:

- [Regeldateien](rule-files.md) für Syntax und Aktivierungsprofile,
- [Makroregeln und emergente Identitäten](macro-rules.md) für gelerntes Wissen,
- [Erweiterungssystem](extension-system.md) für die gemeinsame Architekturkarte.
