# Wann spart ein neu gelerntes Teilschema Sucharbeit?

Regelsuche hat aus vier verschiedenen Lehrpfaden die ausführbare Regel `(A+B)*(A-B)+B*B → A²` gebildet und auf zwölf von 24 zurückgehaltenen Aufgaben eingesetzt. Der Vergleich mit abgeschalteten Schema-Anbietern ergibt auf diesen zwölf Aufgaben 196 eingesparte Arbeitseinheiten. Auf den zwölf anderen Aufgaben kostet der Anbieter 1.128 zusätzliche Einheiten. Über das gesamte Panel wächst die Sucharbeit bei gleichem Lösungserfolg um 932 Einheiten (+16,3 %).

Die acht primitiven Regeln enthielten bereits die Differenz von Quadraten, das Quadrieren eines gleichen Faktorpaars und die Kürzung eines Addenden. Die Zusammensetzung zu einem wiederverwendbaren Schritt entstand aus TRAIN. Der öffentliche synthetische Versuch prüft neue äußere Aufgabenfamilien. Drei davon verwenden absichtlich dieselbe lokale Algebra wie TRAIN. Die anderen drei prüfen eine alternative Faktorisierung und zwei unerreichbare Ziele. [Eingaben und Abnahmekriterien](protocol.md)

## Erwerb und Quellenbindung

Vier TRAIN-Aufgaben erforderten je vier primitive Schritte. Sie ändern ihre Teilbäume unter Addition, Multiplikation oder Subtraktion. Die gemeinsame Teilaufgabe und ein unabhängiger Nachbarzweig ändern sich in unterschiedlicher Reihenfolge. Die bisherige Endpunktformation erzeugte aus den vier vollständigen Lösungswegen kein Schema. Die lokale Formation erzeugte drei symbolisch geprüfte Schemata. Der getrennte GATE mit vier Aufgaben aktivierte die resultierende Generation vor FINAL. Beide Formationen verwendeten nachweislich die gleichen vier primitiven Pfade und 952 Einheiten für deren Suche. Die Prioritätstabelle zählt 16 Kanten aus genau diesen Pfaden.

Alle 16 ausgewählten gelernten Schritte pro FINAL-Lauf verwendeten das vollständige Kompensationsschema mit der Kennung `14cbff69…`. Bei zwei Lehrpfaden waren seine globalen Schrittindizes aufeinanderfolgend, bei zwei weiteren lag eine unabhängige Änderung am anderen Operanden dazwischen. Die Studie umfasst zusammenhängende und auf Teilbäume projizierte Pfade; der jeweilige Einzelbeitrag dieser beiden Mechanismen bleibt offen. [Geltungsbereich der Implementierung](implementation-scope.md) · [Erwerb und GATE](evidence/freeze.json) · [Geprüfte Modelle](evidence/local.json)

## Vergleich auf FINAL

„Gelöst“ verlangt das vorab deklarierte Knotenlimit, eine gültige ausgewählte Herleitung und ein eingehaltenes Suchbudget. Zwölf Fälle enthalten einen oder zwei neue Treffer, vier weitere lassen sich mit primitiver Faktorisierung lösen. Je vier Residual- und triviale Negativkontrollen verfehlen das Ziel. Alle 144 Kombinationen aus Arm und Eingabe haben vollständige Suchkostenzähler. Ein unabhängiger Koeffizientenvergleich bestätigte auch die Endausgaben aller erfolglosen Suchen.

| Arm | Gelöst / 24 | Fälle mit Schema | Erwerb inkl. Prioritäten | Laden + Vorbereitung | Sucharbeit inkl. Wiederprüfung | Gesamt | Separater Koeffizientenaudit | Median Laufzeit / Thread-CPU in ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| BASE · primitive Suche | 16 | 0 | 0 | 0 | 4.540 | 4.540 | 1.512 | 121,5 / 108,6 |
| ORDER · gelernte Reihenfolge | 16 | 0 | 968 | 0 | 4.844 | 5.812 | 1.512 | 76,7 / 76,3 |
| LOCAL · Reihenfolge und lokale Schemata | 16 | 12 | 9.099 | 1.398 | 6.656 | 17.153 | 1.512 | 387,0 / 223,0 |
| DROP · identischer LOCAL-Zustand ohne Schema-Anbieter bei der Abfrage | 16 | 0 | 9.099 | 1.398 | 5.724 | 16.221 | 1.512 | 109,5 / 70,0 |
| WHOLE · bisherige Endpunktformation und Reihenfolge | 16 | 0 | 1.713 | 1 | 4.844 | 6.558 | 1.512 | 66,4 / 63,0 |
| HAND · festes direktes Polynomprofil | 4 | 0 | 0 | 0 | 234.424 | 234.424 | 1.484 | 231,7 / 173,6 |

Bei LOCAL entfallen 952 Einheiten auf die Trainingssuchen, 4.337 auf die Formation, 688 auf die Vorbereitung des Kandidaten, 2.436 auf die Validierung, 40 auf die Auswahl, 630 auf die Prüfung der Aufgabentrennung und 16 auf die Rangzählung. Lade- und Suchkosten kommen hinzu. DROP behält den gleichen geprüften Solver, Zeitplan, Prioritäten und Erwerb. Nur die gelernten Anbieter fehlen bei der Abfrage. BASE nutzt den günstigeren ursprünglichen nativen Zeitplan. Die acht primitiven Regeldefinitionen bleiben in den ersten fünf Armen gleich.

