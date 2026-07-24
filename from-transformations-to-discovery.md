# Von Umformungen zu mathematischen Entdeckungen

Regelsuche behandelt mathematische Ausdrücke als Zustände und Umformungen als
legale Züge. Das reicht aus, um bekannte Identitäten als Suchpfade wiederzufinden.
Eine **mathematische Entdeckung** entsteht aber erst dann, wenn aus solchen Pfaden
eine neue, über die beobachteten Beispiele hinausgehende Aussage oder Strategie
abgeleitet, angegriffen, validiert und mit nachvollziehbarer Evidenz wiederverwendbar
gemacht wird.

Diese Seite verbindet deshalb die Search-/Learning-Arbeit mit dem langfristigen
Discovery-Ziel aus #102. Sie beschreibt nicht nur Komponenten, sondern die
kausale Kette, die jede Änderung nachweislich unterstützen muss. Der datierte,
gemessene Projektstand steht ergänzend in
[docs/discovery-status.md](discovery-status.md).

## Die zentrale Abgrenzung

Ein kürzerer Suchlauf, eine neue Queue-Priorität oder ein erfolgreicher Pfad ist
**noch keine Entdeckung**.

Regelsuche unterscheidet vier Ebenen:

1. **Demonstration:** Eine bekannte Identität wird mit vorhandenen Regeln gefunden.
2. **Rediscovery:** Eine bekannte Regel wurde dem System vorenthalten und aus
   atomaren Umformungen erneut hergeleitet.
3. **Inventar-neue Open-Target-Hypothese:** Das System generalisiert mehrere
   untargetete Suchbeobachtungen zu einer bislang nicht im aktiven
   Regelsuche-Inventar enthaltenen Regel, Strategie oder Randbedingung.
4. **Kandidat für externe mathematische Neuheit:** Die Aussage ist zusätzlich
   gegenüber Literatur, Datenbanken und Expertenwissen zu prüfen. Regelsuche darf
   globale Neuheit nicht allein aus dem eigenen Inventar ableiten.

Auch ein reproduzierbares Gegenbeispiel oder eine neu erkannte notwendige
Voraussetzung kann eine wertvolle Entdeckung sein. Wahrheit, Neuheit,
Interessantheit und Suchnutzen bleiben dabei getrennte Eigenschaften.

## Die geschlossene Discovery-Schleife

