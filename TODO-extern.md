# TODO: Aufgaben mit externer Mitwirkung

Diese Liste enthält offene Arbeit, die eine Person, einen unabhängigen Dritten, geschützte Daten oder eine Betriebsfreigabe braucht. Die verlinkten Issues verfolgen die separat ausführbare Technik. Ein geschlossenes Issue, eine Testinstallation oder eine erfolgreiche CI bedeutet **nicht**, dass eine hier aufgeführte Studie, Veröffentlichung oder Begutachtung stattgefunden hat.

Ist ein Beitrag verfügbar, im zugehörigen Eintrag den freigegebenen Beleg oder die öffentliche Referenz ergänzen und den nächsten ausführbaren Schritt verknüpfen. Keine Kontaktdaten, Schlüssel, unveröffentlichten Kandidaten, Gutachten oder versiegelten Testdaten in öffentliche Issues oder dieses Dokument kopieren.

## SDK-Erprobung

**Herkunft:** [#904](https://github.com/carstenartur/Regelsuche/issues/904) · [Versuchsablauf und Aufgaben](java-sdk-human-dx.md).

- [ ] Zwei bis drei unabhängige Personen aus der Zielgruppe bearbeiten die Aufgaben mit JDK 25, dem veröffentlichten SDK und dem Quickstart auf einer frischen Umgebung. Hilfe, Wartezeiten, Fehler und Abbrüche nach dem Protokoll erfassen.
- [ ] Anonymisiert festhalten, ob die Teilnehmenden das erste Ergebnis selbst reproduzieren und den Unterschied zwischen einem verifizierten Rechenschritt und einer unbelegten Aussage erklären konnten. Auch erfolglose Sitzungen aufnehmen.

**Stand:** Technischer Bezug und isolierte Consumer-Builds sind nachgewiesen. Es liegen keine Nutzerdaten vor, aus denen sich eine Bearbeitungszeit oder studentische Bedienbarkeit ableiten lässt.

## Flagship-Studie

**Herkunft:** [#533](https://github.com/carstenartur/Regelsuche/issues/533) (Vorabfestlegung) und [#220](https://github.com/carstenartur/Regelsuche/issues/220) (Ausführung) · [geschützte Vorbereitung](evolution-rewrite-program-flagship-freeze-execution.md) · [einmaliger Abschlusstest](evolution-final-test-once.md).

- [ ] Die wissenschaftlich Verantwortlichen wählen die Fragestellung vor der Auswertung: weniger Sucharbeit oder Entdeckung geeigneter Darstellungen. Sie prüfen die konkreten Schwellen für einen positiven oder negativen Befund, Fall- und Familienauswahl, Kontrollen, Ressourcen und zugelassene Implementierungsrevisionen, ohne die Ergebnisse zu kennen.
- [ ] Eine vertrauenswürdige Stelle verwahrt die echten, disjunkten VALIDATION- und FINAL-Testdaten außerhalb des öffentlichen Repositories. Vor jedem ausgewerteten Lauf müssen die geprüften Bindungen und ein echter `FROZEN_NOT_RUN`-Beleg vorliegen. Öffentliche TRAIN-Daten, der Showcase und synthetische Testdaten sind kein Ersatz.
- [ ] Erst nach Freigabe des passenden Studienstands: TRAIN ausführen, allein mit VALIDATION zwischen Alternativen wählen und die Auswahl einfrieren. Die verwahrende Stelle gibt den gebundenen FINAL-Versuch einmal frei. Reservierung vor Einsicht; auch ein Abbruch zählt. Alle Fälle und Gründe erhalten, Kontrollen und nachgelagerte Prüfungen getrennt ausweisen. Reproduktion aus den archivierten Belegen; den FINAL-Versuch dafür nicht erneut verbrauchen.

**Bis dahin:** Die öffentliche Integrationsarbeit am Freeze-Vertrag bleibt in #533, die öffentlichen Vergleichsverfahren in [#235](https://github.com/carstenartur/Regelsuche/issues/235). Ein positiver wissenschaftlicher Befund ist keine Voraussetzung für die Archivierung. Eine künftige unabhängige historische Blindstudie zu [#874](https://github.com/carstenartur/Regelsuche/issues/874) braucht eigene, bisher unzugängliche Fälle; öffentliche zielverdeckte Entwicklungstests können vorher laufen.

## Blinde Fachgutachten

**Herkunft:** [#389](https://github.com/carstenartur/Regelsuche/issues/389) · [Studienprotokoll](independent-interestingness-review.md) · [Prüfpakete](blinded-interestingness-review-packets.md).

- [ ] Studienleitung und Datenschutzverantwortliche bestätigen Einwilligung, Rollen, Interessenkonflikte, Aufbewahrung, Widerruf und Incident-Verfahren. Eine benannte Stelle verwahrt Identitäten, geheimes Salt und die private Zuordnung.
- [ ] Geeignete unabhängige Fachleute gewinnen, qualifizieren, Interessenkonflikte prüfen und einen getrennten Pilot durchführen. Pro gezähltem Kandidaten mindestens zwei blinde unabhängige Bewertungen einholen, oder die höhere vorab festgelegte Mindestzahl. Enthaltungen, ausgeschlossene und fehlende Urteile bleiben sichtbar.
- [ ] Den vollständigen gezählten Bestand vor der Auswertung einfrieren. Unberührte TEST-Bewertungen erst nach der Kalibrierung auswerten. Eine getrennte Auditrolle prüft Zuordnung, Unabhängigkeit, Auswahl und Auswertung.

Die Erfassungssoftware und ihre Negativtests bleiben in #389. Ein Testdatensatz oder ein KI-Urteil zählt nicht als Gutachten.

## Mathematische Neuheit

**Herkunft:** [#391](https://github.com/carstenartur/Regelsuche/issues/391), vorbereitet unter anderem durch [#750](https://github.com/carstenartur/Regelsuche/issues/750) · [Literaturdossiers](literature-references.md).

- [ ] Einen geeigneten, bereits eingefrorenen Kandidaten zur tatsächlichen externen Prüfung freigeben. Umfang der Offenlegung und zulässigen Quellenzugriff vorher bestimmen. Bis dahin private Dossiers behalten; keine automatische Übermittlung.
- [ ] Qualifizierte unabhängige Fachleute beurteilen die einschlägigen Quellen, äquivalente Formulierungen, Annahmen und offenen Treffer. Für `EXTERNALLY_NOVEL_CONFIRMED` sind die vollständigen Protokollbedingungen und mindestens zwei unabhängige qualifizierte Zustimmungen erforderlich. Bekannter Stand, ungeklärte Treffer und fehlender Zugang sind zulässige Ergebnisse.

Ein dokumentierter Suchlauf ohne Treffer belegt nur die durchsuchten Quellen. Quellkatalog, Suchprotokoll und technische Entscheidungsprüfungen bleiben in #391.

## Manuskript und Archiv

**Herkunft:** [#388](https://github.com/carstenartur/Regelsuche/issues/388) · [Manuskript](../paper/manuscript.md) · [Reproduktion](paper-foundation-verification.md).

- [ ] Die tatsächlichen Autorinnen und Autoren bestätigen Beiträge, Interessen, Offenlegung der eingesetzten Werkzeuge und die für diese Fassung erforderlichen Rechte. Technisch belegbare Provenienz und reproduzierbare Tabellen werden bereits im Issue erarbeitet.
- [ ] Die Verantwortlichen geben die konkrete Manuskript- und Evidenzrevision frei, hinterlegen das unveränderliche Paket und prüfen den DOI für genau dieses Paket. Der bestehende Software-DOI ersetzt diese Hinterlegung nicht.

Ein Methodenbericht mit begrenzten Aussagen kann ohne externe Neuheitsbestätigung oder positive Flagship-Ergebnisse erstellt werden.

## Öffentlicher Plugin-Katalog

**Herkunft:** [#104](https://github.com/carstenartur/Regelsuche/issues/104) · [Publishing und Governance](plugin-publishing-governance.md).

- [ ] Betreiber benennen und produktiven Endpunkt, Infrastruktur, Rollen für Publisher, Kuration und Vertrauensanker sowie Verwahrung und Wiederherstellung von Schlüsseln und Checkpoints festlegen.
- [ ] Betrieb, Backup-Wiederherstellung, Sperrung und Incident-Verfahren unter den tatsächlichen Zuständigkeiten abnehmen. Die berechtigten Herausgeber entscheiden über die Veröffentlichung echter Pakete. Unabhängige Community-Nutzung erst mit entsprechenden Belegen ausweisen.

Vorher bleiben Implementierung und reproduzierbare Ende-zu-Ende-Prüfung eines Testkatalogs, eigenständig baubare Pakete und die Prüfung der Herkunftsbelege in #104.
