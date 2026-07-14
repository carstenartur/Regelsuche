# Von Umformungen zu mathematischen Entdeckungen

Regelsuche behandelt mathematische Ausdrücke als Zustände und Umformungen als
legale Züge. Das reicht aus, um bekannte Identitäten als Suchpfade wiederzufinden.
Eine **mathematische Entdeckung** entsteht aber erst dann, wenn aus solchen Pfaden
eine neue, über die beobachteten Beispiele hinausgehende Aussage oder Strategie
abgeleitet, angegriffen, validiert und mit nachvollziehbarer Evidenz wiederverwendbar
gemacht wird.

Diese Seite verbindet deshalb die Search-/Learning-Arbeit mit dem langfristigen
Discovery-Ziel aus #102. Sie beschreibt nicht nur Komponenten, sondern die
kausale Kette, die jede Änderung nachweislich unterstützen muss.

## Die zentrale Abgrenzung

Ein kürzerer Suchlauf, eine neue Queue-Priorität oder ein erfolgreicher Pfad ist
**noch keine Entdeckung**.

Regelsuche unterscheidet vier Ebenen:

1. **Demonstration:** Eine bekannte Identität wird mit vorhandenen Regeln gefunden.
2. **Rediscovery:** Eine bekannte Regel wurde dem System vorenthalten und aus
   atomaren Umformungen erneut hergeleitet.
3. **Inventar-neue Hypothese:** Das System generalisiert mehrere Pfade zu einer
   bislang nicht im aktiven Regelsuche-Inventar enthaltenen Regel, Strategie oder
   Randbedingung.
4. **Kandidat für externe mathematische Neuheit:** Die Aussage ist zusätzlich
   gegenüber Literatur, Datenbanken und Expertenwissen zu prüfen. Regelsuche darf
   globale Neuheit nicht allein aus dem eigenen Inventar ableiten.

Auch ein reproduzierbares Gegenbeispiel oder eine neu erkannte notwendige
Voraussetzung kann eine wertvolle Entdeckung sein. Wahrheit, Neuheit,
Interessantheit und Suchnutzen bleiben dabei getrennte Eigenschaften.

## Die geschlossene Discovery-Schleife

```mermaid
flowchart LR
    A[Seeds oder Generatoren] --> B[TransformationEngine\nlegale atomare Züge]
    B --> C[Search\nPfade und Alternativen]
    C --> D[Trajektorien\nEntscheidungen, Annahmen, Pruning]
    D --> E[Mining und Anti-Unifikation\nMuster aus mehreren Pfaden]
    E --> F[HypothesisCandidate\nRegel, Strategie oder Randbedingung]
    F --> G[Fresh Holdouts und\nCounterexample Search]
    G --> H[Symbolische oder\nformale Prüfung]
    H --> I[Promotion mit Provenance\nReusableRule oder Makro]
    I --> J[Gepaarte Wiederholungsmessung\nNutzen auf ungesehenen Aufgaben]
    J --> B
```

Die Schleife ist absichtlich geschlossen: Eine validierte Hypothese soll nicht
nur in einem Report erscheinen. Sie muss als explizit versioniertes Wissen in
die Transformationsebene zurückkehren und anschließend unter identischen
Bedingungen zeigen, ob sie neue Aufgaben besser, kürzer oder überhaupt erst
lösbar macht.

## Warum bessere Suchsteuerung dem Discovery-Ziel hilft

Der Transformationsraum wächst kombinatorisch. Unter endlichen Budgets sieht
die Mining-Schicht nur die Pfade, die Search tatsächlich erreicht. Eine bessere
Suchsteuerung erhöht deshalb die Wahrscheinlichkeit, informative und
unterschiedliche Zeugenpfade zu finden. Sie verändert aber weder die Wahrheit
einer Hypothese noch ihre Neuheit.

Die aktuelle Learning-Kette trägt wie folgt zur Discovery-Schleife bei:

