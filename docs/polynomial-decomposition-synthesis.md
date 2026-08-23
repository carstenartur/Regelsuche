# Semantische Polynomansicht und Zerlegungssynthese

**Implementierungsstand: 23. August 2026**

Regelsuche soll mathematische Fähigkeit nicht durch einen unbegrenzt wachsenden
Katalog konkreter Identitäten darstellen. Insbesondere wäre es falsch, für jede
Einsetzung in eine bekannte Formel eine eigene Regel zu lernen:

```text
x^4 + 4*y^4
(x + 1)^4 + 4*y^4
sin(t)^4 + 4*(z + 1)^4
...
```

Die neue Polynomschicht trennt deshalb drei Dinge:

1. **semantische Darstellung** eines Ausdrucks;
2. **allgemeine Zerlegungsschablone** mit unbekannten Koeffizienten;
3. **exakte Lösung und Zertifizierung** der Koeffizientenbedingungen.

Die bisherige Sophie-Germain-spezifische Bridge bleibt als historischer
Benchmark-Kontrollpfad vorhanden. Sie ist nicht länger die standardmäßig
gewählte Erklärung der allgemeinen Fähigkeit.

## Strukturelle AST-Atome

`PolynomialSemanticView` interpretiert einen begrenzten kommutativen Ausdruck
als exaktes Polynom mit ganzzahligen Koeffizienten. Eine Unbestimmte muss dabei
kein einzelner Variablenname sein. Ein vollständiger AST-Teilbaum kann ein Atom
bilden, beispielsweise:

```text
A := x + 1
B := sin(t)
```

Damit erhält

```text
(x + 1)^4 + 5*(x + 1)^2*sin(t)^2 + 4*sin(t)^4
```

dieselbe semantische Koeffizientenstruktur wie

```text
A^4 + 5*A^2*B^2 + 4*B^4.
```

Eine einzige mathematische Synthese deckt dadurch unendlich viele konkrete
AST-Einsetzungen ab. Es werden weder sichtbare Hilfsvariablen in den
Benutzerausdruck geschrieben noch separate Regeln pro Einsetzung benötigt.

Die Sicht ist content-stabil und enthält:

- deterministisch sortierte strukturelle Atome;
- exakte `BigInteger`-Koeffizienten;
- Exponentenvektoren der Monome;
- Gesamtgrad und Homogenitätsstatus;
- verbrauchte AST-Arbeit;
- einen kanonischen Identitätsstring.

Division, nichtganzzahlige Koeffizienten, negative oder nichtganzzahlige
Exponenten und überschrittene Budgets werden fehlersicher abgelehnt.

## Allgemeine quartische Zerlegung

`PolynomialDecompositionSynthesisOperator` speichert keine
Sophie-Germain-Identität. Für ein binäres homogenes Polynom vierten Grades
verwendet er die allgemeine Schablone

```text
(a*A^2 + b*A*B + c*B^2)
*
(d*A^2 + e*A*B + f*B^2).
```

Durch Ausmultiplizieren entstehen die exakten Bedingungen

```text
a*d             = c40
a*e + b*d       = c31
a*f + b*e + c*d = c22
b*f + c*e       = c13
c*f             = c04
```

für das Eingabepolynom

```text
c40*A^4 + c31*A^3*B + c22*A^2*B^2 + c13*A*B^3 + c04*B^4.
```

Der Synthesizer enumeriert nur Teiler der äußeren Koeffizienten und löst das
verbleibende lineare Gleichungssystem exakt. Ein Kandidat wird nur ausgegeben,
wenn sämtliche fünf Koeffizientenbedingungen erfüllt sind.

## Beispiele aus derselben Methode

### Sophie-Germain ohne gespeicherte Spezialidentität

Für

```text
A^4 + 4*B^4
```

findet der Solver unter anderem

```text
[a,b,c] = [1,-2,2]
[d,e,f] = [1, 2,2]
```

und damit

```text
(A^2 - 2*A*B + 2*B^2)
*
(A^2 + 2*A*B + 2*B^2).
```

### Andere quartische Familie

Für

```text
A^4 + 5*A^2*B^2 + 4*B^4
```

entsteht aus derselben Schablone

```text
(A^2 + B^2) * (A^2 + 4*B^2).
```

### Symmetrische Faktorisierung

Für

