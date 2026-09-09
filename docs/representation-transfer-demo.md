# Darstellungs- und Transferdemo

Nach dem Start der Workbench mit `bin/regelsuche serve --port 8080` öffnet
`http://localhost:8080/static/strategy-demo.html` den vollständigen Ablauf.
Auch die Startseite verlinkt die Demo.

1. **Wege vergleichen:** Eine Aufgabe mit vier unabhängigen Rekurrenzblöcken
   wird direkt und mit automatischer Darstellung gelöst. Beim gewählten
   Anschauungsbudget von 1.100 Einheiten bleibt die direkte Konstruktion
   unentschieden; die Blockroute benötigt 1.082. Das zweite, unabhängig
   ausgeführte Prüfverfahren benötigt zusätzlich 2.922 Einheiten. Dieses
   Beispielbudget ist nach Messung gewählt und kein blinder Benchmark.
2. **Training und Transfer ausführen:** Der Server führt den
   [öffentlichen Vergleich](representation-strategy-transfer.md) aus, friert
   seine Policy ein und zeigt sämtliche Trainings- und Auswertungsversuche.
   Die Tabelle enthält Lern- und Prüfkosten. Der aktuelle Satz zeigt keinen
   zusätzlichen Vorteil des Lernens gegenüber der festen Auswahlregel.
3. **Nachweis exportieren und erneut prüfen:** Ein gespeichertes Lösungsartefakt
   lässt sich nach einem Serverneustart vollständig wiederholen. Veränderte
   Quellen, Budgets, Entscheidungen, Schritte oder Ergebnisse werden abgelehnt.

Eigene rationale lineare Gleichungen können in das Eingabefeld geschrieben
werden. Nichtlineare, nicht unterstützte oder erschöpfte Versuche erhalten
einen ausdrücklichen Status und ihre verbrauchte Arbeit; sie werden nicht
als verifizierte Lösungen dargestellt.

HTTP und Java-CLI verwenden denselben versionierten Vertrag:

```sh
bin/regelsuche representations solve docs/examples/linear-solve.json
bin/regelsuche representations verify-solution docs/examples/linear-solution.json
```

`POST /api/representations/solve` berechnet ein Lösungsartefakt,
`POST /api/representations/solve/replay` prüft es vollständig erneut und
`GET /api/representations/study` liefert den aktuellen eingefrorenen Bericht.
Die Workbench stellt den JSON-Export direkt zum Download bereit.

Der [Python-Client](python-client.md) bietet zusätzlich einen unabhängigen
Prüfer mit Standardbibliothek und `fractions.Fraction`:

```sh
python -m regelsuche verify linear-solution.json
python -m regelsuche verify representation-transfer-study.json
```

Dieser Prüfer rekonstruiert die vollständige kanonische affine Lösungsmenge
aus den ursprünglichen Gleichungen, einschließlich freier Parameter und
Widersprüche. Er vertraut weder einem Server-Prüfflag noch dessen
Arbeitseinheiten. Sein Ergebnis bestätigt ausschließlich die Mathematik,
keine Lernherkunft und keine Laufzeitangabe. Dafür gibt es das vollständige
Java-Replay beziehungsweise die reproduzierte Studie mit Manifest. Es wird
kein Lean-Kernbeweis behauptet. Der Prüfer hat ausdrücklich begrenzte
Eingabegrößen und eine Schranke von 4.096 Bits für rationale Werte.
