# Interessantheit und Überraschung

Regelsuche behandelt **Interessantheit** als nachgelagertes Ranking-Signal. Sie ist weder ein Wahrheitsbeweis noch eine Neuheits- oder Veröffentlichungsentscheidung.

## Evidenzbewusste Bewertung

`regelsuche.interestingness-assessment/v1` trennt zunächst harte wissenschaftliche Gates von den rankbaren Komponenten.

Harte Blocker sind insbesondere:

- ein verworfener Kandidat,
- ein gefundenes Gegenbeispiel,
- Oracle-Widerspruch,
- fehlgeschlagene Positiv- oder Negativprüfungen,
- inkonsistente Zählung konfigurierter, ausgeführter und übersprungener Prüfungen.

Nicht widerlegte, aber unvollständig geprüfte Kandidaten bleiben als `RANKABLE_INCOMPLETE` sichtbar und erhalten einen expliziten Risikoabzug.

Die rankbaren Beiträge werden als benannte Permille-Werte ausgegeben:

- Kompression,
- Generalisierung,
- unabhängige Evidenz,
- Wiederverwendbarkeit,
- strukturelle Überraschung,
- Held-out-Cross-Family-Transfer,
- einfache Annahmen,
- gepaarter Suchnutzen.

Die Profile `THEORY_DISCOVERY` und `SEARCH_REUSE` ändern nur die Gewichte dieser identischen Rohkomponenten.

## Blinde Relevanzreviews

`regelsuche.interestingness-review-consensus/v1` sammelt Relevanzurteile erst **nach** Kandidatenbildung und Assessment.

- Reviewer werden ausschließlich durch SHA-256-Hashes referenziert.
- Ein Reviewer darf pro Kandidat nur ein Expert-Urteil abgeben.
- Mindestens zwei unabhängige blinde Expert-Reviews sind erforderlich.
- Große Abweichungen oder geringe mittlere Sicherheit ergeben `UNCERTAIN`.
- Test-Fixtures können niemals empirischen Konsens vortäuschen und bleiben `DEVELOPMENT_ONLY`.

Die Urteile dürfen weder in Open-Target-Mining noch in die Assessment-Komponenten desselben Kandidaten zurückfließen.

## Eingefrorener Calibration-/TEST-Korpus

`regelsuche.interestingness-calibration-corpus/v1` friert reale Kandidaten und ihre Review-Konsense für spätere Profilevaluation ein.

Der Vertrag verlangt:

- disjunkte Kandidaten-IDs,
- getrennte Kandidatenfamilien zwischen `CALIBRATION` und `TEST`,
- getrennte strukturelle Signaturhashes zwischen beiden Splits,
- mindestens zwei Fälle pro Split,
- Labeldiversität in beiden Splits,
- Expert-Konsens für jeden aufgenommenen Kandidaten.

Zwei Hashes bleiben bewusst getrennt:

- `predictiveCorpusHash` enthält Fälle, Splits, Strukturen und Assessment-Artefakte, aber keine Relevanzlabels;
- `labeledEvaluationHash` bindet zusätzlich die nachgelagerten Review-Konsense.

Eine Änderung von TEST-Labels darf deshalb niemals die Identität des prädiktiven Korpus verändern.

## Held-out-Profilwahl

`regelsuche.interestingness-profile-calibration/v1` vergleicht die beiden Profile auf denselben Rohkomponenten.

1. Für jeden Fall müssen `THEORY_DISCOVERY` und `SEARCH_REUSE` dieselbe Evidenz, dieselbe Eligibility, dieselben Rohkomponenten und dieselben Risiko-/Kontrollabzüge verwenden.
2. Die Profilwahl sieht ausschließlich die Relevanzkonsense im `CALIBRATION`-Split.
3. Das gewählte Profil wird danach unverändert auf `TEST` angewandt.
4. `selectionHash` bindet den prädiktiven Korpus, CALIBRATION-Labels und beide CALIBRATION-Assessments, aber keine TEST-Labels.
5. TEST-Labels beeinflussen nur die nachgelagerte Agreement-Auswertung und den vollständigen Report-Hash.

Der Report enthält außerdem:

- ein TEST-Ranking mit den ausgewählten Assessments,
- die Label-Agreement-Werte beider Profile getrennt für CALIBRATION und TEST,
- eine Pareto-Front über strukturelle Überraschung, Cross-Family-Transfer, gepaarten Nutzen und Wiederverwendbarkeit,
- die Rangordnungsübereinstimmung beider Profile,
- Leave-one-out-Stabilität der Profilwahl,
- Stabilität des höchstgerankten TEST-Kandidaten zwischen den Profilen.

Pareto- und Sensitivitätswerte bleiben Diagnoseevidenz. Sie verändern weder Kandidatenbildung noch Proof-, Novelty-, Promotion- oder Public-Evidence-Entscheidungen.

## Vorab deklarierte Akzeptanz

`regelsuche.interestingness-acceptance/v1` bewertet den abgeschlossenen Calibration-/TEST-Report gegen Schwellen, die **vor** der Interpretation des TEST-Ergebnisses feststehen.

Der Gate-Vertrag kann Mindestwerte verlangen für:

- Zahl der TEST-Fälle,
- Agreement auf CALIBRATION,
- Agreement auf TEST,
- profilübergreifende Rangordnungsstabilität,
- Leave-one-out-Stabilität der Profilwahl,
- Stabilität des höchstgerankten Kandidaten,
- eine nicht leere Pareto-Front.

Unterschreitungen werden als benannte Blocker ausgegeben. Das Gate darf weder Profilwahl noch Rangwerte verändern und führt weder Promotion noch Public Evidence aus. Dadurch kann eine schwache TEST-Auswertung nicht nachträglich durch angepasste Erfolgskriterien als bestanden definiert werden.

## Was diese Artefakte nicht behaupten

Auch ein hoch gerankter, konsensuell relevanter und nach vorab festgelegten Schwellen akzeptierter Kandidat ist nicht automatisch:

- mathematisch wahr,
- projektintern oder extern neu,
- formal bewiesen,
- promotionstauglich,
- für Public Evidence freigegeben.

Diese Entscheidungen bleiben in den bestehenden Falsifikations-, Novelty-, Proof-, Promotion- und Public-Evidence-Gates getrennt.
