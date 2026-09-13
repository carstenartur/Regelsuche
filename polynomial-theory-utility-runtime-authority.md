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
| FactorizationAttempt | v1 | v2 mit tatsächlicher Pipeline-/Roharbeitsbindung |
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

`PolynomialTheoryUtilityFactorizationAttempt.createObserved` erzeugt dafür
Attempt v2 aus dem tatsächlichen, vom Kern ausgestellten Nested-Pipeline-
Resultat. Die Bindung hält Auftretensindex, Pfad, Originalsource-Nachweis,
Pipeline-Zertifikat und das vollständige Rohledger der ausgeführten
Faktorisierung fest. Backend, Request, Kandidaten und Report müssen genau
diesem Resultat entsprechen. Ein öffentlicher Konstruktor kann diese
Metadaten auch mit neu berechneter Attempt-ID nicht durch fremde Hashes
ersetzen. Pfad und Rohledger können nicht als frei geschätzte Daten in die
Ausführungsbindung geschrieben werden.

Die Messprüfung fordert diese Bindung für jeden Versuch eines beobachteten
Resultats. Sie prüft das tatsächliche Auftreten und verbietet mehrfaches
Zählen derselben Pipeline-Ausführung. Die Faktorisierungsstages der Versuche
müssen die entsprechenden Stages der Auftretensarbeit exakt erklären;
sämtliche weitere vom Versuch verbrauchte Arbeit muss im selben Auftreten
enthalten sein. Die Prüfung vergleicht rohe Einheiten, keine einzeln
gerundeten Kosten. Null-Einträge aus Präfixdifferenzen bleiben unverändert
gespeichert und begründen keine zusätzliche Arbeit.

Damit genügen ein weiterer syntaktisch gültiger Versuch, ein geteilter
Arbeitsanteil oder ein zum identischen Geschwister verschobener Versuch
nicht mehr. Die beobachtete Serialisierung enthält die neue Attempt-Revision
und die vollständige Bindung. Ihre bestehenden Ergebnis- und Messumschläge
binden die neuen Attempt-IDs. Historisches `create`, der bisherige
Konstruktor, Attempt-v1-IDs und historische Receipt-Bytes bleiben getrennt
und unverändert. Ein ungebundener v1-Versuch wird nicht automatisch als
beobachteter Nachweis umgedeutet; er bleibt im historischen Resultatvertrag.

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

## Revisionsstrenger Mess- und Runner-Einstieg

`NoFactorizationAdapter.observed()` ergänzt den produktiven Kontrollpfad:
jeder eingefrorene Auftretenspfad erhält den Ausgang `NO_TRANSITION` mit
`FACTORIZATION_DISABLED_BY_FROZEN_PROFILE`, leerer Roharbeit und ohne
Faktorisierungsversuche oder Cache-Ereignisse. Der gewöhnliche Konstruktor
behält seinen bisherigen `Run` und historische Resultate v2. Der beobachtete
Run liefert seine Nullmessung ausdrücklich über `MeasuredRun`; der Runner
rekonstruiert keine vermeintlich fehlenden Messungen.

`PolynomialTheoryUtilityProfileAdapter.resultSchema()` deklariert die
Ergebnisrevision und ist für bestehende Adapter standardmäßig historisch.
Beide produktiven `observed()`-Fabriken deklarieren ausdrücklich v3.
`MeasuredRun.executeObserved` prüft das tatsächliche Resultat v3 samt
Messungen v2. `PolynomialTheoryUtilityMeasuredExecution.executeObserved`
prüft zuerst das vollständige eingefrorene Adapterinventar und sämtliche
Revisionsdeklarationen, bevor ein Run öffnet. Anschließend fordert es pro Run
`MeasuredRun` und prüft jede tatsächliche Ergebnisrevision unmittelbar nach
der Ausführung. Ein falsch deklarierter oder im Verlauf auf v2 wechselnder
Adapter wird an seiner ersten falschen Zeile abgewiesen; weitere Zeilen
laufen nicht mehr und die bestehende Run-Schließung bleibt wirksam.

