# Erholung von ungünstigen modularen Pivotprofilen

`ModularKernel` kann eine rationale Kernbasis auch dann finden, wenn die erste
Primzahl bei unverändertem Rang andere Pivotspalten oder Originalzeilen auswählt.
Die mathematische Prüfung bleibt unverändert: Ein Primkörper belegt einen
nichtverschwindenden Minor; die rekonstruierten Regeln müssen die verlangten
Originalgleichungen exakt erfüllen.

## Reproduzierter Fehlversuch

Auf dem [Stand vor der Korrektur](https://github.com/carstenartur/Regelsuche/blob/c621a1949d29c391ceb6f1bdb5e79bf6e263ccf1/regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/linalg/ModularKernel.java)
hielt die Suche das zuerst gefundene Profil fest, bis eine Primzahl einen
höheren Rang zeigte. Bei der Matrix `[5,1]` und den Primzahlen `[5,7,11]`
wählte sie modulo fünf die zweite Pivotspalte. Modulo sieben und elf war
die erste Spalte ein Pivot, aber der Rang blieb eins. Beide brauchbaren
Reduktionen wurden verworfen; die Suche endete mit `NO_CERTIFICATE`.
Die beiden späteren Primzahlen genügen zur Rekonstruktion von `x0 = -x1/5`.

Ein zweiter Fall betrifft nur die Zeilenauswahl. Für `[[5,10],[1,2]]`
verschwindet die erste Zeile modulo fünf. Daher kommt der erste Rangzeuge
aus der zweiten Originalzeile. Andere Primzahlen wählen die erste Zeile,
obwohl die Pivotspalte und die rationale Regel `x0 = -2*x1` dieselben sind.
Auch diese Reduktionen wurden zuvor verworfen.

## Auswahl und Quellenbindung

Die Suche bevorzugt zunächst höheren Rang und bei gleichem Rang die
lexikographisch frühere aufsteigende Folge der Pivotspalten. Reduktion modulo
einer zulässigen Primzahl kann den rationalen Rang jedes Spaltenpräfixes nur
verkleinern: Ein modulo dieser Primzahl nichtverschwindender Minor ist auch
über den rationalen Zahlen nicht null. Bei gleichem modularen und rationalen
Rang kann das rationale Pivotprofil daher nicht später als das modulare liegen.

Ein besseres Profil ersetzt den Rangzeugen und setzt CRT-Modulus sowie
Koeffizientenreste vollständig zurück. Spätere schlechtere Profile werden
übersprungen. Es gibt weiterhin nur einen gespeicherten Rekonstruktionsversuch
und höchstens so viele Profilwechsel wie angegebene Primzahlen.

Bei gleichen Pivotspalten können die Originalzeilen verschieden sein.
Die normierten Reduktionen verwenden trotzdem dieselben Pivot- und freien
Koordinaten. Ihre Koeffizienten dürfen deshalb zu einem Kandidaten kombiniert
werden. Das Zertifikat behält die Originalzeilen und die Primzahl seines
ersten Rangzeugen. Die exakte Prüfung kontrolliert dessen Minor und
`W*P=0`; bei `COMPLETE_KERNEL` zusätzlich `A*P=0` für alle Eingabezeilen.
Eine kombinierte Restdarstellung allein bestätigt keine Gleichheit.

Die Vergleiche der Pivotspalten zählen zum bisherigen Arbeitsbudget und
prüfen dessen Abbruchsignal. Profilwechsel eröffnen kein neues Budget.
Ungünstige oder zu wenige Primzahlen, nicht rekonstruierbare Koeffizienten
und erschöpfte Ressourcen können weiterhin ohne Zertifikat enden.

## Kontrollen und Reproduktion

Die Regressionstests reproduzieren vor der Korrektur `NO_CERTIFICATE` für
die beiden Beispiele und für einen Wechsel erst an der zweiten Pivotspalte.
Nach der Korrektur erhalten sie die von Hand bestimmten Kernkoeffizienten
und bestehen die unabhängige Zertifikatsprüfung. Ein manipulierter
Zeilenzeuge wird weiterhin verworfen. Der SDK-Test verwendet die
unveränderte Standardprimzahlliste mit der Matrix `[1000000007,1]`.

Für die Fälle mit Profilwechsel und wechselnden Originalzeilen unterbricht
der Test außerdem an jedem Arbeitslimit unterhalb des vollständigen Laufs
und an jeder entsprechenden Abbruchabfrage. Kein solcher Lauf liefert ein
Zertifikat. Die vorhandenen Kontrollen zu Rangabfall, Nennern, Bitgrenzen,
Quellenmanipulation und dem optionalen Rekurrenzpfad bleiben bestehen.
Die 66 rationalen Vergleichsmatrizen liefern weiterhin dieselben Basisvektoren
wie die unabhängige exakte Gauss-Jordan-Reduktion. Die mechanischen Zähler
bleiben dort bei 8.537 Einheiten für RREF und 8.733 für den modularen Pfad.
Das ist keine Messung eines Laufzeitvorteils.

Mit Java 25 und Maven ab 3.9.9 aus der 3.9-Reihe im Repository-Stamm:

```sh
mvn -B -ntp -pl regelsuche-optimization-sdk -am \
  -Dtest=ModularKernelTest,ModularKernelSdkTest,RecurrenceInvariantSynthesisTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
