# Evidence für einen explizit ausgewählten endlichen Plankandidaten

## Zweck

Eine `ConfirmedReplay` weist nach, dass ein unabhängig aufbewahrter endlicher
Polynomplanlauf und sein Replay-Receipt unter den eingefrorenen Eingaben erneut
erzeugt wurden. Sie entscheidet noch nicht, welcher von mehreren äquivalenten
Kandidaten verwendet werden soll.

`ExactFinitePolynomialPlanCandidateEvidenceVerifier` ergänzt deshalb eine enge,
explizite Auswahlgrenze:

```text
ConfirmedReplay
+ exakt validierter SchematicProofPlan
+ exakt validierter ExactFinitePolynomialPlanRun
+ vom Aufrufer angegebener Kandidaten-Content-Hash
  -> vollständige Bindungen erneut prüfen
  -> Kandidat genau einmal in Bestätigung und Planlauf finden
  -> Lösung, Resolution, Plan und Solverresultat binden
  -> verifier-eigene VerifiedCandidateEvidence
```

Es gibt keine Auswahl des ersten, kürzesten, am besten bewerteten oder
lexikografisch kleinsten Kandidaten. Symmetrische Lösungen bleiben getrennte
Evidence-Objekte mit unterschiedlichen Identitäten.

## Ehrliche Theorie-Schritt-Semantik

Der endliche Hole-Solver bestätigt exakte Polynomgleichheit. Er erzeugt jedoch
keine Folge gewöhnlicher primitiver AST-Rewrite-Anwendungen. Eine solche Folge
aus dem Solverresultat abzuleiten, würde nicht vorhandene Provenienz erfinden.

Version 1 beschreibt den Übergang daher als einen benannten exakten
Theorieschritt:

```text
regelsuche.exact-finite-polynomial-plan-candidate-equivalence/v1
```

Das Ergebnis enthält weder `Transformation`, `TransformationEngine` noch
`RewriteProgram`. Es ist noch keine gewöhnliche ausführbare Suchkante.

## Erneut geprüfte Bindungen

Vor der Ausstellung der Evidence werden mindestens folgende Beziehungen erneut
geprüft:

- Receipt- und Planlaufreferenz besitzen ihre nicht austauschbaren Rollen;
- die kanonische Planlaufprojektion erzeugt exakt die bestätigte Referenz;
- Plan-, Planlauf-, Solverresultat- und Solverrevisionshash stimmen überein;
- Status sowie Gesamt-, Auswertungs- und Trefferzähler stimmen überein;
- die geordnete Kandidatenmenge stimmt vollständig mit der Bestätigung überein;
- der ausgewählte Hash tritt genau einmal in Bestätigung und Planlauf auf;
- Lösung und Resolution gehören genau zu diesem Kandidaten;
- die Resolution ist für den vorgelegten Plan strukturell vollständig;
- der v1-Plan ist annahmenfrei;
- Quelle und instanziierter Ausdruck sind textuell verschieden.

Ein vollständiger Lauf ohne Lösung kann keine Kandidatenevidence ausstellen. Bei
einer abgeschnittenen Ergebnismenge sind nur tatsächlich gespeicherte Kandidaten
auswählbar; der abgeschnittene Status bleibt erhalten.

## Gebundene Evidence

Eine erfolgreiche `VerifiedCandidateEvidence` bindet:

- Evidence-Schema, Verifierrevision und Theorie-Schritt-ID;
- vollständige Receipt- und Planlauf-Artifact-Referenzen;
- Receipt-Verifikations- und Replay-Bestätigungshash;
- Plan-, Planlauf-, Solverresultat- und Solverrevisionshash;
- Laufstatus und alle Belegungszähler;
- Anzahl der gespeicherten Kandidaten;
- explizit ausgewählten Kandidaten-, Lösungs- und Resolutionshash;
- Quellausdruck, instanziierten Ausdruck und exakte Polynomnormalform;
- die leere Annahmenmenge des v1-Fragments;
- kanonische Arbeit und einen selbstgeprüften Evidence-Hash.

Die öffentliche Evidence-Schnittstelle ist versiegelt. Ihre einzige
Implementierung und deren Konstruktor sind privat, sodass nur der Verifier ein
positives Evidence-Objekt erzeugen kann.

## Kanonische Arbeit ohne erfundene Historie

Die gespeicherten Planlauf- und Receipt-Bytes belegen ihre Inhalte, aber nicht,
wie oft sie vor der Speicherung erzeugt wurden. Deshalb werden keine
historischen Solverläufe hinzugerechnet.

Als mathematische Ausführungsarbeit zählen ausschließlich die frischen exakten
Replayausführungen, die `ConfirmedReplay.exactReplayExecutions()` tatsächlich
ausweist:

```text
exactReplayExecutions
* evaluatedAssignmentsPerReplay
= replayAssignmentEvaluations
```

Zusätzlich bleiben fünf vollständige Kandidaten-Identitätspässe getrennt
sichtbar:

1. Planlauf materialisieren und seine Artifact-Referenz bilden;
2. Planlaufkandidaten vollständig mit der Bestätigung vergleichen;
3. den ausgewählten Hash vollständig in der Bestätigung suchen;
4. den typisierten Kandidaten vollständig im Planlauf suchen;
5. seine Lösung vollständig in der gespeicherten Lösungsmenge suchen.

Für jeden Pass gilt:

```text
candidateVisits = retainedCandidateCount
```

Damit ergibt sich:

```text
totalCandidateIdentityVisits = 5 * retainedCandidateCount

totalWorkUnits
  = replayAssignmentEvaluations
  + totalCandidateIdentityVisits
```

Multiplikation und Addition verwenden überlaufprüfende Ganzzahlarithmetik.
Widersprüchliche Einzelwerte, Summen oder Überläufe werden fail-closed
abgelehnt.

Diese Zahl ist die kanonische mathematische Evidence-Arbeit dieser engen Stufe.
Ein späterer Adapter muss sie zusätzlich zu seiner eigenen mechanischen
Adapter-, Programm- und Sucharbeit erhalten. Er darf den Theorieschritt weder
als primitive Rewrite-Einheit noch als kostenlose Kante verbuchen.

## Präzise Vertrauensgrenze

Diese Stufe bestätigt:

```text
ein explizit ausgewählter Kandidat
+ gehört zum unabhängig reproduzierten Planlauf
+ ist exakt solverbestätigt äquivalent
+ besitzt vollständige Plan- und Resolution-Bindungen
+ trägt die an dieser Grenze nachgewiesene Replay- und Auswahl-Arbeit
```

Sie bestätigt noch nicht:

- eine unabhängig gespeicherte Folge primitiver Rewrite-Anwendungen;
- die Ausführung als gewöhnliche Search-Frontier-Kante;
- ein kompiliertes oder frei kombinierbares `RewriteProgram`;
- die Qualität einer Auswahlheuristik;
- gelernte Taktikübertragung;
- allgemeine formale Proof-Evidence außerhalb des exakten Polynomfragments;
- Promotion oder mathematische Neuheit.

Der nächste ausführbare Slice benötigt einen budgetbewussten Adapter und ein
typisiertes Provenienzmodell. Evidence-Identität, benannter Theorieschritt,
Annahmen und kanonische Arbeit müssen dabei unverändert erhalten bleiben.

## Reproduktion

```bash
./gradlew :regelsuche-learning:test \
  --tests '*ExactFinitePolynomialPlanCandidateEvidenceVerifierTest'

./gradlew --no-configuration-cache ciCheck
```