Der Einstieg nutzt denselben `TargetBlindRunner`, dieselbe Eingabereihenfolge
und dieselben bereits versionierten Batch-/Messverträge. Er verändert weder
Adapter-/Profilidentitäten noch historische `execute`-/`executeMeasured`-
Aufrufe, Arbeitsprojektion oder Receipts. Die explizite Deklaration ersetzt
keine Prüfung der tatsächlichen Ergebnisse. Eine automatisch auf historische
Resultate zurückfallende Ausführung gibt es in diesem Einstieg nicht.

## Verbleibendes produktives Adapterinventar

Die fünf eingefrorenen Profile entsprechen noch keinem vollständigen Satz
produktiver Studienadapter. Die Produktkomposition
`PolynomialSearchIntegration` ist von der Studienmessung zu unterscheiden:

| Eingefrorenes Profil | Beobachteter Studienpfad | Noch nötige Arbeit |
| --- | --- | --- |
| `NO_FACTORIZATION` | `NoFactorizationAdapter.observed()` | Keine zusätzliche mathematische Messgrundlage für diesen Nullarbeits-Kontrollpfad. |
| `ON_DEMAND_VERIFIED_FACTORIZATION` | `PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.observed(...)` | Vollständige Studienausführung und Reproduktion bleiben separat. |
| `VERIFIED_DERIVED_MACRO_CACHE` | Kein produktiver Studienadapter | Gemessene Retention mit Vorab-Arbeitszulassung sowie Auftreten-/Zeilen-übergreifende Eintrags- und FIFO-Lineage des tatsächlichen Run-Caches. Lookup und Replay besitzen bereits eigene Roharbeitsnachweise. |
| `SPECIALIZED_BINARY_QUARTIC_CONTROL` | Kein produktiver Studienadapter | Additive Messgrenze für semantische Extraktion, Spezialzerlegung und Rendering einschließlich erfolgloser Ausgänge und Originalanforderung. Der vorhandene Spezialbericht enthält nur die Verifier-Arbeit. |
| `OPTIONAL_EXTERNAL_VERIFIED_FACTORIZATION` | Kein produktiver Studienadapter | Ausdrücklich gebundene externe Engine und deren tatsächliche Arbeit, Fehlerausgänge und Nachweise in derselben Zeilenautorität; kein Ersatz durch den nativen Adapter. |

Die Roharbeit dieser fehlenden Pfade darf nicht aus Ergebnisstrings geschätzt
oder aus dem nativen Profil übernommen werden. Insbesondere bietet die
vorhandene Cache-Retention noch keine vollständig gemessene Studiengrenze.
Eine Produkt-Kostenschätzung für Cache-Einträge ist kein nachträglicher
Roharbeitsnachweis. Diese Messfundamente benötigen eigene additive Verträge.

`CandidateMeasurementBatch` bindet die Ergebnisse und Messungen jeder Zeile,
prüft jedoch noch keine vollständige Insert-/Hit-/Evict-Geschichte über einen
Run hinweg. Der revisionsstrenge Einstieg ergänzt diese fehlende
Cache-Autorität nicht und ist keine vollständige produktive Adapterfactory.
Die Runner-Kontrolle verwendet ausdrücklich synthetische Nullarbeitsadapter
für die übrigen Profile und führt einen echten nativen Formationsfall innerhalb
seines Mess-Runs aus; sie prüft nur Reihenfolge, Revision und Messbindung.
Der zusätzliche native Kontrolllauf verwendet die 20 öffentlichen
Formationsfälle eines vorhandenen Checkpoints. Weder diese Komponentenfälle
noch die synthetischen Batches erzeugen eine Studien-Freeze oder belegen
Utility. Historische APIs und der bekannte historische Receipt bleiben
unverändert kontrolliert.

Diese technische Vertragskorrektur schließt #748 nicht. Die vollständigen
aktiven Profiladapter, alle 600 Zeilen unter der neuen Messrevision und die
vorgeschriebenen Reproduktionen stehen weiterhin aus. Erst danach darf die
vorgeschriebene Qualifikation folgen. Die vorliegende Änderung öffnet keine
Qualifikationsdaten, ändert keine Schwellen oder Budgets und begründet keine
Utility- oder Default-Entscheidung.
