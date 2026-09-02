# Baseline: Findet und erkennt Regelsuche interessante Repräsentationen?

**Stand:** 2. September 2026  
**Repository-Revision:** `25d287c603bb0eb1d7e2176286a3562d93918938`  
**Auswertung:** unveränderte, bereits vorhandene Target-free-Artefakte; kein nachträgliches Tuning des Suchers oder Assessors

## Fragestellung

Die bisherige Erfolgsauswertung vermischte mehrere unterschiedliche Fähigkeiten:

```text
im Transformationsraum erreichbar
  -> von der Suchpolitik erreicht
  -> als Kandidat gebildet
  -> im Candidate Freeze behalten
  -> nach dem Freeze als interessant erkannt
  -> so hoch priorisiert, dass er einem Prüfer gezeigt wird
```

Ein `NO_POLICY_QUALIFIED` beantwortet deshalb noch nicht, ob die mathematische
Darstellung nie gefunden oder erst nach dem Finden übersehen wurde.

Diese Baseline untersucht zunächst die bereits eingefrorenen Artefakte. Sie
ändert weder Regeln noch Budgets, Schwellen, Kandidaten noch
Qualifikationsreferenzen.

## Gebundene Evidenz

### Sechs-Fall-Kalibrierung

- Evaluation plan:
  `sha256:a3c78bba60f134bf8b717a2ec631cd8f30fae96dd647b42d3e32fa1ce3c33185`
- Candidate Freeze:
  `sha256:0763d1e1d0e5dfe7f89b2de25d90268aa442bfe260093c4981d3547fe6c39191`
- Post-freeze qualification:
  `sha256:c2049113124e3a5a01a71006cc539c4914b5419486f96e2a8da80f316aa71000`

Die 24 Zeilen bestehen aus sechs Fällen und vier Suchpolitiken. Der Candidate
Freeze enthält 41 Kandidaten. Die historische beziehungsweise semantische
Qualifikation wurde erst nach diesem Freeze geöffnet.

### Schwerere Held-out-Matrix

- Execution plan:
  `sha256:0c324f5f8cb37fbe39d2fbe79ab7c51e2e882337b02c912459b3decf718a7e79`
- Candidate Freeze:
  `sha256:82dae94f4fd7973d43d77fde59fdb753fc3c0af557b6bad234df717c853ddecf`
- Post-freeze qualification:
  `sha256:65c91ed29012d9776ae80ea5bceeab65bba123f71d0d96d4f3e1abcfada2f47e`

Die Matrix umfasst sechs Fälle, vier Politiken und sechs kumulative
Arbeitscheckpoints, also 144 Zeilen. Davon sind 120 Positivzeilen und 24
Near-miss-Kontrollen.

## Ergebnis 1: Es gibt bereits einen nachgewiesenen Erkennungsfehler

Der Fall `repeated-term-compression` startet mit:

```text
x + x
```

und erwartet nach dem Candidate Freeze als eine zulässige interessante
Repräsentation:

```text
2 * x
```

Alle vier zielblinden Politiken haben `2 * x` erreicht und im Candidate Freeze
behalten. Die nachträgliche Referenzzuordnung bestätigt in allen vier Zeilen
`referenceMatched=true`.

Trotzdem klassifiziert der aktuelle `RepresentationCandidateAssessor` den
Kandidaten viermal ausschließlich als:

```text
NO_MATERIAL_REPRESENTATION_GAIN
```

und verwirft ihn mit:

```text
ACCEPTED_CANDIDATE_TYPE_NOT_OBSERVED
FORBIDDEN_OUTCOME_OBSERVED
TOKEN_SAVINGS_BELOW_MINIMUM
```

Damit ist dieser Fall **kein Suchfehler**. Er ist ein
`RETAINED_NOT_RECOGNIZED`: Regelsuche hat die gewünschte Darstellung im
Transformationsraum gefunden, erkennt ihren Gewinn mit den heutigen
Beschreibungsmessungen aber nicht.

