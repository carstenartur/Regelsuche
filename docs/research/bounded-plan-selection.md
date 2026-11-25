# Kostengebundene Planwahl, Version 1

Die SDK-Einstiege liefern einen geprüften Rechenplan oder eine geprüfte Anzahl und das Auswahlprotokoll desselben Aufrufs. Bei einer Ressourcengrenze kann der Wert fehlen. `priorWork()` enthält die frühere Erwerbsarbeit eines mitgelieferten Vorschlags. `work()` summiert die Vorbereitung und alle tatsächlich ausgeführten Versuche im aktuellen Aufruf. Die Liste unterscheidet feste, wiederverwendete und gelernte Anbieter, übersprungene und gescheiterte Versuche sowie das ausgewählte Ergebnis. Für einen Lebenszyklus mit mehreren Anfragen wird ein gemeinsamer Erwerb einmal gezählt.

## Bilineare Ganzzahlprogramme

`BilinearProgramSdk.selectIntegerWithReuse(tensor, profile, objective, reuse, maximumProbeWork, budget, cancellation)` berechnet für den aktuellen Tensor die direkte Koordinatenrechnung einschließlich ihrer Breitenschätzung. Ein früheres Ergebnis von `selectInteger` lässt sich mit `BilinearIntegerPortfolio.Reuse.from(result)` samt tatsächlicher Sucharbeit übergeben. `Optional.empty()` verwendet die feste Rechnung. Ein Vorschlag muss für dieselben Betragsbreiten weniger Wortarbeit erwarten lassen. `NO_ALLOCATION_INCREASE` verlangt zusätzlich, dass die modellierten Anzahlen neu angelegter Wörter und Objekte jeweils höchstens denen der direkten Rechnung entsprechen. Der Kern prüft den Tensor koeffizientenweise und erstellt die wiederverwendete Zerlegung für das aktuelle Profil neu. Er prüft die Kostenvoraussetzungen nochmals am neuen Plan.

| Quelle und Breite | Wortarbeit direkt | Wortarbeit des Vorschlags | Wahl bei `LIMB_WORK` | Wahl bei `NO_ALLOCATION_INCREASE` |
| --- | ---: | ---: | --- | --- |
| Zweitermfaltung, 256 Bit | 353 | 353 | direkt | direkt |
| Zwei Ausgaben der komplexen Multiplikation, 381 Bit | 768 | 672 | wiederverwendet | direkt |
| Zweitermfaltung, 2048 Bit | 17.153 | 13.575 | wiederverwendet | direkt |
| Zweitermfaltung, 8192 Bit | 265.217 | 201.735 | wiederverwendet | direkt |

Die Tabelle zeigt die modellierte Arbeit einer späteren Ausführung. Bei 381 Bit kostete der Erwerb 1.189.854 Sucheinheiten. Bei einem Vorzeichenwechsel in der zweiten Ausgabe kosteten die Vorbereitung 171, die gescheiterte Wiederprüfung 321 und die erfolgreiche direkte Prüfung 494 Einheiten. Der Aufruf verbrauchte insgesamt 986. Wortarbeit und Sucharbeit verwenden verschiedene Modelle. Die JVM-Zeiten und Allokationen des historischen Vergleichs stehen im [JMH-Versuchsbericht](../../research/benchmarks/bilinear-integer-selection/README.md).

## Endliche Faktoren zählen

`FiniteConstraintSdk.countWithPolicy(model, problem, expectedUses, maximumProbeWork, budget, cancellation)` verwendet festes Min-Degree als Kontrolle. Eine trainierte Auswahl setzt eine frühere Beobachtung mit gleichem Strukturmerkmalsvektor und erfolgreichem Kontrolllauf voraus. Die erste passende Beobachtung in Trainingsreihenfolge liefert den Kandidaten. Zu dessen gezählter früherer Arbeit addiert die Schätzung die bisher bezahlte Suche im Modell und den auf `expectedUses` verteilten Erwerb, aufgerundet auf ganze Einheiten. Die Schätzung muss strikt unter der früheren Min-Degree-Arbeit liegen. Jede aktuelle Ausführung prüft ein neues Zertifikat am aktuellen Problem. Quellenbeschriftungen dokumentieren die Beobachtung.

Für die Kette aus sieben booleschen Variablen mit sechs gleichen Nachbarausschlüssen (Trainingsbezeichnung `chain`) verbrauchte der Erwerb 5.472 Einheiten. Eine erwartete Anwendung wählte Min-Degree mit 1.943 aktuellen Einheiten. Bei 1.000 erwarteten Anwendungen wählte die Regel die aufsteigende Reihenfolge mit 1.647 aktuellen Einheiten. Eine Grenze von 100 Einheiten für den Versuch ergab 43 Einheiten Vorbereitung, 100 für den abgebrochenen Versuch und 1.900 für den verifizierten Rückfall. Das ist eine Wiederholung auf derselben Graphstruktur. Die früheren Ergebnisse auf getrennten Projekten stehen im [Transfervergleich](../../research/benchmarks/constraint-planning-transfer/README.md).

## Grenzen und Reproduktion

Beide Einstiege belasten das Arbeits-, Speicher- und Zeitbudget der Anfrage. Ein Probeversuch erhält höchstens `maximumProbeWork`, der feste Rückfall nur die verbliebenen Ressourcen. Bei Abbruchsignal oder abgelaufener Frist endet die Auswahl. Im SDK reserviert ein Budget mit einem einzigen Kandidatenplatz diesen für die feste Methode. Erwartete Anwendungen steuern eine Schätzung. Der Aufruf führt nur eine aktuelle Anfrage aus.

Die Regressionstests verwenden Java 25. Der Befehl im Repository-Stamm prüft die Profilwahl, den Quellenwechsel, die Graphplanung, die Kosten des Rückfalls und beide SDK-Einstiege.

```sh
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=BilinearIntegerPortfolioTest,FiniteConstraintPlanLearningTest,CertificateWorkTest,BoundedPlanningSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
