# Glossar

Dieses Glossar vereinheitlicht die zentralen Begriffe in README, Dokumentation, Issues und Pull Requests. Für maschinenlesbare Verträge bleiben die versionierten Java-Typen und JSON-Schemas autoritativ.

## Grundbegriffe

### Ausdruck (`Expression`)

Eine mathematische Formel oder Teilformel, die Regelsuche als strukturierte Syntax verarbeitet. Ein Ausdruck ist nicht automatisch eine mathematische Aussage.

### Abstrakter Syntaxbaum (`AST`)

Die strukturierte interne Darstellung eines Ausdrucks. Knoten repräsentieren unter anderem Zahlen, Variablen, Summen, Produkte, Potenzen und Funktionsaufrufe.

### Transformationsregel (`Transformation Rule`, `Rewrite Rule`)

Eine versionierte Vorschrift, die passende AST-Strukturen erkennt und in eine andere Struktur überführt. Eine Regel kann äquivalenzerhaltend sein, muss dies aber explizit ausweisen oder nachweisen.

### Transformation

Eine konkrete Anwendung einer Transformationsregel auf einen konkreten Ausdruck.

### Suchzustand (`Search State`)

Ein Ausdruck zusammen mit Suchmetadaten wie Tiefe, Score, Regelpfad, Annahmen und kanonischem Hash.

### Suchpfad (`Transformation Path`)

Eine geordnete Folge konkreter Transformationen. Ein Pfad ist nur dann ein Beleg für mathematische Äquivalenz, wenn sämtliche erforderlichen Schritte entsprechend abgesichert sind.

### Kanonische Darstellung

Eine deterministische Darstellung semantisch gleicher Daten. Sie dient stabilen Vergleichen, Hashes und reproduzierbaren Artefakten.

### Kanonischer Hash

Ein SHA-256-Hash über kanonisch geordnetes semantisches Material. Laufzeit, Dateipfad oder nichtdeterministische Telemetrie gehören nicht in einen semantischen Hash.

### Replay

Die erneute Ausführung oder Darstellung eines retained Such- oder Transformationspfads aus gespeicherter Evidence. Replay ist Reproduktion, nicht automatisch ein zusätzlicher mathematischer Beweis.

## Discovery und Mining

### Seed

Ein vor dem Lauf festgelegter Startausdruck einschließlich Provenienz, Familie, Domäne und Annahmen.

### Seed-Familie

Eine unabhängig bezeichnete Erzeugungsstrategie für Seeds. Familienbezeichnungen sind Provenienz und dürfen nicht nachträglich aus einem gewünschten Ergebnis konstruiert werden.

### Target

Ein vorgegebener Zielausdruck, den eine Suche erreichen soll.

### Targetfrei (`Target-free`, `UNTARGETED`)

Ein Lauf ohne Zielausdruck, erwartete Antwort oder versteckte Referenz in seinem Such- oder Mining-Input.

### Open-Target Discovery

Discovery, bei der kein Zielausdruck vorgegeben wird und Kandidaten erst aus beobachteten Konvergenzen oder Strukturen entstehen.

### Observation

Das unveränderliche Ergebnis eines targetfreien Suchlaufs für einen Seed, einschließlich erkundeter Zustände, Pfade, Scores, Annahmen und Hashes.

### Observation Branch

Die hashgebundene, unveränderliche Lineage-Einheit einer Observation. Sie ist ein Input für Aggregate Mining.

### Aggregate Mining

Gemeinsame Auswertung mehrerer unabhängiger Observations. Das Ergebnis kann null, einen oder mehrere Kandidaten enthalten.

### Cluster

Eine Gruppe strukturell zusammengehöriger Beobachtungen oder Konvergenzen, die der Miner gemeinsam bewertet.

### Abgewiesener Cluster (`Rejected Cluster`)

Ein Cluster, der die Mindestanforderungen für einen Kandidaten nicht erfüllt. Er bleibt Evidence, erzeugt aber keinen Kandidatenbranch.

### Alpha-Äquivalenz

Gleichheit bis auf konsistente Umbenennung von Variablen oder Platzhaltern. Alpha-äquivalente Beispiele liefern nicht automatisch unabhängige Unterstützung.

### Alpha-distinct Support

Anzahl der Unterstützungen, die nach Alpha-Normalisierung weiterhin verschieden sind. Dieser Wert misst strukturelle Vielfalt, nicht bloß die Zahl umbenannter Beispiele.

### Conjecture / Vermutung

Eine vom Miner formulierte verallgemeinerte mathematische Regel oder Beziehung. Eine Vermutung ist noch keine validierte, neue oder bewiesene Erkenntnis.

### Kandidat (`Candidate`)

