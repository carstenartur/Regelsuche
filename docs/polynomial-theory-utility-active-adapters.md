# Beobachtete Cache- und Spezialprofile

Bezug: #748, [Laufzeitautorität](polynomial-theory-utility-runtime-authority.md),
[Spezial-Pipeline](measured-polynomial-decomposition-pipeline.md) und
[verifierautorisierter Store](verified-polynomial-transition-cache.md).

## Gemeinsame Grenzen

Beide zusätzlichen Adapter akzeptieren nur die exakten eingefrorenen Inputs
und Formationsfälle in ihrer Run-Reihenfolge. Jedes Auftreten erhält eine
terminale Beobachtung. Parse, Positionsprüfung, mathematische Operationen und
Ergebnisbildung teilen dieselbe nicht rücksetzbare `PolynomialTheoryUtilityWorkAuthority`.
Die vorhandene v2-Projektion partitioniert den tatsächlichen kumulativen
Rohvektor. Kein Checkpoint, Profil, Kandidatenindex oder Qualifikationsinhalt
wird aufgrund eines beobachteten Ergebnisses geändert.

## Cache-Ausführung

`PolynomialTheoryUtilityDerivedCacheAdapter` besitzt pro Run einen leeren
`VerifiedPolynomialTransitionCacheStore` mit Kapazität 128 sowie einen
exakten Quelltextindex. Ein Miss verwendet die bestehende native rationale
Engine mit Kandidatenindex null und der gemeinsamen Verifier-/Occurrence-
Pipeline. Ein Hit verwendet den Store-Lookup und dessen aktuell gültige
Eintragsgeneration. Replay führt keine neue Faktorisierung aus und behält
die ursprüngliche siebenstufige primitive Evidenz.

`retainMeasured` ergänzt den vorhandenen Store um eine issuergebundene
Retention-Receipt. Der dynamisch begrenzte Messkontext zählt tatsächlich
verarbeitete UTF-8-Bytes der gerahmten Hash-Eingaben, Hash-Abschlüsse,
Index-/Lineage-Prüfungen, Eintragswrites und FIFO-Entfernungen. Außerhalb des
Aufrufs ist dieser Kontext nicht gebunden; die historische API und ihre
Identitäten bleiben unverändert.

`lookupMeasured` und `replayMeasured` binden denselben Messkontext an die
jeweilige `cache.lookup.`- beziehungsweise `cache.replay.`-Dimension. Ihre
privat ausgestellten Receipts enthalten die ausgeführte Operationsarbeit
und sämtliche Hash-Eingaben der historischen Lookup-/Replay-Nachweise.
Die ursprünglichen Nachweise, Store-Issuer und Generationsprüfungen bleiben
unverändert. Vor dem Aufruf werden die bestehende Operationsobergrenze und
16384 Einheiten für die begrenzten Nachweisfelder reserviert; dieser feste
Zusatz ist keine ausgeführte Arbeit. Auch Fremd-/Stale-Replays behalten die
tatsächlich konstruierte negative Evidenz. Ohne Zulassung laufen weder die
Operation noch deren Hash-Konstruktion.

Der Quelltextindex zählt außerdem seine tatsächlich gehashten Identifier-
und Lookup-Receipt-Bytes. Indexarbeit und vollständiger Receipt werden
gemeinsam vorab zugelassen, damit ein Budgetabbruch keine Cachearbeit ohne
das zugehörige Ereignis hinterlässt. Die primitive Expansion wird nach
Retention beziehungsweise Replay direkt aus der bereits ausgestellten,
unveränderlichen Evidenz gelesen. Weder eine Größenprüfung noch die spätere
Trace-Erzeugung rekonstruiert dafür ein `VerifiedTransition`-Zertifikat.
Die vorhandene Studienzählung für Ergebnis-/Trace-Datensätze und die
historische native Trace-Hilfe behalten ihren Vertrag.

