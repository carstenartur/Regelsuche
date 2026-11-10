# Discovery- und Forschungsstand

**Stand: 10. November 2026**

Regelsuche untersucht symbolische Suche, den Erwerb und die Wiederverwendung
geprüften Wissens sowie mathematisch geprüfte Optimierungen. Diese Übersicht
trennt implementierte Verfahren, tatsächlich ausgeführte Entwicklungsversuche
und wissenschaftliche Fragen, die weitere Studien benötigen. Die
[Forschungsmethodik](research-methodology.md) beschreibt diese Trennung und die
Rolle externer Referenzverfahren.

[Regelsuche 0.5.2](releases/0.5.2.md) wurde am 7. November 2026 veröffentlicht.
Diese Seite berücksichtigt auch den neueren Hauptzweig bis einschließlich
8. November. Dessen Ergänzungen gehören nicht automatisch zu den unveränderten
Release-Artefakten von 0.5.2.

## Zusammenfassung

Die vorhandene Plattform verbindet symbolische Suche, exakte Konstruktionen,
unabhängige Prüfung, persistiertes Wissen und ausdrücklich ausgelöste
Lernbatches. Drei aufeinander aufbauende Wissensgenerationen wurden tatsächlich
erworben und später verwendet. Zwei begrenzte Entwicklungsversuche zeigen
weniger deklarierte logische Gesamtarbeit einschließlich Erwerb und erneutem
Laden. Ein weiterer Versuch misst den vollständigen Lebenszyklus vorbereiteter
Sitzungen und bezahlter Selbstverbesserung; dort wurde keine tatsächliche
Amortisation gegenüber der bestehenden persistenten Sitzung nachgewiesen.

Der Forschungsgegenstand reicht inzwischen über Polynomumformungen hinaus:
endliche Summen, lineare Rekurrenzen, Paar- und Fensterreduktionen, geordnete
Tupel und begrenzte Rechenpläne für Körperoperationen besitzen ausführbare
Synthese- und Prüfpfade. Diese durch die Implementierung bereitgestellten Verfahren
liefern Suchmöglichkeiten und überprüfbare Beobachtungen. Ihr Vorhandensein
belegt noch nicht, dass das System die übergeordnete mathematische Strategie
selbst erlernt oder auf unabhängige Aufgabenfamilien überträgt.

## Implementierter Umfang

| Bereich | Vorhandener Umfang und Grenze |
| --- | --- |
| Suche und Produkt | Web-Workbench, CLI, Docker, native Suchereignisse, gespeicherte Pfade, Replay, Proof-Jobs und Exporte; Softwareprüfung und mathematische Ergebnisprüfung haben getrennte Verträge. |
| Exakte Algebra | Parsergebundene Literalprovenienz, begrenzte univariate Faktorisierung über `Z[x]` und `Q[x]`, endliche Körper, Hensel-Lifting, Rekombination und kontextgeprüfte AST-Ersetzungen. Ein Budgetabbruch ist kein Irreduzibilitätsbeweis. |
| Strukturelle Konstruktion | Quadratergänzung, exakte Faktorteilung, Resttermkomposition und begrenzte koeffizientenbasierte binäre Quartikzerlegung im gemeinsamen Suchraum; keine allgemeine multivariate Faktorisierung oder Ferrari-Prozedur. |
| Gleichungen und Darstellung | Exaktes `A*x=b`, Blockzerlegung, RREF, Lösungsklassifikation sowie explizite Eigenproblemrollen; Darstellungswahl und Solver haben gebundene Domänen. |
| Kumulatives Wissen | Unveränderliche Generationen, exakte Schemaprüfung, bezahlte Validierung und Auswahl, persistierte Herkunft und erneuter Beweis beim Laden. Lernen ist ein expliziter Batch; eine Anfrage verändert das Wissen nicht. |
| Vorbereitete Sitzungen | Begrenzte, ausdrücklich besessene Wiederverwendung bereits geprüfter Generationen und kostenpflichtige Verbesserungsversuche mit gemessener Übernahmeentscheidung; deren Prognose ersetzt keinen ausgeführten Gesamtkostennachweis. |
| Endliche Reduktionen | Polynomiale Summen mit Laufzeitgrenzen, verschachtelte/periodische/stückweise Komposition, enges rationales Teleskopieren und affine beziehungsweise gekoppelte lineare Zustandsrekurrenzen. Synthese und unabhängige Prüfung bleiben getrennt. |
| Paar- und geordnete Reduktionen | Ein-Pass-Paarsummen, rollende Paarfenster, geordnete Tupel mit zwei bis acht Zeilenpositionen und eingeschränkte Fenster mit geprüften Indexplänen. Mathematische Pläne erteilen keine pauschale Freigabe für Java-Quellcodeersetzungen. |
| Körper-Rechenpläne | Begrenzte Festpotenz- und Inversionspläne sowie Batch-Inversion; Domänenannahmen und Kostenmodell gehören zum jeweiligen geprüften Plan. |
| Externe Nutzung | Eigenständiges Java-25-Optimierungs-SDK, Standalone-Verteilung und gezählte-Summen-CLI; außerdem externer linearer Python-Client, Java-Beispiel und Browserdemo. Quelladapter müssen Effekte, Überläufe und Auswertungsreihenfolge gesondert absichern. |
| Literaturbezüge | Versionierte Quellenzuordnungen in Replay/Export und private Prüfung bekannter Vorarbeit am gespeicherten Kandidaten; ein begrenzter Suchumfang entscheidet keine weltweite mathematische Neuheit. |

