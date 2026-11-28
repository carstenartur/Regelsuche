# Forschungsnotizen und Preprints

Die Manuskripte aus Primachsenraum und Regelsuche dokumentieren ihre Beweise, Rechnungen und offenen Fragen. Die mathematischen Projektentwürfe sind mit erheblicher KI-Unterstützung entstanden. Eine unabhängige fachliche Begutachtung steht aus. [Öffentliche Leseseite](https://carstenartur.github.io/Regelsuche/forschungsnotizen/)

| Arbeit | Fassung und Gegenstand | Quellen und Prüfprogramme |
| --- | --- | --- |
| [An unconditional upper bound for the Golomb–Keller exponent limsup (PDF)](golomb-keller/Golomb_Keller_v6_Review_Draft.pdf) | Review-Entwurf v6, 20. November 2026. Unbedingte obere Schranke C_GK ≤ c∗ mit c∗ ≈ 0,430038367231, bisher 0,4332. Ob Gleichheit gilt, bleibt offen. | [Quellen und Verifikation (ZIP)](golomb-keller/Golomb_Keller_v6_Source_Verification.zip) |
| [Which primes divide twice a greedy offset plus one? (PDF)](greedy-prime-offsets/Moree-2088-note.pdf) | Forschungsnotiz vom 28. November 2026. Primteiler von 2g + 1 für alle ungeraden Primzahlen unter 2.300.003 und Anwendung auf die 2.088 positiven Offsets von Ford, Luca und Moree. | [Daten, Zertifikate und unabhängige Rechenwege (ZIP)](greedy-prime-offsets/Moree-2088-reproduction.zip) |
| [Welche Primzahlen teilen 2g + 1? (PDF)](a135311/primteiler-a135311-v1.pdf) | Deutschsprachiger Einstieg vom 28. November 2026. Das Restklassenargument lässt sich für die 18 ungeraden Primzahlen bis 67 direkt nachrechnen. | [Prüfskript für A135311](a135311/pruefen.py) |
| [Regelsuche-Methodenmanuskript](../../paper/manuscript.md) | Englische Arbeitsfassung zur Entdeckung und Wiederverwendung symbolischer Regeln. | [Aussagen und Belege](../../paper/claims-and-evidence.md) |

## Begleitnotizen

- [Zählfunktion der Greedy-Folge (PDF)](begleitnotizen/zaehlfunktion.pdf)
- [Greedy-Optimalität an Thompsons Wurzel und der erste Rekord einer neuen Primachse (PDF)](begleitnotizen/thompsons-wurzel.pdf)
- [Optimalität und Sättigung (PDF)](begleitnotizen/optimalitaet-und-saettigung.pdf)

## Versionsgeschichte des Golomb–Keller-Manuskripts

| Version | Manuskriptdatum 2026 | Entwicklung |
| --- | --- | --- |
| v1 | 10. September | Erste Arbeitsnotiz mit Prüfauftrag. |
| v2 | 13. September | Thompsons Vorarbeit sowie Korrekturen bei Einzelmengen, Rundung und Resttermen. |
| v3 | 13. September | Ergänzte Danksagung. Eine deutsche didaktische Begleitfassung vom 17. September gehört zu v3. |
| v4 | 2. Oktober | Zuletzt versandte Fassung mit Zulässigkeitsbeispiel und separater Frage zur Zählfunktion. |
| v5 | 17. November | Strukturelle Charakterisierung, Stabilität und gewichtete Dominanz an Thompsons Wurzel. |
| v6 | 20. November | Aktueller Review-Entwurf mit der vollständigen v5 und den Erweiterungen zu Optimalität, Primfaktoren und Verschiebung um drei. |

[SHA-256-Prüfsummen der Downloads](SHA256SUMS)

Das v6-Quellenpaket gibt den Stand vom 20. November einschließlich der damaligen Review-Anleitung wieder. Die öffentliche Freigabe dieser Fassung erfolgte am 28. November 2026. Im entpackten Ordner `golomb-keller-v6` prüft `sha256sum -c SHA256SUMS` die Dateien. `python3 docs/review/golomb-keller/v6-draft/review.py --check` startet die Prüfung mit Python 3.10 oder neuer und NumPy.

## Weitere Entwürfe ablegen

Jede Forschungsnotiz erhält ein eigenes Unterverzeichnis und einen Eintrag auf der [Übersichtsseite](index.html). Die Notiz nennt Fragestellung, Vorarbeiten, Beiträge von Menschen und KI, Prüfstand und offene Fragen. Datierte PDF-Fassungen bleiben unter ihrem jeweiligen Dateinamen zitierbar.
