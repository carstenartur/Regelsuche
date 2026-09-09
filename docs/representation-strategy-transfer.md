# Gelernte Darstellungswahl: öffentlicher Transfervergleich

Der Vergleich verbindet die [automatische Darstellungswahl](automatic-linear-representations.md)
mit einem begrenzten Strategielerner. Er lernt die Bedingung, ab welcher
Variablenzahl unabhängige Blöcke getrennt gelöst werden. Die mathematischen
Verfahren und die Abfolge Zerlegen → Lösen → Zusammensetzen sind vorgegeben.
Allgemeine Beweistaktiken oder neue mathematische Verfahren werden nicht gelernt.

Sechs Trainingssysteme erzeugen 18 tatsächlich ausgeführte und unabhängig
auditierte Beobachtungen (jeweils direkt, vollständige Matrix und Blöcke).
Die feste Grammatik enthält die Mindestgrößen 2, 4, 6, 8, 12 und 17. Der Fit
bevorzugt verifizierte Abschlüsse und anschließend geringere Arbeitskosten.
Die private, unveränderliche Policy wird vor der Auswertung eingefroren.
Aufgabenfamilien, Soll-Lösungen und Auswertungsergebnisse sind keine Eingaben
des Lerners. Die Zufallskontrolle verwendet eine veröffentlichte feste Formel
aus Quellgrößen mit Seed 730.

Der Auswertungssatz enthält 42 Aufgaben: je acht Fälle für Koeffizientenvergleich,
Rekurrenzblöcke, redundante Bedingungen, freie Parameter und verbundene Systeme
sowie zwei negative Kontrollen (nichtlinear und außerhalb der rationalen Domäne).
Rekurrenzblöcke haben drei statt der in den zerlegbaren Trainingsfällen
vorkommenden ein oder zwei Variablen je Block. Das ist ein begrenzter Transfer
innerhalb rationaler linearer Algebra, kein domänenübergreifender Beweisfund.
Sortierte exakte Alpha-Polynomidentitäten der einzelnen Gleichungen schließen
umbenannte und umsortierte Trainingskopien aus. Dieses konservative Kriterium
entscheidet keine allgemeine semantische Äquivalenz ganzer Systeme.

Dies ist ein **öffentlicher Entwicklungssatz**, kein unangetasteter abschließender
Holdout und keine externe Preregistrierung. Die feste AUTO-Schwelle wurde anhand
von Entwicklungsfällen gewählt. Die folgenden Zahlen beschreiben den aktuellen
ausgeführten Stand; sie dürfen nicht als blinder Leistungsnachweis ausgegeben werden.

| Profil | Anwendung einschließlich Audit | Mit einmaligem Lernen | Verifizierte Abschlüsse |
|---|---:|---:|---:|
| DIRECT | 83.508 | 83.508 | 40 |
| MATRIX | 83.508 | 83.508 | 40 |
| BLOCKS | 79.748 | 79.748 | 40 |
| FIXED_AUTO | 78.460 | 78.460 | 40 |
| RANDOM | 81.592 | 81.592 | 40 |
| LEARNED | 78.544 | 91.229 | 40 |

Die gelernte Mindestgröße ist 8 und entspricht hier der festen AUTO-Regel.
Die feste Wahl benötigt rund 6,0 % weniger gezählte Arbeit als DIRECT. Das
Lernen verursacht 12.685 zusätzliche Einheiten; der Lernaufwand amortisiert
sich in diesem Satz nicht. Ein zusätzlicher Nutzen des Lernens gegenüber der
festen Regel ist nicht belegt. Die 40 Abschlüsse umfassen vollständige exakte
Lösungsmengen; die beiden negativen Kontrollen bleiben in jedem Profil erhalten.
Die Null bei `regressions` zählt verlorene verifizierte Abschlüsse gegenüber
DIRECT, nicht einzelne Fälle mit höheren Arbeitskosten.

Die Bilanz umfasst alle Trainingstrials, Fitvergleiche, Anwendung, Auswahl,
Darstellungsvorbereitung, Komposition und zusätzliche algorithmische Audits.
Das Konstruktionsbudget beträgt 20.000, das getrennte Auditbudget 200.000.
Parsing, Hashing, Identitätsbildung für den Split und die Bitkomplexität
rationaler Arithmetik liegen außerhalb der mechanischen Einheiten. Diese
Zahlen sind weder CPU-Messungen noch eine Gesamtkomplexitätsanalyse.

`RepresentationTransferExperiment.main(outputDirectory)` schreibt zuerst das
feste Protokoll und anschließend den vollständigen Bericht, die Zusammenfassung
und ein SHA-256-Manifest. Das Manifest bindet auch alle Bytes des vollständigen
Berichts, einschließlich fehlgeschlagener Versuche. Referenzdateien:
[Protokoll](generated/representation-transfer-protocol.json),
[Zusammenfassung](generated/representation-transfer-summary.json),
[Manifest](generated/representation-transfer-manifest.json).
Die [Browserdemo](representation-transfer-demo.md) führt die aktuelle Studie aus
und exportiert den vollständigen Bericht.