| Arbeitsschritt | Beitrag zur Discovery-Schleife | Was damit noch nicht behauptet wird |
|---|---|---|
| Erklärbare Policy und Telemetrie (#268) | Macht Ranking-Entscheidungen, Beiträge und Fallback reproduzierbar. | Dass die Policy auf ungesehenen Familien generalisiert. |
| Held-out-Familien-Evaluation (#274) | Trennt TRAIN, VALIDATION und TEST und verhindert einen Erfolg durch bekannte Rule-IDs. | Dass ein negatives Ergebnis eine Entdeckung ist. |
| Korrekte Kandidatenbudgets (#288) | Stellt sicher, dass Guards und Duplikate kein verborgenes Policy-Budget erzeugen. | Dass mehr Kandidaten automatisch bessere Hypothesen liefern. |
| Regel-ID-unabhängige Deskriptoren (#291, #298) | Überträgt strukturelle Erfahrung auf bislang unbekannte konkrete Regeln. | Dass strukturelle Ähnlichkeit allein mathematische Gültigkeit beweist. |
| Frontier-Priorität (#301, schließt #300) | Lässt gelernte Evidenz die reale Dequeue-Reihenfolge beeinflussen, nachdem Kandidaten regulär aufgenommen wurden. | Dass eine schnellere Dequeue-Reihenfolge bereits neue Mathematik erzeugt. |
| Lokale Descriptor-v2-Änderungen (#304) | Unterscheidet Wurzel- und Teilbaumänderungen, Rolle, Tiefe und unmittelbaren Kontext. | Dass ein ungesehener Operatorübergang sicher extrapoliert werden darf. |
| TRAIN-Support-Gate (#305) | Unterdrückt lokale Gewichte ohne passende TRAIN-Evidenz und verhindert falsche Übertragung. | Dass fehlende Evidenz als negative mathematische Aussage gilt. |
| Paarweise Kandidatenkonkurrenz (#306, aktiv) | Lernt aus echten Alternativen derselben Expansion statt aus isolierten späteren Pfadschritten. | Dass der 20-Prozent-Gewinn oder eine Discovery bereits bestätigt ist. |

Der direkte Output dieser Arbeiten sind **bessere, leakage-resistente
Trajektorien**. Diese Trajektorien sind das Rohmaterial für Mining,
Generalisierung und Falsifikation.

## Verträge zwischen den Stufen

Jede Stufe muss ein prüfbares Artefakt an die nächste übergeben:

| Stufe | Erforderliches Artefakt | Zentrale Frage |
|---|---|---|
| Search | `SearchTrajectoryDataset` mit erzeugten, ausgewählten und verworfenen Alternativen | Welche legalen Wege wurden unter welchem Budget tatsächlich gesehen? |
| Mining | generalisiertes Muster mit mehreren `supportingPaths` und konkreten Zeugen | Welche gemeinsame Struktur erklärt mehrere unabhängige Pfade? |
| Hypothese | `HypothesisCandidate` mit Annahmen, Parametern und stabiler Identität | Welche Aussage wird genau behauptet? |
| Falsifikation | Counterexample-Status, Quellen, Budgets und gefundene Gegenbeispiele | Wo scheitert die Behauptung? |
| Prüfung | symbolischer oder formaler Proof-Status mit Artefakten | Welche Stärke besitzt die Gültigkeitsaussage? |
| Promotion | versionierte `ReusableRule` oder Makroregel mit Provenance | Welches neue Wissen darf die Engine künftig anwenden? |
| Evaluation | gepaarter Lauf vor und nach Promotion auf ungesehenen Aufgaben | Erweitert oder verbessert das neue Wissen die Suche tatsächlich? |

Fehlt eines dieser Artefakte, bleibt das Ergebnis auf der vorherigen Stufe. Ein
`NO_COUNTEREXAMPLE_FOUND` ist zum Beispiel keine formale Verifikation, und ein
hoher Interestingness-Score ist keine Wahrheitsevidenz.

## Discovery-Gate

Eine Hypothese darf nur als belastbarer Discovery-Kandidat bezeichnet werden,
wenn die für ihren Anspruch relevanten Punkte dokumentiert sind:

- **Inventar-Neuheit:** Die Hypothese war nicht als ausführbare Regel oder
  äquivalentes Makro aktiv.
- **Unabhängige Stützung:** Sie stammt aus mehreren Zeugen oder einer explizit
  begründeten Ableitung, nicht aus einer einzelnen zufälligen Spur.
- **Kein Split-Leakage:** Exakte und alpha-äquivalente Aufgaben überschreiten
  TRAIN/VALIDATION/TEST nicht.
- **Fresh Holdouts:** Die generalisierte Aussage funktioniert auf zuvor nicht
  verwendeten Instanzen und enthält eine definierte Negativsuite.
- **Explizite Annahmen:** Definitionsbereiche und Nebenbedingungen gehören zur
  Identität der Aussage.
- **Aktive Widerlegungssuche:** Quellen, Budget und Ergebnis der
  Counterexample-Suche sind sichtbar.
- **Passende Proof-Stärke:** Der Bericht unterscheidet Beobachtung, Beispiele,
  symbolische Verifikation und formalen Beweis.
- **Deterministische Provenance:** Seeds, Versionen, Inventare, Modelle, Pfade
  und Artefakthashes sind reproduzierbar.
- **Getrennter Nutzen:** Suchkosten, Pfadlänge, Beweiskompression oder
  Wiederverwendung werden separat von Wahrheit und Neuheit gemessen.
- **Externe Neuheitsprüfung:** Ein Anspruch über Neuheit in der mathematischen
  Literatur benötigt zusätzlich Literaturrecherche und fachliche Prüfung.

## Konkretes Rediscovery-Experiment

Die bekannten Complete-Square- und Sophie-Germain-Beispiele zeigen, dass die
Pipeline Pfade, Makros und Evidenz erzeugen kann. Sie sind Kalibrierungen, keine
Behauptung neuer Mathematik.

Ein belastbareres Experiment folgt dem Hidden-Rule-Muster aus #227:

1. Eine bekannte Zielregel wird aus Inventar, Ranking-Daten und Discovery-Hints
   entfernt.
2. Nur atomare, allgemein verfügbare Umformungen bleiben aktiv.
3. Search erzeugt mehrere erfolgreiche Pfade und speichert auch konkurrierende
   Alternativen.
4. Mining anti-unifiziert diese Pfade zu einem Rule-ID-unabhängigen Muster.
5. Fresh Positives, definierte Negatives und Counterexample Search prüfen die
   Generalisierung.
6. Ein geeigneter symbolischer oder formaler Backend-Versuch prüft die Aussage.
7. Erst danach wird die Regel mit vollständiger Provenance promoted.
8. Ein gepaarter Lauf misst, ob die promoted Regel auf weiteren ungesehenen
   Aufgaben Pfade verkürzt, Zustände spart oder neue Erreichbarkeit schafft.

Damit ist sichtbar, wie die Learning-Arbeit hilft: Sie soll Schritt 3 unter
festen Budgets reichhaltiger machen. Die eigentliche Entdeckung entsteht in den
Schritten 4 bis 7 und wird in Schritt 8 auf Nutzen geprüft.

## Noch offene Bausteine zum langfristigen Ziel

Die Search-Learning-Issues #219 und #297 verbessern den Explorationsunterbau.
Für autonome mathematische Discovery bleiben insbesondere folgende getrennte
Fähigkeiten wesentlich:

- **#227:** Hidden-Rule-Rediscovery als defensibler End-to-End-Benchmark.
- **#221:** Open-Target-Conjecture-Generation ohne vorgegebenen Zielausdruck.
- **#222:** strukturelles Clustering und Bridge-Hypothesen über Familien hinweg.
- **#223:** getrennte Bewertung von Interessantheit und Überraschung.
- **#225:** budgetierter autonomer Kampagnenplaner.
- **#226:** maschinenprüfbares Release-Gate für autonome Discovery.

Keines dieser Issues sollte durch eine reine Suchzeitverbesserung geschlossen
werden. Umgekehrt ist eine leistungsfähige, faire und erklärbare Suche eine
notwendige Voraussetzung, damit diese Stufen unter realistischen Budgets genug
gutes Material erhalten.

## Pflichtfragen für künftige PRs

Jeder PR im Learning-/Discovery-Pfad sollte in seiner Beschreibung beantworten:

1. Welche Stufe der Discovery-Schleife verändert er?
2. Welches reproduzierbare Eingabeartefakt konsumiert er?
3. Welches überprüfbare Ausgabeartefakt verbessert oder erzeugt er?
4. Welche held-out oder adversariale Evidenz trägt die Änderung?
5. Welche Discovery-Behauptung wird ausdrücklich **nicht** gemacht?
6. Wie wird das Ergebnis in die nächste Stufe der Schleife übergeben?

So bleiben technische Fortschritte, wissenschaftliche Evidenz und das
langfristige Ziel miteinander verbunden, ohne Zwischenresultate als
mathematische Entdeckungen zu überzeichnen.
