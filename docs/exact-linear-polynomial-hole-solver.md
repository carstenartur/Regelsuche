# Exakter linearer Koeffizientenabgleich

`ExactLinearPolynomialHoleSolver` ergänzt den ausführbaren C-Pfad aus #874 um rationale lineare
Koeffizientenlösung ohne Wertaufzählung. Die bisherige `ExactFinitePolynomialHoleSolver`-Enumeration,
ihre Domänen und ihre v1-Belege bleiben unverändert. Der vorhandene numerische Rekurrenz-Präfixlöser
verwendet bereits exakte Elimination; dieser neue Slice implementiert keine Rekurrenzinvarianten-
oder Closed-form-Orchestrierung.

Der additive [Plan-, Replay- und Programmpfad](exact-linear-polynomial-program.md)
bindet diese Eingaben vor dem Lösen, prüft geladene vollständige Laufbytes durch
erneute native Ausführung und stellt genau eine geprüfte Lösung als explizite
budgetierte mathematische `RewriteProgram`-Quelle bereit.

## Gefrorene Eingaben und mathematisches Fragment

Der Aufruf erhält ausschließlich Quelle, Ansatztemplate, explizite Koeffizienten-Hole-IDs, Annahmen
und Ressourcenlimits. Hole-Platzhalter haben die bisherige Form `${alpha}`. IDs werden eindeutig
und sortiert gebunden; interne formale Variablennamen dürfen keine Namen aus Quelle oder Template
verdecken. Ein historischer Zielausdruck oder späteres Holdout-Ergebnis ist kein Eingabefeld.

Unterstützt werden Polynome über rationalen Skalaren mit **affiner Abhängigkeit von den Holes**.
Die übrigen Symbole sind freie Polynomvariablen. Gesucht werden rationale Zahlen, keine rationalen
Funktionen dieser Variablen. Nichtlineare Hole-Produkte und Hole-Potenzen bleiben `UNSUPPORTED`,
auch wenn eine spätere Normalisierung sie wegkürzen könnte. Symbolische Nenner, Funktionen und
nichtleere Annahmen gehören nicht zum ersten Fragment. Insbesondere wird `x/x` nicht ohne eine
Domänenprüfung zu `1` vereinfacht. Bedingte Lösungszweige oder frei gewählte Parameterbelegungen
werden nicht erfunden.

## Tatsächlicher Produktionspfad

1. Die vorhandene parsergebundene `ExactResidualPolynomialArithmetic` projiziert Template und
   Quelle exakt. Pro Monom der normalen Polynomvariablen entsteht eine rationale lineare Gleichung
   für die deklarierten Holes. Auch ein vollständig weggekürztes Hole bleibt eine unbekannte Größe.
2. `ExactRrefSolver.solveCoefficients` nimmt diese Rohmatrix mit expliziter Variablenreihenfolge
   und Quellzeilen an. Es ermittelt Ränge und Klassifikation selbst; eine erfundene Rangangabe ist
   kein Eintrittsticket. Derselbe vorhandene Gauss-Jordan-Algorithmus erzeugt Reduktion, affine
   Lösungsbeschreibung beziehungsweise Widerspruch und konkrete elementare Zeilenoperationen.
3. Die vorhandene RREF-Prüfung wiederholt die Zeilenoperationen. Der neue begrenzte Eingang prüft
   zusätzlich den Bitraum der Rückeinsetzung und verbucht sowohl diese Prüfung als auch die
   identischen Rechnungen im bestehenden Reduktionskonstruktor vor deren Ausführung.
4. Nur bei eindeutiger Lösung setzt der Adapter alle rationalen Werte in das ursprüngliche
   Template ein. `ExactPolynomialAnalysis.requireEquivalent` prüft danach Quelle und vollständigen
   Kandidaten erneut, unabhängig vom abgeleiteten Koeffizientensystem.

Beispiel mit einem vorgegebenen generischen Basiswechsel:

```java
var result = new ExactLinearPolynomialHoleSolver().solve(
    "17*x/7+3*y/11",
    "${alpha}*(x+y)+${beta}*(x-y)",
    List.of("alpha", "beta"),
    List.of(),
    new ExactLinearPolynomialHoleSolver.Limits(8, 64, 512, 100_000));
// UNIQUE: alpha=104/77, beta=83/77; der vollständige Ausdruck ist unabhängig geprüft.
```

Die alte, vorher deklarierte endliche Ganzzahldomäne `[-3,3]` für beide Holes hat hier nach 49
Prüfungen keinen Treffer. Die neue API erhält keine Kandidatenwerte und löst das rationale System
direkt. Diese öffentliche Charakterisierung verwendet keine geschützten Studiendaten.

## Status, Identität und Grenzen

