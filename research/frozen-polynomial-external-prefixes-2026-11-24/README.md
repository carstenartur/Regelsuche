# Öffentliche Aufrufe des historischen GraalPy-Profils

Der vorhandene Java-Empfänger prüfte 120 öffentliche Zeilen bis zum ersten
Backend-Aufruf. Die aktuellen Budgets und der ursprüngliche Plan blieben
unverändert. 19 Zeilen erreichten den Aufruf. 99 hielten vorher an einer
Budgetgrenze an, zwei lagen außerhalb des unterstützten Fragments.

Jede erreichbare Anfrage lief in einem eigenen Prozess unter der gepinnten
Oracle-Distribution GraalPy 25.1.3 mit SymPy 1.14.0 und mpmath 1.3.0. In
allen 19 strengen Messungen meldet der Operationszähler
`UNMEASURED` an `transport.json.loads`, bevor er eine Arbeitseinheit
zugelassen hat. Der strenge Lauf gibt dort keinen Kandidaten aus.

Bei einem separaten Aufruf der unveränderten Bridge prüfte ein unabhängiger
`Fraction`-Empfänger 19 Produkte auf exakte Übereinstimmung mit der
jeweiligen Quelle. 15 enthalten mehr als einen Faktor einschließlich
Vielfachheit. Vier bestehen aus einem einzelnen Faktor mit Vielfachheit eins
und einer Einheit.

| Checkpoint | Erreichte Aufrufe | Strenge Abbrüche bei JSON-Eintritt | Rohprodukte mit mehr als einem Faktor |
| --- | ---: | ---: | ---: |
| CP01 und CP02 | 0 | 0 | 0 |
| CP03 | 1 | 1 | 1 |
| CP04 | 3 | 3 | 3 |
| CP05 | 5 | 5 | 4 |
| CP06 | 10 | 10 | 7 |

Die Rohprodukte zeigen die mathematische Funktion des historischen
Backends. Sie sind kein Ergebnis unter dem eingefrorenen Arbeitsbudget.
Kein externer Studienkandidat wurde erzeugt. Die strengen Abbrüche sind
fehlende Messabdeckung und bleiben von Budgeterschöpfung unterscheidbar.

## Gebundene Daten

- [Protokoll mit SHA-256 der ursprünglichen Planung und aller Daten](protocol.json)
- [120 öffentliche Zeilen der Quellenprüfung](prefix-rows.jsonl)
- [19 strenge Belege](strict.jsonl)
- [19 Rohprodukte mit unveränderter Bridge](raw.jsonl)

Das Protokoll bindet die SHA-256 des eingefrorenen Plans
`6cfac16d65611820b713cf1f2aca0fdb724fc542ccef6b9de80981dc290af619`,
der unveränderten Bridge, des Operationszählers, des Oracle-Archivs, seiner
`release`-Datei und beider Wheels. Berichtete Zeitwerte der Bridge gehören
nicht zur angefragten Arbeitsrechnung und sind aus der Rohdiagnose
herausgelassen. Der Prüfer rekonstruiert die Polynome aus den originalen
strukturierten Anfragen und multipliziert die ausgegebenen Faktoren exakt.

## Reproduktion

Die externe Provisionierung enthält das
[Oracle-Archiv für Linux amd64](https://github.com/oracle/graalpython/releases/download/graal-25.1.3/graalpy3.12-25.1.3-linux-amd64.tar.gz)
als `graalpy.tar.gz` und die beiden unter ihren Originalnamen in `wheels/`
gespeicherten [SymPy-](https://pypi.org/project/sympy/1.14.0/#files) und
[mpmath-Wheels](https://pypi.org/project/mpmath/1.3.0/#files). Bei der
Reproduktion werden die Archive geprüft und frisch entpackt.

```sh
area=docs/research/frozen-polynomial-external-prefixes-2026-11-24
python3 "$area/verify.py"
output=$(mktemp -d)
python3 "$area/reproduce.py" --provision /path/to/provision \
  --prefixes "$area/prefix-rows.jsonl" --output "$output"
python3 "$area/verify.py" --result-directory "$output"
```

Für einen frischen Java-Export der Präfixe sind Java 25 und der vorhandene
Maven-Reaktor nötig. Der Export verwendet den Produktions-Codec, der auch das
historische Backend aufruft.

```sh
area=docs/research/frozen-polynomial-external-prefixes-2026-11-24
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
source=regelsuche-math-sympy/src/main
codec="$source/java/de/regelsuche/math/sympy"
javac -cp "$classpath" -d "$compiled" "$area/ExportPrefixes.java" \
  "$codec/SymPyScript.java" "$codec/SymPyEvidence.java" \
  "$codec/SymPyFactorizationCodec.java" "$codec/SymPyFactorizationPolicy.java"
java -cp "$compiled:$source/resources:$classpath" de.regelsuche.math.sympy.ExportPrefixes \
  > "$compiled/prefix-rows.jsonl"
cmp "$compiled/prefix-rows.jsonl" "$area/prefix-rows.jsonl"
```

## Verbleibende Messarbeit

Die aktuelle Studienprüfung erwartet weiterhin eine passende Runtime-Sperre
und eine vollständige Abbildung auf die v2-Arbeitseinheiten. Die bereits
instrumentierten String- und Regex-Präfixe des eingebetteten GraalPy helfen
beim JSON-Eintritt. Scanner, native Sammlungsoperationen, Callback-Aufrufe,
ausgeführte Koeffizientenkernel und JSON-Ausgabe müssen anschließend
durchgängig gedeckt sein. Die getrennt bereitgestellte exakt gepinnte
Distribution löst die lokale Provisionierung für diese Diagnose. Die
Sperre der Studienprovisionierung und die fünf eingefrorenen Profile bleiben
bestehen. [Operationsdiagnose](../frozen-polynomial-work-2026-11-20.md)