Einstiege sind die [domänenbewusste Faktorisierung](domain-aware-polynomial-factorization.md),
[verschachtelten Vorkommen](exact-nested-factorization-transformation.md),
[strukturelle Konstruktion](research/structural-construction-learning.md), das
[Optimierungs-SDK](../regelsuche-optimization-sdk/README.md), sein
[Vertrag für endliche Reduktionen](../regelsuche-optimization-sdk/FINITE_SERIES.md)
und die [Literaturreferenzen](literature-references.md).

Für Paarreduktionen prüft der Verifier die Beiträge einer und zweier symbolischer
Zeilen. Fensterpläne bewahren ihre Invariante beim Entfernen und Einfügen;
geordnete Pläne prüfen zusätzlich die Auswahlintervalle und die umgekehrte
Entfernungsbedingung. Lineare Zustandspläne unterscheiden simultane und
sequenzielle Zuweisungen und verwenden geprüfte Matrixpotenzierung. Die
Batch-Inversion unterstützt zwei bis 64 Ausgaben mit einer Inversion und
`3*(n-1)` Multiplikationen unter den erforderlichen Invertierbarkeitsannahmen.
Diese Operationszahlen sind keine gemessenen Laufzeitfaktoren.

## Ausgeführte begrenzte Lern- und Kostenversuche

### Drei kumulative Wissensgenerationen

[CumulativeTaskKnowledge](research/cumulative-task-knowledge.md) gewinnt jede
Generation aus tatsächlich gelösten und unabhängig wiederholten Aufgaben.
Spätere Lektionen verwenden frühere Schemata und primitive Restschritte; neue
Regeln wirken erst nach Veröffentlichung des Nachfolgers. Nach drei Generationen
enthält der Bestand drei Schemata. Auf demselben vierteiligen Transferstrom
beträgt die automatische Anfragearbeit für Generation 0/1/2/3 jeweils
**1.762 / 1.763 / 1.594 / 1.194**. Die erste Generation ist hier geringfügig
teurer; eine Verbesserung jeder einzelnen Aufgabe oder Generation folgt daraus
nicht.

Ein tatsächlich ausgeführter Strom aus **512 Anfragen** enthält drei
Kompositionstiefen und einen nicht erreichten Negativfall, wechselnde Namen und
zusammengesetzte Einsetzungen. Kein früheres Anfrageergebnis wird zwischengespeichert.
Alle Vergleichsarme haben dieselben Erfolgsergebnisse.

| Arm | Deklarierte logische Gesamtarbeit |
| --- | ---: |
| Primitive Baseline | 225.536 |
| Generation 1 erwerben und einfrieren | 229.264 |
| Kumulativ bis Generation 3, einschließlich Erwerb und frischer Sitzung | 186.706 |

