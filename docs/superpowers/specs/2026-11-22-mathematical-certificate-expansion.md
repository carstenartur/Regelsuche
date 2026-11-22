# Mathematische Fähigkeiten aus OpenAI Math weiterentwickeln

## Auftrag und Entscheidungen

Carsten hat am 22. November 2026 die im vorangehenden Quellen- und Codevergleich
empfohlenen Erweiterungen zur Umsetzung freigegeben. Die fachlichen Verträge
dieses Dokuments konkretisieren diese Zustimmung; weitere Freigaben für
reversible Implementierungsentscheidungen sind nicht erforderlich.

Der Ausgangspunkt ist `98a60e30629f65bc12bb573e9beb46b04053e66c`.
Die untersuchte Sammlung ist `openai/math@4eaee87548250b2682affda7d05d947c05554bae`.

## Architektur und Alternativen

Die gewählte Umsetzung ergänzt begrenzte, typisierte Such- und Auswertungsverträge
im bestehenden Java-Kern. Das Optimierungs-SDK delegiert. Jeder erfolgreiche
Kandidat wird an die ursprüngliche Eingabe gebunden und unabhängig geprüft.
Eine Prüfung wiederholt nicht die Suchheuristik. Vorhandene exakte Skalare,
Polynome, Gram-Prüfer und Feldarithmetik werden wiederverwendet.

Die Alternative einer vollständigen Übertragung der asymptotischen Algorithmen
aus den Familien 107, 116, 142 oder 378 ist für diesen Auftrag verworfen:
Die vorausgegangene Empfehlung priorisiert kleine ausführbare Methoden mit
belegtem Nutzen. Ein zusätzlicher externer CAS-/Python-/SDP-Dienst wäre möglich,
ist für die erste selbständig ausführbare Java-Implementierung nicht erforderlich.

## Globale Anforderungen

- Java 25; mathematische Implementierung in `regelsuche-math-algorithms`, dünne
  typisierte Einstiege in `regelsuche-optimization-sdk`.
- Exakte rationale/ganze Arithmetik; kein numerischer Toleranzwert autorisiert
  ein Zertifikat. Formeln und Zertifikate sind typisierte unveränderliche Daten.
- Jede öffentliche Rechnung bindet Quelle, Parameter und Voraussetzungen.
  Abbruch, Laufzeit-, Arbeits-, Speicher- und Bitgrenzen liefern kein positives
  oder negatives mathematisches Urteil.
- Vorhandene Verträge und Qualitätsgrenzen bleiben gültig. Tests und Produktion
  werden mit strengen Compilerwarnungen geprüft.
- Keine Aussage über einen neuen mathematischen Satz, allgemeinen optimalen
  Tensor-Rang, eine universelle Greedy-Frist oder ungeprüfte Java-Refactorings.
- Neue Verfahren erhalten SDK-Tests, ausführbare Beispiele, Quellen und
  dokumentierte Grenzen. Der Strategien-Index unterscheidet Hypothese,
  Implementierung, ausgeführten Befund und Folgerung.

## A. Ungleichungen und Potentiale

Ein begrenzter allgemeiner Ansatz sucht rationale Gram-/Quadratsummenzeugen für
Polynome, gegebenenfalls mit vorgegebenen polynomialen Gleichheits- und
Nichtnegativitätsvoraussetzungen. Eingabe sind die Zielpolynome und eine
explizite begrenzte Monombasis bzw. Suchkonfiguration; eine fertige Zerlegung
ist keine erforderliche Eingabe. Koeffizientenvergleich und PSD-Prüfung
verifizieren das gefundene Ergebnis unabhängig. Die Suche darf unvollständig
sein und meldet dann `INCONCLUSIVE` statt Nichtnegativität zu widerlegen.

Ein eigener Potentialauftrag erhält eine explizite polynomielle Transition,
einen gegebenen invarianten Bereich und eine endliche Kandidatenbasis. Er sucht
ein nichtnegatives Potential mit mindestens der geforderten positiven Abnahme
auf dem Bereich. Der Nachweis bindet beide Ungleichungen und die Transition;
eine Terminierungsaussage verlangt zusätzlich den ausdrücklich bestätigten
Bereichs-/Übergangsvertrag. Bestehende Gleichheitsinvarianten bleiben erhalten.

## B. Gemeinsame bilineare Rechenprogramme

