# Endliches Suchwissen mit korrigierter Aufwandserfassung

Der aktuelle Launcher schreibt `regelsuche.finite-search-knowledge-comparison/v2`
und kennzeichnet den Controller als `regelsuche.guided-factor-deepening/v2`.
Als Eingaben gelten weiterhin die öffentliche Matrix mit 16 Fällen und zehn
Verfahren, das reversible Vier-Regel-Inventar und die Budgets des
[ursprünglichen Plans](../finite-search-knowledge-v1/plan.json).

- Eine erschöpfte Tabellenabfrage unterscheidet ein fehlendes Ziel
  (`NOT_IN_TABLE`) von einem gespeicherten Ziel ohne gefundenen Pfad innerhalb
  der Abfragetiefe (`NO_PATH_IN_TABLE_WITHIN_QUERY_DEPTH`). Eine durch
  Budgetende unterbrochene Abfrage meldet `WORK_EXHAUSTED`.
- Erzeugung und Wiederladen verwenden dieselbe Obergrenze von 4 MiB für
  den kanonischen UTF-8-Umschlag. Eine abgewiesene Erzeugung meldet die bereits
  eingesetzten Aufrufe und die Zahl erzeugter Kandidaten.
- Der geführte Suchcontroller verbucht Hinweise für einen weiteren
  Tiefendurchlauf nur, wenn ein solcher Durchlauf zulässig bleibt.

Der [eingefrorene v1-Bericht](../finite-search-knowledge-v1/README.md) bleibt
an den ursprünglichen Quellstand gebunden. Die geänderten Status- und
Arbeitsfelder machen v2 zu einem eigenen Ausgabeformat. Ein qualifizierter v2-Versuchsbericht mit **vollständiger mechanischer
Arbeitserfassung** steht noch aus. Die technische Dreifach-Reproduktion ist
davon zu unterscheiden; sie beweist weder eine 1.000-fache Beschleunigung noch
die Gleichheit aller Arbeitsmaße.

Zum technischen Reproduzieren der aktuellen Ausgabe im Repository-Stamm mit
Java 25 und Maven ausführen.

```bash
mvn -pl regelsuche-experiments -am -DskipTests -DincludeScope=runtime \
  -Dmdep.outputFile=target/finite-search-classpath.txt package dependency:build-classpath
module=regelsuche-experiments/target
output="$module/finite-search-v2"
java -Xmx512m -cp "$module/classes:$(cat "$module/finite-search-classpath.txt")" \
  de.regelsuche.benchmark.FiniteSearchKnowledgeComparison \
  "$output/comparison.json" "$output/runtime.json"
```

## Reproduktion des veröffentlichten v2-Produzenten

Der [Reproduktionsplan](../../superpowers/plans/2026-11-21-finite-search-v2-reproduction.md) bindet den unveränderten Produzenten `6dce1fae5bf84cbb588129993830c41be45a8bda`. Das [historische Workflow-Rezept](source-replay-workflow.yml) dokumentiert den ursprünglichen Lauf mit drei frischen Checkouts mit getrennten Maven-Repositories. Zwei laufen auf demselben Host, der dritte in einem digest-gepinnten JDK-Image. Der Java-Messlauf im Container hat keinen Netzwerkzugriff. Das unveränderte Rezept wird hier ausschließlich als Quellartefakt aufbewahrt; es ist **kein** weiterer aktiver GitHub-Workflow und wird nicht erneut als Studie gestartet.

Das Artefakt enthält je Umgebung den vollständigen Vergleich mit allen 160 Zeilen und den erzeugten Quelltabellen sowie die einzelnen Zeit- und Allokationsbeobachtungen. Die Prüfung verlangt bytegleiche kanonische Vergleiche und bindet die Laufzeitdateien separat. Die öffentliche Matrix und die v1-Befunde bleiben an ihre ursprünglichen Quellen gebunden. Ein reproduzierter v2-Befund erfordert den abgeschlossenen Lauf und die Prüfung seines Artefakts.

