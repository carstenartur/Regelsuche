# Evidence für einen explizit ausgewählten endlichen Plankandidaten

## Zweck

Die unabhängig bestätigte Planlaufevidence aus #890 weist nach, dass ein
endlicher Polynomplan, sein Solverresultat und sein Replay-Receipt unter den
eingefrorenen Eingaben vollständig reproduzierbar sind. Sie entscheidet jedoch
noch nicht, welcher der möglicherweise mehreren gültigen Kandidaten verwendet
werden soll.

`ExactFinitePolynomialPlanCandidateEvidenceVerifier` ergänzt dafür eine bewusst
enge Auswahlgrenze:

```text
VerifiedReplayConfirmation
+ exakt validierter SchematicProofPlan
+ exakt validierter ExactFinitePolynomialPlanRun
+ explizit angegebener Kandidaten-Content-Hash
  -> vollständige Cross-Bindings erneut prüfen
  -> Kandidat genau einmal in Bestätigung und Planlauf finden
  -> Lösung, Resolution, Plan und Solverresultat binden
  -> Quelle und instanziierten Ausdruck übernehmen
  -> verifier-eigene VerifiedCandidateEvidence
```

Der Aufrufer muss den vollständigen Content-Hash angeben. Es gibt keine
implizite Auswahl des ersten, kürzesten, am besten bewerteten oder lexikografisch
kleinsten Kandidaten. Zwei symmetrische Lösungen bleiben deshalb zwei getrennte
Evidence-Objekte mit unterschiedlichen Identitäten.

## Ehrliche Theorie-Schritt-Semantik

Der endliche Hole-Solver beweist exakte Polynomgleichheit, erzeugt aber keine
Folge einzelner gewöhnlicher AST-Rewrite-Anwendungen. Eine solche Folge aus dem
Solverresultat abzuleiten, würde nicht vorhandene Provenienz erfinden.

Version 1 beschreibt den Übergang daher als genau einen benannten exakten
Theorieschritt:

```text
regelsuche.exact-finite-polynomial-plan-candidate-equivalence/v1
```

Das Ergebnis enthält weder `Transformation` noch `TransformationEngine` oder
`RewriteProgram`. Es ist nicht unmittelbar als Suchkante ausführbar.

## Gebundene Identitäten

Eine erfolgreiche `VerifiedCandidateEvidence` bindet:

- Verifier-, Revisions-, Schema- und Theorie-Schritt-ID;
- vollständige Receipt- und Planlauf-Artifact-Referenzen;
- Confirmation-, Plan-, Planlauf-, Solverresultat- und Solverrevisionshash;
- Laufstatus;
- explizit ausgewählten Kandidatenhash;
- Lösungs- und Resolutionshash;
- normalisierten Quellausdruck;
- exakt instanziierten Zielausdruck und seine Polynomnormalform;
- die für diesen v1-Resolver leere Annahmenmenge;
- kanonische Work-Evidence;
- einen eigenen Hash über die vollständige kanonische Darstellung ohne
  Selbst-Hash.

Ein Kandidat wird nur akzeptiert, wenn er genau einmal in der bestätigten
Kandidatenliste und genau einmal im validierten Planlauf vorkommt. Seine Lösung
muss genau einmal im Solverresultat enthalten sein, seine Resolution muss für
den vorgelegten Plan strukturell vollständig sein, und sämtliche Plan- und
Solverlinks müssen übereinstimmen. Null-Läufe können keine
Transformationsevidence ausstellen. Bei abgeschnittenen Ergebnismengen sind nur
die tatsächlich gespeicherten Kandidaten auswählbar.

Ein instanziierter Ausdruck, der textuell bereits dem normalisierten
Quellausdruck entspricht, wird als Nicht-Transformation abgelehnt.

## Kanonische Arbeit

Die Evidence versteckt die vollständige endliche Suche nicht hinter einer
scheinbar kostenlosen Kante. `CanonicalWork` bindet getrennt:

```text
ursprünglicher gespeicherter Planlauf
+ erste vollständige Ausführung der Replay-Bestätigung
+ zweite vollständige Ausführung des Replay-Verifiers
+ zwei vollständige Kandidaten-Identitätsdurchläufe
= totalWorkUnits
```

Für jede der drei exakten Suchen wird die Zahl der ausgewerteten endlichen
Belegungen übernommen. Die beiden Bestätigungswerte müssen dem gespeicherten
Lauf exakt entsprechen. Kandidatenarbeit wird als zwei vollständige Durchläufe
über die gespeicherte Kandidatenmenge gezählt. Addition und Multiplikation
verwenden überlaufprüfende Ganzzahlarithmetik; Überlauf oder widersprüchliche
Summen werden fail-closed abgelehnt.

Diese Work-Zahl ist die kanonische Evidence-Arbeit dieses engen Vertrags. Ein
späterer Suchadapter muss sie zusätzlich zu seiner eigenen Adapter-, Programm-
und Sucharbeit erhalten; er darf den Theorieschritt nicht als eine primitive
Rewrite-Einheit verbuchen.

## Konstruktionsgrenze

`VerifiedCandidateEvidence` ist eine versiegelte öffentliche
Nur-Lese-Schnittstelle. Ihre einzige Implementierung und deren Konstruktor sind
privat. Nur der Verifier kann daher ein positives Evidence-Objekt erzeugen.

Die kanonische JSON-Darstellung ist größenbegrenzt und ihr `evidenceHash` wird
im privaten Konstruktor erneut aus sämtlichen Feldern berechnet. Ausgetauschte
Artefaktreferenzen, Planläufe, Bestätigungen, Kandidaten, Lösungen,
Resolutionen, Zähler oder Work-Summen werden abgelehnt.

## Präzise Vertrauensgrenze

Diese Stufe bestätigt:

```text
ein explizit ausgewählter Kandidat
+ gehört zum unabhängig reproduzierten Planlauf
+ ist exakt solverbestätigt äquivalent
+ besitzt vollständige Plan- und Resolution-Bindungen
+ trägt die vollständige deklarierte Solver-/Replay-Arbeit
```

Sie bestätigt noch nicht:

- eine Folge primitiver Rewrite-Anwendungen;
- die Ausführbarkeit als gewöhnliche Suchkante;
- ein kompiliertes `RewriteProgram`;
- die Qualität einer Auswahlheuristik;
- gelernte Taktikübertragung;
- formale Proof-Evidence außerhalb des exakten Polynomfragments;
- Promotion oder mathematische Neuheit.

Der nächste ausführbare Slice benötigt deshalb einen work-aware Adapter, der
diese Evidence-Identität, den benannten Theorieschritt, die leere
Annahmenmenge und die vollständige kanonische Arbeit unverändert durch
`RewriteProgram`- und Suchausführung trägt. Primitive Provenienz darf nur dann
angegeben werden, wenn sie tatsächlich unabhängig gespeichert und replayt
wurde.

## Reproduktion

```bash
./gradlew :regelsuche-learning:test \
  --tests '*ExactFinitePolynomialPlanCandidateEvidenceVerifierTest'

./gradlew --no-configuration-cache ciCheck
```
