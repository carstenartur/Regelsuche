# Laufzeitautorität der Polynomstudie

Bezug: #748. Die Qualifikation bleibt versiegelt. Dieses Dokument präzisiert
die Ausführung der bestehenden Arbeitsprojektion v2 und die ausdrücklich
versionierte Ergebnisdarstellung vollständiger Auftretensbeobachtungen.
Fallauswahl, Quanten, Checkpoint-Budgets und Profile bleiben unverändert.

## Zulassung vor der Operation

`PolynomialTheoryUtilityWorkAuthority` hält genau eine kumulative rohe Ledger
pro Eingabezeile. Projektor, exakte Extraktion, Renderer, Rekonstruktion und
verschachtelter Ersatz verwenden dieselbe Instanz. Vor jedem erfassten
Arbeitsschritt wird die vorgeschlagene Gesamtledger mit der bestehenden
v2-Projektion geprüft. Nur eine zulässige Erweiterung wird übernommen.
Zurückgewiesene Arbeit wird nicht ausgeführt oder nachträglich abgeschnitten.

Die beiden Projektor-Stages für Literalanzahl und kodierte Literalvalidierung
werden atomar zugelassen. Damit kann ein Abbruch keine halbe, nicht mehr
dekodierbare Rohpartition erzeugen. Rundungen erfolgen auf den kumulativen
Stage-Summen, auch über mehrere Auftreten hinweg.

Engine und gemeinsamer Verifier erhalten vor ihrem synchronen Aufruf eine
konservative rohe Obergrenze: das Minimum der verbleibenden mechanischen und
Faktorisierungsarbeit. Ihre vollständige Ergebnisledger wird vor dem nächsten
Schritt übernommen. Jede rohe Einheit passt damit auch ohne Rabatt in beide
Grenzen. Unverbrauchte Arbeit verbleibt in derselben Autorität. Die gemeinsame
Engine-/Verifier-Grenze ist konservativ; sie verspricht keine optimale
Ausschöpfung getrennter Dimensionsbudgets. Die tatsächliche Verteilung bleibt
in der unveränderten Projektion sichtbar.

Ein Request-Dispatch zählt vor seinem Aufruf als eine zusätzliche, unbekannte
und deshalb ungekürzt berechnete Faktorisierungseinheit. Zusätzlich wird sein
Attempt-Datensatz mit einer Evidenzeinheit zugelassen. Auch ein Request mit
leerem Backend-Report bleibt so samt verbrauchter Arbeit messbar. Ohne genug
Arbeit für Dispatch und mindestens eine rohe Backend-Einheit entsteht kein
Request.

Lehnt die gemeinsame Autorität bereits den Dispatch ab, liefert die Pipeline
`BUDGET_INCONCLUSIVE` mit der vollständig erhaltenen Extraktionsarbeit; Engine
und Verifier werden nicht aufgerufen. Eine Ablehnung der Ergebnisledger nach
dem synchronen Aufruf ist dagegen ein Bruch der zugesicherten konservativen
Obergrenze. Die Pipeline meldet dafür
`OPAQUE_FACTORIZATION_WORK_REJECTED_BY_SHARED_AUTHORITY` als technischen
Invariantenfehler mit der ursprünglichen Ursache. Bereits ausgeführte Arbeit
darf dadurch nicht als gewöhnlicher Budgetabbruch ohne Request erscheinen.

Die bisherigen Komponentenobergrenzen gelten zusätzlich als
Repräsentationsschutz. Sie erteilen dem Studienadapter keine weitere
kanonische Arbeit. Bestehende Aufrufer ohne zusätzliche Arbeitsautorität
behalten ihren bisherigen Vertrag.

## Nativer Adapter

Der native Adapter verarbeitet die eingefrorenen Pfade in numerischer
Reihenfolge und führt jeden Auftretensversuch gegen die sichtbare Quellwurzel
aus. Alle Auftreten teilen die Zeilenautorität. Eine erfolgreiche frühere
Position setzt kein Budget für eine spätere Position zurück.

`Execution` behält den vollständigen Rohvektor, seine v2-Projektion sowie jede
Position einschließlich Budgetabbrüchen vor einem Pipeline-Aufruf. Ein
Pipeline-Ergebnis trägt seine originale Request-/Verifier-/Transformations-
Evidenz. Die sieben primitiven Evidenzschritte stammen aus dem gemeinsamen
`VerifiedTransition`; AST-Pfad und Suchpfadtiefe werden getrennt erfasst.

Der Adapter akzeptiert ausschließlich echte eingefrorene Eingabeumschläge und
Formationsfälle. Synthetische größere Arbeitsautoritäten existieren nur in
Komponententests und gelangen nicht in einen Studienresultatvertrag.

## Historische Messgrenze

Das eingefrorene Ergebnis erlaubt Übergänge ausschließlich zusammen mit
`VALIDATED_TRANSITION`. Ebenso sind Cache-Replay-Arbeit und Replay-Ereignisse
an einen erfolgreichen Übergang gebunden. Ein bereits begonnener Replay mit
anschließendem Budgetabbruch lässt sich dort nicht als negatives Resultat mit
seiner vollständigen Arbeit darstellen. Auch ein gemischter Ausgang mehrerer
Auftreten darf nicht stillschweigend zu einem vollständig erfolgreichen Fall
werden.

`PolynomialTheoryUtilityInterruptedReplayContractTest` reproduziert die
Cache-Grenze mit einem echten verifier-autorisierten Eintrag und dem
eingefrorenen Checkpoint `CP03_1_OF_3` des Vier-Auftreten-Falls. Der Replay
bricht innerhalb der zulässigen Arbeit ab; der unveränderte Resultatvertrag
verwirft genau die dafür notwendige negative Beobachtung. Der Seed-Eintrag
ist eine Komponenten-Testfixture, keine vorgetäuschte Studien-Cachehistorie.

