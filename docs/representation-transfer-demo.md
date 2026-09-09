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
   Die Tabelle trennt wiederkehrende Anwendungsarbeit von den einmaligen
   Trainingskosten. Gegen die statische DIRECT-Strategie reduziert die gelernte
   Auswahl die Anwendungsarbeit im aktuellen Entwicklungssatz um 4.964 Einheiten
   beziehungsweise 5,94 %. Bei derselben mittleren Einsparung amortisiert sich
   der einmalige Lernaufwand rechnerisch nach etwa 108 Anwendungen. FIXED_AUTO
   ist dagegen eine handgeschriebene Expertenreferenz, die bereits dieselbe
   Schwelle 8 verwendet; sie ist keine Kontrolle „ohne Lernen“. Die Diagnose
   weist den verbleibenden Auswahlspielraum und den Aufwand des vollständigen
   zweiten Lösungsverfahrens gesondert aus.
3. **Nachweis exportieren und erneut prüfen:** Ein gespeichertes Lösungsartefakt
   lässt sich nach einem Serverneustart vollständig wiederholen. Veränderte
   Quellen, Budgets, Entscheidungen, Schritte oder Ergebnisse werden abgelehnt.

Dieser Darstellungsversuch lernt nur eine Auswahlbedingung zwischen bereits
vorhandenen Lösungswegen. Er testet **nicht**, ob das Erlernen mathematischer
Regeln die Fähigkeiten von Regelsuche erweitert. Für diese andere Frage enthält
das Projekt die [generationenübergreifende Regelgewinnung](generational-rule-mining.md):
Deren Reachability-Test verlangt einen Fall, den das Basisinventar unter dem
vorgegebenen Budget nicht erreicht, der aber mit dem angesammelten gelernten
Regelwissen erreichbar wird.

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