| Status | Bedeutung |
|---|---|
| `UNIQUE` | Ein rationaler Koeffizientenvektor; vollständiger Kandidat unabhängig geprüft |
| `INCONSISTENT` | Exaktes widersprüchliches Koeffizientensystem; Widerspruchszeile bleibt erhalten |
| `UNDERDETERMINED` | Freie Koordinaten bleiben; keine willkürlich ausgewählte Komplettierung |
| `UNSUPPORTED` | Das mathematische Fragment oder die explizite Hole-Deklaration passt nicht |
| `BUDGET_INCONCLUSIVE` | Dimension, Bitraum, Projektion oder kumulatives Arbeitsbudget erschöpft |
| `CHECK_FAILED` | Der vorhandene nachgelagerte Systemprüfer hat keinen gültigen RREF-Beleg geliefert |

Nur `UNIQUE` enthält einen Kandidaten. Negative Ergebnisse behalten Quelle, Template, Annahmen,
Limits, verbuchte Arbeit und bereits vollständig erzeugte System-/Reduktionsdaten. Ein unbekanntes
oder erschöpftes Ergebnis wird nicht als mathematisch widerlegt ausgegeben.

Die additive Identität `regelsuche.exact-linear-polynomial-hole-solver/v1` bindet diese vollständigen
Eingaben und Ergebnisse, einschließlich konkreter RREF-Daten und Zertifikatsfelder. Ein unveränderter
mitgelieferter Hash darf abweichende Zeilenoperationen oder andere Zertifikatsdaten nicht verstecken.
`replay(result)` rekonstruiert den gesamten Lauf und verlangt vollständige Objektgleichheit. Ein
öffentlich konstruierbares Resultat oder dessen Hash ist allein keine Autorität.

Die konfigurierbaren Höchstgrenzen liegen bei 12 Holes, 128 Monomen, 4096 Bits je System-/Eliminationsskalar und
10.000.000 verbuchten Arbeitseinheiten. Die Rohmatrix-API prüft ihre eigenen Dimensionen, Namen
und Zeilenlängen vor der Elimination. Zähler/Nenner und konservativer Bitraum für Addition und
Multiplikation werden vor großen Rechenoperationen geprüft. Diese Vorabschätzung kann einen
Lauf ablehnen, dessen spätere Kürzung kleinere Werte ergeben hätte.

Die vorhandene Polynomprojektion behält außerdem ihre eigenen festen Text-, Syntax-, Grad-, Term-,
Produkt- und 4096-Bit-Grenzen; das konfigurierbare Skalarlimit begrenzt die abgeleitete Matrix und
Elimination. Der neue Aufruf verbucht Zeichenanalyse, AST-/Termarbeit, Monomabgleich, tatsächliche
RREF-Arbeit mit Replay und unabhängige Endprüfung in einem gemeinsamen, nicht zurückgesetzten
Budget. Rückeinsetzungsarbeit wird vorab reserviert; fehlgeschlagene oder abgelehnte Versuche
behalten ihre Belastung. Diese mechanischen Einheiten behaupten weder CPU-Zeit noch eine Zählung
aller Speicher-, Hash- oder Formatierungsoperationen. Die bisherigen RREF- und Projektionsaufrufe
behalten ihre unveränderten Zähler; neue Rohmatrix-Ergebnisse werden mit `verifyCoefficients`
und ihrem Bitlimit reproduziert.

Unabhängige Review-Kontrollen prüfen die Bit-Zulassung mit einer tatsächlichen
`BigInteger.multiply`-Sonde: derselbe RREF-Pfad erreicht die Multiplikation mit ausreichendem Bitraum
und verweigert sie vorher bei knapper Grenze. Ein erst nach abgeschlossenem RREF erschöpftes Budget
behält Matrix und Zertifikat, liefert aber ohne unabhängige Template-Prüfung keinen Kandidaten.
Ein im Template weggekürztes Hole bleibt außerdem bei anderen eindeutig bestimmten Koeffizienten
frei; eine zusätzliche konstante Widerspruchsgleichung bleibt als `INCONSISTENT` erkennbar.

## Verbleibender #874-Umfang

Dieser Solver vervollständigt die rationale lineare C-Operation innerhalb des beschriebenen
Fragments. Der additive lineare Plan-/Evidence-Adapter ermöglicht inzwischen unabhängig
replaygeprüfte, explizite budgetierte Programmnutzung; die bisherige endliche Evidence-Kette
wird dafür nicht umgedeutet. Nichtlineare Hole-Systeme,
bedingte symbolische Lösungen, automatische Basis-/Grammatikwahl, Rekurrenzinvarianten samt
Initialwert- und Induktionszertifikaten sowie weitergehende logische Taktiken bleiben offen.

Lerntransfer, historische Holdout-Zuordnung und Leistungsvergleiche erfordern ihre tatsächlich
preregistrierten kontrollierten Studien. Öffentliche Tests schließen #874 nicht und begründen
weder mathematische Neuheit noch Produktionspromotion. Unabhängige Review und vollständige
integrierte CI sind zusätzliche Integrationsgates.

```bash
mvn -o -pl regelsuche-math-algorithms -am -Dmaven.compiler.useIncrementalCompilation=false \
  -Dtest=ExactLinearPolynomialHoleSolverTest,ExactRrefCoefficientSystemTest,ExactLinearPolynomialHoleReviewTest,ExactRrefCoefficientAdmissionReviewTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
