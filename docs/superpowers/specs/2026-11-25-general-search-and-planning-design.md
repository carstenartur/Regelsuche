# Allgemeine Suche, Planung und nutzbare Mathematik

## Auftrag und Ziel
Carsten hat am 25. November 2026 die Umsetzung der unmittelbar vorher besprochenen Empfehlungen beauftragt. Diese Spezifikation konkretisiert diese bereits gewählte Richtung: vorhandene mathematische Fähigkeiten gemeinsam und effizient nutzen, vollständige Kosten ausweisen, praktische Abdeckung erweitern und Ergebnisse über Produkt und veröffentlichtes SDK verfügbar machen.

## Verbindliche Anforderungen
- Java 25; vorhandene Maven-/Gradle-Verträge und Modulgrenzen erhalten.
- Bestehende AST-, ExactRational-, Polynomial-, Solver-IR-, Budget- und Evidence-Typen wiederverwenden.
- Allgemeine Verfahren; keine Auswahl über Benchmarknamen oder vorbereitete Zielantworten.
- Historische Messartefakte und eingefrorene Vergleichsbudgets unverändert lassen. Neue Varianten und Messungen ausdrücklich versionieren.
- Lernen, feste Verfahren und Wiederverwendung getrennt ausweisen. Kein Leistungs-, Transfer- oder Optimalitätsversprechen ohne ausgeführte passende Prüfung.
- Quellen, Voraussetzungen, Definitionsbereiche und Budgetverbrauch einschließlich Fehlversuchen und Rückfall binden.
- Unabhängige mathematische Prüfer erhalten. Caches sind begrenzte, eigentümergeführte Vorschlagsspeicher.
- Tests für neue mathematische und zustandsbehaftete Verträge: positive, negative, verfälschte und Ressourcenfälle; keine gelockerten Qualitätsgates.
- StandardCharsets verwenden; keine festen globalen Workerzahlen; keine neuen externen Laufzeitdienste.
- Kleine nachvollziehbare Commits und PRs; keine Veröffentlichung vor bestandener Releaseprüfung.

## Gewählter Aufbau
Die bestehende Architektur wird schrittweise verbessert. Bestehende Sonderprofile werden nur dann zu gemeinsamen Produktpfaden, wenn ihr Ausführungsvertrag dies erlaubt. Wir vermeiden eine zweite AST-/Solverhierarchie und einen großen Neubau des Suchkerns.

Erwogene Alternativen: Ein großer gemeinsamer Neuentwurf hätte erhebliche Integrationsrisiken. Nur weitere Einzelalgorithmen würden die erkannten Verwaltungs-, Auswahl- und Lieferlücken lassen. Die vom Nutzer gewählte Empfehlung wird durch kleine kompatible Änderungen mit getrennten Nachweisen umgesetzt.

## Arbeitspakete
1. Laufende CI-Korrektur nachprüfen; schnelle frühe Vertragsprüfung und verbleibende Build-Duplikate anhand bestehender Paritätsbelege behandeln.
2. Parserbelege auch für echte Teilbäume/äußere Leerzeichen wiederverwenden; Quellen-/Vorkommensbindung und Stalenessprüfung erhalten.
3. Zähler mit Inzidenzindex sowie kompakter, zählerhaltender Klauselübersetzung und unabhängiger Prüfung erweitern.
4. Gemeinsame kostenbewusste Auswahl und bezahlten Rückfall für bestehende Anbieter liefern; stärkste feste Verfahren als gleichberechtigte Kandidaten.
5. Fortsetzungsquotient am öffentlichen Pfad qualifizieren, echte CPU-/Allokationsmessungen und Kontrollen bei veränderten Annahmen/Syntax vornehmen.
6. Rationalen Funktionsvertrag mit erhaltenen Definitionsbedingungen auf vorhandener Normalformprüfung in SDK und eigenständigem Vergleichspfad verfügbar machen.
7. Bilineare Grenzen und Eingabebasis-Transfer ergänzen; Ausführungswahl nach konkretem Größen-/Speichermodell und unabhängiger Prüfung.
8. Offene Beweispläne schrittweise fortsetzen und gemeinsame Präfixe gebunden wiederverwenden.
9. Logische Einzelprüfungen dauerhaft speichern/öffnen; einen editierbaren Zählablauf bis Prüfung und Download bereitstellen.
10. Getrennte Aufgabenfamilien und vollständigen Lebenszyklus ausführen und Ergebnisse ohne positive Vorannahme dokumentieren.
11. Einstieg, Fähigkeitenmatrix, Strategieindex und Release-/Consumerpfad konsistent ausliefern.

## Abnahme
Jedes Arbeitspaket endet mit ausführbarer Funktion, sinnvollen Tests, überprüftem Diff und datierter Dokumentation. Gesamtabnahme umfasst vollständigen Maven-Produktbuild, betroffene Browserabläufe, bestehende Forschungs-/Proof-Gates im aktuellen CI-Lauf und externe SDK-Verbraucher. Forschungsziele wie allgemeine Lernamortisation bleiben Ergebnisfragen; ein sauberer Nullbefund wird veröffentlicht statt durch Fallauswahl verändert.