Die Ursache ist fachlich plausibel: Der Assessor verlangt Verbesserung in
mindestens zwei seiner gemessenen Dimensionen. Die Umformung `x + x -> 2 * x`
reduziert wiederholte semantische Struktur beziehungsweise macht eine
Multiplizität explizit, wird von der gegenwärtigen Token-/AST-Metrik aber nicht
als hinreichend mehrdimensionaler Gewinn bewertet.

## Ergebnis 2: Die einfache Kalibrierung findet alle sechs Zielrepräsentationen

| Fall | eingefrorene Zielrepräsentation | von Politiken gefunden | vom heutigen Assessor als relevant anerkannt | Tiefe |
| --- | --- | ---: | ---: | ---: |
| `assumption-sensitive-cancellation-control` | `1` | 4/4 | 4/4 | 1 |
| `catalog-blind-trigonometric-bridge` | `sin(x)^2 + cos(x)^2` | 4/4 | 4/4 | 1 |
| `neutral-element-compression` | `x` | 4/4 | 4/4 | 1 |
| `occurrence-local-trigonometric-bridge` | `y * (sin(x)^2 + cos(x)^2)` | 4/4 | 4/4 | 1 |
| `repeated-term-compression` | `2 * x` | 4/4 | **0/4** | 1 |
| `telescoping-capability-bridge` | `1 / (n * (n + 1))` | 4/4 | 4/4 | 1 |

Daraus folgen für diese Kalibrierung:

```text
Target-Reach/Retention:      24 / 24 = 100 %
Recognition | retained:     20 / 24 = 83,3 %
Fallweise Recognition:       5 /  6 = 83,3 %
```

Der bisherige Bericht `5 von 6 Fällen qualifiziert` verdeckte also eine wichtige
Information: Beim sechsten Fall war die gesuchte Repräsentation vorhanden.

Diese Kalibrierung ist noch kein starker historischer Nachweis, weil alle
anerkannten Kandidaten Tiefe 1 haben.

## Ergebnis 3: Auch mehrschrittige Held-out-Repräsentationen werden gefunden

Die schwerere Matrix verbirgt Zielausdrücke, historische Bedeutungen und
Qualifikationsbedingungen bis nach dem vollständigen Candidate Freeze.
Positivkandidaten müssen je nach Fall drei bis zehn primitive Schritte besitzen;
Direktkanten und verschiedene Abkürzungen sind verboten.

| Held-out-Fall | kleinste qualifizierte Tiefe | größte qualifizierte Tiefe | Politiken mit qualifiziertem Fund | erster qualifizierter Checkpoint je Politik |
| --- | ---: | ---: | --- | --- |
| `assumption-chain-cancellation` | 3 | 5 | 4/4 | enum 64; random 128; scalar 32; diversity 32 |
| `binomial-expansion-complexity-valley` | 3 | 8 | 3/4 | random 16; scalar 32; diversity 32 |
| `difference-of-squares-reverse-bridge` | 3 | 8 | 3/4 | random 16; scalar 16; diversity 16 |
| `occurrence-local-trigonometric-bridge-held-out` | 3 | 6 | 3/4 | enum 128; scalar 32; diversity 256 |
| `telescoping-capability-bridge-held-out` | 3 | 3 | 4/4 | enum 64; random 64; scalar 32; diversity 32 |

Damit wurde in **allen fünf positiven Held-out-Familien** mindestens eine
qualifizierte, mehrschrittige Zielrepräsentation target-free gebildet. Insgesamt
qualifizieren 62 von 120 positiven Fall-/Politik-/Checkpoint-Zeilen. Alle 24
Near-miss-Kontrollzeilen bestehen; es gibt dort keine
Capability-/Known-structure-Fehlklassifikation.

Die Matrix zeigt gleichzeitig echte Suchpolitik-Unterschiede:

