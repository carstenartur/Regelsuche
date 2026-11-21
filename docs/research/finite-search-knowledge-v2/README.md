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
Arbeitsfelder machen v2 zu einem eigenen Ausgabeformat. Ein qualifizierter
v2-Versuchsbericht steht noch aus.

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
