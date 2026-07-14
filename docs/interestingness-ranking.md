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

Eine Änderung von TEST-Labels darf deshalb niemals die Identität des prädiktiven Korpus oder eine später auf CALIBRATION gewählte Policy verändern.

## Was diese Artefakte nicht behaupten

Auch ein hoch gerankter, konsensuell relevanter Kandidat ist nicht automatisch:

- mathematisch wahr,
- projektintern oder extern neu,
- formal bewiesen,
- promotionstauglich,
- für Public Evidence freigegeben.

Diese Entscheidungen bleiben in den bestehenden Falsifikations-, Novelty-, Proof-, Promotion- und Public-Evidence-Gates getrennt.