- Die Random-Policy erreicht die occurrence-lokale trigonometrische
  Zielrepräsentation an keinem Checkpoint.
- Die vollständige Enumeration findet bei Binomial- und
  Differenz-von-Quadraten zwar bereits eine referenzgleiche Darstellung bei
  Tiefe 2, erfüllt aber gerade deshalb die bewusst geforderte
  Drei-Schritt-/Komplexitätstal-Bedingung nicht.
- Scalar Best First findet für alle fünf Positivfamilien qualifizierte Pfade.
- Structural Diversity findet ebenfalls alle fünf, teilweise aber erst bei
  höherem Arbeitscheckpoint.

Das ist wichtig: „Zielrepräsentation gefunden“ und „den für die Studie
geforderten interessanten Herleitungsweg gefunden“ sind nicht dasselbe.

## Was damit bereits beantwortet ist

Unter den eingefrorenen endlichen Regelbeständen und Budgets kann Regelsuche:

1. interessante Zielrepräsentationen ohne Zielausdruck während der Suche
   erreichen;
2. auch Pfade mit drei bis acht primitiven Schritten und temporärer
   Komplexitätszunahme finden;
3. occurrence-lokale bekannte Strukturen und daraus ausführbar freigeschaltete
   Fähigkeiten erkennen;
4. Near-miss-Strukturen mit unterschiedlichen Variablen bislang ohne
   Fehlalarm zurückweisen.

Ebenso ist jetzt empirisch belegt, dass Regelsuche eine interessante
Repräsentation finden und anschließend **nicht als interessant erkennen kann**.
`x + x -> 2 * x` ist der erste klare Baseline-Fall.

## Was noch nicht beantwortet ist

Die bestehenden Artefakte beweisen noch nicht:

- wie hoch die Recall-Rate auf einer größeren, familienfremden historischen
  TEST-Menge ist;
- ob ein gefundener interessanter Kandidat in einer großen offenen
  Kandidatenmenge hoch genug gerankt und tatsächlich gezeigt wird;
- ob bislang unbekannte Kandidaten mathematisch relevant sind;
- ob ein mathematisch relevanter Kandidat extern neu ist.

Insbesondere existiert in dieser Baseline noch kein eigenständiges,
eingefrorenes Salience-Ranking. Kandidatenqualifikation ist nicht dasselbe wie
Top-k-Ranking. Ranking-Recall darf daher aus diesen Zahlen nicht abgeleitet
werden.

## Verbindlicher nächster Test

Issue #863 und `RepresentationSalienceAudit/v1` trennen künftig für jeden
Kandidaten:

```text
REACHABLE_NOT_REACHED_BY_POLICY
REACHED_NOT_FORMED
FORMED_NOT_RETAINED
RETAINED_NOT_RECOGNIZED
RECOGNIZED_NOT_RANKED
FULLY_DETECTED
```

Der nächste Lauf muss:

1. den unveränderten Baseline-Assessor einfrieren;
2. die historische TEST-Menge mit 3–10 Schritten und Distraktoren ausführen;
3. alle erreichten, gebildeten und verworfenen Kandidaten behalten;
4. Recognition-Recall und Ranking-Recall getrennt messen;
5. danach erst eine verbesserte Erkennung für Sharing, explizite
   Multiplizitäten, capability-bearing Zwischenformen und Transfer testen;
6. offene Kandidaten vor Literatur- oder Expertenprüfung einfrieren;
7. mathematische Relevanz durch echte blinde Experten und externe Neuheit in
   einem getrennten Verfahren bewerten.

## Claim-Grenze

Diese Baseline belegt bounded target-free Reachability und Formation für die
genannten Fälle sowie einen konkreten Recognition-Miss. Sie belegt keine globale
Vollständigkeit des Transformationsraums, keine universelle
Interessantheitserkennung, keine externe mathematische Neuheit und keine
allgemeine Überlegenheit gegenüber anderen Systemen.