```text
A^4 + A^2*B^2 + B^4
```

findet der Solver

```text
(A^2 - A*B + B^2)
*
(A^2 + A*B + B^2).
```

### Beliebige AST-Einsetzungen

Dieselbe bereits implementierte Schablone arbeitet beispielsweise auf

```text
sin(t)^4 + 4*(x + 1)^4
```

ohne eine neue Regel für `sin(t)` oder `x + 1` zu lernen. Die beiden Teilbäume
werden lediglich als die beiden semantischen Atome des aktuellen Problems
gebunden.

## Zertifikat und Suchkante

Jeder erzeugte Kandidat trägt einen SHA-256-Nachweis, der mindestens bindet:

- Version der Synthesemethode;
- Version der semantischen Polynomansicht;
- kanonische Eingabekoeffizienten;
- beide gelösten Koeffiziententripel;
- vollständig gerenderten Ergebnis-AST.

Die Suchkante verwendet

```text
hypothesis_polynomial_decomposition_synthesis
```

als Regel-ID und bleibt damit von einem deklarativen oder gelernten
Spezialmakro unterscheidbar. Kandidatenzahl, Koeffizientenbetrag, AST-Größe und
untersuchte Faktorkonfigurationen sind begrenzt.

## Auswahlpolitik

In den allgemeinen Discovery-Profilen ist die Zerlegungssynthese nun der
standardmäßig aktivierte Faktorisierungs-Hypothesenoperator.

Der bisherige

```text
hypothesis_difference_of_squares_preparation
```

bleibt registriert, ist dort aber standardmäßig deaktiviert und als
`historical-control` markiert. Bestehende reproduzierbare Sophie-Germain-
Szenarien können ihn weiterhin ausdrücklich auswählen; ihre historische
Evidence wird nicht stillschweigend umgedeutet.

Die Trennung lautet damit:

```text
kleiner Regelkern
  -> semantische Theorieansicht
  -> allgemeine Zerlegungssynthese
  -> exakte Kandidatenkante
  -> optionaler abgeleiteter Makro-Cache
```

Nicht jede erfolgreich erzeugte Faktorisierung wird als neue Standardregel in
das Inventar übernommen.

## Aktuelle Grenze

Die erste ausführbare Stufe ist bewusst eng und exakt:

- zwei strukturelle Atome;
- homogenes Polynom vierten Grades;
- ganzzahlige Koeffizienten;
- Produkt zweier quadratischer Faktoren;
- ganzzahlige, betragsmäßig begrenzte Faktorkoeffizienten.

Sie ist kein vollständiger allgemeiner Polynomfaktorisierer. Insbesondere noch
nicht abgedeckt sind:

- beliebige Grade und Faktorgradpartitionen;
- rationale oder algebraische Faktorkoeffizienten;
- mehr als zwei semantische Atome;
- vollständige multivariate Faktorisierung;
- Auswahl zwischen mehreren Zielringen;
- optimale Rangordnung aller möglichen Zerlegungen.

Diese Grenzen sind nun jedoch Erweiterungen eines allgemeinen Modells statt
Anlässe für weitere benannte Einzelfalloperatoren. Weitere Templates können
unter derselben semantischen Ansicht ergänzt werden, beispielsweise
`Grad 6 -> 2 + 4`, vollständiges Quadrat plus Rest oder allgemeine
Faktorgradpartitionen.

## Prüfung aus dem Checkout

Fokussierte Kerntests:

```bash
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.transform.PolynomialDecompositionSynthesisOperatorTest
```

Discovery-Integration:

```bash
./gradlew :app:test \
  --tests de.regelsuche.docs.PolynomialDecompositionDiscoveryIntegrationTest
```

Vollständiger Repositoryvertrag:

```bash
./gradlew --no-configuration-cache ciCheck
```

## Aussagegrenze

Die Implementierung belegt:

> Regelsuche kann innerhalb eines exakt begrenzten Polynomfragments
> Faktorisierungsdarstellungen durch allgemeine Koeffizientensynthese erzeugen,
> ohne die konkrete Identität oder jede AST-Einsetzung als eigene Regel zu
> speichern.

Sie belegt noch keine vollständige Polynomfaktorisierung, keine externe
mathematische Neuheit und keine allgemeine Überlegenheit gegenüber etablierten
Computer-Algebra-Systemen.
