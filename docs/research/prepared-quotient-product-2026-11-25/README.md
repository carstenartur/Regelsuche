# Vorbereiteter Fortsetzungsquotient im Produkt am 25. November 2026

`POST /api/search` bietet für Terme das optionale Profil
`PREPARED_QUOTIENT_DISCOVERY`. Die Antwort weist die gebundene Eingabe, die
normalisierten Annahmen, die gewählte Position, die Regelmenge, die Grenzen,
Sucharbeit und den Status der unabhängigen primitiven Nachrechnung aus.
Ein Budget lässt sich unter die Profilgrenzen absenken. Der leere Pfad
wählt den ganzen Term. Ein angegebener Pfad benötigt den beim Auswählen
angezeigten Formattertext.

```json
{
  "expression": "pair(x+0,x+0)",
  "profile": "PREPARED_QUOTIENT_DISCOVERY",
  "continuation": {
    "assumptions": ["x != 0"],
    "position": {"path": [1], "text": "x + 0"},
    "budget": {"maxVisitedExpressions": 40}
  }
}
```

Die Auswahl liefert `pair(x + 0, x)`. Der Parser bindet das zweite Funktionsargument
an seine Bereiche in der Anfrage. BestFirst verwendet die beim Parsen erzeugte
Wurzel und bereitet neue Syntax einmal pro Lauf auf. Nur Anbieter mit dem
expliziten zustandslokalen Vertrag gelangen in den vorbereiteten Quotienten.
Der Produktanbieter verwendet eigene exakte Musterregeln mit unverändertem
Platzhalterbestand und einen vorbereiteten Regelindex. Die Nachfolgerbindung
enthält exakte Syntax und normalisierte Annahmen. Weniger verbleibende Tiefe
oder Expansionsschritte darf einen besser ausgestatteten Pfad nicht verdrängen.
Der Referenzmatcher rechnet den gewählten Pfad mit der gebundenen Regelmenge
nach, bevor der Ersatz für das ursprüngliche Vorkommen ausgegeben wird.
Der Status beschreibt die konkrete begrenzte Suche. Die Ergebnisse werden
als Antwort des expliziten Profils ausgegeben. Die früheren Suchprofile und
der native Suchendpunkt behalten ihre bisherigen Aufrufverträge.

## Vergleich

Sechs vorab in der Testklasse festgelegte Quellterme liefen unter jeweils
denselben Grenzen (Tiefe 4, 300 Zustände, 4 Expansionen, 64 Kandidaten pro
Zustand), mit derselben Wertung, geordneten Regelmenge und unabhängigen
Nachrechnung. Jede Variante verwendet denselben indexierten AST-Anbieter.
Die Historienkontrolle und der frühere Quotient parsen den Ausdruck beim
Anbieteraufruf. Die vorbereitete Variante übernimmt den AST aus der
Identitätsberechnung der jeweiligen Suche. Alle drei Varianten verwenden
dieselbe syntaktische Reihenfolge bei Gleichstand. Die Messfälle geben
keinen Zielausdruck vor und verwenden leere Anfangsannahmen.

| Variante | Besuchte Labels | Anbieteraufrufe | Regelversuche | AST-Knotenbesuche beim Anbieter | Vorschläge | Nachfolgertreffer | Referenzaufrufe |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Anwendungshistorie | 78 | 50 | 647 | 477 | 107 | 0 | 7 |
| Früherer Quotient | 73 | 50 | 647 | 477 | 107 | 0 | 7 |
| Vorbereiteter Quotient | 73 | 50 | 647 | 477 | 107 | 0 | 7 |

Die drei Varianten liefern dieselben drei unabhängig nachgerechneten Ergebnisse.
Die drei übrigen Eingaben liefern jeweils keinen verbesserten Kandidaten.
Bei Quadrat und Distributivität sinkt die Zahl besuchter Labels von 27 auf
25 beziehungsweise von 28 auf 25. Anbieteraufrufe, Regelversuche und
Nachfolgertreffer bleiben in diesem Vergleich gleich. Ein separater
Fortsetzungstest erreicht bei einem späteren Pfad mit zusätzlichem
Expansionsbudget das Ziel und beobachtet einen echten Nachfolgertreffer.

| Variante | CPU-Zeit in ms | Wandzeit in ms | Allokierte Bytes |
| --- | ---: | ---: | ---: |
| Anwendungshistorie | 16,596 | 16,574 | 9.578.560 |
| Früherer Quotient | 16,340 | 16,417 | 9.173.024 |
| Vorbereiteter Quotient | 16,075 | 16,084 | 9.146.304 |

Die Tabelle summiert die Medianwerte pro Fall. Je Fall und Variante gab
es in einer JVM sechs Aufwärm- und 15 Messaufrufe mit rotierender
Variantenreihenfolge. `ThreadMXBean` erfasste CPU-Zeit und allokierte Bytes
auf dem ausführenden Thread. `System.nanoTime()` erfasste die Wandzeit.
Die Messung umfasst die Produktadapter-Methode mit Parsen, Projektion,
Suche, Nachrechnung und Antwortaufbau. HTTP-Transport und Einlesen der
Antwort folgen außerhalb der Zeitmessung. JaCoCo war für diesen Lauf
deaktiviert. Die Fallwerte schwanken in beide Richtungen. Vergleiche über
mehrere JVMs und andere Eingabefamilien sind offen.

[Vollständige Fallwerte](measurements.tsv) aus Temurin 25.0.5+8 auf Linux
x86-64 mit AMD EPYC 9V74. SHA-256 der Datei
`58e35899f6298e3bb1697e3989f34a3ea1f902007b3924b623d641cf1c1dbeaa`.

```sh
mvn -pl app -am -Dtest=PreparedQuotientProductComparisonTest \
  -Dregelsuche.preparedQuotient.measure=true -Djacoco.skip=true test
```

Die Fallwerte landen in `app/target/prepared-quotient-product-v1/measurements.tsv`.
Die API-Tests prüfen vertauschte gleichwertige Syntax, unterschiedliche
Annahmen, zwei gleiche Teilbäume an verschiedenen Pfaden, einen veralteten
Formatterstand und ein knappes Zustandsbudget.
