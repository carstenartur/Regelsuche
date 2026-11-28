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

## Forschungsnotizen vom 28. November 2026

| Fragestellung | Text | Datierte PDF-Fassung |
| --- | --- | --- |
| Wann verbessert eine dritte Primzahl erstmals den Rekord der festen Basis 2 und 127? | [Beweis und rationale Zertifikate](primachsenrekord/index.html) | [Primachsenrekord v1](primachsenrekord/primachsenrekord-v1.pdf) |
| Welche Primquadrate und multiplikativen Familien fehlen dauerhaft in der Greedy-Folge? | [Dichteschranke und endliche Faktorzeugen](primquadrate/index.html) | [Primquadrate v1](primquadrate/primquadrate-v1.pdf) |
| Wie schnell wächst die Folge, und wie früh sind fast alle Restklassen besetzt? | [Wachstum und Belegung](wachstum/index.html) | [Wachstum v1](wachstum/wachstum-v1.pdf) |
| Welche Entscheidungen erzwingen schon Regeln für kleinere Primzahlen? | [Chronologische Zertifikate](chronologie/index.html) | [Chronologie v1](chronologie/chronologie-v1.pdf) |
| Wie klein ist der mögliche Überschuss bei Verschiebung um drei? | [Datierter Nachtrag zu Golomb–Keller v6](verschiebung-drei/index.html) | [Verschiebung um drei v1](verschiebung-drei/verschiebung-drei-v1.pdf) |
| Was folgt aus dem quantitativen Siebsatz des OpenAI-Preprints für die Jacobsthal-Funktion? | [Herleitung und Literaturstand](jacobsthal/index.html) | [Jacobsthal v1](jacobsthal/jacobsthal-v1.pdf) |

Die Softwareversuche dokumentieren den [Strukturwert gekoppelter Wurzelprogramme im 381-Bit-Feld](https://github.com/carstenartur/Regelsuche/blob/5639e1af7c7e857b0c439cc3ab821d6c4c579714/docs/research/quadratic-root-programs.md) und den [prospektiven Transfer gelernter Taktiken auf zwei neue Aufgabenfamilien](https://github.com/carstenartur/Regelsuche/blob/5639e1af7c7e857b0c439cc3ab821d6c4c579714/docs/research/prospective-tactic-transfer/README.md).

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