Die letzte Summe besteht aus **31.611** Erwerbs-/Validierungs-/Vorbereitungseinheiten,
**2.263** Einheiten für erneutes Laden/Vorbereiten und **152.832** Anfrageeinheiten.
Das sind rund **17,2 %** weniger deklarierte Arbeit als die primitive Baseline
auf diesem Entwicklungsstrom. Es ist ein ausgeführtes Amortisationsbeispiel;
Wall-Zeit, vollständige JVM-CPU, Speicher und Serialisierung werden durch dieses
logische Modell nicht vollständig erfasst.

### Lernen mit denselben strukturellen Fähigkeiten

Der [strukturelle Lernversuch](research/structural-construction-learning.md)
verwendet dieselben Konstruktionen, Aufgabenbudgets und Erfolgskriterien in
Baseline und Lernarmen. Nach zwei Lernbatches, Speichern und erneutem Laden
werden **32 Aufgaben** ausgeführt: acht Runden aus zwei historischen Formen,
einem bereits faktorisierten Ausdruck und einem erfolglosen Fall. Alle Arme
lösen **24 von 32** Aufgaben; es sind vier Strukturvorlagen, keine 32 unabhängigen
Mathematikfamilien.

| Arm | Logische Gesamtarbeit einschließlich Erwerb und Sitzungsvorbereitung |
| --- | ---: |
| Konstruktionsbaseline | 506.872 |
| Erste Fähigkeit lernen und einfrieren | 316.450 |
| Beide Fähigkeiten lernen und erneut laden | 163.881 |

**153.329** Erwerbs-/Validierungs-/Vorbereitungseinheiten, **1.424** Ladeeinheiten
und **9.128** Anfrageeinheiten ergeben rund **67,7 %** weniger deklarierte Arbeit
als die Baseline mit denselben Fähigkeiten. Ein zweiter JVM-Prozess verwendet
beide gespeicherten Schemata auf zusammengesetzten Einsetzungen ohne erneutes
Training. Die vorbereitende Resttermstrategie ist dabei weiterhin bereitgestellt;
der Versuch zeigt ihren Wissenserwerb aus Ergebnissen, nicht die selbstständige
Erfindung dieser Strategie.

### Vorbereitete Sitzungen und bezahlte Selbstverbesserung

[Vorbereitete Sitzungen](prepared-task-knowledge.md) vermeiden wiederholte volle
Beweise identischer bereits geprüfter Generationen innerhalb ihrer eigenen
begrenzten Lebensdauer. Der Verbesserungszyklus bezahlt Formation, alte und neue
Vergleichsausführungen sowie erfolglose Versuche. Seine Übernahmeentscheidung
verlangt eine prognostizierte Rückzahlung der aktuellen Versuchskosten in
Wall-Zeit und gesamter JVM-CPU bei unveränderter individueller Ergebnisqualität.
Frühere abgelehnte Versuche bleiben in der Gesamtbilanz bezahlt.

Der [Lebenszyklusversuch vom 7. November](research/knowledge-lifecycle-improvement-2026-11-07.md)
führte **36 vollständige Lebenszyklen, 2.448 Anfragezeilen und 60 eigene Prozesse**
aus. Er umfasst Erwerb, frischen ersten Lade-/Beweislauf, Dateizugriffe,
Verbesserungsversuche, Anfragen, Transport, unabhängige Prüfung und Beenden.
Die Längen betragen acht beziehungsweise 128 Anfragen; alle Profile erreichen
7/8 beziehungsweise 112/128 Ziele. Eine vorhandene persistente Sitzung ist als
Kontrolle enthalten.

