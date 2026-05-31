# Makros

Makros sind textbasierte Kurzformen für häufige Umformungen.

## Modell

Makros werden als `de.regelsuche.plugin.RuleMacro` registriert und über `MacroRegistry` sichtbar gemacht.

## Beispiel

```text
macro expand_square:
  input: (A + B)^2
  output: A^2 + 2*A*B + B^2
  tags:
    - macro
```

Makros werden aktuell geladen, gelistet und deaktivierbar verwaltet. Die Such- und UI-Integration kann darauf aufbauend in weiteren Phasen erweitert werden.
