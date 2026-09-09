# Eigene Probleme mit Python und Java lösen

Der lokale Python-Client benötigt Python 3.10 oder neuer und keine
Laufzeitabhängigkeiten. Java 25 wird für den Regelsuche-Server benötigt, nicht
für die unabhängige Offlineprüfung. Das Paket ist aus dem Repository und aus
der Regelsuche-Distribution installierbar; eine Veröffentlichung auf PyPI wird
nicht vorausgesetzt.

## Installieren und starten

Im Quellcheckout erzeugt `mvn -DskipTests package` die aktuelle Distribution.
Das ZIP liegt unter `app/target/regelsuche-<Version>.zip`. Nach dem Entpacken:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install ./python
bin/regelsuche serve --port 8080
```

Für ein Wheel aus dem Quellcheckout:

```sh
python -m pip wheel ./python --no-deps --wheel-dir dist
python -m pip install dist/regelsuche_client-0.1.0-py3-none-any.whl
```

Der Build verwendet die in `python/pyproject.toml` fixierte setuptools-Version.
Das Wheel benötigt selbst keine Abhängigkeiten und kann offline installiert
werden. Für die erste Erstellung müssen die Build-Abhängigkeiten verfügbar sein.

## Python und Notebooks

```python
from regelsuche import Client

client = Client("http://127.0.0.1:8080")
result = client.solve(["x+y=3", "x-y=1", "z=3"])
assert result.verified
print(result.particular)  # exakte Fraction-Werte: x=2, y=1, z=3
result.save("linear-solution.json")
assert client.replay(result).verified

family = client.solve(["a+b=3", "c+d=5"])
print(family.particular, family.basis)  # vollständige affine Lösungsmenge

stopped = client.solve(["x+y=3"], max_work_units=0)
assert not stopped.verified and stopped.status == "BUDGET_INCONCLUSIVE"
```

Diese API funktioniert auch in einer Notebook-Zelle. Sie bringt kein eigenes
Notebook-Frontend mit. Ein vollständiges Beispiel liegt in
`python/examples/linear_systems.py`. Die Ergebnisdaten werden als Kopien
zurückgegeben; Änderungen an einer Kopie ändern nicht den geprüften Snapshot.

Die Anfrage akzeptiert `route="AUTO"`, `"DIRECT"`, `"MATRIX"` oder `"BLOCKS"`.
Quelle, Route und Budget werden an die Antwort gebunden. Jeder als gelöst
gemeldete Abschluss wird im Client unabhängig nachgerechnet. Nicht unterstützte
oder erschöpfte Ergebnisse bleiben ausdrücklich unverifiziert. HTTP-Fehler
erzeugen `ClientError`, fehlerhafte mathematische Nachweise `VerificationError`.
Ein optionaler `authorization`-Header unterstützt geschützte Workbenches;
HTTP-Weiterleitungen werden abgelehnt.

## CLI und Offlineprüfung

```sh
python -m regelsuche --url http://127.0.0.1:8080 solve \
  -e 'x+y=3' -e 'x-y=1' --output linear-solution.json
python -m regelsuche verify linear-solution.json
python -m regelsuche study --output representation-transfer-study.json
python -m regelsuche verify representation-transfer-study.json
```

Exitcode 0 bedeutet mathematisch geprüft, 2 einen nicht abgeschlossenen
Lösungsversuch und 1 einen Fehler. Bei einer Studie werden verifizierte und
unabgeschlossene Beobachtungen getrennt gezählt. Die Prüfung ersetzt kein
vollständiges Java-Replay für Kosten und Herkunft und erzeugt keinen Lean-Beweis.
Der unabhängige Prüfer akzeptiert explizite rationale affine Arithmetik, bis zu
16 Gleichungen/Variablen und rationale Werte mit höchstens 4.096 Bits. Er
rekonstruiert die vollständige kanonische Nullraumbasis, nicht bloß eine
einzelne passende Belegung.

## Java ohne Server

Das Distributionsbeispiel kann direkt gegen die mitgelieferten Bibliotheken
ausgeführt werden:

```sh
java --class-path 'lib/*' examples/LinearSolve.java
```

Es verwendet den öffentlichen `LinearRepresentationPlanner` und gibt denselben
versionierten Nachweis aus. Der vorhandene [Java-Discovery-SDK](java-discovery-sdk.md)
bleibt der Zugang für umfangreichere Entdeckungsaufgaben.