Die dritte Generation wird in allen drei langen Verbesserungsfolgen übernommen,
in den kurzen abgelehnt. Trotzdem benötigt der vollständige gemischte Strom
mehr mediane Wall-Zeit und CPU als die persistente Kontrolle. **Eine tatsächliche
Amortisation ist hier nicht gezeigt.** Der prognostizierte Aufgabenumfang macht
eine einseitige Übernahmestichprobe noch nicht repräsentativ für die spätere
Mischung. Einzelne niedrigere Vorbereitungskosten werden deshalb nicht als
vollständiger Effizienzgewinn ausgegeben. Der Bericht bewahrt Messgrenzen,
Einzelwiederholungen, erfolglose Versuche und spätere Quellcodekorrekturen getrennt
vom unveränderten gemessenen Runtime-Artefakt.

## Vergleichsverfahren und historische Aufgaben

Externe Verfahren sind austauschbare Referenzen für klar festgelegte Fragen.
SymPy wurde praktisch gewählt, weil seine Nutzung keine Lizenzgebühren erfordert;
andere geeignete Software kann diese Rolle ebenfalls erfüllen. Jede Aussage
bezieht sich auf die eingefrorenen Aufrufe, Informationen, Domänen und Messgrenzen
des jeweiligen [Vergleichsprotokolls](external-polynomial-comparison.md).

Im [nativen Polynomvergleich vom 6. November](research/native-polynomial-parity-2026-11-06.md)
enthalten **72 unterstützte Fall-IDs 70 verschiedene Eingabezeichenketten**.
Beide nativen Profile erreichen je Wiederholung bei **69** Fall-IDs dieselbe
arithmetische Ausgabeoperationszahl wie das feste SymPy-1.14.0-Portfolio und
bei **drei** eine niedrigere. Die drei nicht unterstützten Kontrollen bleiben
außerhalb dieses Nenners. Die Zielfunktion zählt arithmetische Syntaxoperationen;
sie misst weder Latenz noch globale mathematische Einfachheit. Die native
Baseline und der Lernarm haben hier dieselben Qualitätswerte. Ihre vollständigen
Sequenzen sind in dieser Messung langsamer als das Referenzportfolio; ein
zusätzlicher Qualitäts- oder Gesamtkostennutzen durch Lernen folgt daraus nicht.

Der separate [historische Vergleich](research/historical-sympy-comparison-2026-11-06.md)
prüft Cassini, Pell, Brahmagupta–Fibonacci und Sophie Germain anhand ihrer
jeweiligen mathematischen Ziele. Beide nativen Profile und das Referenzverfahren
lösen alle **vier kanonischen Aufgaben**. Auf **zehn öffentlichen Varianten**
steigt die native Erfolgszahl mit tatsächlich erworbenem Wissen von **sechs auf
zehn**; das Referenzverfahren löst ebenfalls zehn. Die drei Negativ-/Budgetkontrollen
bleiben ungelöst. Der gelernte vollständige Lebenszyklus ist bis zur gemessenen
Länge von 72 Anfragen teurer als das Referenzverfahren, auch wenn einzelne
Brahmagupta-Anfragen kürzere gemessene Latenzen haben.

Für Cassini und die Pell-Transition werden nichtkonstante quadratische
Invarianten aus dem angegebenen Übergang synthetisiert. Unabhängige Prüfung
kontrolliert den universellen Übergangsschritt und den Anfangswert; daraus folgt
die Invariantaussage für die deklarierte Rekurrenz. Das ist ein domänenspezifischer
Induktionsnachweis, kein allgemeiner Pell-Gleichungslöser. Die kanonischen Fälle
und Varianten sind bekannte Entwicklungsfamilien, teilweise mit Lehr- oder
Validierungsüberlappung. Sie ersetzen keinen versiegelten Test auf unabhängigen
Familien.

## Frühere eingefrorene Ergebnisse und ihre Bedeutung

Die älteren Experimente bleiben mit ihren ursprünglichen Verfahren und Grenzen
reproduzierbar. Neue Fähigkeiten verändern ihre Ergebnisse nicht nachträglich.

- [Generationenbasiertes Regelmining](generational-rule-mining.md) vergleicht
  eingefrorene Schatteninventare `0+1` und `0+1+2` unter engem Tiefenbudget und
  verlangt die tatsächliche Verwendung einer neuen Regel aus Generation 2.
  Der [autonome Walkthrough](autonomous-discovery-walkthrough.md) untersucht
  parametrisch geprüfte Wiederverwendung. Weniger äußere Makroschritte sind
  allein kein vollständiger Arbeitskostennachweis.