Eine Vermutung, die als eigenständiger weiterer Evidenzzweig retained wird. Der Kandidat trägt exakt die Observations, die ihn tatsächlich unterstützen.

### Lineage

Die vollständige, hashgebundene Herkunft eines Artefakts. Bei einem Kandidaten umfasst sie nur die tragenden Observations, nicht pauschal alle Inputs eines Mining-Batches.

## Evidence und wissenschaftliche Stufen

### Evidence

Retained, maschinenlesbare Information darüber, was tatsächlich konfiguriert, ausgeführt, beobachtet oder nicht ausgeführt wurde. Evidence ist ein Oberbegriff und nicht automatisch mathematische Evidence.

### Mathematische Evidence

Evidence, die direkt eine mathematische Behauptung stützt oder widerlegt, etwa bestandene Holdouts, ein Counterexample oder ein bestätigter Proof. Planner-Entscheidungen, Prioritäten und Ressourcenbudgets sind keine mathematische Evidence.

### Provenienz

Information über Herkunft, Version, Generator, Regelbestand, Modell, Seed und Verarbeitungsschritte eines Artefakts.

### Validation

Prüfung eines Kandidaten an vorab getrennten positiven und negativen Holdouts. Validation zeigt beobachtete Gültigkeit innerhalb dieser Suite, nicht externe Novelty oder allgemeine Wahrheit.

### Positiver Holdout

Ein frischer Fall, auf den die Kandidatenregel korrekt anwendbar sein soll und das erwartete Resultat erreichen muss.

### Negativer Holdout

Ein frischer Fall, auf den die Kandidatenregel nicht anwendbar sein soll. Er schützt gegen Überanwendung.

### Held-out Familie oder Cluster

Eine vor Candidate Formation vollständig zurückgehaltene strukturelle Familie oder ein Cluster, der erst bei der Qualifikation verwendet wird. Einzelne nachträglich ergänzte Werte derselben Trainingsstruktur ersetzen dies nicht.

### Counterexample Search

Gezielte Suche nach einer Belegung oder Eingabe, für die die behauptete Beziehung fehlschlägt.

### Counterexample

Ein konkreter, reproduzierbarer Gegenfall. Ein Counterexample widerlegt die betroffene allgemeine Behauptung innerhalb ihrer angegebenen Annahmen.

### Annahme (`Assumption`)

Eine notwendige Einschränkung der Gültigkeit, beispielsweise Nichtnullheit, Positivität oder Definitionsbereich. Nicht aufgelöste Annahmen müssen sichtbar bleiben.

### Projekt-Novelty

Vergleich eines Kandidaten mit dem bekannten Regel- und Kandidatenbestand dieses Repositorys. `NOVEL_WITHIN_PROJECT` bedeutet nicht, dass die Mathematik weltweit neu ist.

### Externe Novelty

Bewertung gegenüber externer mathematischer Literatur und Fachwissen. Sie benötigt unabhängige, nachvollziehbare Prüfung außerhalb des internen Regelbestands.

### Interestingness

Bewertung, ob ein Resultat fachlich nützlich, überraschend, lehrreich oder forschungsrelevant ist. Interestingness ist unabhängig von Wahrheit und Novelty.

### Proof Obligation

Die präzise, versionierte Behauptung, die ein Proof-Backend prüfen soll, einschließlich Annahmen und Provenienz.

### Symbolischer Proof

Eine erfolgreiche Prüfung durch das konfigurierte symbolische Backend. Der genaue Status und das Backend müssen retained werden.

### Formaler Proof

Ein von einem als formal autoritativ behandelten Prover bestätigter Beweis. `SYMBOLICALLY_VERIFIED` und `FORMALLY_PROVED` sind nicht synonym.

### Proof Gate

Die Stufe, die entscheidet, ob vorhandene Proof-Evidence für den nächsten Lifecycle-Schritt ausreicht. Sie erzeugt keine Novelty- oder Promotion-Entscheidung.

### Lifecycle Handoff

Konservative Übergabe eines ausreichend geprüften Kandidaten in das bestehende Kandidaten-/Regelsystem. Der Handoff ist weder Promotion noch Veröffentlichung.

### Promotion

Explizite Aufnahme eines Kandidaten in einen autoritativen aktiven Regelbestand nach den dafür vorgesehenen Gates.

### Public Evidence

Für externe Leser aufbereitete, überprüfbare Evidence. Interne CI-Artefakte oder ein erfolgreicher Autopilot-Lauf sind nicht automatisch Public Evidence.

## Autopilot und Ressourcen

### Autopilot

Die Orchestrierung begrenzter targetfreier Discovery-, Mining- und Evidenzarbeit. Autopilot priorisiert und bilanziert Arbeit, entscheidet aber nicht selbst über mathematische Wahrheit, externe Novelty oder Veröffentlichung.