Vor einer Retention gilt weiterhin die historische Untergrenze aus Quelle,
Ersetzung, Provenienz und 1024 Einheiten. Eine zusätzliche konservative
Komponentengrenze deckt die wiederholte Identitätsprüfung des unveränderlichen
Eintragsgraphen ab: 65536 feste Einheiten plus 256 pro gezählter Material-
Codeeinheit. Der Faktor deckt höchstens 64 Identitätstraversierungen mit bis
zu vier UTF-8-Bytes pro Codeeinheit ab. Bestehende Lineages und Reparse-Literale
sind Teil dieser Grenze. Die folgende Ergebnis-/Indexarbeit bleibt separat
reserviert. Diese Reservierungen werden nicht als ausgeführte Arbeit verbucht;
sie verleihen auch keine zusätzliche Studienarbeit.

Kann die Retention nicht zugelassen werden, entstehen keine Einfügung und
kein akzeptierter Cache-Übergang. Der native Versuch und sein verbrauchtes
Ledger bleiben als `BUDGET_INCONCLUSIVE` erhalten. Insbesondere darf die
1024-Grenze nicht entfernt werden, um in kleinen Checkpoints Cachetreffer zu
erzwingen. Nach echter FIFO-Verdrängung ist eine neue native Ableitung nötig.

`PolynomialTheoryUtilityCacheRunHistory` prüft zusätzlich die Messfolge über
Zeilengrenzen: Jeder Hit benötigt eine frühere Einfügung dieses Runs;
erfolgreiche Replays müssen Quell-/Zielsyntax, Backend, Transformations-ID,
Annahmen und primitive Evidenz bewahren. Verdrängung ist nur bei voller
eingefrorener Kapazität und für den ältesten Eintrag zulässig.

## Spezialkontrolle

`PolynomialTheoryUtilitySpecializedAdapter` verwendet die gemessene
`PolynomialDecompositionSynthesisOperator.factorExpression`-Überladung.
Integer-Request, `BinaryQuarticFactorizationEngine`, bestehende Homogenisierung,
gemeinsamer Verifier und ursprünglicher Renderer bleiben die mathematische
Autorität. Erzeugte Ergebnisse tragen die sechs tatsächlich ausgestellten
Evidenzstufen der Spezial-Pipeline. Sie werden nicht durch die allgemeine
exakte Transformations-ID ersetzt.

Die additive `createObserved`-Überladung bindet denselben Attempt-v2-Vertrag
an das private Spezial-Resultat. Backend, Request, ausgewählter Kandidat,
Report, Quelle, Pfad und vollständige Roharbeit stammen aus dieser tatsächlichen
Ausführung. Historische und native Attempt-Bytes behalten ihren Vertrag.

## Bereitschaft und Evidenzgrenze

Die Registry enthält alle fünf eingefrorenen Profile. Vier besitzen einen
beobachteten Adapter. Das externe Profil bleibt mit
`EXTERNAL_CANONICAL_INTERNAL_WORK_UNAVAILABLE` gesperrt: die gepinnte SymPy-API
belegt ihre Adapteroperationen, aber keine vollständige interne symbolische
Arbeit. Weder eine native Substitution noch erfundene externe Nullarbeitszeilen
füllen diese Lücke. Der vollständige Start scheitert vor dem ersten Run.

Die Kontrollen umfassen je 20 öffentliche CP06-Formationsfälle für Cache und
Spezialprofil, echte Cache-Insert-/Replay-/FIFO-Folgen unter ausdrücklich
größeren Komponentenautoritäten, Ablehnung eines außerhalb des Runs
vorbereiteten Replay-Eintrags und manipulierte Spezial-Requests. Größere
Komponentenautoritäten können keine Studienresultate ausstellen. Historische
Receipt-/Attempt-Bytepins bleiben Teil der Regression.
Ein delegierender SHA-256-Testprovider prüft zusätzlich die tatsächlich
verarbeiteten Bytes und Hash-Abschlüsse gegen die Cache-Dimensionen der
Ledger, einschließlich Retention-Abbruch sowie fremder und verdrängter
Lookup-Generationen. Die Hash-Berechnung selbst bleibt die JDK-SUN-Implementierung.

Diese Arbeit führt weder die 600-Zeilen-Studie noch eine Candidate-Freeze aus.
Die Qualifikation bleibt versiegelt. Cache-Nutzen, externe Parität und eine
Produktentscheidung sind weiterhin nicht nachgewiesen.