- Der ältere targetfreie Simplification-Vergleich behält **Regelsuche 6/7 und
  SymPy `simplify` 7/7** auf seinem eingefrorenen Bestand. Der spätere native
  Polynomversuch hat einen anderen Vertrag.
- Der frühere Brahmagupta–Fibonacci-Pfad benötigt elf Schritte und verwendet
  zweimal dieselbe gelernte Ergänzungsregel unter deklarierter Phasenfolge.
  Best-First ohne diese Phasenvorgabe fand innerhalb von 20.000 Zuständen keinen
  Pfad. Die spätere
  [Resttermkomposition](schematic-proof-plan-residual-composition.md) wählt
  disjunkte Paare und Vorzeichen durch exakte Nullrestprüfung ohne Zielausdruck.
- [Budgettreue Programmkomposition](budgeted-rewrite-program-composition.md)
  bewahrt Pfadbudgets, erfolglose Aufrufe und unvollständige Ergebnisse bei
  `Choice`, `FirstApplicable`, `Sequence`, `Repeat` und `Prune`. Die
  [gelernten Regelfolgen](trace-strategy-transfer.md) aus acht öffentlichen
  Entwicklungsaufgaben erreichen dieselben Endausdrücke mit mehr Sucharbeit.
- Die [bedingte Fortsetzungsauswahl](conditional-strategy-dispatch.md) senkt
  auf 288 neuen Entwicklungsfällen die gezählte Anfragearbeit von **8.960 auf
  8.832** bei gleichen Scores (rund 1,4 %; 1.152 Vergleichszeilen). Die
  **1.166** zusätzlichen Lerneinheiten einschließlich **84** Einheiten für die
  [Prüfung kürzerer primitiver Wege](primitive-trace-minimality.md) sind noch
  nicht amortisiert. Dies bleibt vom späteren kumulativen Versuch getrennt.
- [Spurbasierte Polynomvorlagen](trace-derived-polynomial-plans.md) und ihre
  [endliche Auswahl](finite-polynomial-strategy-selection.md) bewahren
  Formationssperren und lösen Koeffizienten bei jeder Anwendung erneut.
  Zwölf Auswahlzeilen und zwei frisch geprüfte Theorieanwendungen belegen
  diesen begrenzten Vertrag, nicht die Erfindung der Ansatzgrammatik.
- Die automatische Darstellungswahl spart im öffentlichen Entwicklungssatz
  rund 6 % gezählte Arbeit. Die [gelernte Auswahl](representation-learning-diagnosis.md)
  verwendet 18 Trainingstrials und 42 weitere Aufgaben in sechs Profilen;
  ihre feste Bedingung nutzt den gemessenen Spielraum, erwirbt dadurch aber
  noch kein neues mathematisches Verfahren.

Ein unveränderter, zur Referenz äquivalenter Ausgangsausdruck zählt weiterhin
nicht als Wiederentdeckung einer verlangten Faktorform. Intrinsische Prüfung,
strukturelle Korrespondenz, Erkennung und Ranking bleiben getrennt; siehe
[Salienz-Audit](representation-salience-audit.md).

<!-- capability-status:start -->
## Maschinengebundener Capability-Status

Die folgende Kurzmatrix wird aus den kanonischen Release-, Domain-, Trust- und Runtime-Verträgen erzeugt. Die vollständige Matrix mit Evidence-Roots steht in [`capability-status.md`](generated/capability-status.md).

| Capability | Status |
|---|---|
| `AUTONOMOUS_CAMPAIGN` | `QUALIFIED` |
| `DOMAIN_GENERIC_DISCOVERY` | `QUALIFIED` |
| `EXTERNAL_NOVELTY_REVIEW` | `BLOCKED` |
| `FORMAL_PROOF_OF_RETAINED_CANDIDATE` | `NOT_EVALUATED` |
| `PLUGIN_ARTIFACT_TRUST` | `IMPLEMENTED` |
| `PLUGIN_INDEX_AUTHENTICATION` | `IMPLEMENTED` |
| `PLUGIN_TRUST_STATE_REVISIONS` | `IMPLEMENTED` |
| `PROMOTION` | `NOT_EVALUATED` |
| `PUBLIC_EVIDENCE` | `NOT_EVALUATED` |
| `PUBLIC_PLUGIN_DISTRIBUTION` | `BLOCKED` |
| `SAFE_PREPARATION_PRODUCT` | `EXPERIMENTAL` |