Das direkte Polynomprofil löst alle vier Faktorisierungen. Auf den vier Residualfällen verkleinert es die Endausgabe von 13 auf 7 Knoten. Das dort verlangte Ziel liegt bei 3. Die Zielvorgabe misst, ob eine bestimmte Kompaktheit erreicht wird. Andere Polynomziele können diesen Profilen unterschiedliche Stärken zuweisen.

| FINAL-Familie, je vier Fälle | Gelöst LOCAL / DROP | Schema-Schritte | LOCAL | DROP | LOCAL minus DROP |
|---|---:|---:|---:|---:|---:|
| Summe zweier Kompensationen | 4 / 4 | 8 | 2.296 | 2.328 | −32 |
| Quadrierter Kontext mit kompensiertem Produkt | 4 / 4 | 4 | 940 | 988 | −48 |
| Rationaler Quotient | 4 / 4 | 4 | 808 | 924 | −116 |
| Gemeinsamer Faktor | 4 / 4 | 0 | 748 | 428 | +320 |
| Ähnlicher, nicht wegfallender Rest | 0 / 0 | 0 | 1.480 | 768 | +712 |
| Irreduzible Summe | 0 / 0 | 0 | 384 | 288 | +96 |

Auf den drei Transferfamilien sinkt die Sucharbeit gegenüber DROP von 4.240 auf 4.044 (−4,6 %). Die unmittelbare primitive Suche benötigt dort 3.696 Einheiten. Auf den drei übrigen Familien steigt die Arbeit von 1.484 auf 2.612. Über das gesamte Panel erreicht der Lernarm weder das präregistrierte Kriterium eines niedrigeren Suchaufwands noch eine Amortisation der Erwerbskosten. Ein positiver, außerhalb der gezielt geteilten lokalen Algebra übertragbarer Nutzen ist damit weiterhin offen. [Fälle mit Ausgaben, Pfaden und Arbeitskonten](evidence/final.jsonl)

## Wiederholung

Vor der ersten Ausführung wurde das [Protokoll veröffentlicht](https://github.com/carstenartur/Regelsuche/commit/f070e56c9c7537010a963aea90d91979d1334f44). Vor FINAL wurde der [Modellstand samt Prüfsumme veröffentlicht](https://github.com/carstenartur/Regelsuche/commit/ad292fecf20931f911c57670f98e83ebca02de1b). Drei frische JVMs mit rotierten Armreihenfolgen lieferten je 144 gleiche fachliche Ergebnisse einschließlich exakter Arbeitskonten, Beweispfade und Endausgaben. Die 144 stabilen Ergebniszeilen sind einmal gespeichert. Die drei [Zeitreihen und Rohdatei-Digests](evidence/times.json) gehören zum ersten Versuch mit Temurin 25.0.5+8 auf Linux. Die Zeiten erfassen Solveraufbau, Laden, Parsen der Eingaben, Suche, beide integrierten Prüfungen und den separaten Koeffizientenaudit. Das vorab durchgeführte Training, JVM-Start und die Ausgabe der Gesamtergebnisdatei liegen außerhalb des Zeitfensters. Die logischen Einheiten der Solver messen gezählte Operationen. Für die initiale Konstruktion der primitiven Anbieter und für gewöhnliche JVM-/JSON-/Dateiarbeit gibt es keinen vollständigen logischen Zähler. JIT, Klassenladen, GC und die übrige Hostlast waren nicht isoliert. Diese drei Beobachtungen erlauben keine belastbare allgemeine Zeitrangfolge.

Eine zweite Serie nach der Begrenzung sehr langer Herkunftsmarken rekonstruierte TRAIN-Dateien und Modellbytes identisch. 432 von 432 fachlichen Paaren stimmten mit dem Erstlauf überein. Die zusätzliche Erfassung der Kennung aus dem Beleg jedes ausgewählten gelernten Schritts bestätigte die oben ausgewiesenen 16 Anwendungen. [Wiederholungsbeleg mit Schema-IDs](evidence/reproduction.json)

Mit JDK 25 und Maven im Projektverzeichnis ausführen. Jeder Maven-Aufruf startet eine neue Test-JVM. Neue Ausgabepfade verwenden, da der Runner vorhandene Ergebnisse schützt.

```sh
study_dir=$(mktemp -d)
study_digest=sha256:e08c31e2002624414e2daa827859c2ff1188b4886a2362eac798a1f5ef1fbc02
mvn -B -pl regelsuche-learning -am -Dtest=LocalTraceFamilyStudyTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DlocalTrace.phase=train \
  -DlocalTrace.directory="$study_dir" test
for rotation in 0 1 2; do
  mvn -B -pl regelsuche-learning -am -Dtest=LocalTraceFamilyStudyTest \
    -Dsurefire.failIfNoSpecifiedTests=false -DlocalTrace.phase=final \
    -DlocalTrace.directory="$study_dir" -DlocalTrace.digest="$study_digest" \
    -DlocalTrace.rotation="$rotation" -DlocalTrace.output="$study_dir/final-$rotation.json" test
done
```
