# Schwächen aus dem Vergleich vom 22. November beheben

## Auftrag und Ausgangspunkt

Carsten hat am 24. November 2026 die Umsetzung der identifizierten Schwächen
beauftragt. Ausgangspunkt ist `f183aedd1ff2ab061ed903559b96a11d28974e7f`;
die eigene `main`-CI dieses Commits ist erfolgreich. PR #1375 gehört zur
bereits laufenden Maven-Migration und wird nicht erneut implementiert.

Die Arbeit betrifft fehlende direkte Vergleichsläufe und fremde Eingaben,
vermeidbaren Gesamtaufwand, widersprüchliche Einstiegspfade und die Lücke
zwischen Quellstand, veröffentlichtem SDK und qualifizierten Consumern.
Sie übernimmt nicht pauschal sämtliche weitergehenden Forschungsziele der
zehn offenen Architektur-Issues.

## Entwurf

Vorhandene Such-, Prüf- und Vergleichspfade bleiben die Grundlage. Echte
Originalwerkzeuge werden über die vorhandenen Prozessgrenzen ausgeführt;
bekannte Fälle erhalten Herkunft und vollständige Ausschlussgründe.
Laufgebundene Wiederverwendung darf ausschließlich schon exakt geprüfte
und vollständig gebundene Informationen verwenden. Ein schnellerer
Spezialfall wird als solcher berichtet.

Ein bloßes Anheben von Budgets oder Umdeuten historischer Fehlschläge würde
die Bewertungsgrundlage verändern und behebt die Ursache nicht. Ein neuer
allgemeiner Solver wäre für diese Lücken zu breit. Deshalb werden konkrete
Kosten und fehlende Übergänge in den bestehenden APIs bearbeitet.

## Globale Bedingungen

- Java 25, typisierte APIs, bestehende Modulgrenzen und strikte Warnungen.
- Keine Schwächung von exakten Prüfern, Budgets, CI-Gates oder Negativfällen.
- Historische Ergebnisse und eingefrorene Protokolle bleiben unverändert.
- Neue Leistungsaussagen enthalten Quelle, Version, Kostenumfang, Kaltstart,
  Wiederholungen und den tatsächlich ausgeführten Vergleich.
- Mechanische Arbeit verschiedener Werkzeuge ist nicht dieselbe Einheit.
- Fremde öffentliche Fälle sind kein unbeobachteter Holdout und keine
  unabhängig durchgeführte Studie. Neuheit und allgemeine Überlegenheit
  werden nicht aus Tests abgeleitet.
- Getrennte Git-Arbeitsbereiche; Änderungen an gemeinsamen Dokumenten
  (`strategies.md`, Capability-Matrix) werden bei der Integration abgestimmt.
- Keine Veröffentlichung ohne bestandene dafür vorgesehene Release-Gates;
  keine manuell inkonsistenten Modulversionen.
- Änderungen erhalten gezielte Verhaltensprüfungen, unabhängige Review und
  die reguläre CI. Reine Textkorrekturen benötigen keine künstlichen Tests.

## Task 1: Gesamtaufwand für Aufgabenwissen

Arbeitsbereich: `regelsuche-learning-fix`.

1. Aktuelle `TaskKnowledgeSession`-, `TaskKnowledgeSelfImprover`- und
   `CumulativeTaskKnowledge`-Pfade profilieren; bereits gelieferte gemischte
   Workloads berücksichtigen.
2. Einen wiederholbaren Kostenfehler mit einem Verhaltenstest sichtbar
   machen. Begrenzte Wiederverwendung an Quelle, Generation, Semantik und
   Budget binden; alle bisherigen Ergebnis- und Beweisverträge erhalten.
3. Bestehenden gemischten und wiederholten Workload vor/nach dem Fix ausführen.
   Erwerb, Prüfung, Vorbereitung und Auswertung gemeinsam ausweisen.
4. Code, Tests und einen Befund mit positiven und negativen Grenzen liefern.

## Task 2: Direkte Originalwerkzeuge und fremde Fälle

Arbeitsbereich: `regelsuche-peer-fix`.

1. Den originalen Polyhedral-Reduktionscompiler mit festem Commit tatsächlich
   ausführen; gemeinsamen Ausdrucksbereich vor den Ergebnissen festhalten.
2. Fremdes vollständiges Eingabeinventar erfassen. Unterstützte, falsche,
   ausgeschöpfte und nicht darstellbare Fälle bleiben im Ergebnis sichtbar.
3. Die vorhandenen Prozess-/Prüfgrenzen nutzen. Original Enumo/Ruler nur als
   tatsächlich ausgeführtes Werkzeug ausweisen; Erwerb und Anwendung trennen.
4. Einen reproduzierbaren Java/Maven-Einstieg, Quellenbindungen und Rohdaten
   liefern. Originalartefakte und Lizenzen respektieren; keine nachgebaute
   Referenz als Messung des Originals bezeichnen.

## Task 3: Aktueller SDK-Einstieg und Consumer-Qualifikation

Arbeitsbereich: `regelsuche-product-fix`.

1. README und Startseiten auf den tatsächlich veröffentlichten SDK-Stand
   ausrichten; Quellstand und Release klar benennen.
2. Die historische 0.5.2-Archivqualifikation erhalten und um den aktuellen
   veröffentlichten Central-Consumer sowie das neue Releaseverfahren ergänzen.
3. Durchgehende ausführbare Beispiele mit dem wirklichen Artefakt testen.
   Fehlende oder falsche Artefakte müssen als Fehler sichtbar bleiben.
4. Release- und Sandbox-Qualifikationspfad vorbereiten; keine stillschweigende
   Änderung von SDK-Pins oder JARs ohne tatsächliche Qualifikation.

## Task 4: Integration, Ursachenbefund und Release

Arbeitsbereich: `regelsuche-remediation`.

1. Die Null-Ergebnisse des öffentlichen Faktorisierungsprofilvergleichs
   untersuchen. Eine beobachtete vermeidbare Ursache beheben, soweit sie
   unabhängig vom eingefrorenen Budget nachgewiesen werden kann.
2. Teiländerungen unabhängig reviewen, integrieren und referenzierte
   Strategien/Verfügbarkeit aktualisieren. Historische Befunde bewahren.
3. Reguläre PRs erstellen; nur nach vollständigem verifiziertem Gate mergen.
   Den kombinierten Hauptzweig separat prüfen.
4. Release- und Consumer-Gates tatsächlich ausführen. Veröffentlichen nur
   über den bestehenden freigegebenen Ablauf und dessen Voraussetzungen.
5. Nicht technisch herstellbare Nachweise (unabhängige Anwenderurteile,
   unabhängige Replikation, wissenschaftliche Neuheit) ausdrücklich als
   verbleibend ausweisen. Keine technische Teilabnahme als Gesamtabschluss
   der weitergehenden Forschungs-Issues behandeln.

## Abnahme

Die abschließende Antwort nennt für jede bewertete Schwäche den konkreten
geänderten Pfad, die tatsächlich durchgeführte Prüfung und verbleibende
Grenzen. Nur vorhandene Messergebnisse dürfen einen Nutzen belegen.