`QUALIFIED` autorisiert nur den jeweils benannten Claim. Externe mathematische Neuheit, formaler Beweis, Promotion und Public Evidence werden nicht aus einem anderen erfolgreichen Profil abgeleitet.
<!-- capability-status:end -->

## Weitere wissenschaftliche Fragen

Die nächsten aussagekräftigen Studien müssen über neue Koeffizienten oder
Variablennamen bekannter Formen hinausgehen. Relevant bleiben insbesondere:

- Übertragung erworbener Schemata und gelernter Pläne auf vorab getrennte
  mathematische Familien, mit starken Kontrollen unter denselben Fähigkeiten,
  Erfolgskriterien und Gesamtarbeitsgrenzen.
- Vollständige Lebenszykluskosten bei repräsentativen Aufgabenmischungen,
  einschließlich bezahlter Ablehnungen, Erwerb, Wiederherstellung und Prüfung;
  logische Arbeit und tatsächlich gemessene Wall-Zeit/CPU getrennt ausweisen.
- Lernen der vorbereitenden Strukturwahl oder der übergeordneten Beweisstrategie,
  statt nur Auswahl und Wiederverwendung bereitgestellter Konstruktionen.
- Unabhängige Aufzählungs-/Zufallskontrollen, vollständige Fallmatrizen und
  unabhängige Reproduktion; die frühere Studienplanung unter #750/#874 bleibt
  dafür ein historischer Bezug, ohne hier einen aktuellen Issue-Status festzulegen.
- Ein gesondert nachgewiesenes Flagship-Protokoll mit realem `FROZEN_NOT_RUN`-Vertrag,
  TRAIN, VALIDATION und genau einmal verwendetem FINAL TEST. Die früheren
  Referenzen #533/#220/#521 werden durch diese Entwicklungsversuche nicht ersetzt.

Euklids Primzahlzeuge und eine allgemeine quartische Resolventenkonstruktion
sind durch die hier beschriebenen Nachweise nicht geliefert. Literaturbezug,
externe Relevanz und mathematische Neuheit benötigen jeweils ihren eigenen
Prüfumfang; die historischen Referenzen #389/#391 kennzeichnen diese getrennten
Fragen. Interne Tests und automatisierte Reviews ersetzen keine unabhängige
fachliche Bewertung.

## Build und Reproduktion

Für eine neue Ausführung gelten die JDK-25-, Build- und Runtime-Vorgaben des
jeweiligen Checkout und Versuchsberichts. Der umfassende Gradle-Prüfeinstieg ist:

```bash
./gradlew --no-configuration-cache ciCheck
```

Die Seiten zu [kumulativem Wissen](research/cumulative-task-knowledge.md) und
[strukturellem Lernen](research/structural-construction-learning.md) enthalten
die fokussierten Testbefehle. Der vollständige Lebenszyklus hat einen eigenen
Runner:

```bash
bash gradle/run-knowledge-lifecycle-improvement.sh
```

Der [Lebenszyklusbericht](research/knowledge-lifecycle-improvement-2026-11-07.md)
dokumentiert auch die Offline-Prüfung unveränderter Evidence. Für Polynom- und
historische Vergleiche führen die jeweiligen Berichte zu Protokoll, gebundenen
Quellständen, Runtime-Identitäten und Reproduktionsbefehlen. Beim
[Standalone-SDK](../regelsuche-optimization-sdk/README.md) kommen dessen
Distributions- und Verbraucherverträge hinzu. Eine bestandene Softwareprüfung,
ein ausgeführtes Forschungsprotokoll, ein mathematischer Nachweis und ein
veröffentlichtes Release-Artefakt sind jeweils gesondert nachzusehen.
