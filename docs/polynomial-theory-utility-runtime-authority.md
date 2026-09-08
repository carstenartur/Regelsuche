# Laufzeitautorität der Polynomstudie

Bezug: #748. Die Qualifikation bleibt versiegelt. Dieses Dokument präzisiert
die Ausführung der bestehenden Arbeitsprojektion v2; Fallauswahl, Quanten,
Checkpoint-Budgets, Profile und Messschemas werden nicht geändert.

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

## Noch offene Messgrenze

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

Der native Adapter bricht bei einem solchen gemischten Ausgang die
Batch-Erzeugung ausdrücklich ab. Er löscht weder erfolgreiche Teile noch
negative Geschwister, um eine Candidate-Freeze erzeugen zu können. Vor einer
vollständigen Fünf-Profil-Studie muss diese vorab erkennbare Vertragsgrenze
separat geklärt werden. Dieses Dokument autorisiert keine nachträgliche
Schemaänderung, keinen unvollständigen 600-Zeilen-Freeze und keine Öffnung der
Qualifikation.

Eine getrennte Vertragskorrektur müsste mindestens folgende Punkte gemeinsam
versionieren: negative Resultate mit vollständig erhaltener Replay-Arbeit;
Replay-Versuche ohne akzeptierten Übergang; geordnete terminale Beobachtungen
für jedes Auftreten, auch bei gemischtem Ausgang; und eine Freeze-Bindung an
deren vollständige Rohpartition. Sie dürfte weder Fallbudgets noch Quanten,
Backendauswahl oder Qualifikationsdaten ändern. Vor der Qualifikation wären
alle 600 Zeilen gegen diese neue Messrevision erneut auszuführen und die
vorgeschriebenen Reproduktionen zu erstellen. Der vorliegende Adapter nimmt
diese Änderung des eingefrorenen Vertrags ausdrücklich nicht vor.
