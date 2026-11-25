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
Der Anbieter erhält die Gruppierung in der Syntax der Nachfolger. Der
Referenzmatcher beginnt beim ausgewählten Parserbaum und erzeugt an jedem
primitiven Schritt den tatsächlichen Zielbaum. Die Syntax des gespeicherten
Pfads muss beim erneuten Parsen diesen Baum ergeben. Auch die vollständige
Ausgabe wird gegen den rekonstruierten Ersatzbaum geprüft. Bei einer
abweichenden Formatierung setzt der Adapter den geklammerten Ersatz direkt
in den Parser-Quellbereich ein und prüft das Ergebnis erneut. `STATE_BUDGET` bedeutet,
dass beim Erreichen der Zustandsgrenze noch Einträge vorgemerkt waren.
`EXHAUSTED` bezeichnet eine abgearbeitete Suche ohne Verbesserung. Die Ergebnisse werden
als Antwort des expliziten Profils ausgegeben. Die früheren Suchprofile und
der native Suchendpunkt behalten ihre bisherigen Aufrufverträge.

## Vergleich vor dem typisierten Replay (v1)

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
| Anwendungshistorie | 16,730 | 16,733 | 9.613.136 |
| Früherer Quotient | 16,044 | 16,197 | 9.200.056 |
| Vorbereiteter Quotient | 16,440 | 16,492 | 9.173.336 |

Die Tabelle summiert die Medianwerte pro Fall. Je Fall und Variante gab
es in einer JVM sechs Aufwärm- und 15 Messaufrufe mit rotierender
Variantenreihenfolge. `ThreadMXBean` erfasste CPU-Zeit und allokierte Bytes
auf dem ausführenden Thread. `System.nanoTime()` erfasste die Wandzeit.
Die Messung umfasst die Produktadapter-Methode mit Parsen, Projektion,
Suche, Nachrechnung, Prüfung des vollständigen Ausgabetexts und Antwortaufbau. HTTP-Transport und Einlesen der
Antwort folgen außerhalb der Zeitmessung. JaCoCo war für diesen Lauf
deaktiviert. Die Fallwerte schwanken in beide Richtungen. Vergleiche über
mehrere JVMs und andere Eingabefamilien sind offen.

[Vollständige Fallwerte](measurements.tsv) aus Temurin 25.0.5+8 auf Linux
x86-64 mit AMD EPYC 9V74. SHA-256 der Datei
`7bfe63faaabbf1463c7a8ede232cad7af02aa1175648c17047e15097be173761`.

## Vergleich mit typisiertem Replay (v2)

Die drei Varianten liefen erneut mit denselben sechs Eingaben, Grenzen und
Messaufrufen. Jede Variante verwendet denselben Anbieter mit erhaltener
Gruppierung und dieselbe unabhängige Rekonstruktion auf dem AST. Die drei
nachgerechneten Ergebnisse, die drei Nullresultate und sämtliche obigen
Arbeitszählungen stimmen mit v1 überein. Die Gruppierungsregressionen prüfen
zusätzlich eine Wurzelauswahl und zwei Schritte in einem ausgewählten
Teilbaum mit geschachtelter Addition, Subtraktion, Multiplikation und Division.

| Variante | CPU-Zeit in ms | Wandzeit in ms | Allokierte Bytes |
| --- | ---: | ---: | ---: |
| Anwendungshistorie | 16,918 | 16,949 | 9.635.720 |
| Früherer Quotient | 16,134 | 16,158 | 9.233.272 |
| Vorbereiteter Quotient | 16,516 | 16,570 | 9.207.816 |

[Fallwerte mit typisiertem Replay](measurements-typed-replay.tsv), SHA-256
`9cb46b2e719304a402254905190885bbd8308567e86fca11090624390b62208b`.
Die Messung lief mit Temurin 25.0.5+8 auf Linux x86-64 und AMD EPYC 9V74
aus dem Git-Quellbaum `c34b0170e1fec0e41bdda26ed30e2aef4a837dae` des
Messcommits `f52d5d7084da3a5a825dc82dea80a83f33b9c742`. Die Tabellen zeigen
jeweils die Summe der Fallmediane. Pro Laufzeitmessung wurde eine JVM
verwendet. Die einzelnen Fallwerte schwanken weiterhin in beide Richtungen.

```sh
mvn -pl app -am -Dtest=PreparedQuotientProductComparisonTest \
  -Dregelsuche.preparedQuotient.measure=true -Djacoco.skip=true test
```

Die Fallwerte landen in `app/target/prepared-quotient-product-v2/measurements-typed-replay.tsv`.
Die API-Tests prüfen vertauschte gleichwertige Syntax, unterschiedliche
Annahmen, zwei gleiche Teilbäume an verschiedenen Pfaden, einen veralteten
Formatterstand, einen rechten gruppierten Nachbarterm und beide Stopps an
einer Zustandsgrenze von eins.