Ein typisierter rationaler Tensor beschreibt alle Ausgaben. Ein begrenzter
Suchraum aus linearen Eingabeformen und einem Produktbudget liefert U/V/W-
Programme. Die vollständige Koeffizientengleichheit
`T[k,i,j] = sum_l W[k,l]*U[l,i]*V[l,j]` ist der unabhängige Prüfvertrag.
Die Suche verwendet keine fest kodierte Karatsuba-/Komplex-/Strassen-Lösung.
Kosten umfassen variable Multiplikationen, Additionen, Konstantenfaktoren und
benötigte Zwischenwerte. Eine begrenzte erfolglose Suche beweist keine
Unmöglichkeit oder optimale Rangschranke. SDK-Aufrufe machen Kandidat und
Prüfung zugänglich; Java-Umschreibungen benötigen weiterhin den Consumer-Gate.

## C. Rationale nichtkommutative Formeln

Ein eigener typisierter Formelbaum über Q enthält Scalar, Variable, Sum,
geordnetes Product und Inverse. Rekursive affine Realisierungen `h=u L^-1 v`
behalten jedes ursprüngliche Inversenvorkommen. Exakte Matrixbelegungen prüfen
Definitionsbedingungen und Wert. Eine unabhängige strukturelle Prüfung bindet
die Realisierung an den ursprünglichen Baum, einschließlich weggekürzter oder
mit null multiplizierter Zweige. Die vorhandene divisionsfreie API bleibt
unverändert. Es wird kein universeller schneller Identitätsentscheider behauptet.

## D. Dynamische algebraische Auswertung

Ein typisierter Rechenbaum wird über einer quadratfreien Quotientenalgebra
eines Primkörpers ausgewertet. Nulltests/Inverse können einen echten ggT-Split
erzeugen. Beide Teilmoduln und die laufende Rechnung werden weitergeführt;
undefinierte Zweige bleiben ausdrücklich sichtbar. Zertifikate prüfen
Faktorprodukt, Teilerfremdheit, vollständige Zweigüberdeckung, Restgleichheiten
und Inversen. Es werden vorhandene Polynom-/Bézout-Bausteine verwendet, wo ihr
Vertrag passt. Kein vollständiger Port des Faktorisierungssatzes aus Familie 142.

## E. Endliche lokale Constraints

Lokale boolesche Faktoren mit nichtnegativen ganzen Gewichten werden exakt
durch budgetierte Variablenelimination kontrahiert. Ausgegeben werden die
Gesamtzahl bzw. das Gesamtgewicht, bedingte Anzahlen und bei positivem
Ergebnis ein Zeuge. Eine vollständige Eliminationsspur wird unabhängig
nachgerechnet. Ein Abbruch ist niemals eine Nullanzahl. Freie Variablen,
konstante Faktoren, unverbundene Komponenten und widersprüchliche Bedingungen
gehören zum Vertrag. Ein optionales erzeugendes Polynom darf auf getrennte
exakte Zählanfragen mit explizitem Gewichtsparameter aufbauen.

## F. Zyklische Energie-/Potentialzertifikate

Ein endlicher gerichteter Graph mit Min-/Max-Zuständen, ganzzahligen Gewichten
und mindestens einer Kante je Zustand erhält durch begrenzte Suche einen
Kreditvektor und eine Max-Strategie. Ein unabhängiger Prüfer kontrolliert
Nichtnegativität sowie `credit(v)+weight(v,u)>=credit(u)` an der gewählten
Max-Kante und an allen Min-Kanten. Für nicht gewinnende oder nicht innerhalb
des Budgets entschiedene Zustände wird keine endliche Kreditgarantie behauptet.
Zyklen, parallele Kanten und Selbstschleifen werden berücksichtigt.

## Abnahme und Integration

Jede Fähigkeit braucht positive Fälle außerhalb des einen Motivationsbeispiels,
manipulierte Zertifikate, Quellenwechsel, Grenzbudgets und Abbruchtests.
Vergleiche verwenden unabhängige kleine Referenzen, etwa direkte Belegung,
Koeffizientenexpansion oder vollständige Enumeration. Aussagen über Beschleunigung
werden nur aus tatsächlich gemessenen Vergleichen abgeleitet.

Die Umsetzung wird in getrennten überprüfbaren Commits und kleinen PR-Gruppen
integriert. Laufende fremde PRs und ihre Workflows werden nicht übernommen oder
verändert. Die Bereitstellung einer neuen veröffentlichten SDK-Version ist von
den hier geprüften Quellcode-Einstiegen getrennt zu kennzeichnen.
