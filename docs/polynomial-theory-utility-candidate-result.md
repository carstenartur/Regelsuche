# Typisierter Candidate-Result-Vertrag der Polynomstudie

Status: Resultatcontainer vor Runner-Migration und Candidate-Freeze

Bezug: Issue #748

## Zweck

Die typisierten Übergänge und Arbeitsvektoren bilden erst dann eine belastbare
Studienzeile, wenn ein Resultatcontainer ihre Reihenfolge, Herkunft und
Gesamtarbeit gemeinsam bindet. Der Container darf weder einzelne lokale
Aufwände verdecken noch Übergänge nachträglich umordnen, duplizieren oder auf
einen anderen Ausführungseingang übertragen.

`PolynomialTheoryUtilityCandidateResult` ist deshalb ein eigenständiger
v2-Vertrag. Der bestehende Runner verwendet ihn in diesem Schritt noch nicht.
Die Trennung erlaubt, die Resultatsemantik unabhängig von Adapter- und
Orchestrierungsänderungen zu prüfen.

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

Der Container lädt die eingefrorenen Formation-Fälle als eigene
Validierungsautorität. Konstruktion und spätere `validateAgainst`-Prüfung
fordern:

- denselben vollständigen Ausführungseingang,
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

## Umfang dieses Schritts

Dieser Schritt verändert noch nicht:

- `PolynomialTheoryUtilityProfileAdapter.Run`,
- den 30-Run-Orchestrator,
- `CandidateBatch`,
- die Candidate-Freeze-Serialisierung,
- mathematische Profiladapter,
- Ausführungsplan, Formation oder versiegelte Qualifikation,
- Produkt- oder Suchvorgaben.

Die nächste Tranche migriert den Runner und den Batch atomar auf diesen
Container. Erst danach dürfen echte Profilresultate kanonisch eingefroren
werden.
