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

Makros werden geladen, gelistet und deaktivierbar verwaltet. Aktivierte Makros werden zusätzlich als einstufige Transformationskanten (`macro.<id>`) in den Suchgraphen eingebunden, sodass sie wie reguläre Umformungen während der Suche angewendet werden. Ein über `--disable-rule <id>` oder die Konfiguration deaktiviertes Makro erzeugt keine solche Kante.


Makros erscheinen auch in `rules debug` als normale Regelversuche und werden beim `rules export` nur dann berücksichtigt, wenn ihre daraus erzeugten Transformationskanten aktiv sind. So bleiben Profilfilter und deaktivierte Makros in Debug- und Paket-Workflows konsistent sichtbar.