```mermaid
flowchart LR
    A[Seeds oder Generatoren] --> B[TransformationEngine<br/>legale atomare Züge]
    B --> C[Untargeted oder targeted Search<br/>Pfade und Alternativen]
    C --> D[Trajektorien und Graph-Evidenz<br/>Annahmen, Pruning, Konvergenz]
    D --> E[Mining und Anti-Unifikation<br/>Muster aus mehreren Pfaden]
    E --> F[HypothesisCandidate<br/>Regel, Strategie oder Randbedingung]
    F --> G[Fresh Holdouts und<br/>Counterexample Search]
    G --> H[Novelty und symbolische<br/>oder formale Prüfung]
    H --> I[Promotion mit Provenance<br/>ReusableRule oder Makro]
    I --> J[Gepaarte Wiederholungsmessung<br/>Nutzen auf ungesehenen Aufgaben]
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

Die aktuelle Kette trägt wie folgt zur Discovery-Schleife bei:

| Arbeitsschritt | Beitrag zur Discovery-Schleife | Was damit noch nicht behauptet wird |
|---|---|---|
| Erklärbare Policy und Telemetrie (#268) | Macht Ranking-Entscheidungen, Beiträge und Fallback reproduzierbar. | Dass die Policy auf ungesehenen Familien generalisiert. |
| Held-out-Familien-Evaluation (#274) | Trennt TRAIN, VALIDATION und TEST und verhindert einen Erfolg durch bekannte Rule-IDs. | Dass ein negatives Ergebnis eine Entdeckung ist. |
| Korrekte Kandidatenbudgets (#288) | Stellt sicher, dass Guards und Duplikate kein verborgenes Policy-Budget erzeugen. | Dass mehr Kandidaten automatisch bessere Hypothesen liefern. |
| Regel-ID-unabhängige Deskriptoren (#291, #298) | Überträgt strukturelle Erfahrung auf bislang unbekannte konkrete Regeln. | Dass strukturelle Ähnlichkeit mathematische Gültigkeit beweist. |
| Frontier-Priorität (#301) | Lässt gelernte Evidenz die reale Dequeue-Reihenfolge beeinflussen. | Dass eine schnellere Dequeue-Reihenfolge bereits neue Mathematik erzeugt. |
| Lokale Descriptor-/Kontext-Evidenz (#304–#306) | Unterscheidet Vorkommensrolle, Tiefe, Kontext und reale Kandidatenkonkurrenz. | Dass unbeobachtete Operatorübergänge sicher extrapoliert werden dürfen. |
| Zielschritt-Konkurrenz (#310, schließt #219) | Verbessert eine ungesehene TEST-Familie von 7 auf 5 erkundete Zustände, also um 28,5 %, ohne Korrektheitsverlust. | Dass das vorgegebene Ziel selbst entdeckt wurde. |
| Hidden-Rule-Benchmark (#309, schließt #227) | Misst 19 von 20 akzeptierte ausführbare Rediscoveries über vier Familien bei 0 False Positives unter 38 ausgeführten Negativ-Holdouts. | Dass die bekannten Referenzregeln extern neu sind. |
| Open-Target-Formation (#311) | Erzeugt aus untargeteten, alpha-distinkten Konvergenzbeobachtungen eine parametrisierte Hypothese mit kanonischer Evidenz. | Dass die Hypothese wahr oder neu ist. |
| Kompilierung und Falsifikation (#313) | Prüft einen bereits gebildeten Kandidaten auf frischen Positives, Negatives und Gegenbeispiele; unvollständige Evidenz kann nicht vakuos bestehen. | Dass `NO_COUNTEREXAMPLE_FOUND` ein Beweis ist. |
| Projektinterne Novelty (#314) | Trennt Exact-/Alpha-Duplikate im aktiven Inventar und früheren Campaigns von `NOVEL_WITHIN_PROJECT`. | Dass Projekt-Neuheit externe mathematische Neuheit ist. |
| Proof-Obligation (#315) | Prüft einen akzeptierten Kandidaten erst nach Mining und Falsifikation über ein isoliertes Symbolic-Backend. | Dass symbolische Äquivalenz automatisch ein formaler Theorem-Prover-Beweis ist. |
| Lifecycle-Handoff (#316) | Übergibt vollständige akzeptierte Evidenz konservativ als `HypothesisCandidate` mit `VALIDATED_BY_EXAMPLES`. | Dass der Kandidat automatisch aktiviert, promoted oder veröffentlicht wird. |

Der direkte Output der Search-Arbeit sind **bessere, leakage-resistente
Trajektorien und Suchgraphen**. Diese sind das Rohmaterial für Mining,
Generalisierung, Falsifikation und späteres Bridge-Clustering.

## Verträge zwischen den Stufen

Jede Stufe muss ein prüfbares Artefakt an die nächste übergeben:

| Stufe | Erforderliches Artefakt | Zentrale Frage |
|---|---|---|
| Search | `SearchTrajectoryDataset` oder untargetete Graph-Evidenz mit erzeugten, ausgewählten und verworfenen Alternativen | Welche legalen Wege wurden unter welchem Budget tatsächlich gesehen? |
| Mining | generalisiertes Muster mit mehreren `supportingPaths`, Alpha-/Value-Fingerprints und konkreten Zeugen | Welche gemeinsame Struktur erklärt mehrere unabhängige Pfade? |
| Hypothese | `HypothesisCandidate` mit Annahmen, Parametern und stabiler Identität | Welche Aussage wird genau behauptet? |
| Falsifikation | konfigurierte, ausgeführte und übersprungene Holdouts sowie Counterexample-Status | Wo scheitert die Behauptung, und ist die Prüfung vollständig? |
| Novelty | Exact-/Alpha-Vergleich gegen Inventar und frühere Campaigns | Ist der Kandidat im Projekt bereits vorhanden? |
| Prüfung | symbolischer oder formaler Proof-Status mit versionierter Obligation und Artefakten | Welche Stärke besitzt die Gültigkeitsaussage? |
| Promotion | versionierte `ReusableRule` oder Makroregel mit Provenance | Welches neue Wissen darf die Engine künftig anwenden? |
| Evaluation | gepaarter Lauf vor und nach Promotion auf ungesehenen Aufgaben | Erweitert oder verbessert das neue Wissen die Suche tatsächlich? |

Fehlt eines dieser Artefakte, bleibt das Ergebnis auf der vorherigen Stufe. Ein
`NO_COUNTEREXAMPLE_FOUND` ist zum Beispiel keine formale Verifikation, ein
`NOVEL_WITHIN_PROJECT` keine Literatur-Neuheit und ein hoher
Interestingness-Score keine Wahrheitsevidenz.

## Discovery-Gate

Eine Hypothese darf nur als belastbarer Discovery-Kandidat bezeichnet werden,
wenn die für ihren Anspruch relevanten Punkte dokumentiert sind:

- **Inventar-Neuheit:** Die Hypothese war nicht als ausführbare Regel oder
  äquivalentes Makro aktiv.
- **Unabhängige Stützung:** Sie stammt aus mehreren Zeugen, nicht aus einer
  einzelnen zufälligen Spur oder bloßer Variablenumbenennung.
- **Kein Split-/Target-Leakage:** Exakte und alpha-äquivalente Aufgaben
  überschreiten TRAIN/VALIDATION/TEST nicht; Open-Target-Mining sieht kein Ziel.
- **Fresh Holdouts:** Die generalisierte Aussage funktioniert auf zuvor nicht
  verwendeten Instanzen und enthält eine definierte Negativsuite.
- **Vollständige Bilanz:** Konfigurierte Prüfungen entsprechen ausgeführten plus
  explizit übersprungenen Prüfungen; Pflichtprüfungen dürfen nicht fehlen.
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

## Gemessene Rediscovery und Open-Target-Grenze

Der Hidden-Rule-Benchmark aus #227 ist inzwischen umgesetzt: 20 bekannte Regeln
aus vier Familien werden vor dem Lauf aus Inventar und Discovery-Hinweisen
entfernt. Regelsuche rekonstruiert 19 ausführbare und symbolisch verifizierte
Kandidaten. Die zwei Prüfungen des einen vor der Kandidatenbildung gescheiterten
Falls werden explizit als übersprungen ausgewiesen, nicht als bestanden.

Das validiert die End-to-End-Kette, bleibt aber Rediscovery. Die nächste
wissenschaftliche Stufe ist deshalb #221: Kandidaten werden ohne Zielausdruck
aus untargeteten Suchgraphen gebildet. Formation, Kompilierung, Falsifikation,
projektinterne Novelty, symbolische Proof-Evidenz und Lifecycle-Handoff sind
bereits gemergt. Offen ist die Verbindung dieser getrennten Evidenzachsen mit
dem bestehenden Promotion- und Public-Evidence-Gate.

## Noch offene Bausteine zum langfristigen Ziel

- **#221:** Promotion-/Public-Evidence-Integration abschließen, ohne Wahrheit,
  Novelty und Interessantheit zusammenzufassen.
- **#222:** familien- und Rule-ID-blinde Structural Clusters, anschließend
  Bridge-Hypothesen und frische familienweise Validierung.
- **#223:** getrennte Bewertung von Interessantheit und Überraschung auf realen
  Kandidaten aus #221/#222.
- **#225:** budgetierter autonomer Open-Target-Kampagnenplaner.
- **#233/#234:** solver-neutrale Obligations-/Proof-IR und capability-aware
  Backend-Orchestrierung.
- **#235:** informationsparitäre Vergleichsbenchmarks.
- **#226:** maschinenprüfbares Release-Gate für autonome Discovery.

#220, #224 und #104 bleiben wichtige langfristige Erweiterungen, sollten die
aktuelle Ausdrucks-Domain-Discovery-Schleife aber nicht unterbrechen.

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