### Historischer öffentlicher Reproduktionsbeleg (keine neue Studie)

[GitHub-Lauf 44879420814](https://github.com/carstenartur/Regelsuche/actions/runs/44879420814)
dokumentiert bereits zwei frische Quell-Checkouts und einen digest-gepinnten,
netzwerkfreien Containerlauf des zuvor eingefrorenen Produzenten. Jede der drei
Ausführungen lieferte die 160 vorregistrierten Ergebniszeilen; die kanonischen
Vergleichsdateien waren byteidentisch (SHA-256
`f2d2831971f7e1509b3160879085f98f733a9bedd393ac560806860804d3d3fe`).
Die Laufzeitbeobachtungen wurden getrennt je Umgebung aufbewahrt. Das
verzeichnete öffentliche Ergebnisarchiv trägt die SHA-256-Prüfsumme
`09a5081e908d5561390a0e53f593c3eacb0b88c6f40c0b278aa3a875e990199b`.

Diese Prüfung ist **nur** eine Reproduktion der begrenzten öffentlichen
Vergleichsmatrix. Ihr ausgewiesener Geltungsbereich lautet
`NATIVE_CALLS_ONLY_FULL_MECHANICAL_WORK_UNMATCHED`: Weder vollständige
mechanische Arbeitsparität noch der angestrebte Faktor 1.000 sind damit
nachgewiesen. Das Quellrezept wird hier nur zur Nachvollziehbarkeit aufbewahrt,
nicht erneut ausgeführt. Geschützte Qualifikationsdaten und externe Studien
sind ausdrücklich nicht Bestandteil dieses Belegs.

## Engpassanalyse

Die v2-Ausführung liefert für die einseitige und die bidirektionale Suche sowie für alle drei
Tabellenarme dieselben sechs bestätigten Ziele. Der tiefste Fall
`independent-factor-sites-7` hat im vollständigen Referenzabschluss eine
Mindesttiefe von sieben Primitivschritten. [Archiv der unveränderten Ausführung](https://github.com/carstenartur/Regelsuche/actions/runs/44879420814/artifacts/14697253542)

| Verfahren am Siebenstellenfall | Bestätigte Tiefe | Native Suche | Erwerb oder Wiederherstellung | Native Pfadprüfung | Native Summe | Controller- oder Tabellenoperationen |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Einseitig | 7 | 968 | 0 | 56 | 1.024 | nicht erfasst |
| Bidirektional | 7 | 472 | 0 | 56 | 528 | nicht erfasst |
| Iterativ | ohne Pfad | 4.096 | 0 | 0 | 4.096 | nicht erfasst |
| Geführt, ein Cache-Eintrag | ohne Pfad | 4.096 | 0 | 0 | 4.096 | 21.382 |
| Geführt, 128 Cache-Einträge | ohne Pfad | 960 | 0 | 0 | 960 | 262.144 |
| Kalte bzw. wiederhergestellte Tabelle, je Arm | 7 | 0 | 1.016 | 56 | 1.072 | 1.024 |
| Warme Tabelle | 7 | 0 | 0 | 56 | 56 | 1.024 |

- **Suche und Pfadprüfung.** Die bidirektionale Suche senkt die Zahl der
  Suchaufrufe bei gleicher bestätigter Tiefe. Die warme Tabelle ersetzt die
  native Suche durch 1.024 Tabellenoperationen und benötigt weiterhin 56
  native Prüfaufrufe. Der Prüfer erzeugt zu jedem Pfadschritt die reversiblen
  Nachbarn frisch. Das Verhältnis von 1.024 zu 56 ist mit 18,29 der größte
  beobachtete Faktor bei nativen Aufrufen der warmen Tabelle gegenüber der
  einseitigen Suche auf den sechs gemeinsam bestätigten Zielen.
- **Controller.** Der 128-Einträge-Cache erreicht trotz 6.319 Cache-Treffern
  und null Ersetzungen seine getrennte Controllergrenze bei 262.144
  Operationen. Traversierung, Ordnung und Cachezugriffe teilen diese Grenze.
  Der Ein-Eintrag-Cache und die ungeführte Vertiefung stoßen dagegen an die
  native Aufrufgrenze. Die geführten Verfahren bestätigen in diesem Panel
  höchstens Tiefe fünf.
- **Erwerb und Wiederherstellung.** Die kalte und die wiederhergestellte
  Tabelle verbrauchen je 1.016 native Aufrufe vor der Pfadprüfung. Ihre
  Gesamtsumme liegt damit am Siebenstellenfall über jener der einseitigen Suche.
- **Laufzeit und Allokation.** Die drei archivierten Einzelbeobachtungen am
  Siebenstellenfall liegen für die einseitige Suche bei 223,9–232,5 ms und
  919,0–922,3 MB Thread-Allokation. Für die warme Tabelle liegen sie bei
  12,2–13,6 ms und 50,8–51,0 MB. Die MB-Werte summieren Allokationen im
  messenden Thread. Das Dreierportfolio verbraucht wie das Einerportfolio
  2.917 native Aufrufe, bei 328,6–381,1 ms gegenüber 648,7–669,2 ms.
  Die Messung isoliert weder den Kerneldurchsatz noch die Kosten der einzelnen
  Parser-, Matcher- und Identitätsoperationen.

Der Faktor 1.000 bleibt im öffentlichen Panel unerreicht. Der warme Zugriff
spart native Sucharbeit, während Pfadprüfung und Tabellenabfrage verbleiben.
Der geführte Arm stößt bei größerem Cache zuerst an die Controllergrenze.
Für tiefere Fälle mit vollständig vergleichbarer Arbeit gibt es hier keine
Messung.

Die Zahlen lassen sich ohne erneuten Suchlauf anhand des bereits erzeugten
ZIP-Archivs prüfen. Dazu den Pfad zum heruntergeladenen Archiv einsetzen und
im Repository-Stamm ausführen.

```sh
archive=/path/to/finite-search-v2-reproduction-44879420814-1.zip
printf '%s  %s\n' 09a5081e908d5561390a0e53f593c3eacb0b88c6f40c0b278aa3a875e990199b "$archive" | sha256sum -c -
out=$(mktemp -d)
unzip -q "$archive" -d "$out"
python3 docs/research/finite-search-knowledge-v2/verify_reproduction.py . "$out"
python3 - "$out" <<'PY'
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
case = "independent-factor-sites-7"
report = json.loads((root / "host-1/comparison.json").read_bytes())
rows = {(row["caseId"], row["arm"]): row for row in report["rows"]}
for (case_id, arm), row in rows.items():
    if case_id == case:
        fields = ("pathLength", "searchCalls", "acquisitionCalls", "restorationCalls",
                  "replayCalls", "totalNativeCalls", "lookupOperations", "cacheHits", "replacements")
        print(arm, *(row[field] for field in fields))
for (case_id, arm), row in rows.items():
    if arm == "ONE_SIDED" and row["pathLength"] >= 0:
        warm = rows[case_id, "TABLE_WARM"]
        assert row["pathLength"] == warm["pathLength"] == row["referenceMinimumDepth"]
        print(case_id, "native call ratio", row["totalNativeCalls"] / warm["totalNativeCalls"])
for environment in ("host-1", "host-2", "container"):
    runtime = json.loads((root / environment / "runtime.json").read_bytes())
    for row in runtime["samples"]:
        if row["caseId"] == case:
            allocated = row["currentThreadAllocatedBytes"]
            print(environment, row["arm"], row["wallNanos"] / 1e6, allocated / 1e6 if allocated >= 0 else None)
PY
```