### Research Brief

Der versionierte, vor dem Lauf fixierte Vertrag einer Campaign: Domänen, Generatoren, Inventar- und Modellhashes, deterministischer Seed, Diversitätsanforderungen und Stufenbudgets.

### Campaign

Eine zusammenhängende Ausführung vom Research Brief über Generation und Candidate Formation bis zu den vorgesehenen Evidenzstufen und dem nächsten Plan.

### Campaign Round

Eine hashgebundene Runde aus Plan, Ausführung, Lineage, Outcomes und anschließendem Plan.

### Decision

Eine vor Ausführung retained Planungsentscheidung. Sie beschreibt beabsichtigte Arbeit und ist keine Behauptung, dass diese Arbeit bereits erfolgt ist.

### Receipt

Ein nach Ausführung erzeugtes Artefakt, das konfigurierte, ausgeführte, übersprungene und verbleibende Ressourcen sowie konkrete Outputs bindet.

### Ledger

Die zusammengeführte, balancierte Ressourcenbilanz mehrerer Receipts.

### Ressourcenbilanz

Für jede Ressource gilt:

```text
configured = executed + skipped + remaining
```

Nicht ausgeführte Arbeit darf nicht nachträglich als ausgeführt gezählt werden.

### Manifest

Ein äußeres Artefakt, das die semantischen Hashes aller retained Teilartefakte eines Laufs verbindet.

### Clean Run

Ein vollständiger Lauf mit denselben gepinnten Inputs und ohne Fehler, dessen kanonische semantische Outputs den anderen Clean Runs entsprechen.

## Release-Profile

### Evidence Profile

Eine benannte Menge maschinenlesbarer Anforderungen, die genau einen begrenzten Claim autorisiert.

### Release Readiness

Ergebnis der fail-closed Auswertung eines Evidence Profiles. Fehlende oder nicht gebundene Evidence führt zu `BLOCKED`, nicht zu einer Annahme.

### `READY`

Alle Anforderungen des konkreten Profils sind durch gebundene Evidence erfüllt. `READY` gilt nur für den Claim dieses Profils.

### `BLOCKED`

Mindestens eine notwendige Evidence fehlt, ist unvollständig oder widersprüchlich. `BLOCKED` ist ein sachlicher Status und kein Buildfehler.

### `SEARCH_REPRODUCIBILITY`

Profil für reproduzierbare targetfreie Suche mit gepinnten Inputs, kanonischem Manifest und mehreren identischen Clean Runs.

### `HIDDEN_RULE_REDISCOVERY`

Profil für die Wiederentdeckung zurückgehaltener bekannter Regeln ohne Leakage der Referenz in den Suchinput.

### `OPEN_TARGET_DISCOVERY`

Profil für targetfreie Candidate Formation einschließlich Lineage, Validation, Counterexample Search, Projekt-Novelty, Proof und Lifecycle-Handoff.

### `AUTONOMOUS_CAMPAIGN`

Das einzige Profil, das einen Autonomie-Claim für Release 0.2 autorisieren darf. Es verlangt zusätzlich unabhängige Held-out-Struktur, eine ausgeglichene Release-Holdout-Suite, Paired Utility und mehrere identische Clean Runs.

### `EXTERNAL_NOVELTY_REVIEW`

Separates Profil für extern geprüfte mathematische Novelty und Public Evidence. Es ist keine Voraussetzung dafür, intern eine autonome Campaign technisch nachzuweisen.

### Paired Held-out Utility

Vergleich derselben vorab getrennten Fälle mit und ohne die neu entdeckte Regel oder Strategie. Der Vergleich muss einen gebundenen, positiven Nutzen zeigen und darf nicht auf Trainingsfällen beruhen.

## Statusbegriffe

### `NOT_EVALUATED`

Die betreffende Stufe wurde bewusst nicht bewertet. Dieser Status darf nicht als bestanden, fehlgeschlagen oder unbekannt umgedeutet werden.

### `INCONCLUSIVE`

Die vorhandene Evidence reicht für keine abschließende Entscheidung. Inconclusive darf keinen erfolgreichen Folgebranch vortäuschen.

### `BACKEND_UNAVAILABLE`

Die vorgesehene technische Prüfung konnte wegen eines nicht verfügbaren Backends nicht ausgeführt werden. Dies ist kein positiver mathematischer Befund.

### `COMPLETED`

Die innerhalb des konkreten Vertrags vorgesehenen internen Stufen wurden erfolgreich abgeschlossen. `COMPLETED` bedeutet nicht automatisch promoted, published, externally novel oder formally proved.
