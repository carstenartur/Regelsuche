# Forschungsfragen und Vergleichsmethodik

Regelsuche erforscht Verfahren zur symbolischen Suche, zur Wiederverwendung
geprüften Wissens und zur Herleitung effizienterer Rechenpläne. Die Workbench,
die mathematischen Bibliotheken und die SDKs machen diese Verfahren ausführbar
und ihre Ergebnisse untersuchbar. Diese Seite beschreibt das Forschungsziel
und die Rolle von Vergleichsmessungen. Den datierten Implementierungs- und
Versuchsstand führt [Discovery- und Forschungsstand](discovery-status.md).

## Welche Fragen untersucht das Projekt?

- **Suche und Darstellung:** Welche Regeln, Zwischenformen und mathematischen
  Repräsentationen erschließen einen Lösungsweg unter einem gegebenen Budget?
- **Kumulatives Lernen:** Wann hilft geprüftes Wissen bei späteren Aufgaben,
  und wann spielt die Wiederverwendung Erwerb, Auswahl, Laden und Prüfung ein?
- **Strukturübertragung:** Welche Bestandteile einer Herleitung lassen sich
  auf neue Eingaben übertragen, und welche Voraussetzungen müssen erneut
  geprüft werden?
- **Rechenoptimierung:** Wie werden aus einem mathematischen Rechenmodell
  günstigere Auswertungspläne mit unabhängig prüfbarer Gleichheit abgeleitet?

Ein Versuch legt fest, welche dieser Fragen er beantwortet. Eine zusätzliche
mathematische Fähigkeit, weniger Sucharbeit und eine kürzere gemessene Laufzeit
sind unterschiedliche Ergebnisse und werden getrennt ausgewiesen.

## Rolle externer Referenzsoftware

SymPy wurde für mehrere Versuche als praktisch verfügbare Referenz gewählt,
weil seine Nutzung keine Lizenzgebühren verursacht. Andere Software mit den
benötigten mathematischen Fähigkeiten kann dieselbe Rolle übernehmen. Die
Auswahl richtet sich nach der untersuchten Frage, dem unterstützten
Definitionsbereich und der Möglichkeit einer reproduzierbaren Ausführung.

Verglichen werden konkret konfigurierte Verfahren: beispielsweise eine feste
Folge von Polynomoperationen, eine Invariantenberechnung oder ein Verfahren zur
Darstellung als Quadratsumme. Software, Version, Operationen und Auswahlpolitik
werden im jeweiligen Protokoll benannt. So bleibt nachvollziehbar, welches
Verhalten die Referenzzeile tatsächlich misst.

Eine weitere Referenzimplementierung erhält einen eigenen dokumentierten Lauf.
Vorhandene Messreihen behalten ihre ursprünglichen Eingaben, Versionen und
Ergebnisse. Die [Anleitung für externe Polynomvergleiche](external-polynomial-comparison.md)
beschreibt die vorhandenen Profile und ihre Reproduktion.

## Aufgaben und Informationsgrenzen

Der Aufgabenvertrag bestimmt das gesuchte Ergebnis: etwa eine kleinere
Ausdrucksform, eine nichttriviale Faktorisierung, eine Quadratsumme oder eine
Invariante eines gegebenen Rekurrenzübergangs. Mathematische Gleichheit und
Erfüllung dieses strukturellen Ziels werden separat geprüft.

Der Versuchsbericht hält fest, welche Informationen die Verfahren erhalten,
welche mathematischen Bausteine bereits vorgegeben sind und welche Anteile
tatsächlich gelernt werden. Quelle, Zielkriterium, Annahmen und Budgets gehören
zum Vertrag; eine mitgelieferte Zielantwort würde eine andere Frage untersuchen
als die Suche aus der Quelle.

Lehrdaten, Validierungsfälle, öffentliche Entwicklungsvarianten und unabhängig
zurückgehaltene Prüfaufgaben werden benannt. Umbenannte Variablen oder neue
Parameterwerte zählen als Instanzen ihrer jeweiligen Familie. Einfache Fälle,
Fehlschläge, Budgetabbrüche und abgelehnte Lernversuche bleiben im Bericht.

## Welche Kosten und Ergebnisse werden gezählt?

| Größe | Aussage des jeweiligen Versuchs |
| --- | --- |
| Zielerfüllung | Welche Aufgaben erfüllen den deklarierten Ergebnisvertrag? |
| Ergebnismaß | Wie viele Syntaxoperationen oder AST-Knoten hat die gewählte Darstellung? |
| Logische Arbeit | Welche Such-, Konstruktions-, Auswahl- und Prüfschritte zählt das deklarierte Kostenmodell? |
| Lebenszykluskosten | Welche Kosten entstehen einschließlich Erwerb, Speicherung, Laden, erneuter Prüfung, Anfragen und erfolgloser Versuche? |
| Laufzeit, CPU und Speicher | Was wurde unter der dokumentierten Umgebung und Messgrenze tatsächlich beobachtet? |

Logische Einheiten verschiedener Implementierungen werden nur verglichen, wenn
ihre Zählverträge dies erlauben. Anfragezeiten und vollständige Lebenszyklen
bleiben getrennt. Bei einem Laufzeitvergleich werden auch die pro Aufgabe
erreichte Qualität und die tatsächlichen Prozesskosten ausgewiesen.

Die bestehenden Vergleiche verwenden Kontrollen mit demselben mathematischen
Inventar, eingefrorenem Wissen oder einer bereits persistent gehaltenen Sitzung.
So kann ein Versuch untersuchen, welcher Anteil auf neue Fähigkeiten, Lernen,
Wiederverwendung oder vermiedene Vorbereitung zurückgeht. Eine prognostizierte
Übernahmeentscheidung wird anschließend an der tatsächlich ausgeführten Folge
gemessen.

## Nachweise und zeitlicher Stand

Suchpfade, exakte Identitätsprüfungen, domänenspezifische Induktionsnachweise und
Zertifikate eines formalen Beweiswerkzeugs besitzen eigene Verträge. Literatur
und Herkunft werden über [versionierte Quellenzuordnungen](literature-references.md)
an Regeln und Herleitungsschritte gebunden.

Datierte Versuchsberichte beschreiben ihre gebundenen Läufe. Redaktionelle
Aktualisierungen ändern keine Messwerte, Protokolle oder Evidence-Hashes. Neue
Fähigkeiten erscheinen in der aktuellen Statusübersicht und werden mit ihren
eigenen Nachweisen verlinkt. Die [generierte Capability-Matrix](generated/capability-status.md)
bewertet genau die jeweils benannten Profile und Kandidaten.

## Ausgeführte Untersuchungen

- [Kumulatives Aufgabenwissen](research/cumulative-task-knowledge.md) und
  [strukturelle Konstruktionen](research/structural-construction-learning.md):
  Wissensgenerationen, Übertragung und Bilanzierung logischer Arbeit.
- [Native Polynomverfahren](research/native-polynomial-parity-2026-11-06.md):
  Ergebnisdarstellungen und vollständige Kosten eines festgelegten Portfolios.
- [Historische Aufgaben](research/historical-sympy-comparison-2026-11-06.md):
  Konstruktion, Invarianten und Wiederverwendung bei expliziten Ergebniszielen.
- [Vorbereitetes Wissen und weitere Lernversuche](research/knowledge-lifecycle-improvement-2026-11-07.md):
  gemessene Übernahmeentscheidungen und vollständige Lebenszyklen.

Reproduktionsanforderungen stehen in
[Scientific Reproducibility](scientific-reproducibility.md); die konkreten
Protokolle, Befehle und Artefakte stehen bei jedem Versuch.
