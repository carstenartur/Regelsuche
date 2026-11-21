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
Arbeitsfelder machen v2 zu einem eigenen Ausgabeformat.

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

Der [Reproduktionsplan](../../superpowers/plans/2026-11-21-finite-search-v2-reproduction.md) bindet den unveränderten Produzenten `6dce1fae5bf84cbb588129993830c41be45a8bda`. Der [Workflow](../../../.github/workflows/finite-search-v2-reproduction.yml) baut drei frische Checkouts mit getrennten Maven-Repositories. Zwei laufen auf demselben Host, der dritte in einem digest-gepinnten JDK-Image. Der Java-Messlauf im Container hat keinen Netzwerkzugriff.

Am 21. November 2026 lieferten alle drei Checkouts dieselben kanonischen Ergebnisbytes (SHA-256 `f2d2831971f7e1509b3160879085f98f733a9bedd393ac560806860804d3d3fe`). Jede Ausführung erfasste 16 Quelltabellen und alle 160 vorab festgelegten Fall-Verfahrens-Kombinationen, einschließlich Fehlsuchen und Budgeterschöpfungen. Das Originalarchiv enthält die drei Vergleiche, die getrennten Laufzeitdateien sowie Build- und Ausführungslogs. [Gehosteter Lauf](https://github.com/carstenartur/Regelsuche/actions/runs/44879420814) · [Original-ZIP](hosted-44879420814/finite-search-v2-reproduction-44879420814-1.zip) · [Prüfsummen und Quellenbindung](hosted-44879420814/receipt.json)

Über alle 16 Fälle erreichten die einseitige und die bidirektionale Suche dieselben sechs Ziele mit 1.278 bzw. 688 nativen Aufrufen. Der warme Tabellenarm erreichte diese sechs Ziele mit 105 nativen Aufrufen zur Pfadnachprüfung und 1.277 gesondert erfassten Tabellen-Abfrageoperationen. Der kalte Arm benötigte zusätzlich 1.265 native Aufrufe für den Aufbau, der Wiederherstellungsarm ebenso viele für das erneute Laden und Prüfen. Beide kamen damit auf je 1.370 native Aufrufe. Alle drei Tabellenarme verzeichneten zehn fehlende Ziele.

Die Gesamtarbeit ist über die Verfahren hinweg `UNMATCHED`, das 1.000×-Ziel bleibt `UNMET`. Die Zeit- und Allokationsmessungen sind Einzelbeobachtungen je Umgebung. Für die 32 Portfolio-Messungen je Lauf ist die Thread-Allokation unbeobachtet.