Der historische Einstieg des nativen Adapters bricht bei einem solchen gemischten Ausgang die
Batch-Erzeugung ausdrücklich ab. Er löscht weder erfolgreiche Teile noch
negative Geschwister, um eine Candidate-Freeze erzeugen zu können. Dieser
Vertrag bleibt für historische Resultate unverändert reproduzierbar.

## Expliziter beobachteter Ergebnisvertrag

`PolynomialTheoryUtilityCandidateResult.createObserved` erzeugt Resultate v3.
`PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.observed` öffnet
den entsprechenden produktiven Run; `executeObservedCase` führt eine einzelne
echte eingefrorene Eingabe aus. Der bisherige Konstruktor und `create` erzeugen
weiterhin historische Resultate. Alte Receipts werden nicht umgedeutet oder
automatisch in eine neue Messrevision umgewandelt.

`PolynomialTheoryUtilityExecutionObservations` bindet Vorarbeit und genau eine
terminale Beobachtung für jeden eingefrorenen Pfad in numerischer Reihenfolge.
Jede Beobachtung enthält den Detailcode, den ursprünglichen Pipeline-Nachweis
(oder `NONE`, falls die Pipeline nicht aufgerufen wurde), den optionalen
akzeptierten Übergang, primitive und rohe Arbeit sowie geordnete Referenzen
auf Faktorisierungsversuche und Cache-Ereignisse. Auch nach verbrauchtem
Budget bleiben die übrigen Auftreten mit negativem Ausgang sichtbar.

Unterschiedliche terminale Kategorien ergeben auf Zeilenebene
`MIXED_OUTCOMES` mit `RETAINED_OCCURRENCE_OUTCOMES`. Akzeptierte Übergänge
bleiben einzeln samt Verifier-/Primitiv-Lineage erhalten; sie verleihen der
gemischten Zeile kein globales `VERIFIED`. Der Pipeline-Nachweis eines
erfolgreichen Auftretens muss genau dem `transitionEvidenceHash` seines
akzeptierten Übergangs entsprechen. Ein negativer Ausgang löscht keinen
erfolgreichen Vorgänger. Unterschiede innerhalb einer Kategorie, etwa
`NO_CANDIDATE` und `IRREDUCIBLE`, bleiben in Detailcode und Pipeline-Nachweis
erhalten.

`PolynomialTheoryUtilityCacheEvent.createReplayAttempt` bindet einen
ausdrücklichen terminalen Replay-Ausgang. Ein negativer Replay benötigt keinen
akzeptierten Übergang, aber seinen vorausgehenden Lookup-Hit, dieselbe
Eintragsidentität, den passenden Auftretensausgang und erhaltene Replay-Arbeit.
Cache-Mutationen bleiben an akzeptierte Übergänge gebunden. Alte v1-Ereignisse
behalten ihre ursprüngliche Bedeutung.

| Vertrag | Historisch | Vollständige Beobachtung |
| --- | --- | --- |
| CandidateResult | v2 | v3 |
| CandidateMeasurements | v1 | v2 |
| CacheEvent | v1 | v2 für Replay mit terminalem Ausgang |
| CandidateBatch | v2 | v3 |
| CandidateMeasurementBatch | v1 | v2 |
| CandidateFreeze | v1 | v2 |

Die Roharbeit aus Vorarbeit und Auftreten muss sich exakt zur vollständigen
Dimensionspartition addieren. Jeder kumulative Präfix wird mit der
unveränderten Arbeitsprojektion v2 gegen dieselbe Eingabeautorität geprüft.
Übergangsarbeit entspricht der Differenz zweier kumulativer Projektionen;
einzeln gerundete Auftretenskosten werden nicht addiert. Attempts und Events
dürfen weder fehlen noch doppelt auftauchen oder in Vorarbeit verschwinden.

Die neue Freeze bindet die gesamte Rohpartition, Vorarbeit und jedes
Auftreten im kanonischen JSON und schreibt ausschließlich
`polynomial-theory-utility-candidate-freeze-v2.json`. Bereits Rohänderungen
unterhalb eines Rundungsquantums verändern die Ergebnis-/Mess-/Freeze-IDs.
Ein Batch mit gemischten historischen und beobachteten Resultaten wird vor
einer Receipt-Erzeugung zurückgewiesen. Die v1-Datei und ihre kanonischen Bytes
bleiben getrennt; `PolynomialTheoryUtilityHistoricalReceiptTest` prüft einen
Receipt, der mit den unveränderten Quellen aus Main `3f437b044dc9` ermittelt
wurde.

Die neuen Komponententests verwenden echte autorisierte Replay-Einträge und
die unveränderten Checkpoints `CP03_1_OF_3` und `CP06_FULL` des Vier-Auftreten-
Falls. Sie zeigen sowohl einen rein negativen Replay als auch erfolgreiche
und später abgebrochene Replays in derselben Zeilenautorität. Seed-Ableitung
und synthetische Serialisierungsbatches sind ausdrücklich Komponentenfixtures;
sie ergeben keine gültige Studien-Cachehistorie oder Utility-Evidenz.

Diese technische Vertragskorrektur schließt #748 nicht. Die vollständigen
aktiven Profiladapter, alle 600 Zeilen unter der neuen Messrevision und die
vorgeschriebenen Reproduktionen stehen weiterhin aus. Erst danach darf die
vorgeschriebene Qualifikation folgen. Die vorliegende Änderung öffnet keine
Qualifikationsdaten, ändert keine Schwellen oder Budgets und begründet keine
Utility- oder Default-Entscheidung.
