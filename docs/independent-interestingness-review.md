# Unabhängiges Interessantheits-Review-Protokoll

Dieser Baustein bereitet den empirischen Teil von Issue #332 vor. Er friert
Kandidaten, Reviewregeln und Akzeptanzschwellen ein, bevor Relevanzurteile
erhoben werden. Die mitgelieferte CI-Evidence verwendet ausschließlich
`DEVELOPMENT_FIXTURE` und ist ausdrücklich **keine** unabhängige Expertenstudie.

## Studienplan vor der Reviewerhebung

`regelsuche.independent-review-study-plan/v1` bindet:

- Kandidaten-, Assessment- und verblindete Präsentationsartefakte;
- die Aufteilung in `CALIBRATION` und `TEST`;
- disjunkte Kandidatenfamilien und strukturelle Signaturen;
- einen Expositionsledger für frühere Reviews, öffentliche Präsentationen,
  Entwicklungsauswertungen und frühere Held-out-Verwendungen;
- einen Commitment-Hash für den geheim gehaltenen Reviewer-Hash-Salt;
- einen Hash der unveränderten Reviewer-Instruktionen;
- vorab deklarierte Qualifikationskriterien;
- Relevanz- und Konfidenzskalen sowie einen Rationale-Codebook;
- die unveränderten Profile `THEORY_DISCOVERY` und `SEARCH_REUSE`;
- alle Akzeptanzschwellen über `thresholdLockHash`.

Der Plan hat `labelsStatus=NOT_COLLECTED`. Sein `predictiveCorpusHash` enthält
nur die prädiktiven Kandidaten-/Split-Artefakte. Reviewlabels, Reviewprotokoll
und Akzeptanzschwellen verändern diesen Hash nicht.

Mindestens zwei Fälle pro Split sind bereits für die Protokollvalidierung
notwendig. Die tatsächliche empirische Studie aus #332 benötigt darüber hinaus
einen ausreichend großen und begründeten Korpus; die kleine CI-Fixture ist kein
Ersatz dafür.

## Expositionsledger

Ein TEST-Artefakt darf nicht bereits als eines der folgenden Artefakte bekannt
sein:

- `PRIOR_EXPERT_REVIEW`;
- `PUBLIC_PRESENTATION`;
- `DEVELOPMENT_EVALUATION`;
- `PRIOR_HELD_OUT_USE`.

Die Prüfung verwendet den unveränderlichen Kandidaten-Artefakthash, nicht nur
eine frei wählbare Kandidaten-ID. Ein bereits exponierter Kandidat kann später
als historische Vergleichsevidenz ausgewiesen werden, aber nicht erneut als
frischer TEST-Fall zählen.

## Blinder Intake

`regelsuche.independent-review-intake/v1` validiert jeden Reviewdatensatz gegen
den eingefrorenen Plan. Explizite Blocker umfassen:

- falschen Study-Plan-, Case- oder Candidate-Bezug;
- doppelte Review-IDs;
- mehr als ein Expert-Review desselben Reviewerhashes pro Kandidat;
- nicht verblindete Reviews;
- nicht vorab deklarierte Skalenwerte oder Rationale-Codes;
- fehlende Qualifikations- oder Unabhängigkeits-Attestationshashes.

Reviewer werden ausschließlich als gesalzene SHA-256-Hashes gespeichert. Der
Plan publiziert nur das Commitment auf den Salt, nicht den Salt oder eine
Identität. Das Softwaregate kann trotzdem nicht beweisen, dass eine Person
wirklich unabhängig und qualifiziert ist. Diese Aussage benötigt eine externe,
nachvollziehbare Studienorganisation; im Artefakt werden nur deren
Qualifikations- und Attestationshashes gebunden.

## Development- und externe Reviews

- `DEVELOPMENT_FIXTURE` ergibt `DEVELOPMENT_ONLY` und zählt niemals als
  Expertenreview.
- `EXTERNAL_EXPERT` kann nur nach allen Intake-Gates als
  `COUNTED_EXPERT_REVIEW` erscheinen.
- Ein Batch mit beiden Ursprüngen wird als
  `MIXED_WITH_DEVELOPMENT_FIXTURES` markiert und ist nicht als reine empirische
  Konsensevidenz zulässig.
- `eligibleForEmpiricalConsensus()` verlangt ausschließlich externe Evidence
  und die vorab festgelegte Mindestzahl blinder Reviews für jeden Kandidaten.

Die spätere Konsensaggregation verwendet weiterhin
`InterestingnessReviewConsensus`; Intake und Konsens bleiben getrennte
Verträge.

## Korrekturen und Identitäten

Reviewkorrekturen überschreiben kein altes Artefakt. Revision 1 besitzt keinen
Vorgänger. Jede spätere Revision muss
`priorLabeledEvaluationHash` des unmittelbar vorangegangenen Labelsatzes
binden. Dadurch entstehen neue `labeledEvaluationHash`- und `contentHash`-
Identitäten, während `predictiveCorpusHash` unverändert bleibt.

## TEST-Sperre und Akzeptanz

Der Study-Plan friert die `InterestingnessAcceptanceGate.Thresholds` vor der
Reviewerhebung ein. `thresholdLockHash` muss in jedem Intake- und späteren
Evaluationsartefakt unverändert wiederkehren. TEST-Labels dürfen weder die
Profilwahl noch den Schwellen-Lock verändern.

Der vorhandene Ablauf bleibt:

1. Kandidaten und Assessments erzeugen und einfrieren.
2. Reviewplan und Schwellen registrieren.
3. Reviews unabhängig und blind erheben.
4. Intake und Konsens bilden.
5. Profil ausschließlich auf `CALIBRATION` wählen.
6. Gewähltes Profil einmalig auf `TEST` auswerten.
7. Das vorab registrierte Akzeptanzgate anwenden.

Promotion und Public Evidence bleiben sowohl im Plan als auch im Intake
`NOT_EVALUATED`.

## CI-Evidence

Der dedizierte Workflow schreibt:

- `study-plan.json`;
- `development-intake.json`.

Er verlangt byte-identische Wiederholungen, validiert beide Draft-2020-12-
Schemas, prüft Hashverkettungen, Split-Isolation, Schwellenbindung und bestätigt,
dass die gespeicherte Referenz null gezählte Expertenreviews und
`DEVELOPMENT_ONLY` enthält.

## Versionierte Schemas

- `docs/schemas/regelsuche-independent-review-study-plan-v1.schema.json`
- `docs/schemas/regelsuche-independent-review-intake-v1.schema.json`

## Verbleibender empirischer Umfang von #332

Der Software-Slice schließt #332 nicht. Noch erforderlich sind insbesondere:

- reale, ausreichend große Kandidatenmengen;
- tatsächlich unabhängige, blinde Expert-Reviews;
- dokumentierte Reviewerrekrutierung und Qualifikationsprüfung;
- vorab eingefrorene CALIBRATION-/TEST-Auswertung;
- unveränderte Profilwahl und Akzeptanzschwellen;
- vollständige positive und negative Resultate;
- eine externe Replikation oder ein alternatives System/Ranking.
