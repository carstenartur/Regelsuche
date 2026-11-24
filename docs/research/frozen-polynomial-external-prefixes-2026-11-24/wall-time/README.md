# Kaltstartvergleich zur exakten Faktorisierung

Dieser Versuch verwendet die 19 identischen strukturierten QQ-Anfragen aus
den [öffentlichen Quellenpräfixen](../prefix-rows.jsonl). Alle Anfragen gehen
in derselben Reihenfolge an Regelsuches rationalen Faktorpfad und an die
unveränderte SymPy-Bridge unter dem gepinnten GraalPy 25.1.3. Es handelt sich
um acht unterschiedliche Polynome. Die übrigen öffentlichen Formationszeilen
haben beim bestehenden Quellpfad keine erste Anfrage geliefert.

Der [Versuchsvertrag](protocol.json) fixiert vor der Messung zehn Sekunden
pro Anfrage, zwei Wiederholungen und wechselnde Reihenfolge. Die
Anfragezeit umfasst frischen Prozess, JVM-Start beziehungsweise GraalPy-Start
und Modulimporte, Übertragung der gleichen JSON-Eingabe, Ausgabe, Prozessende
und die unabhängige exakte Produktprüfung mit `Fraction`. Native Ergebnisse
durchlaufen zusätzlich den regulären Produktprüfer in Java. Die einmalige
Archivprüfung und Provisionierung für GraalPy wird separat gemessen und ist
im Rohbeleg enthalten. Das Kompilieren des einzelnen Java-Workers findet vor
der Messung statt. Die vorgelagerte Java-Quellenprüfung ist für beide Arme
dieselbe und liegt außerhalb dieser Messgrenze.

Die Quelle und die Antwort sind über die SHA-256 der exakten Transportbytes
gebunden. Ein Produkt mit mehr als einem nichtkonstanten Faktor gilt auch bei
Vielfachheiten als nichttrivial. Die Grade im Beleg erfassen außerdem die
Feinheit der Zerlegung. Ein bestätigtes Produkt belegt für sich keine
Irreduzibilität seiner Faktoren.

## Ergebnisse

| Verfahren | Abgeschlossene Anfragen | Nichttriviale exakte Produkte | Median je abgeschlossener Anfrage | Summe aller Anfragezeiten |
| --- | ---: | ---: | ---: | ---: |
| Regelsuche, Java 25 | 38/38 | 30 | 0,780 s | 31,93 s |
| Original-SymPy, GraalPy 25.1.3 | 36/38 | 28 | 6,170 s | 242,06 s |

Das externe Verfahren überschritt die gemeinsame Frist bei je einer
Anfrage zu `x^10-1` und `x^6-1` in der ersten Wiederholung. Wenn beide
Arme fertig wurden, war Regelsuche in allen 36 Paaren schneller. Der Median
des paarweisen Quotienten der Anfragezeiten lag bei 7,50 zugunsten von
Regelsuche. Alle 36 ausgegebenen externen Faktorlisten stimmen exakt mit der
separaten [Rohdiagnose](../raw.jsonl) überein. Die separate GraalPy-Prüfung
und Provisionierung aus den vorhandenen Archiven dauerte 2,80 s.

In 14 der 36 abgeschlossenen Paare stimmen die Grade der nichttrivialen
Faktoren überein. Der Median des paarweisen Zeitquotienten beträgt dort
8,55 zugunsten von Regelsuche. In acht Paaren meldet Regelsuche keinen nichttrivialen
Faktor, und SymPy liefert nur einen Faktor. In den übrigen 14 Paaren
liefert SymPy eine feinere Zerlegung. Bei `x^10-1` gibt Regelsuche zum
Beispiel zwei Faktoren vom Grad fünf aus. SymPy liefert vier Faktoren der
Grade vier, vier, eins und eins.

Der [Messbeleg](receipt.json) bindet alle 76 [Einzelbeobachtungen](observations.jsonl).

## Reproduktion

Die [historische Provisionierung](../README.md#reproduktion) liefert das
exakte Oracle-Archiv und die Wheels. Das folgende Beispiel nutzt das
Classpath-Attribut der normalen Maven-Testausführung. Die ausgegebene
Laufzeit ist bei einer Wiederholung eine neue Beobachtung.

```sh
study=docs/research/frozen-polynomial-external-prefixes-2026-11-24/wall-time
mvn -B -ntp -pl regelsuche-experiments -am \
  -Dtest=PolynomialTheoryUtilityExternalAdmissionTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
classpath=$(python3 - <<'PY'
from pathlib import Path
from xml.etree.ElementTree import parse
reports = Path('regelsuche-experiments/target/surefire-reports')
test = next(reports.glob('TEST-*PolynomialTheoryUtilityExternalAdmissionTest.xml'))
value = next(p for p in parse(test).findall('properties/property')
             if p.attrib['name'] == 'java.class.path')
print(value.attrib['value'])
PY
)
compiled=$(mktemp -d)
javac -cp "$classpath" -d "$compiled" "$study/NativeFactorizationWorker.java"
taskset -c 2 python3 "$study/compare.py" --provision /path/to/provision \
  --java "$(command -v java)" --classpath "$compiled:$classpath" \
  --output /tmp/polynomial-wall-results.json
```

## Geltungsbereich

Die Zeiten beziehen sich auf jeweils einen kalten Prozess je Anfrage und
enthalten keine Messung eines dauerhaft vorbereiteten Dienstes. Der
Versuch war auf eine logische CPU beschränkt. Unterschiede im geteilten
Host bleiben möglich. Die strenge Arbeitsrechnung der 120 externen
Profilzeilen, deren eingefrorene Budgets und die geschützte Studie behalten
ihren bestehenden Vertrag. [Reproduktion der Messgrenze](../README.md)
