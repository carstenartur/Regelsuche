# Typisierter Candidate-Result-Vertrag der Polynomstudie

Status: aktiver Resultatcontainer in Runner und Pre-Freeze-Batch

Bezug: Issue #748

## Zweck

Die typisierten Übergänge und Arbeitsvektoren bilden erst dann eine belastbare
Studienzeile, wenn ein Resultatcontainer ihre Reihenfolge, Herkunft und
Gesamtarbeit gemeinsam bindet. Der Container darf weder einzelne lokale
Aufwände verdecken noch Übergänge nachträglich umordnen, duplizieren oder auf
einen anderen Ausführungseingang übertragen.

`PolynomialTheoryUtilityCandidateResult` ist der eigenständige v2-Vertrag für
alle Adapter-Runs und das noch nicht kanonisch eingefrorene Ergebnisbatch. Der
frühere verschachtelte v1-Datensatz im Adapter wurde entfernt, damit Runner,
Batch und spätere Candidate-Freeze nicht zwei konkurrierende
Resultatautoritäten besitzen.

## Gebundene Felder

Ein Resultat enthält:

- die inhaltlich adressierte Resultatidentität,
- den exakten eingefrorenen Ausführungseingang,
- die unveränderte Quellwurzel aus dem Formation-Korpus,
- einen terminalen Status und einen maschinenlesbaren Detailcode,
- den typisierten aggregierten Arbeitsvektor,
- die geordnete unveränderliche Liste der Übergangsergebnisse,
- das Ergebnis der unabhängigen Verifikation.

Die Resultatidentität bindet alle Felder einschließlich der Übergangsanzahl und
der Übergangsidentitäten in Listenreihenfolge. Eine zulässige Neuindizierung in
anderer Reihenfolge erzeugt deshalb eine andere Resultat- und
Übergangsevidenzidentität. Eine bloße Umordnung mit alten Indizes wird
abgewiesen.

## Formation- und Profilbindung

Der Container lädt die eingefrorenen Ausführungseingänge und Formation-Fälle
als eigene Validierungsautorität. Konstruktion und spätere
`validateAgainst`-Prüfung fordern:

- den wertgleichen vollständigen Eingang aus der eingefrorenen Matrix,
- dieselbe Fallidentität,
- den byte-/wertgleich geladenen Formation-Fall,
- dieselbe Quellwurzel,
- für jeden Übergang die schon im Übergangsvertrag geprüfte Profil-, Backend-
  und Transformationsbindung.

Ein Fall mit passender Ausdrucksform, aber anderer Fallidentität, ist damit
keine zulässige Substitution.

## Arbeitsdeckung

Der aggregierte Arbeitsvektor muss drei Grenzen einhalten:

1. primitive Arbeit höchstens das zugelassene primitive Budget,
2. mechanische Arbeit höchstens das eingefrorene mechanische Budget,
3. Faktorisierungsarbeit höchstens das Faktorisierungsbudget.

Profilregeln gelten auch für den Aggregatvektor: Ein deaktiviertes Backend darf
keine Faktorisierungsarbeit und ein deaktivierter Cache keinerlei Cache-Arbeit
behalten. Ein übergangsfreies Resultat darf keine Einfügungs-, Verdrängungs-
oder Replay-Arbeit behaupten.

Zusätzlich werden die lokalen Arbeitsvektoren aller Übergänge exakt addiert.
Der Resultatvektor muss diese Summe komponentenweise abdecken. Matching-Arbeit
kann somit beispielsweise nicht durch einen höheren Wert in der
Evidenzerzeugung verborgen werden. Nicht einem einzelnen Übergang zurechenbare
Orchestrierungsarbeit darf als zusätzlicher Aggregataufwand erhalten bleiben.

## Terminale Evidenz

`VALIDATED_TRANSITION` verlangt mindestens einen Übergang und exakt den
Verifier-Ausgang `VERIFIED`. Alle anderen terminalen Zustände müssen ohne
Übergänge auskommen und dürfen keine Verifikationsautorität behaupten:

- `NO_TRANSITION`,
- `UNSUPPORTED`,
- `BUDGET_INCONCLUSIVE`,
- `TECHNICAL_FAILURE`.

Ein leerer erfolgreich-verifizierter Datensatz und ein fehlgeschlagener
Datensatz mit versteckten Übergängen werden beide fail-closed abgewiesen.

## Abgeleitete Übergangsevidenz

Für kompatible Auswertungen erzeugt der Container einen separaten SHA-256 über
die geordnete Liste der Übergangsidentitäten. Bei null Übergängen lautet der
Wert ausdrücklich `NONE`. Dieser Wert ersetzt nicht die vollständige Liste und
besitzt keine eigene Entscheidungsautorität.

## Runner- und Batch-Integration

`PolynomialTheoryUtilityProfileAdapter.Run.execute` liefert ausschließlich
diesen v2-Typ. Der Nulladapter erzeugt mit dem sichtbaren Formation-Fall ein
Zero-Work-Resultat, sodass dessen Quellwurzel Teil der Resultatidentität bleibt.

Der 30-Run-Orchestrator prüft jedes zurückgegebene Resultat unmittelbar gegen
den exakten Matrixeingang und den positionsgleichen Formation-Fall. Das
`CandidateBatch` wiederholt diese Prüfung für alle 600 Positionen, verlangt
eindeutige Resultatidentitäten und veröffentlicht deshalb das Schema:

```text
regelsuche.polynomial-theory-utility-candidate-batch/v2
```

Das Batch ist weiterhin nur eine unveränderliche In-Memory-Grenze mit Status
`TARGET_BLIND_RESULTS_COLLECTED_NOT_FROZEN`.

## Verbleibender Umfang

Noch nicht umgesetzt sind:

- die kanonische Candidate-Freeze-Serialisierung des v2-Batches,
- die vier mathematischen Profiladapter,
- die Öffnung und Auswertung der versiegelten Qualifikation,
- die mechanische Studienentscheidung,
- Änderungen an Ausführungsplan, Formation, Produkt- oder Suchvorgaben.

Die nächste Tranche darf die Candidate-Freeze erst aus dem vollständig
migrierten v2-Batch erzeugen. Eine Rückkehr zum entfernten aggregierten
v1-Ergebnisformat ist nicht zulässig.
